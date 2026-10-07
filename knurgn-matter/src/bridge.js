// The Matter side: one bridge that Apple Home, Google Home, Alexa or SmartThings pair with, showing
// one contact sensor per knot. A verified tap "opens" that sensor for a few seconds; the person's
// own routine in their smart-home app does the rest ("when Desk candle opens, turn on the lamp").
//
// The bridge only has its own sensors. It can't see or control any other device in the home.

import { createHash, randomBytes, randomInt } from "node:crypto";
import { chmodSync, existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { Endpoint, Environment, ServerNode, VendorId } from "@matter/main";
import { BridgedDeviceBasicInformationServer } from "@matter/main/behaviors/bridged-device-basic-information";
import { ContactSensorDevice } from "@matter/main/devices/contact-sensor";
import { AggregatorEndpoint } from "@matter/main/endpoints/aggregator";

/** Matter's test vendor and product ids, for devices that are not certified. */
const TEST_VENDOR_ID = 0xfff1;
const TEST_PRODUCT_ID = 0x8000;

/** Passcodes Matter forbids because they are too easy to guess. */
const FORBIDDEN_PASSCODES = new Set([
    0, 11111111, 22222222, 33333333, 44444444, 55555555, 66666666, 77777777, 88888888, 99999999,
    12345678, 87654321,
]);

/** Picks a random setup passcode Matter allows (1 to 99999998, not one of the forbidden ones). */
export function randomPasscode() {
    for (;;) {
        const passcode = randomInt(1, 99999999);
        if (!FORBIDDEN_PASSCODES.has(passcode)) return passcode;
    }
}

/**
 * The bridge's identity and pairing code, made once and kept in the storage folder. Kept stable so
 * a restart doesn't make the home app see a new device.
 */
export function loadOrCreateIdentity(storageDir) {
    const file = join(storageDir, "identity.json");
    if (existsSync(file)) {
        return JSON.parse(readFileSync(file, "utf8"));
    }
    const identity = {
        uniqueId: randomBytes(8).toString("hex"),
        passcode: randomPasscode(),
        discriminator: randomInt(0, 4096),
    };
    writeFileSync(file, JSON.stringify(identity, null, 2) + "\n", { mode: 0o600 });
    return identity;
}

/** Makes the storage folder private to this user: it holds the bridge's Matter keys. */
export function prepareStorage(storageDir) {
    mkdirSync(storageDir, { recursive: true, mode: 0o700 });
    chmodSync(storageDir, 0o700);
}

/** Matter endpoint ids can't contain ".", which knot names may; hex keeps every name distinct. */
export function endpointId(knotName) {
    return "knot-" + Buffer.from(knotName, "utf8").toString("hex");
}

/** A stable serial number per knot, within Matter's 32-character limit. */
export function knotSerial(uniqueId, knotName) {
    const hash = createHash("sha256").update(knotName, "utf8").digest("hex");
    return `knura-${uniqueId.slice(0, 8)}-${hash.slice(0, 8)}`;
}

/**
 * Starts the Matter bridge. Tests pass {@code online: false} to build the devices without going on
 * the network.
 *
 * @returns {Promise<{press: (knot: string) => Promise<void>, pairing: () => object|undefined,
 *     server: ServerNode, close: () => Promise<void>}>}
 */
export async function startBridge(config, log = console, { online = true } = {}) {
    const environment = Environment.default;
    prepareStorage(config.storageDir);
    const identity = loadOrCreateIdentity(config.storageDir);

    environment.vars.set("path.root", config.storageDir);
    environment.vars.set("storage.path", config.storageDir);

    const server = await ServerNode.create({
        id: `knurgn-matter-${identity.uniqueId}`,
        network: { port: config.matterPort },
        commissioning: { passcode: identity.passcode, discriminator: identity.discriminator },
        productDescription: { name: "Knura taps", deviceType: AggregatorEndpoint.deviceType },
        basicInformation: {
            vendorName: "Knura",
            vendorId: VendorId(TEST_VENDOR_ID),
            productName: "knurgN Matter bridge",
            productLabel: "Tap bridge",
            nodeLabel: "Knura taps",
            hardwareVersion: 1,
            hardwareVersionString: "1",
            softwareVersion: 1,
            softwareVersionString: "0.1.0",
            productId: TEST_PRODUCT_ID,
            serialNumber: `knura-${identity.uniqueId}`,
            uniqueId: identity.uniqueId,
        },
    });

    const aggregator = new Endpoint(AggregatorEndpoint, { id: "knots" });
    await server.add(aggregator);

    const sensors = new Map();
    for (const knot of config.knots) {
        const sensor = new Endpoint(ContactSensorDevice.with(BridgedDeviceBasicInformationServer), {
            id: endpointId(knot.name),
            // stateValue true means "closed". Sensors rest closed and open briefly on a tap.
            booleanState: { stateValue: true },
            bridgedDeviceBasicInformation: {
                nodeLabel: knot.label,
                productName: "Knura knot",
                productLabel: knot.label,
                serialNumber: knotSerial(identity.uniqueId, knot.name),
                reachable: true,
            },
        });
        await aggregator.add(sensor);
        // The state is not kept across restarts, but make sure no sensor starts out open.
        await sensor.set({ booleanState: { stateValue: true } });
        sensors.set(knot.name, { endpoint: sensor, timer: undefined });
    }

    if (online) await server.start();

    const pulseMs = config.pulseSeconds * 1000;

    async function press(knotName) {
        const sensor = sensors.get(knotName);
        if (!sensor) throw new Error(`no sensor for knot ${knotName}`);
        if (sensor.timer) {
            // Still open from a tap a moment ago: keep it open a little longer. Taps this close
            // together count as one, which also stops a flood of taps from spamming routines.
            clearTimeout(sensor.timer);
        } else {
            await sensor.endpoint.set({ booleanState: { stateValue: false } });
        }
        sensor.timer = setTimeout(() => {
            sensor.timer = undefined;
            sensor.endpoint
                .set({ booleanState: { stateValue: true } })
                .catch(e => log.error(`could not close sensor for ${knotName}: ${e.message}`));
        }, pulseMs);
    }

    function pairing() {
        if (server.lifecycle.isCommissioned) return undefined;
        return server.state.commissioning.pairingCodes;
    }

    async function close() {
        for (const sensor of sensors.values()) clearTimeout(sensor.timer);
        await server.close();
    }

    return { press, pairing, server, close };
}
