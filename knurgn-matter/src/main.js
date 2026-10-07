#!/usr/bin/env node
// knurgn-matter: shows each knot as a contact sensor in Apple Home, Google Home, Alexa and
// SmartThings, and "opens" it for a few seconds when knurgN sends a verified tap.
//
//   node src/main.js [--config knurgn-matter.properties]

import { Logger, LogLevel } from "@matter/main";
import { QrCode } from "@matter/main/types";
import { loadConfig, loadSecret } from "./config.js";
import { startBridge } from "./bridge.js";
import { startReceiver, tapHandler } from "./receiver.js";

function argValue(name, fallback) {
    const args = process.argv.slice(2);
    const i = args.indexOf(name);
    if (i < 0) return fallback;
    if (i + 1 >= args.length) throw new Error(`${name} needs a value`);
    return args[i + 1];
}

const log = {
    info: m => console.log(`${new Date().toISOString()} knurgn-matter: ${m}`),
    warn: m => console.warn(`${new Date().toISOString()} knurgn-matter: ${m}`),
    error: m => console.error(`${new Date().toISOString()} knurgn-matter: ${m}`),
};

async function main() {
    const configFile = argValue("--config", "knurgn-matter.properties");
    const config = loadConfig(configFile);
    const secret = loadSecret(config.secretFile);

    // matter.js is chatty at its default level; warnings and up are enough day to day.
    Logger.level = process.env.KNURGN_MATTER_DEBUG ? LogLevel.DEBUG : LogLevel.WARN;

    const bridge = await startBridge(config, log);
    const knots = new Set(config.knots.map(k => k.name));
    const handle = tapHandler({
        secret,
        knots,
        press: knot => bridge.press(knot).catch(e => log.error(`could not press ${knot}: ${e.message}`)),
    });
    const receiver = await startReceiver(config.listenPort, handle, log);

    log.info(`showing ${knots.size} knot(s): ${config.knots.map(k => `${k.name} ("${k.label}")`).join(", ")}`);
    log.info(`Matter on UDP port ${config.matterPort}; taps from knurgN on http://127.0.0.1:${config.listenPort}/tap`);
    const codes = bridge.pairing();
    if (codes) {
        log.info("not paired yet. Add it in your smart-home app with this code:");
        console.log(`\n${QrCode.get(codes.qrPairingCode)}\n    Manual pairing code: ${codes.manualPairingCode}\n`);
    } else {
        log.info("already paired. To add another app, turn on pairing mode for \"Knura taps\" in the app you used first.");
    }

    bridge.server.lifecycle.commissioned.on(() => log.info("paired with a smart-home app"));
    bridge.server.lifecycle.decommissioned.on(() => log.info("removed from every app; it can be paired again with the same code"));

    let stopping = false;
    const stop = async () => {
        if (stopping) return;
        stopping = true;
        receiver.close();
        await bridge.close();
        process.exit(0);
    };
    process.on("SIGINT", stop);
    process.on("SIGTERM", stop);
}

main().catch(e => {
    log.error(e.message);
    process.exit(1);
});
