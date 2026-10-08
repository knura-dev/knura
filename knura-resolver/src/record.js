// Owner records: which server an owner's taps go to, signed with the owner's Ed25519 key.
// Must match io.knura.core.resolver in knura-core, which signs them.

import { createPublicKey, verify as cryptoVerify } from "node:crypto";

/** 10 characters, no l, o, 0 or 1 so an ID read aloud or off a screen can't be mistaken. */
export const OWNER_ID = /^[a-km-np-z2-9]{10}$/;
export const SIGNED_TEXT_HEADER = "knura-resolver-v1";
export const MAX_SERVER_LENGTH = 200;
const HEX_PUBLIC_KEY = /^[0-9a-f]{64}$/;
const HEX_SIGNATURE = /^[0-9a-f]{128}$/;
/** DER prefix of an X.509 SubjectPublicKeyInfo for a raw Ed25519 key (RFC 8410). */
const ED25519_SPKI_PREFIX = Buffer.from("302a300506032b6570032100", "hex");

/** The exact bytes an owner signs. */
export function signedText(owner, server, seq) {
    return `${SIGNED_TEXT_HEADER}\nowner=${owner}\nserver=${server}\nseq=${seq}\n`;
}

/**
 * Checks a server address: where taps are sent, as `<server>/tap`. Returns null if it is fine,
 * else the reason. Only https with a real host name, so the resolver can't be pointed at an IP
 * address, a login prompt (user@host) or a page with its own query string.
 *
 * @param {string} server
 * @param {{allowLoopbackHttp?: boolean}} [options] for local testing only
 */
export function checkServer(server, { allowLoopbackHttp = false } = {}) {
    if (typeof server !== "string" || server.length === 0 || server.length > MAX_SERVER_LENGTH) {
        return `server must be 1 to ${MAX_SERVER_LENGTH} characters`;
    }
    if (!/^[\x21-\x7e]+$/.test(server)) return "server must be plain ASCII with no spaces";
    if (server.endsWith("/")) return "server must not end with /";
    let url;
    try {
        url = new URL(server);
    } catch {
        return "server is not a valid URL";
    }
    if (url.href !== server && url.href !== server + "/") {
        return "server must be written in its plain form, e.g. https://host.example.net";
    }
    if (url.username || url.password || url.search || url.hash) {
        return "server must not have a user name, ?query or #fragment";
    }
    if (allowLoopbackHttp && url.protocol === "http:" && isLoopback(url.hostname)) return null;
    if (url.protocol !== "https:") return "server must start with https://";
    const host = url.hostname;
    if (!/^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z][a-z0-9-]{0,61}[a-z0-9]$/.test(host)) {
        return "server must use a host name such as your-computer.your-tailnet.ts.net, not an IP address";
    }
    if (host === "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) {
        return "server must be reachable from the internet";
    }
    return null;
}

function isLoopback(host) {
    return host === "127.0.0.1" || host === "localhost" || host === "[::1]";
}

/**
 * Checks the shape of an update an owner sent. Returns null if it is fine, else the reason.
 *
 * @param {object} update {server, seq, publicKey, signature}
 */
export function checkUpdateShape(update, options) {
    if (typeof update !== "object" || update === null) return "body must be a JSON object";
    const { server, seq, publicKey, signature } = update;
    const serverProblem = checkServer(server, options);
    if (serverProblem) return serverProblem;
    if (!Number.isSafeInteger(seq) || seq < 1) return "seq must be a positive whole number";
    if (typeof publicKey !== "string" || !HEX_PUBLIC_KEY.test(publicKey)) {
        return "publicKey must be 64 lowercase hex digits (a raw Ed25519 key)";
    }
    if (typeof signature !== "string" || !HEX_SIGNATURE.test(signature)) {
        return "signature must be 128 lowercase hex digits";
    }
    return null;
}

/** True if signature (hex) is publicKey's (hex) Ed25519 signature over the record. */
export function verifySignature(owner, { server, seq, publicKey, signature }) {
    const key = createPublicKey({
        key: Buffer.concat([ED25519_SPKI_PREFIX, Buffer.from(publicKey, "hex")]),
        format: "der",
        type: "spki",
    });
    return cryptoVerify(null, Buffer.from(signedText(owner, server, seq), "utf8"), key, Buffer.from(signature, "hex"));
}
