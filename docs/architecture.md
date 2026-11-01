# How Knura fits together

A one-page map for new contributors: what happens when someone taps a tag, which module does
each step, and where the keys live. The [README](../README.md) has the commands; this page has
the why.

## A tap, start to finish

```
 NTAG 424 DNA tag          phone             (optional)            your computer
 ┌──────────────┐   NFC  ┌───────┐  HTTPS  ┌──────────────┐ HTTPS ┌──────────────────────────┐
 │ makes a new  │ ─────▶ │ opens │ ──────▶ │ tap.knura.io │ ────▶ │ knurd                    │
 │ link per tap │        │ link  │         │ (resolver)   │       │  1. throttle             │
 └──────────────┘        └───────┘         └──────────────┘       │  2. check MAC (core)     │
                                                                  │  3. counter must go up   │
                                                                  │  4. ok → knurgN          │
                                                                  └────────────┬─────────────┘
                                                                               │ signed event
                                                     ┌─────────────────────────┼──────────────────┐
                                                     ▼                         ▼                  ▼
                                               your webhook            Home Assistant      knurgn-matter
                                                                                           (Apple/Google
                                                                                            Home, Alexa)
```

1. **The tag** has its own AES keys, written by `knurctl provision`. On every tap it raises a
   read counter and writes a fresh link: `?e=<encrypted UID and counter>&c=<MAC>`. The phone
   just opens that link; no app is needed.
2. **tap.knura.io** (`knura-resolver`, optional) looks up the owner id in the link and sends
   the phone on to that owner's registered knurd. It holds no tag keys and cannot read or make
   a tap, so it is a convenience, not part of the trust chain.
3. **knurd** limits how fast taps arrive (`TapThrottle`), then `TapVerifier` uses knura-core's
   `SdmVerifier` to decrypt the UID and counter and check the MAC with that tag's key. Only
   after the MAC checks out does it move the tag's counter forward (`CounterStore`), so forged
   taps can't use up counters. A counter that isn't higher than the last one is a replay.
4. **knurgN** gets only taps that came back `ok`. It finds the tag's rule by its verified UID
   (nothing in the link can pick the action) and delivers it through a connector with retries
   in the background.

With a hosted account instead of your own knurd, the account does steps 2 and 3, and
**knura-link** at home receives the verified tap over an outgoing WebSocket and runs step 4 with
your own knurgN rules and secrets.

## Modules

| Module | Owns | Depends on |
| --- | --- | --- |
| `knura-core` | AES-CMAC, key diversification, tap verification (`sdm`), talking to the tag (`ntag424`), provisioning, claim codes, resolver and admin-login helpers. JDK only. | nothing |
| `knurctl` | The CLI: PC/SC reader, `provision`/`tap`/`info`/`reset`, `admin ...` client, resolver registration. | core |
| `knurd` | Tap HTTP server, replay counters, the admin API on its own port, phone relay for the Android app, audit log. JDK HTTP server, no third-party libraries. | core, knurgn |
| `knurgn` | Actions: rules per tag (`Knot`), connectors (webhook, Home Assistant, Matter bridge), Standard Webhooks signatures, delivery and retries. | nothing |
| `knura-link` | Home link for hosted accounts: pairing, outgoing WebSocket, hands taps to knurgN. | core, knurgn |
| `knurgn-matter` | Node.js Matter bridge: each tag shows up as a contact sensor. | (separate npm project) |
| `knura-resolver` | Node.js AWS Lambda behind tap.knura.io. | (separate npm project) |
| `android` | The Knura app: sets up, claims and checks tags through knurd's admin API. | (separate Gradle build) |

knura-core has no third-party runtime dependencies on purpose: it is the code everything else
trusts, so it should be small enough to read in one sitting.

## Where the keys live

| Secret | Where | What it protects |
| --- | --- | --- |
| `master.key` | `knurd.properties` or `master.key.file` (chmod 600) on your computer | Every tag's keys are worked out from it plus the tag's UID (`TagKeyDerivation`, NXP AN10922). Losing it means you can't verify or rewrite your tags. |
| Per-tag keys | On the tag only; knurd recomputes them | Copying one tag's keys doesn't help with any other tag. |
| Replay counters | `knurd-counters.properties` | Never restore an older copy: lower counters let used links work again. |
| `resolver.key` | `knurd.properties` | Who may change where tap.knura.io sends your taps. |
| Admin and phone tokens | `knurctl` creds file / Android Keystore; knurd keeps only SHA-256 | Reading tags and taps, and setting tags up from the phone. |
| Webhook secrets, Home Assistant ids | `knurgn-secrets.properties` (knurd refuses it if others can read it) | Your own automations. |

Backups (`knurctl backup`, `io.knura.core.backup`): `BackupFile` is a passphrase-locked zip of the
settings folder (AES-256-GCM, PBKDF2-HMAC-SHA256), `RecoverySheet` writes a key as checked hex rows
for paper, and `BackupRecord` keeps `knura-backup.properties`, which holds only a short key ID and
dates so knurctl, knurd and the app can remind you. The backup format and the sheet's check are
read back by later versions, so treat them as fixed too.

Keys never go to the phone: when the app sets up a tag, knurd works out the keys and sends each
command through the admin API, and the phone only passes bytes to the tag. No real or
development keys belong in this repository; tests use NXP's published example keys.

## The Android app

The app lives in `android/`, a separate Gradle build (`./gradlew -p android assembleDebug`, tests
with `testDebugUnitTest`), so the Java build never needs the Android SDK. Kotlin, Android 11+
(minSdk 30), target SDK 35. The only outside library is Google's code scanner for QR codes.

```
 MainActivity + Ui.kt ──── screens built in code (no XML layouts, no Compose)
   │        │
   │        └── Snapshot + Notes ── last server answer saved on the phone; Notes compares answers
   │                 │               to make notifications. Widgets.kt and CheckTapTile read it.
   ▼
 Pairing · KnurdViews · TagResults · ClaimCodes ── plain classes, unit-tested without a phone
   │
 KnurdClient ── HTTPS + token per request ──▶ knurd admin API (cert pinned by SHA-256 from the QR)
   │      └── TokenVault: token encrypted with an Android Keystore key,      or a hosted account
   │          unlocked for 30 s by fingerprint or screen lock                 (normal TLS)
   ▼
 NFC reader mode (IsoDep) ── relays APDUs the server built; keys never reach the phone
```

- **Pairing:** `knurctl admin pair-phone` shows a `knura://pair` QR holding the address, the
  certificate pin and a one-time `knurp_` code; the app swaps the code at `POST /v1/pair` for its
  own token. A hosted account answers the same requests under `/o/<owner>/v1`, so every screen
  works with both.
- **Shipping:** `android-latest.yml` keeps a pre-release APK up to date on app changes;
  `release.yml` attaches `knura-<version>.apk` signed with the release key (GitHub secrets
  `ANDROID_KEYSTORE*`) to each release. Release-signed builds install over each other.

## Ways to run it

| Setup | What runs | How phones reach it |
| --- | --- | --- |
| Release zips + systemd | knurd (and knurctl to set tags up) | Tailscale Funnel or an HTTPS reverse proxy in front of the tap port (8424). The admin port (8425) stays on the home network. |
| Docker / Kubernetes | Image from `knurd/Dockerfile`; Helm chart in `deploy/helm/knura` | The chart's ingress. CI installs the chart on kind and taps it. |
| Hosted account | Only `knura-link` at home | The account checks taps and sends verified ones down the home link; your knurgN rules run at home. |

## CI and releases

- `ci.yml` on every pull request: Java build and tests, the Android app (APK as an artifact),
  Node tests for `knura-resolver` and `knurgn-matter`, and the Helm chart on kind.
- `release.yml` on a `v*` tag: knurctl, knurd and knura-link zips, the signed APK, `SHA256SUMS`
  and signed build provenance on the GitHub release. Actions are pinned to exact commits.
- tap.knura.io is deployed by hand: `npm run package` in `knura-resolver/` and upload the zip to
  its Lambda, as its README describes. Nothing in this repository's CI holds AWS credentials.

## Things that must not change

- **Key derivation** (`TagKeyDerivation`): the derived values are pinned by tests. Changing them
  would make every provisioned tag stop verifying.
- **Counter before action**: knurd records the counter before handing a tap to knurgN, and only
  `ok` taps reach knurgN.
- **Sensitive actions**: locks, garage doors and alarms stay out until there is confirmation from
  an approved phone.

See [how-we-review.md](how-we-review.md) for how changes are checked, and
[CONTRIBUTING.md](../CONTRIBUTING.md) to make one.
