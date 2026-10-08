// A record signed with RFC 8032's first Ed25519 test key. knura-core's OwnerRecordTest checks the
// same bytes, so Java (which signs records) and the resolver (which checks them) agree.

import { createPrivateKey, createPublicKey, sign } from "node:crypto";
import { signedText } from "../src/record.js";

export const SEED = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60";
export const PUBLIC_KEY = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a";
export const OWNER = "zk7hq4m2xa";
export const SERVER = "https://fedora.tail113e80.ts.net";
export const SEQ = 1759881600000;
export const SIGNATURE =
    "f8f47dcdea335b450ae3758888bfe1785772f17d7c5f3cd2511a9a3b75020f04" +
    "09395aabd9a9fd56a8b51e2edf7a4ab05a0ddb7eb582c3221722f7ab5894fd0b";

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
