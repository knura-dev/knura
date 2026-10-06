# Knura

Open-source NFC trust and actions platform for NTAG 424 DNA tags: verify that a tap came from a
genuine tag, then trigger actions from it.

| Module | What it is | Status |
| --- | --- | --- |
| `knura-core` | Crypto and NTAG 424 DNA logic, JDK only | SDM tap verifier |
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

Tests use NXP's published AN12196 example (all-zero keys) and RFC 4493 CMAC vectors. No real or
development keys belong in this repository.

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
