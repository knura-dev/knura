import assert from "node:assert/strict";
import { test } from "node:test";
import { OWNER_ID, checkServer, checkUpdateShape, signedText, verifySignature } from "../src/record.js";
import { OWNER, PUBLIC_KEY, SEQ, SERVER, SIGNATURE, signedUpdate } from "./vector.js";

test("the shared test vector verifies and matches what Node signs", () => {
    const update = { server: SERVER, seq: SEQ, publicKey: PUBLIC_KEY, signature: SIGNATURE };
    assert.equal(verifySignature(OWNER, update), true);
    assert.deepEqual(signedUpdate(OWNER, SERVER, SEQ), update);
    assert.equal(
        signedText(OWNER, SERVER, SEQ),
        "knura-resolver-v1\nowner=zk7hq4m2xa\nserver=https://fedora.tail113e80.ts.net\nseq=1759881600000\n",
    );
});

test("a signature is bound to the owner, server and seq", () => {
    const update = { server: SERVER, seq: SEQ, publicKey: PUBLIC_KEY, signature: SIGNATURE };
    assert.equal(verifySignature("zk7hq4m2xb", update), false);
    assert.equal(verifySignature(OWNER, { ...update, server: "https://evil.example.com" }), false);
    assert.equal(verifySignature(OWNER, { ...update, seq: SEQ + 1 }), false);
});

test("owner IDs", () => {
    assert.ok(OWNER_ID.test("zk7hq4m2xa"));
    for (const bad of ["zk7hq4m2x", "zk7hq4m2xaa", "Zk7hq4m2xa", "zk7hq4m2xl", "zk7hq4m2xo", "zk7hq4m2x0", "zk7hq4m2x1", "api/v1/own"]) {
        assert.equal(OWNER_ID.test(bad), false, bad);
    }
});

test("servers must be plain https host names", () => {
    for (const good of ["https://fedora.tail113e80.ts.net", "https://knurd.example.com:8443", "https://example.com/knurd"]) {
        assert.equal(checkServer(good), null, good);
    }
    for (const bad of [
        "http://fedora.tail113e80.ts.net",
        "https://fedora.tail113e80.ts.net/",
        "https://1.2.3.4",
        "https://[::1]",
        "https://localhost",
        "https://printer.local",
        "https://user@example.com",
        "https://example.com?x=1",
        "https://example.com#x",
        "https://Example.com",
        "https://example.com:443",
        "https://exa mple.com",
        "javascript:alert(1)",
        "https://" + "a".repeat(200) + ".com",
        "",
        42,
    ]) {
        assert.notEqual(checkServer(bad), null, String(bad));
    }
    assert.equal(checkServer("http://127.0.0.1:8424"), "server must start with https://");
    assert.equal(checkServer("http://127.0.0.1:8424", { allowLoopbackHttp: true }), null);
    assert.notEqual(checkServer("http://example.com", { allowLoopbackHttp: true }), null);
});

test("update shape", () => {
    const good = { server: SERVER, seq: SEQ, publicKey: PUBLIC_KEY, signature: SIGNATURE };
    assert.equal(checkUpdateShape(good), null);
    assert.notEqual(checkUpdateShape(null), null);
    assert.notEqual(checkUpdateShape({ ...good, seq: 0 }), null);
    assert.notEqual(checkUpdateShape({ ...good, seq: "1" }), null);
    assert.notEqual(checkUpdateShape({ ...good, seq: 1.5 }), null);
    assert.notEqual(checkUpdateShape({ ...good, seq: 2 ** 53 }), null);
    assert.notEqual(checkUpdateShape({ ...good, publicKey: PUBLIC_KEY.toUpperCase() }), null);
    assert.notEqual(checkUpdateShape({ ...good, signature: SIGNATURE.slice(2) }), null);
});
