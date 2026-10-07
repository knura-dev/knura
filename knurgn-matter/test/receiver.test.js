import assert from "node:assert/strict";
import { test } from "node:test";
import { tapHandler, startReceiver } from "../src/receiver.js";
import { parseSecret, sign, verify } from "../src/signature.js";

const SECRET_TEXT = "whsec_" + Buffer.alloc(32, 7).toString("base64");
const SECRET = parseSecret(SECRET_TEXT);
const NOW_MS = Date.parse("2026-10-07T10:00:00Z");
const NOW_S = NOW_MS / 1000;

function event(id, knot = "desk-candle") {
    const [uid, counter] = id.split(":");
    return JSON.stringify({
        type: "tap.verified",
        timestamp: "2026-10-07T10:00:00Z",
        data: { id, knot, uid, counter: Number(counter), tappedAt: "2026-10-07T10:00:00Z", action: "home" },
    });
}

function signed(id, body, timestamp = NOW_S, secret = SECRET) {
    return {
        "webhook-id": id,
        "webhook-timestamp": String(timestamp),
        "webhook-signature": sign(secret, id, timestamp, body),
    };
}

function handler(now = () => NOW_MS) {
    const pressed = [];
    const handle = tapHandler({ secret: SECRET, knots: new Set(["desk-candle"]), press: k => pressed.push(k), now });
    return { handle, pressed };
}

test("matches knurgN's signature for the same input", () => {
    // Same vector as WebhookSignatureTest in knurgn: Standard Webhooks' published example.
    const secret = parseSecret("whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw");
    const body = '{"test": 2432232314}';
    assert.equal(sign(secret, "msg_p5jXN8AQM9LWM0D4loKWxJek", 1614265330, body),
        "v1,g0hM9SsE+OTPJTGt/tmIKtSyZlE3uFJELVlNIOLJ1OE=");
});

test("a signed tap presses its knot", () => {
    const { handle, pressed } = handler();
    const body = event("042D6B02936980:61");
    assert.deepEqual(handle(signed("042D6B02936980:61", body), body), { status: 204, detail: "pressed desk-candle" });
    assert.deepEqual(pressed, ["desk-candle"]);
});

test("the same tap delivered twice presses once", () => {
    const { handle, pressed } = handler();
    const body = event("042D6B02936980:61");
    handle(signed("042D6B02936980:61", body), body);
    assert.equal(handle(signed("042D6B02936980:61", body, NOW_S + 2), body).status, 200);
    assert.deepEqual(pressed, ["desk-candle"]);
});

test("refuses a wrong secret, a changed body and missing headers", () => {
    const { handle, pressed } = handler();
    const body = event("042D6B02936980:61");
    const other = parseSecret("whsec_" + Buffer.alloc(32, 8).toString("base64"));
    assert.equal(handle(signed("042D6B02936980:61", body, NOW_S, other), body).status, 401);
    assert.equal(handle(signed("042D6B02936980:61", body), body.replace("desk-candle", "front-door")).status, 401);
    assert.equal(handle({ "webhook-id": "042D6B02936980:61" }, body).status, 401);
    assert.deepEqual(pressed, []);
});

test("refuses an old or future timestamp", () => {
    const { handle, pressed } = handler();
    const body = event("042D6B02936980:61");
    assert.equal(handle(signed("042D6B02936980:61", body, NOW_S - 301), body).status, 401);
    assert.equal(handle(signed("042D6B02936980:61", body, NOW_S + 301), body).status, 401);
    assert.deepEqual(pressed, []);
});

test("refuses a knot the bridge doesn't show", () => {
    const { handle, pressed } = handler();
    const body = event("042D6B02936980:61", "garage");
    assert.equal(handle(signed("042D6B02936980:61", body), body).status, 422);
    assert.deepEqual(pressed, []);
});

test("refuses an event whose id doesn't match the signed webhook-id", () => {
    const { handle, pressed } = handler();
    const body = event("042D6B02936980:60");
    assert.equal(handle(signed("042D6B02936980:61", body), body).status, 400);
    assert.deepEqual(pressed, []);
});

test("refuses events that aren't verified taps", () => {
    const { handle } = handler();
    const body = JSON.stringify({ type: "tap.other", data: { id: "x:1", knot: "desk-candle" } });
    assert.equal(handle(signed("x:1", body), body).status, 400);
    assert.equal(handle(signed("x:2", "not json"), "not json").status, 400);
});

test("verify accepts any one of several signatures", () => {
    const body = event("042D6B02936980:61");
    const good = sign(SECRET, "042D6B02936980:61", NOW_S, body);
    const headers = { id: "042D6B02936980:61", timestamp: String(NOW_S), signature: "v1,AAAA " + good };
    assert.equal(verify(SECRET, headers, body, NOW_S), null);
});

test("parseSecret refuses short or malformed secrets without echoing them", () => {
    assert.throws(() => parseSecret("whsec_" + Buffer.alloc(8).toString("base64")), /24 to 64/);
    assert.throws(() => parseSecret("hunter2-not-a-secret"), e => !e.message.includes("hunter2"));
    assert.throws(() => parseSecret("whsec_!!!"), /base64/);
});

test("the HTTP endpoint serves only POST /tap on 127.0.0.1", async () => {
    const pressed = [];
    const handle = tapHandler({ secret: SECRET, knots: new Set(["desk-candle"]), press: k => pressed.push(k) });
    const quiet = { info() {}, warn() {} };
    const server = await startReceiver(0, handle, quiet);
    try {
        const { address, port } = server.address();
        assert.equal(address, "127.0.0.1");
        const base = `http://127.0.0.1:${port}`;

        assert.equal((await fetch(`${base}/tap`)).status, 405);
        assert.equal((await fetch(`${base}/other`, { method: "POST" })).status, 404);
        assert.equal((await fetch(`${base}/tap`, { method: "POST", body: "x".repeat(9000) })).status, 413);

        const id = "042D6B02936980:61";
        const body = event(id);
        const ts = Math.floor(Date.now() / 1000);
        const res = await fetch(`${base}/tap`, { method: "POST", body, headers: signed(id, body, ts) });
        assert.equal(res.status, 204);
        assert.deepEqual(pressed, ["desk-candle"]);
    } finally {
        server.close();
    }
});
