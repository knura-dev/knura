import assert from "node:assert/strict";
import { chmodSync, mkdtempSync, readFileSync, statSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { endpointId, knotSerial, loadOrCreateIdentity, prepareStorage, randomPasscode } from "../src/bridge.js";
import { configFrom, loadConfig, loadSecret, parseProperties } from "../src/config.js";

const config = text => configFrom(parseProperties(text), "/srv/knura");

test("reads knots, labels and settings", () => {
    const c = config("# comment\nknots=desk-candle, front.door\nknot.desk-candle.label=Desk candle\npulse.seconds=5\n");
    assert.deepEqual(c.knots, [
        { name: "desk-candle", label: "Desk candle" },
        { name: "front.door", label: "front.door" },
    ]);
    assert.equal(c.pulseSeconds, 5);
    assert.equal(c.listenPort, 5541);
    assert.equal(c.matterPort, 5540);
    assert.equal(c.storageDir, "/srv/knura/knurgn-matter-storage");
    assert.equal(c.secretFile, "/srv/knura/knurgn-matter-secret");
});

test("refuses typos and mistakes instead of ignoring them", () => {
    assert.throws(() => config("knots=a\nknot.a.lable=x"), /unknown setting/);
    assert.throws(() => config("pulse.seconds=3"), /knots is missing/);
    assert.throws(() => config("knots=a,a"), /twice/);
    assert.throws(() => config("knots=has space"), /letters, digits/);
    assert.throws(() => config("knots=a\nknot.b.label=B"), /not in knots/);
    assert.throws(() => config("knots=a\nknot.a.label=" + "x".repeat(33)), /32/);
    assert.throws(() => config("knots=a\npulse.seconds=0"), /1 to 60/);
    assert.throws(() => config("knots=a\nlisten.port=5540"), /must differ/);
    assert.throws(() => config("knots=a\nknots=b"), /set twice/);
    assert.throws(() => config("knots=a\nsecret=whsec_x"), /doesn't belong/);
});

test("the secret file must be private", () => {
    const dir = mkdtempSync(join(tmpdir(), "knurgn-matter-"));
    const file = join(dir, "secret");
    writeFileSync(file, "whsec_" + Buffer.alloc(32, 1).toString("base64") + "\n");
    chmodSync(file, 0o644);
    assert.throws(() => loadSecret(file), /chmod 600/);
    chmodSync(file, 0o600);
    assert.equal(loadSecret(file).length, 32);
    assert.throws(() => loadSecret(join(dir, "missing")), /missing/);
});

test("loadConfig resolves paths next to the config file", () => {
    const dir = mkdtempSync(join(tmpdir(), "knurgn-matter-"));
    writeFileSync(join(dir, "k.properties"), "knots=a\nsecret.file=s\n");
    assert.equal(loadConfig(join(dir, "k.properties")).secretFile, join(dir, "s"));
});

test("passcodes are in range and never a forbidden one", () => {
    for (let i = 0; i < 1000; i++) {
        const p = randomPasscode();
        assert.ok(p >= 1 && p <= 99999998, String(p));
        assert.ok(![11111111, 12345678, 87654321].includes(p));
    }
});

test("identity is created once, kept private and reused", () => {
    const dir = join(mkdtempSync(join(tmpdir(), "knurgn-matter-")), "storage");
    prepareStorage(dir);
    assert.equal(statSync(dir).mode & 0o777, 0o700);
    const first = loadOrCreateIdentity(dir);
    assert.equal(statSync(join(dir, "identity.json")).mode & 0o777, 0o600);
    assert.deepEqual(loadOrCreateIdentity(dir), first);
    assert.equal(JSON.parse(readFileSync(join(dir, "identity.json"), "utf8")).passcode, first.passcode);
});

test("endpoint ids and serials are distinct, dot-free and short enough", () => {
    assert.notEqual(endpointId("a.b"), endpointId("a_b"));
    assert.ok(!endpointId("a.b").includes("."));
    assert.ok(knotSerial("0123456789abcdef", "x".repeat(64)).length <= 32);
    assert.notEqual(knotSerial("0123456789abcdef", "a"), knotSerial("0123456789abcdef", "b"));
});

test("the example config loads", () => {
    const c = loadConfig(new URL("../knurgn-matter.example.properties", import.meta.url).pathname);
    assert.deepEqual(c.knots, [{ name: "desk-candle", label: "Desk candle" }]);
});
