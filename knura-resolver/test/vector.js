// A record signed with RFC 8032's first Ed25519 test key. knura-core's OwnerRecordTest checks the
// same bytes, so Java (which signs records) and the resolver (which checks them) agree.

import { createPrivateKey, createPublicKey, sign } from "node:crypto";
import { signedText } from "../src/record.js";

export const SEED = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60";
export const PUBLIC_KEY = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a";
export const OWNER = "rq5tw8np3a";
export const SERVER = "https://knurd.example.ts.net";
export const SEQ = 1759881600000;
export const SIGNATURE =
    "ca378da0d9a347f7b12388edfdaddad62b3fe9f324915220783b669b49e7bfe8" +
    "2f26c3febdb7a24a9066846c248af8417321fd0836b6b738b95cecd0db45f405";

const PKCS8_PREFIX = Buffer.from("302e020100300506032b657004220420", "hex");

/** An update for owner signed with the test key, or with another 32-byte hex seed. */
export function signedUpdate(owner, server, seq, seed = SEED) {
    const key = createPrivateKey({
        key: Buffer.concat([PKCS8_PREFIX, Buffer.from(seed, "hex")]),
        format: "der",
        type: "pkcs8",
    });
    // The last 32 bytes of the SubjectPublicKeyInfo are the raw public key.
    const publicKey = createPublicKey(key).export({ format: "der", type: "spki" }).subarray(-32).toString("hex");
    const signature = sign(null, Buffer.from(signedText(owner, server, seq), "utf8"), key).toString("hex");
    return { server, seq, publicKey, signature };
}
