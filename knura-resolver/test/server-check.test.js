import assert from "node:assert/strict";
import { test } from "node:test";
import { createServer } from "node:http";
import { confirmOwner } from "../src/server-check.js";

async function withServer(handler, run) {
    const server = createServer(handler);
    await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
    try {
        await run(`http://127.0.0.1:${server.address().port}`);
    } finally {
        server.close();
    }
}

test("a server that names the owner is confirmed", async () => {
    await withServer(
        (req, res) => {
            assert.equal(req.url, "/.well-known/knura-owner");
            res.writeHead(200, { "content-type": "application/json" });
            res.end('{"owner":"rq5tw8np3a"}');
        },
        async base => assert.equal(await confirmOwner(base, "rq5tw8np3a"), null),
    );
});

test("a server that names someone else, or nobody, is refused", async () => {
    await withServer(
        (req, res) => res.end('{"owner":"zzzzzzzzzz"}'),
        async base => assert.match(await confirmOwner(base, "rq5tw8np3a"), /does not name owner/),
    );
    await withServer(
        (req, res) => res.end("<html>hello</html>"),
        async base => assert.match(await confirmOwner(base, "rq5tw8np3a"), /JSON/),
    );
    await withServer(
        (req, res) => {
            res.writeHead(404);
            res.end();
        },
        async base => assert.match(await confirmOwner(base, "rq5tw8np3a"), /HTTP 404/),
    );
});

test("redirects are not followed", async () => {
    await withServer(
        (req, res) => {
            res.writeHead(302, { location: "https://elsewhere.example.com/.well-known/knura-owner" });
            res.end();
        },
        async base => assert.match(await confirmOwner(base, "rq5tw8np3a"), /HTTP 302/),
    );
});

test("long answers are cut off", async () => {
    await withServer(
        (req, res) => res.end(JSON.stringify({ owner: "rq5tw8np3a", pad: "x".repeat(5000) })),
        async base => assert.match(await confirmOwner(base, "rq5tw8np3a"), /too long/),
    );
});

test("an unreachable server is refused", async () => {
    assert.match(await confirmOwner("http://127.0.0.1:1", "rq5tw8np3a"), /could not reach/);
});
