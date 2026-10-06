# Knura

Open-source NFC trust and actions platform for NTAG 424 DNA tags: verify that a tap came from a
genuine tag, then trigger actions from it.

| Module | What it is | Status |
| --- | --- | --- |
| `knura-core` | Crypto and NTAG 424 DNA logic, JDK only | SDM tap verifier, per-tag keys |
| `knurctl` | Provisioning and management CLI | placeholder |
| `knurd` | Verification daemon | HTTP tap endpoint with replay protection |

## Build

Requires JDK 21.

```sh
./gradlew build
```

## knura-core

`SdmVerifier` checks Secure Dynamic Messaging (SUN) taps:

- `verifyEncrypted` decrypts the PICC data (UID + read counter) with SDMMetaReadKey and checks
  the CMAC.
- `verifyPlain` checks the CMAC when the UID and counter are mirrored in clear.

Both return a `VerifiedTap` with the UID and read counter, or throw `SdmVerificationException`
with reason `MALFORMED` or `BAD_MAC`. Rejecting replayed counters is up to the caller.

`TagKeyDerivation` gives every tag its own keys, worked out from one master key and the tag's
UID with NXP's AN10922 key diversification (`KeyDiversification.aes128`). If someone copies the
keys out of one tag, the other tags stay safe, and a server needs only the master key, not a list
of every tag's keys. Key slots: 0 app master key, 1 SDMMetaReadKey, 2 SDMFileReadKey (3 and 4
spare). SDMMetaReadKey is the same for every tag under a master key, because the server needs it
to decrypt a tap before it knows which tag sent it. It only hides the UID and counter: forging a
tap still needs that tag's own SDMFileReadKey. The derivation is pinned by tests and must not
change, or provisioned tags would stop verifying.

Tests use NXP's published AN12196 example (all-zero keys), NXP's AN10922 example and RFC 4493
CMAC vectors. No real or development keys belong in this repository.

## knurd

knurd checks taps over HTTP. It verifies the SDM MAC with knura-core, then rejects replays: it
remembers the last read counter it accepted for each tag and refuses any tap whose counter is
not higher. Counters are saved to a file, so this survives restarts. It uses the JDK's built-in
HTTP server and has no third-party dependencies, so it runs the same in the cloud or on your
own machine.

Try it with NXP's example keys:

```sh
cp knurd/knurd.example.properties knurd.properties
./gradlew :knurd:run
# in another terminal:
curl 'http://127.0.0.1:8424/tap?e=EF963FF7828658A599F3041510671E88&c=94EED9EE65337086'
# {"status":"ok","uid":"04DE5F1EACC040","counter":61}
curl 'http://127.0.0.1:8424/tap?e=EF963FF7828658A599F3041510671E88&c=94EED9EE65337086'
# {"status":"replay","uid":"04DE5F1EACC040","counter":61,"detail":"counter already used"}
```

To have knurd work out each tag's keys from a master key instead, set `master.key` in place of
`sdm.metaReadKey` and `sdm.fileReadKey`. With NXP's AN10922 example master key:

```sh
printf 'master.key=00112233445566778899AABBCCDDEEFF\n' > knurd.properties
rm -f knurd-counters.properties   # the example above already used up this tag's counters
./gradlew :knurd:run
# in another terminal:
curl 'http://127.0.0.1:8424/tap?e=F5D049F6CEC31568AFAA3409EC06A9E8&c=53D5EC307125E7A6'
# {"status":"ok","uid":"04DE5F1EACC040","counter":1}
```

| Request | Meaning |
| --- | --- |
| `GET /tap?e=<PICC data>&c=<MAC>` | Encrypted mirror (AN12196 parameter names) |
| `GET /tap?uid=<UID>&ctr=<counter>&c=<MAC>` | Plain mirror |
| `GET /health` | `{"status":"up"}` |

`/tap` answers with JSON and one of: `ok` (200), `malformed` (400), `rejected` (403: bad MAC or
unknown tag), `replay` (409). `knurd.properties` and the counters file are git-ignored; set your
own keys there. For now the MAC must cover an empty input (SDMMACInputOffset equal to
SDMMACOffset). knurd listens on 127.0.0.1 by default; phones need to reach it over HTTPS, so put
a reverse proxy in front of it.
