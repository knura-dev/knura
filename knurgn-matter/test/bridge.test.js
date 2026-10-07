import assert from "node:assert/strict";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { Environment } from "@matter/main";
import { MdnsService } from "@matter/main/protocol";
import { endpointId, startBridge } from "../src/bridge.js";

// Building a Matter node starts mDNS, which needs IPv6 and multicast that test machines may not
// have. This test never goes on the network, so a do-nothing stand-in is enough.
Environment.default.set(
    MdnsService,
    Object.create(MdnsService.prototype, {
        construction: { value: Promise.resolve() },
        close: { value: async () => {} },
        names: { value: {} },
    }),
);

test("each knot is a closed contact sensor that opens for a pulse when pressed", async () => {
    const storageDir = join(mkdtempSync(join(tmpdir(), "knurgn-matter-")), "storage");
    const config = {
        knots: [{ name: "desk-candle", label: "Desk candle" }, { name: "front.door", label: "Front door" }],
        listenPort: 5541,
        matterPort: 5540,
        pulseSeconds: 1,
        storageDir,
    };
    const bridge = await startBridge(config, console, { online: false });
    try {
        const knots = bridge.server.parts.get("knots");
        const desk = knots.parts.get(endpointId("desk-candle"));
        const door = knots.parts.get(endpointId("front.door"));
        assert.equal(desk.state.bridgedDeviceBasicInformation.nodeLabel, "Desk candle");
        assert.equal(desk.state.booleanState.stateValue, true);
        assert.ok(bridge.pairing().manualPairingCode.length >= 11);

        await bridge.press("desk-candle");
        assert.equal(desk.state.booleanState.stateValue, false, "open right after the tap");
        assert.equal(door.state.booleanState.stateValue, true, "other knots stay closed");
        await new Promise(r => setTimeout(r, 1300));
        assert.equal(desk.state.booleanState.stateValue, true, "closed again after the pulse");

        await assert.rejects(bridge.press("garage"), /no sensor/);
    } finally {
        await bridge.close();
    }
});
