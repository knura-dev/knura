// tap.knura.io. A tag provisioned for the resolver carries
//   https://tap.knura.io/<owner>?e=<encrypted UID and counter>&c=<MAC>
// and the resolver sends the phone on to <server>/tap?e=..&c=.., the knurd that owner registered.
//
// The resolver is a pipe, not a judge: it holds no tag keys, can't read e= (the UID is encrypted)
// and can't make a tap that knurd would accept. knurd checks every tap itself.
//
// Owners register and move their server with a record signed by their own Ed25519 key, so nobody
// else can repoint their tags, and an old signed record can't be replayed (seq must go up). The
// server must also confirm it belongs to that owner, so the resolver can't be used to send phones
// to a site its registrant doesn't run.
//
//   GET  /<owner>?e=..&c=..           302 to the owner's knurd
//   GET  /api/v1/owners/<owner>       the owner's signed record, as JSON (public)
//   PUT  /api/v1/owners/<owner>       register or move: {server, seq, publicKey, signature}
//
// The operator can suspend an owner ID that is used to dress up phishing links by setting a
// "suspended" reason on its record by hand: its taps then get a page instead of a redirect, and it
// can't be moved. New registrations are limited per address (REGISTRATIONS_PER_HOUR).

import { OWNER_ID, checkUpdateShape, verifySignature } from "./record.js";
import { confirmOwner } from "./server-check.js";

const HOME = "https://knura.io";
const MAX_BODY_BYTES = 2048;
const OWNER_API = /^\/api\/v1\/owners\/([^/]+)$/;
const PICC = /^[0-9A-Fa-f]{32}$/;
const MAC = /^[0-9A-Fa-f]{16}$/;
/** New owner IDs one address may register in an hour, per function instance. Moves don't count. */
export const REGISTRATIONS_PER_HOUR = 5;
const HOUR_MS = 60 * 60 * 1000;

const SECURITY_HEADERS = {
    "cache-control": "no-store",
    "referrer-policy": "no-referrer",
    "strict-transport-security": "max-age=31536000",
    "x-content-type-options": "nosniff",
    "x-frame-options": "DENY",
    "x-robots-tag": "noindex",
};

/**
 * Builds the request handler. Takes and returns API Gateway HTTP API (payload 2.0) shaped objects:
 * {requestContext.http.method, rawPath, rawQueryString, body, isBase64Encoded}.
 *
 * @param {object} options
 * @param {{get: Function, put: Function}} options.store owner records
 * @param {(server: string, owner: string) => Promise<string|null>} [options.confirm] asks the
 *     server whether it belongs to owner; null means yes
 * @param {() => Date} [options.now]
 * @param {boolean} [options.allowLoopbackHttp] accept http://127.0.0.1 servers, for local tests only
 * @param {(line: string) => void} [options.log]
 */
export function createHandler({
    store,
    confirm = confirmOwner,
    now = () => new Date(),
    allowLoopbackHttp = false,
    log = line => console.log(line),
}) {
    /** Recent new registrations by source address: address -> times (ms). */
    const registrations = new Map();

    return async event => {
        const method = event.requestContext?.http?.method ?? "GET";
        const path = event.rawPath ?? "/";
        try {
            const api = OWNER_API.exec(path);
            if (api) {
                if (method === "GET" || method === "HEAD") return await getRecord(api[1]);
                if (method === "PUT") return await putRecord(api[1], event, event.requestContext?.http?.sourceIp ?? "");
                return json(405, { error: "use GET or PUT" }, { allow: "GET, PUT" });
            }
            if (method !== "GET" && method !== "HEAD") {
                return json(405, { error: "use GET" }, { allow: "GET" });
            }
            if (path === "/") return redirect(HOME);
            const owner = path.slice(1);
            if (OWNER_ID.test(owner)) return await forwardTap(owner, event.rawQueryString ?? "");
            return page(404, "Not found", "There is nothing at this address.");
        } catch (e) {
            log(`error ${method} ${path}: ${e?.stack ?? e}`);
            return page(500, "Something went wrong", "tap.knura.io could not pass this tap on. Try tapping again.");
        }
    };

    async function forwardTap(owner, rawQuery) {
        const tap = parseTap(rawQuery);
        if (!tap) {
            return page(400, "Not a tap", "This link is missing its tap code. Tap the tag again rather than reusing a link.");
        }
        const record = await store.get(owner);
        if (!record) {
            log(`tap owner=${owner} unknown`);
            return page(404, "Tag not connected", "This tag's owner hasn't connected it to a server, so the tap can't be checked.");
        }
        if (record.suspended) {
            log(`tap owner=${owner} suspended`);
            return page(410, "Link suspended", "tap.knura.io no longer passes on taps for this link, because it was reported as misused.");
        }
        log(`tap owner=${owner} forwarded`);
        return redirect(`${record.server}/tap?e=${tap.e}&c=${tap.c}`);
    }

    async function getRecord(owner) {
        if (!OWNER_ID.test(owner)) return json(400, { error: "not an owner ID" });
        const record = await store.get(owner);
        return record ? json(200, record) : json(404, { error: "no such owner" });
    }

    /** Whether address may register one more new owner ID now, and if so counts it. */
    function mayRegister(address) {
        const cutoff = now().getTime() - HOUR_MS;
        if (registrations.size > 10000) {
            for (const [a, times] of registrations) if (times.every(t => t <= cutoff)) registrations.delete(a);
        }
        const recent = (registrations.get(address) ?? []).filter(t => t > cutoff);
        if (recent.length >= REGISTRATIONS_PER_HOUR) return false;
        recent.push(now().getTime());
        registrations.set(address, recent);
        return true;
    }

    async function putRecord(owner, event, address) {
        if (!OWNER_ID.test(owner)) return json(400, { error: "owner must be 10 characters from a-z and 2-9, without l or o" });
        const raw = event.isBase64Encoded ? Buffer.from(event.body ?? "", "base64").toString("utf8") : (event.body ?? "");
        if (Buffer.byteLength(raw) > MAX_BODY_BYTES) return json(413, { error: "body too large" });
        let update;
        try {
            update = JSON.parse(raw);
        } catch {
            return json(400, { error: "body is not JSON" });
        }
        const problem = checkUpdateShape(update, { allowLoopbackHttp });
        if (problem) return json(400, { error: problem });

        const old = await store.get(owner);
        if (old?.suspended) return json(403, { error: "this owner ID is suspended" });
        if (old && old.publicKey !== update.publicKey) {
            return json(403, { error: "this owner ID belongs to another key" });
        }
        if (old && update.seq <= old.seq) {
            return json(409, { error: `seq must be higher than ${old.seq}`, seq: old.seq });
        }
        if (!verifySignature(owner, update)) return json(403, { error: "bad signature" });

        if (!old && !mayRegister(address)) {
            log(`owner=${owner} registration refused: too many from one address`);
            return json(429, { error: "too many new owner IDs from this address; try again in an hour" }, { "retry-after": "3600" });
        }
        const refused = await confirm(update.server, owner);
        if (refused) return json(422, { error: refused });

        const record = {
            owner,
            server: update.server,
            seq: update.seq,
            publicKey: update.publicKey,
            signature: update.signature,
            updatedAt: now().toISOString(),
        };
        if (!(await store.put(record))) return json(409, { error: "changed at the same time by another update; try again" });
        log(`owner=${owner} ${old ? "moved" : "registered"} server=${update.server} seq=${update.seq}`);
        return json(200, record);
    }
}

/** e= and c= exactly once each and nothing else; anything more is not a tag's URL. */
export function parseTap(rawQuery) {
    const params = new Map();
    for (const pair of rawQuery.split("&")) {
        const eq = pair.indexOf("=");
        if (eq < 0) return null;
        const name = pair.slice(0, eq);
        if (params.has(name)) return null;
        params.set(name, pair.slice(eq + 1));
    }
    const e = params.get("e");
    const c = params.get("c");
    if (params.size !== 2 || !PICC.test(e ?? "") || !MAC.test(c ?? "")) return null;
    return { e, c };
}

function redirect(location) {
    return { statusCode: 302, headers: { ...SECURITY_HEADERS, location }, body: "" };
}

function json(statusCode, body, extraHeaders = {}) {
    return {
        statusCode,
        headers: { ...SECURITY_HEADERS, ...extraHeaders, "content-type": "application/json" },
        body: JSON.stringify(body),
    };
}

function page(statusCode, title, message) {
    const body = `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${title} · Knura</title>
<style>body{font:16px/1.5 system-ui,sans-serif;max-width:32rem;margin:15vh auto;padding:0 16px;color:#1c1b1a;background:#faf9f7}
@media (prefers-color-scheme:dark){body{color:#eeece8;background:#1a1918}}h1{font-size:1.4rem}</style>
</head><body><h1>${title}</h1><p>${message}</p></body></html>`;
    return {
        statusCode,
        headers: {
            ...SECURITY_HEADERS,
            "content-type": "text/html; charset=utf-8",
            "content-security-policy": "default-src 'none'; style-src 'unsafe-inline'",
        },
        body,
    };
}
