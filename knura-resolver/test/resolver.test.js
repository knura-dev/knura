import assert from "node:assert/strict";
import { test } from "node:test";
import { createHandler, parseTap } from "../src/resolver.js";
import { MemoryStore } from "../src/memory-store.js";
import { OWNER, SERVER, SEQ, signedUpdate } from "./vector.js";

const E = "EF963FF7828658A599F3041510671E88";
const C = "94EED9EE65337086";
const OTHER_SEED = "4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb";

function setup({ confirm = async () => null } = {}) {
    const store = new MemoryStore();
    const confirmed = [];
    const handle = createHandler({
        store,
        confirm: async (server, owner) => {
            confirmed.push([server, owner]);
            return confirm(server, owner);
        },
        now: () => new Date("2026-10-08T00:00:00Z"),
        log: () => {},
    });
    return { store, handle, confirmed };
}

const get = (path, query = "") => ({ requestContext: { http: { method: "GET" } }, rawPath: path, rawQueryString: query });
const put = (owner, body) => ({
    requestContext: { http: { method: "PUT" } },
    rawPath: `/api/v1/owners/${owner}`,
    rawQueryString: "",
    body: typeof body === "string" ? body : JSON.stringify(body),
});

test("a registered owner's tap is sent to their knurd with e and c unchanged", async () => {
    const { handle } = setup();
    assert.equal((await handle(put(OWNER, signedUpdate(OWNER, SERVER, SEQ)))).statusCode, 200);

    const response = await handle(get(`/${OWNER}`, `e=${E}&c=${C}`));
    assert.equal(response.statusCode, 302);
    assert.equal(response.headers.location, `${SERVER}/tap?e=${E}&c=${C}`);
    assert.equal(response.headers["cache-control"], "no-store");
    assert.equal(response.headers["referrer-policy"], "no-referrer");
});

test("a tap for an unknown owner gets a page, not a redirect", async () => {
    const { handle } = setup();
    const response = await handle(get(`/${OWNER}`, `e=${E}&c=${C}`));
    assert.equal(response.statusCode, 404);
    assert.equal(response.headers.location, undefined);
    assert.match(response.headers["content-type"], /text\/html/);
});

test("only a tag's exact e= and c= are passed on", async () => {
    for (const query of [
        "",
        `e=${E}`,
        `e=${E}&c=${C}&next=https://evil.example.com`,
        `e=${E}&c=${C}&c=${C}`,
        `e=${E.slice(1)}&c=${C}`,
        `e=${E}&c=${C}%0d%0aSet-Cookie:x`,
        `e=${E}&c=${C}x`,
        `uid=04DE5F1EACC040&ctr=000001&c=${C}`,
    ]) {
        assert.equal(parseTap(query), null, query);
    }
    assert.deepEqual(parseTap(`c=${C.toLowerCase()}&e=${E}`), { e: E, c: C.toLowerCase() });

    const { handle } = setup();
    await handle(put(OWNER, signedUpdate(OWNER, SERVER, SEQ)));
    const response = await handle(get(`/${OWNER}`, `e=${E}&c=${C}&next=https://evil.example.com`));
    assert.equal(response.statusCode, 400);
    assert.equal(response.headers.location, undefined);
});

test("registering checks the server names the owner", async () => {
    const { handle, confirmed } = setup({ confirm: async () => "does not name owner" });
    const response = await handle(put(OWNER, signedUpdate(OWNER, SERVER, SEQ)));
    assert.equal(response.statusCode, 422);
    assert.deepEqual(confirmed, [[SERVER, OWNER]]);
    assert.equal((await handle(get(`/api/v1/owners/${OWNER}`))).statusCode, 404);
});

test("the record is served back with its signature", async () => {
    const { handle } = setup();
    const update = signedUpdate(OWNER, SERVER, SEQ);
    await handle(put(OWNER, update));
    const response = await handle(get(`/api/v1/owners/${OWNER}`));
    assert.equal(response.statusCode, 200);
    assert.deepEqual(JSON.parse(response.body), {
        owner: OWNER,
        ...update,
        updatedAt: "2026-10-08T00:00:00.000Z",
    });
});

test("the owner can move to a new server with a higher seq", async () => {
    const { handle } = setup();
    await handle(put(OWNER, signedUpdate(OWNER, SERVER, SEQ)));
    const moved = "https://knurd.example.com";
    assert.equal((await handle(put(OWNER, signedUpdate(OWNER, moved, SEQ + 1)))).statusCode, 200);
    const response = await handle(get(`/${OWNER}`, `e=${E}&c=${C}`));
    assert.equal(response.headers.location, `${moved}/tap?e=${E}&c=${C}`);
});

test("an old signed record can't be replayed", async () => {
    const { handle } = setup();
    const first = signedUpdate(OWNER, "https://old.example.com", SEQ);
    await handle(put(OWNER, first));
    await handle(put(OWNER, signedUpdate(OWNER, SERVER, SEQ + 1)));
    const replay = await handle(put(OWNER, first));
    assert.equal(replay.statusCode, 409);
    assert.equal(JSON.parse(replay.body).seq, SEQ + 1);
    const same = await handle(put(OWNER, signedUpdate(OWNER, SERVER, SEQ + 1)));
    assert.equal(same.statusCode, 409);
});

test("another key can't take over an owner ID", async () => {
    const { handle, confirmed } = setup();
    await handle(put(OWNER, signedUpdate(OWNER, SERVER, SEQ)));
    const response = await handle(put(OWNER, signedUpdate(OWNER, "https://evil.example.com", SEQ + 1, OTHER_SEED)));
    assert.equal(response.statusCode, 403);
    assert.equal(confirmed.length, 1);
    const tap = await handle(get(`/${OWNER}`, `e=${E}&c=${C}`));
    assert.equal(tap.headers.location, `${SERVER}/tap?e=${E}&c=${C}`);
});

test("a bad signature is refused before the server is contacted", async () => {
    const { handle, confirmed } = setup();
    const update = signedUpdate(OWNER, SERVER, SEQ);
    const forged = { ...update, server: "https://evil.example.com" };
    assert.equal((await handle(put(OWNER, forged))).statusCode, 403);
    // Signed for a different owner ID.
    assert.equal((await handle(put("rq5tw8np3b", update))).statusCode, 403);
    assert.deepEqual(confirmed, []);
});

test("malformed updates", async () => {
    const { handle } = setup();
    assert.equal((await handle(put(OWNER, "not json"))).statusCode, 400);
    assert.equal((await handle(put(OWNER, "x".repeat(3000)))).statusCode, 413);
    assert.equal((await handle(put("NOT-AN-ID", signedUpdate(OWNER, SERVER, SEQ)))).statusCode, 400);
    assert.equal((await handle(put(OWNER, signedUpdate(OWNER, "http://fedora.example.com", SEQ)))).statusCode, 400);
    assert.equal((await handle(put(OWNER, signedUpdate(OWNER, "http://127.0.0.1:8424", SEQ)))).statusCode, 400);
});

test("base64 request bodies are decoded", async () => {
    const { handle } = setup();
    const event = put(OWNER, signedUpdate(OWNER, SERVER, SEQ));
    event.body = Buffer.from(event.body).toString("base64");
    event.isBase64Encoded = true;
    assert.equal((await handle(event)).statusCode, 200);
});

test("other paths and methods", async () => {
    const { handle } = setup();
    const home = await handle(get("/"));
    assert.equal(home.statusCode, 302);
    assert.equal(home.headers.location, "https://knura.io");
    assert.equal((await handle(get("/wp-admin"))).statusCode, 404);
    assert.equal((await handle(get(`/${OWNER}/extra`, `e=${E}&c=${C}`))).statusCode, 404);
    const post = { ...get(`/${OWNER}`), requestContext: { http: { method: "POST" } } };
    assert.equal((await handle(post)).statusCode, 405);
    const del = { ...get(`/api/v1/owners/${OWNER}`), requestContext: { http: { method: "DELETE" } } };
    assert.equal((await handle(del)).statusCode, 405);
});

test("a store failure gives a 500 page without details", async () => {
    const handle = createHandler({
        store: { get: async () => { throw new Error("DynamoDB is down"); } },
        log: () => {},
    });
    const response = await handle(get(`/${OWNER}`, `e=${E}&c=${C}`));
    assert.equal(response.statusCode, 500);
    assert.doesNotMatch(response.body, /DynamoDB/);
});

test("two racing updates can't both win", async () => {
    const store = new MemoryStore();
    const update = { owner: OWNER, ...signedUpdate(OWNER, SERVER, SEQ), updatedAt: "x" };
    assert.equal(await store.put(update), true);
    assert.equal(await store.put(update), false);
    assert.equal(await store.put({ ...update, seq: SEQ + 1, publicKey: "00".repeat(32) }), false);
});
