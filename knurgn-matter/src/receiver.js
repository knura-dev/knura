// The bridge's only way in for taps: a small HTTP endpoint on 127.0.0.1 that knurgN posts signed
// tap events to. It presses a knot's sensor only for a correctly signed, fresh, never-seen event
// for a knot in the config.

import { createServer } from "node:http";
import { verify, TOLERANCE_SECONDS } from "./signature.js";

const MAX_BODY_BYTES = 8 * 1024;
/** Remember handled ids a little longer than a signed request stays valid. */
const SEEN_MS = (TOLERANCE_SECONDS + 60) * 1000;
const MAX_SEEN = 10_000;

/**
 * Handles one request body. Kept apart from HTTP so tests can call it directly.
 *
 * @param {object} options
 * @param {Buffer} options.secret the shared whsec_ secret
 * @param {Set<string>} options.knots knot names this bridge shows
 * @param {(knot: string, id: string) => void} options.press presses the knot's sensor
 * @param {() => number} [options.now] milliseconds since 1970
 * @returns {(headers: object, body: string) => {status: number, detail: string}}
 */
export function tapHandler({ secret, knots, press, now = Date.now }) {
    const seen = new Map(); // webhook-id -> when we handled it

    return (headers, body) => {
        const at = now();
        const problem = verify(
            secret,
            {
                id: headers["webhook-id"],
                timestamp: headers["webhook-timestamp"],
                signature: headers["webhook-signature"],
            },
            body,
            Math.floor(at / 1000),
        );
        if (problem) return { status: 401, detail: problem };

        const id = headers["webhook-id"];
        forgetOld(seen, at);
        if (seen.has(id)) {
            // knurgN retries when it doesn't hear back in time; the tap already counted.
            return { status: 200, detail: "already handled" };
        }

        let event;
        try {
            event = JSON.parse(body);
        } catch {
            return { status: 400, detail: "body is not JSON" };
        }
        const data = event?.data;
        if (event?.type !== "tap.verified" || typeof data?.knot !== "string" || data.id !== id) {
            return { status: 400, detail: "not a knurgN tap.verified event" };
        }
        if (!knots.has(data.knot)) {
            return { status: 422, detail: `knot ${data.knot} is not in this bridge's knots setting` };
        }

        if (seen.size >= MAX_SEEN) seen.delete(seen.keys().next().value);
        seen.set(id, at);
        press(data.knot, id);
        return { status: 204, detail: `pressed ${data.knot}` };
    };
}

function forgetOld(seen, at) {
    for (const [id, when] of seen) {
        if (at - when <= SEEN_MS) break; // Map keeps insertion order, so the rest are newer
        seen.delete(id);
    }
}

/**
 * Starts the HTTP endpoint on 127.0.0.1 only: other computers can't reach it, even on the home
 * network. Only POST /tap is served.
 *
 * @returns {Promise<import("node:http").Server>}
 */
export function startReceiver(port, handle, log = console) {
    const server = createServer((req, res) => {
        const reply = (status, detail) => {
            res.writeHead(status, { "Content-Type": "text/plain; charset=utf-8", "Cache-Control": "no-store" });
            res.end(status === 204 ? undefined : detail + "\n");
        };
        if (req.url !== "/tap") {
            req.resume();
            return reply(404, "not found");
        }
        if (req.method !== "POST") {
            req.resume();
            res.setHeader("Allow", "POST");
            return reply(405, "use POST");
        }
        const declared = Number(req.headers["content-length"] ?? 0);
        if (declared > MAX_BODY_BYTES) {
            req.resume();
            return reply(413, "body too large");
        }
        const chunks = [];
        let size = 0;
        let tooBig = false;
        req.on("data", chunk => {
            size += chunk.length;
            if (size > MAX_BODY_BYTES) {
                tooBig = true;
                req.destroy();
            } else {
                chunks.push(chunk);
            }
        });
        req.on("end", () => {
            if (tooBig) return;
            const { status, detail } = handle(req.headers, Buffer.concat(chunks).toString("utf8"));
            // The id is UID:counter, which is not secret; never log headers or the secret.
            const id = String(req.headers["webhook-id"] ?? "-").slice(0, 80);
            if (status < 300) {
                log.info(`tap ${id}: ${detail}`);
            } else {
                log.warn(`tap ${id}: refused (${status} ${detail})`);
            }
            reply(status, detail);
        });
        req.on("error", () => {});
    });
    server.requestTimeout = 10_000;
    server.headersTimeout = 5_000;
    return new Promise((resolveStart, reject) => {
        server.once("error", reject);
        server.listen(port, "127.0.0.1", () => {
            server.off("error", reject);
            resolveStart(server);
        });
    });
}
