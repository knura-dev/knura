// Checks Standard Webhooks signatures (https://www.standardwebhooks.com), the format knurgN signs
// taps with: HMAC-SHA256 over "id.timestamp.body", sent as "webhook-signature: v1,<base64>".

import { createHmac, timingSafeEqual } from "node:crypto";

export const SECRET_PREFIX = "whsec_";
const MIN_SECRET_BYTES = 24;
const MAX_SECRET_BYTES = 64;
/** Taps signed more than this far from our clock are refused, so an old request can't be replayed. */
export const TOLERANCE_SECONDS = 5 * 60;

/** Decodes a "whsec_<base64>" secret into its bytes. Never puts the secret in an error message. */
export function parseSecret(text) {
    const trimmed = text.trim();
    if (!trimmed.startsWith(SECRET_PREFIX)) {
        throw new Error(`the secret must start with ${SECRET_PREFIX}`);
    }
    const base64 = trimmed.slice(SECRET_PREFIX.length);
    if (!/^[A-Za-z0-9+/]+={0,2}$/.test(base64)) {
        throw new Error("the secret is not valid base64");
    }
    const bytes = Buffer.from(base64, "base64");
    if (bytes.length < MIN_SECRET_BYTES || bytes.length > MAX_SECRET_BYTES) {
        throw new Error(`the secret must be ${MIN_SECRET_BYTES} to ${MAX_SECRET_BYTES} random bytes`);
    }
    return bytes;
}

/** The "v1,<base64>" signature knurgN sends for this id, timestamp and body. */
export function sign(secret, id, timestamp, body) {
    return "v1," + createHmac("sha256", secret).update(`${id}.${timestamp}.${body}`).digest("base64");
}

/**
 * Checks a signed request. Returns null if it is good, else a short reason that is safe to log.
 *
 * @param {Buffer} secret
 * @param {{id?: string, timestamp?: string, signature?: string}} headers
 * @param {string} body the raw request body
 * @param {number} nowSeconds Unix time
 */
export function verify(secret, { id, timestamp, signature }, body, nowSeconds) {
    if (!id || !timestamp || !signature) {
        return "missing webhook-id, webhook-timestamp or webhook-signature";
    }
    if (!/^\d{1,12}$/.test(timestamp)) {
        return "bad webhook-timestamp";
    }
    if (Math.abs(nowSeconds - Number(timestamp)) > TOLERANCE_SECONDS) {
        return "webhook-timestamp too far from this computer's clock";
    }
    const expected = Buffer.from(sign(secret, id, timestamp, body));
    // The header may carry several space-separated signatures (for example during a key change).
    for (const candidate of signature.split(" ")) {
        const given = Buffer.from(candidate);
        if (given.length === expected.length && timingSafeEqual(given, expected)) {
            return null;
        }
    }
    return "bad signature";
}
