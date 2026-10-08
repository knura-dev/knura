// Before forwarding taps to a server, ask it who it belongs to. knurd answers
// GET <server>/.well-known/knura-owner with {"owner":"<id>"}. This stops anyone from pointing
// tap.knura.io at a server they don't run, which would turn it into an open redirect.

export const OWNER_PATH = "/.well-known/knura-owner";
const TIMEOUT_MS = 5000;
const MAX_BODY_BYTES = 1024;

/**
 * Returns null if the server says it belongs to owner, else a reason safe to show the caller.
 * Never follows redirects: the answer must come from the server itself.
 *
 * @param {string} server already checked with checkServer
 * @param {string} owner
 * @param {typeof fetch} [fetchImpl]
 */
export async function confirmOwner(server, owner, fetchImpl = fetch) {
    const url = server + OWNER_PATH;
    let response;
    try {
        response = await fetchImpl(url, {
            redirect: "manual",
            headers: { accept: "application/json" },
            signal: AbortSignal.timeout(TIMEOUT_MS),
        });
    } catch {
        return `could not reach ${url}`;
    }
    if (response.status !== 200) {
        return `${url} answered HTTP ${response.status}, not 200`;
    }
    let text;
    try {
        text = await readCapped(response);
    } catch {
        return `${url} sent an answer that is too long`;
    }
    let body;
    try {
        body = JSON.parse(text);
    } catch {
        return `${url} did not answer with JSON`;
    }
    if (body?.owner !== owner) {
        return `${url} does not name owner ${owner}; set resolver.owner in knurd's settings and restart knurd`;
    }
    return null;
}

async function readCapped(response) {
    const reader = response.body.getReader();
    const chunks = [];
    let size = 0;
    for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        size += value.length;
        if (size > MAX_BODY_BYTES) {
            await reader.cancel();
            throw new Error("too long");
        }
        chunks.push(value);
    }
    return Buffer.concat(chunks).toString("utf8");
}
