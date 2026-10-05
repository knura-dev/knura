# Knura

Open-source NFC trust and actions platform for NTAG 424 DNA tags: verify that a tap came from a
genuine tag, then trigger actions from it.

| Module | What it is | Status |
| --- | --- | --- |
| `knura-core` | Crypto and NTAG 424 DNA logic, JDK only | SDM tap verifier |
| `knurctl` | Provisioning and management CLI | placeholder |
| `knurd` | Verification daemon | placeholder |

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
