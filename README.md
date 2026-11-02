# Knura

Open-source NFC trust and actions platform for NTAG 424 DNA tags: verify that a tap came from a
genuine tag, then trigger actions from it.

| Module | What it is | Status |
| --- | --- | --- |
| `knura-core` | Crypto and NTAG 424 DNA logic, JDK only | SDM tap verifier, per-tag keys, tag commands |
| `knurctl` | Provisioning and management CLI | sets up NTAG 424 DNA tags over a PC/SC reader |
| `knurd` | Verification daemon | HTTP tap endpoint with replay protection |
| `knurgn` | Actions engine (knurgN) | signed webhooks and the Matter bridge connector |
| `knura-link` | Home link | runs knurgN at home for taps a hosted Knura account checked |
| `knurgn-matter` | Matter bridge (Node.js, matter.js) | knots as contact sensors in Apple Home, Google Home, Alexa |
| `knura-resolver` | tap.knura.io (Node.js, AWS Lambda) | forwards taps to the owner's knurd, so tags survive a move |

> **Alpha.** Knura is early software for people comfortable with a command line. Expect
> breaking changes between versions, and don't use it to protect anything that matters yet. Locks,
> garage doors and alarms are deliberately not supported. See [Known limits](#known-limits).

## Getting started

**Easiest:** install Knura for Windows, Mac, Ubuntu/Debian, Fedora or Raspberry Pi from the
[latest release](https://github.com/knura-dev/knura/releases) ([Install Knura](docs/install.md)),
then open the setup page at http://127.0.0.1:8425/setup.

**By hand:** download knurctl and knurd from the [latest release](https://github.com/knura-dev/knura/releases)
and follow [Set up Knura from the release downloads](docs/setup-from-release.md), which goes from
a new tag to a phone tap that sends a webhook.

To build from source instead you need an NTAG 424 DNA tag, a PC/SC USB reader such as the ACR122U,
JDK 21, and Linux or macOS. This takes one tag from new to a verified tap on your own computer.

```sh
# 1. Reader driver (Fedora shown; Debian/Ubuntu: sudo apt install pcscd libccid)
sudo dnf install pcsc-lite pcsc-lite-ccid
sudo systemctl enable --now pcscd.socket

# 2. Build and set up
git clone https://github.com/knura-dev/knura.git && cd knura
./gradlew :knurctl:installDist
alias knurctl="$PWD/knurctl/build/install/knurctl/bin/knurctl"
knurctl init                 # writes knurd.properties with a new master.key: back it up

# 3. Set up a tag (put it on the reader)
knurctl provision
knurctl tap                  # reads the tag like a phone and prints its link

# 4. Start knurd and check a tap
./gradlew :knurd:run
# in another terminal, open the link knurctl tap printed:
curl '<link from knurctl tap>'   # {"status":"ok",...}; the same link again gives "replay"
```

Next steps: [tap with a phone](#tapping-with-a-phone), send each tap to a
[signed webhook](#knurgn) or the [Matter bridge](knurgn-matter/README.md), or run it all on
[Kubernetes](deploy/README.md).

Keep `master.key` secret and backed up: it is the only way to verify or rewrite your tags.
`knurctl backup create` packs your settings folder into one passphrase-locked file, and
`knurctl backup sheet` writes a one-page recovery sheet to print; `knurctl backup restore` puts
either back (see [Back up and move your keys](https://docs.knura.io/home/keys-backup/)). Never
keep `knurd.properties` unencrypted in a cloud folder: anyone with the file can make tags yours
accept. Restoring an older `knurd-counters.properties` lets tap links made since that backup work
once more, so tap each tag once after a restore so its counter catches up.

To run knurd and the Matter bridge on Kubernetes, use the Helm chart and container images in
[`deploy/`](deploy/README.md). `deploy/kind/test.sh` tries it all on a throwaway local cluster.

## Build

Requires JDK 21.

```sh
./gradlew build
```

New to the code? [docs/architecture.md](docs/architecture.md) shows how a tap flows through the
modules and where the keys live. To make a change, see [CONTRIBUTING.md](CONTRIBUTING.md).

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

`Ntag424` speaks to the tag itself over any `Transceiver`: AuthenticateEV2First and EV2 secure
messaging, GetCardUid, GetFileSettings, ChangeFileSettings, WriteData, ChangeKey and reading the
NDEF file. It is tested byte for byte against APDU traces recorded from a real tag on an ACR122U.
`TagProvisioner` uses it to set a tag up for Knura or reset it, and `TapUrlLayout` builds the tap
URL and SDM settings. `FakeNtag424` (test fixtures) simulates a tag for tests.

## knurctl

knurctl sets up NTAG 424 DNA tags on a PC/SC reader such as the ACR122U. On Linux it needs the
PC/SC daemon and CCID driver (Fedora: `sudo dnf install pcsc-lite pcsc-lite-ccid`, then
`sudo systemctl enable --now pcscd.socket`).

```sh
./gradlew :knurctl:installDist
alias knurctl="$PWD/knurctl/build/install/knurctl/bin/knurctl"
knurctl init        # writes knurd.properties with a new random master.key (back it up)
knurctl readers     # lists readers
knurctl provision   # put a tag on the reader: Knura keys and a tap URL for knurd
knurctl tap         # read the tag like a phone does and check the URL
```

`provision` writes a tap URL `<base>?e=<encrypted UID and counter>&c=<MAC>`, which knurd verifies
with the same `master.key`. The base defaults to `<public.url>/tap` when `public.url` is set (see
"Tapping with a phone" below), else `http://127.0.0.1:8424/tap` (local knurd); set another with
`--base-url`. It gives the tag Knura's keys (see `TagKeyDerivation`): anyone may
read the URL, but only key 0 can rewrite it or change settings. It finishes by reading the tag back
and checking the URL. Other commands: `info` (UID, URL and whose keys the tag has) and `reset`
(back to factory keys).

`--from` says which keys the tag has now: `factory` (the default for `provision`), `knura` (set up
by Knura with this master key), or a file with `key0=` to `key4=` lines (32 hex digits each) for a
tag set up by other software. `--verbose` prints every command sent to the tag.

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
`sdm.metaReadKey` and `sdm.fileReadKey` (or `master.key.file`, a file holding only the key, which
must be `chmod 600`; that is how a Kubernetes or Docker secret passes it in). With NXP's AN10922 example master key:

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
unknown tag), `replay` (409). A browser, which is what a phone opens a tag's link in, gets a
small page with the same status instead ("Tap verified", "Link already used"...). The page has no
scripts and a strict Content-Security-Policy. `knurd.properties` and the counters file are
git-ignored; set your own keys there. For now the MAC must cover an empty input
(SDMMACInputOffset equal to SDMMACOffset).

### Tapping with a phone

knurd listens on 127.0.0.1, which phones can't reach. Put an HTTPS tunnel or reverse proxy in
front of it and tell Knura its address with `public.url` in `knurd.properties`. On a home
computer, [Tailscale Funnel](https://tailscale.com/kb/1223/funnel) is free, needs no domain or
router changes, and gives a fixed `https://<computer>.<tailnet>.ts.net` address:

```sh
sudo tailscale funnel --bg 8424
echo 'public.url=https://<computer>.<tailnet>.ts.net' >> knurd.properties
knurctl provision --from knura    # rewrites the tag's link to <public.url>/tap
```

The address is written onto tags, so it has to stay the same: a tunnel that changes address on
every start (such as a Cloudflare quick tunnel) would break every tag. knurd serves only `/tap`,
`/health` and `/.well-known/knura-owner`, all meant to be public.

### Tags that survive a move: tap.knura.io

Register with the tap resolver and tags carry `https://tap.knura.io/<owner>?e=...&c=...`
instead of your own address. The resolver sends each tap on to the server you registered, so
moving knurd means registering the new address, not rewriting every tag:

```sh
knurctl resolver register   # first time: creates your owner ID and key in knurd.properties
# restart knurd, then:
knurctl resolver register   # tap.knura.io now forwards to public.url
knurctl provision --from knura
knurctl resolver check      # any time: where do my taps go?
```

The resolver only forwards. It holds no tag keys, can't read the encrypted UID and can't make a
tap knurd would accept; knurd still checks every tap. Only your `resolver.key` can move your
taps, knurd checks every 6 hours that they still come to it, and you can always skip the
resolver with `--base-url`. Details, the security model and AWS hosting steps:
[knura-resolver/README.md](knura-resolver/README.md).

### Admin API: managing knurd from knurctl

knurd can open a second port, 8425, for management (and later LoomStudio). It is separate from
the tap port so the tap port can be public and this one never is: knurd only listens on this
computer or a local network address, refuses requests from public addresses and requests that
came through a proxy or tunnel, and writes every request to an audit log (tokens and keys never
are). It never returns key material. Apart from setting tags up through a phone (below) and the
setup page's requests, it only reads.

`admin.security` in knurd.properties picks one of two modes, like Kafka's PLAINTEXT and SASL:

- **plaintext**: no login. `knurctl admin ...` works without `--creds`. knurd then only listens
  on 127.0.0.1, so it is for knurd's own computer.
- **token** (the default): every request logs in with a token that knurctl reads from a
  `--creds` file. Needed for other computers, together with TLS. Use it also when other people
  or programs you don't trust have accounts on knurd's computer: in plaintext mode any of them
  can read your tags and tap history.

```sh
# Plaintext: add admin.security=plaintext to knurd.properties, restart knurd, then:
knurctl admin status
knurctl admin taps                      # the latest taps and how each went
knurctl admin tags                      # every tag seen, with its last counter

# Token: make a token on knurd's computer. The first time this turns the admin API on (restart knurd).
knurctl admin token add laptop          # writes knurctl-creds.properties (chmod 600)
knurctl admin status --creds knurctl-creds.properties

# From other computers on your network: TLS first, then listen on the LAN address.
knurctl admin tls create                # self-signed certificate; knurctl pins its fingerprint
# set admin.listen.host=<LAN address> in knurd.properties, restart knurd, then:
knurctl admin token add desk --admin-url https://<LAN address>:8425 --out desk.properties
knurctl admin token remove desk         # stops working at once, no restart
```

The creds file (`--creds`, like Kafka's `--command-config`) holds the URL, the token and the
certificate fingerprint. knurctl refuses it unless it is chmod 600, and refuses plain `http://`
unless it is this computer. knurd keeps only each token's SHA-256.

### The setup page

Once the admin API is on, open **http://127.0.0.1:8425/setup** in a browser on knurd's computer.
It walks you through seven steps: name this Knura and pick a password for the page, save a
recovery kit (a passphrase-locked backup file and a printable recovery sheet, both required
before the first tag), let phones reach it (it installs nothing, but turns on Tailscale Funnel and saves the address for you), pick a phone or USB reader,
set up and tap your first tag, give it an action (Home Assistant, Matter or a web service) and
send a test. The first password can only be set from knurd's own computer. Changes go into the
usual files and take effect without a restart, so the page and a text editor always agree.

### The dashboard

After setup, **http://127.0.0.1:8425/** is Knura's dashboard, signed in with the same password.
**Home** says whether everything is working, with health checks that link to the fix, a chart of
genuine taps and your last tap. **Tags** lists every tag with its week of taps; each tag's page
renames it, changes its icon, switches its actions on and off and sends a test. **Taps** is the
full history (a year is kept, in `knurd-taps.log`), filtered by tag or result, and downloads as
CSV or JSON. **Actions** adds Home Assistant, a Matter bridge or a webhook, shows whether each
one answered its last tap, and sends a test to just that one. **Phones and readers** pairs a phone
with a QR code, removes one, and lists the `knurctl` commands for USB readers. **Keys and backup**
downloads a backup file or the recovery sheet and shows how to restore. **Logs** shows knurd's log
with a filter and a copy that hides tag UIDs and addresses. **Settings** renames this Knura, changes
the password, the public address and the log detail.

Both pages are a few static files inside knurd. They load nothing from the internet and run
under a strict Content-Security-Policy. See [docs/setup-api.md](docs/setup-api.md).

### Setting tags up with an Android phone

The Knura app in [android/](android/) sets tags up, checks them and resets them with the phone's
NFC instead of a USB reader. The phone only relays: knurd works out the tag's keys from
`master.key`, sends each command through the admin API and checks each reply, so keys never
reach the phone. `knurctl admin pair-phone fold7` names a token for it in
`admin.provision.tokens` and shows a QR code with a one-time pairing code, which the app swaps
for the token. See
[docs/android-app.md](docs/android-app.md).

## knurgn

knurgN is Knura's actions engine: it decides what a tap does. knurd hands it each tap that came
back `ok`, and only those, so malformed, forged and replayed taps can never start an action.
knurgN looks up the tag's rule by its verified UID; nothing in the tap link can choose or change
the action.

The first connector is a **signed webhook**: each verified tap sends a JSON POST to an address
you choose (your own code, Node-RED, a home server):

```json
{"type":"tap.verified","timestamp":"2026-10-06T21:14:03.120Z",
 "data":{"id":"042D6B02936980:61","knot":"desk-candle","uid":"042D6B02936980","counter":61,
         "tappedAt":"2026-10-06T21:14:03.120Z","action":"desk-webhook"}}
```

Signatures follow [Standard Webhooks](https://www.standardwebhooks.com): headers `webhook-id`
(the tap id, `UID:counter`, the same on every retry), `webhook-timestamp` and
`webhook-signature` (`v1,` + base64 HMAC-SHA256 of `id.timestamp.body`). Receivers should check
the signature, refuse old timestamps and ignore ids they have already seen.
`examples/webhook-receiver/WebhookReceiver.java` does all three in one file.

A tap that comes through the [home link](docs/home-link.md) also has `"name"` after `"knot"`
when the tag has a name on the account's dashboard, like `"name":"Front door"`.

Set it up next to `knurd.properties`:

```sh
cp knurgn/knurgn.example.properties knurgn.properties     # then set your tag's UID in it
echo 'actions.file=knurgn.properties' >> knurd.properties
(umask 077; printf 'desk-webhook=whsec_%s\n' "$(openssl rand -base64 32)" > knurgn-secrets.properties)
```

Rules:

- Secrets live in their own file, which knurd refuses to read if other users can. They never
  appear in logs; neither do webhook addresses, since some services put a secret in them.
- Addresses must use HTTPS, except `localhost` and private network addresses. Redirects are not
  followed.
- Deliveries run in the background. Failures (network errors, 408, 429, 5xx) are retried after
  1, 2, 4... seconds; other answers are final. An action that hasn't gone through 30 seconds
  after the tap is dropped rather than run late. Each action is logged as delivered, refused or
  expired.
- The **Matter bridge** connector (`matter.<id>.url`) sends the same signed event to
  [knurgn-matter](knurgn-matter/README.md) on this computer, which shows each knot as a contact
  sensor in Apple Home, Google Home, Alexa and SmartThings. A tap opens the sensor for a few
  seconds; a routine in the app does the rest. Its address must be on this computer.
- The **Home Assistant** connector (`homeassistant.<id>.url`) starts a Home Assistant
  automation through its webhook trigger, with no login token. The webhook id goes in the
  secrets file. See [docs/home-assistant.md](docs/home-assistant.md).
- With a **hosted Knura account** instead of your own knurd, the home link (`knura-link`) runs
  the same knurgN rules at home for the taps the account checked. It dials out, so no ports need
  opening. See [docs/home-link.md](docs/home-link.md).
- Not yet: sensitive actions (locks, garage, alarm), a saved outbox that survives a restart, and
  encrypting the secrets file. See the knurgN connectors design.

## Known limits

- No sensitive actions (locks, garage doors, alarms) until approved-phone confirmation exists.
- Actions waiting to be sent are lost if knurd restarts (no saved outbox yet).
- Secrets files are protected by file permissions only, not encrypted.
- One knurd per set of tags: replay counters live in a local file.
- The admin API can't change keys, tokens or `public.url`; use knurctl for those.
- Connectors: signed webhooks, the Matter bridge and Home Assistant webhooks. MQTT and others are
  planned.
- The tap MAC must cover an empty input (SDMMACInputOffset equal to SDMMACOffset).

## Who builds Knura

Knura is built by one person, Zack, working with AI coding tools (mostly Claude). Every change
goes through a pull request, is reviewed by hand, runs 300+ automated tests in CI (the tag crypto
against NXP's published examples), and is tried on real tags, readers and phones before release.
In October 2026 it had a threat model and a security audit, with the fixes merged through normal
pull requests. [How Knura is built and checked](docs/how-we-review.md) has the details. The code
is all here, so you can check it yourself.

Contributions are welcome, AI-assisted ones included: see [CONTRIBUTING.md](CONTRIBUTING.md).

## Community

Questions, ideas and projects go in [GitHub Discussions](https://github.com/knura-dev/knura/discussions).
Use [issues](https://github.com/knura-dev/knura/issues) for confirmed bugs.

## Security

Please report vulnerabilities privately, as described in [SECURITY.md](SECURITY.md).

## License

Apache License 2.0, see [LICENSE](LICENSE). NTAG and NTAG 424 DNA are trademarks of NXP B.V.
