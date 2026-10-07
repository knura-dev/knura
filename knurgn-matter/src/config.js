// Reads knurgn-matter.properties: which knots the bridge shows, and where it keeps its state.
//
//   knots=desk-candle,front-door
//   knot.desk-candle.label=Desk candle
//   secret.file=knurgn-matter-secret
//
// Unknown settings are errors, so a typo can't silently change what the bridge does.

import { readFileSync, statSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { parseSecret } from "./signature.js";

const NAME = /^[A-Za-z0-9._-]{1,64}$/;
/** Matter limits device names (NodeLabel) to 32 characters. */
const MAX_LABEL = 32;

export const DEFAULTS = Object.freeze({
    listenPort: 5541,
    matterPort: 5540,
    pulseSeconds: 3,
    storageDir: "knurgn-matter-storage",
    secretFile: "knurgn-matter-secret",
});

/** Parses Java-style properties: key=value lines, # or ! comments. Enough for this file. */
export function parseProperties(text) {
    const props = new Map();
    for (const raw of text.split(/\r?\n/)) {
        const line = raw.trim();
        if (line === "" || line.startsWith("#") || line.startsWith("!")) continue;
        const eq = line.search(/[=:]/);
        if (eq < 0) throw new Error(`can't read line "${line}": expected key=value`);
        const key = line.slice(0, eq).trim();
        if (props.has(key)) throw new Error(`${key} is set twice`);
        props.set(key, line.slice(eq + 1).trim());
    }
    return props;
}

/** Turns parsed properties into a config. Paths are relative to {@code dir}. */
export function configFrom(props, dir) {
    const config = {
        knots: [],
        listenPort: DEFAULTS.listenPort,
        matterPort: DEFAULTS.matterPort,
        pulseSeconds: DEFAULTS.pulseSeconds,
        storageDir: resolve(dir, DEFAULTS.storageDir),
        secretFile: resolve(dir, DEFAULTS.secretFile),
    };
    const labels = new Map();
    for (const [key, value] of props) {
        const parts = key.split(".");
        if (key === "knots") {
            for (const name of value.split(",").map(s => s.trim())) {
                if (!NAME.test(name)) {
                    throw new Error(`knots: use 1 to 64 letters, digits, '.', '_' or '-' per knot (got "${name}")`);
                }
                if (config.knots.some(k => k.name === name)) throw new Error(`knots lists ${name} twice`);
                config.knots.push({ name });
            }
        } else if (parts.length >= 3 && parts[0] === "knot" && parts.at(-1) === "label") {
            const name = parts.slice(1, -1).join(".");
            if (value.length < 1 || value.length > MAX_LABEL || /[\u0000-\u001f\u007f]/.test(value)) {
                throw new Error(`${key}: a label is 1 to ${MAX_LABEL} characters`);
            }
            labels.set(name, value);
        } else if (key === "listen.port") {
            config.listenPort = port(key, value);
        } else if (key === "matter.port") {
            config.matterPort = port(key, value);
        } else if (key === "pulse.seconds") {
            config.pulseSeconds = number(key, value, 1, 60);
        } else if (key === "storage.dir") {
            config.storageDir = resolve(dir, value);
        } else if (key === "secret.file") {
            config.secretFile = resolve(dir, value);
        } else if (key.endsWith("secret")) {
            throw new Error(`${key}: the secret doesn't belong in this file; put it in secret.file`);
        } else {
            throw new Error(`unknown setting ${key}`);
        }
    }
    if (config.knots.length === 0) {
        throw new Error("knots is missing: list the knot names from knurgn.properties, like knots=desk-candle");
    }
    for (const [name, label] of labels) {
        const knot = config.knots.find(k => k.name === name);
        if (!knot) throw new Error(`knot.${name}.label: ${name} is not in knots`);
        knot.label = label;
    }
    for (const knot of config.knots) {
        knot.label ??= knot.name.slice(0, MAX_LABEL);
    }
    if (config.listenPort === config.matterPort) {
        throw new Error("listen.port and matter.port must differ");
    }
    return config;
}

/** Loads the config file. */
export function loadConfig(file) {
    return configFrom(parseProperties(readFileSync(file, "utf8")), dirname(resolve(file)));
}

/** Reads the shared secret, refusing the file if anyone but its owner can read or change it. */
export function loadSecret(file) {
    let stat;
    try {
        stat = statSync(file);
    } catch {
        throw new Error(`${file} is missing; see the test guide for how to make it`);
    }
    if (process.platform !== "win32" && (stat.mode & 0o077) !== 0) {
        throw new Error(`${file} can be read or changed by other users; run: chmod 600 ${file}`);
    }
    return parseSecret(readFileSync(file, "utf8"));
}

function port(key, value) {
    return number(key, value, 1, 65535);
}

function number(key, value, min, max) {
    if (!/^\d+$/.test(value) || Number(value) < min || Number(value) > max) {
        throw new Error(`${key}: expected a whole number from ${min} to ${max} (got "${value}")`);
    }
    return Number(value);
}
