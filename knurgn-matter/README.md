# knurgn-matter

A [Matter](https://csa-iot.org/all-solutions/matter/) bridge for knurgN. Each knot (tag) shows up
as a **contact sensor** in Apple Home, Google Home, Amazon Alexa and SmartThings. A verified tap
"opens" that sensor for a few seconds, and a routine in your app does the rest: "when Desk candle
opens, turn on the lamp".

```
tag ──tap──▶ knurd ──verified, first-time tap──▶ knurgN ──signed POST, this computer only──▶ knurgn-matter ──Matter──▶ your app's routine
```

It runs next to knurd as a small Node.js program, built on [matter.js](https://github.com/project-chip/matter.js).

## Why a contact sensor

Every big app can start a routine from a contact sensor opening, while support for Matter buttons
still differs between apps. The bridge only has its own sensors: it can't see or control any other
device in the home, so taking it over gets an attacker nothing beyond pressing your knots.

## Security

- Taps come in only from knurgN on this computer: the endpoint listens on `127.0.0.1` and knurgN
  refuses a `matter.<id>.url` anywhere else.
- Each tap is signed (Standard Webhooks, HMAC-SHA256) with a secret shared by knurgN and the bridge.
  The bridge refuses bad signatures, timestamps more than 5 minutes off, knots it doesn't show, and
  ignores a tap id it has already handled.
- The secret lives alone in a file only you can read (`chmod 600`); the bridge won't start
  otherwise and never logs it.
- The pairing code is random per bridge (not Matter's well-known test code). The storage folder,
  which holds it and the keys from the apps you paired with, is made private (`chmod 700`).
- Taps closer together than `pulse.seconds` count as one, so a burst of taps can't spam routines.
- Not certified: apps will say "uncertified accessory". It uses Matter's test vendor id `0xFFF1`.

## Run it

Needs Node.js 20.19 or newer (Fedora: `sudo dnf install nodejs`), on a computer on the same home
network as your smart-home hub, with IPv6 (on by default on Fedora).

```sh
cd knurgn-matter
npm install
cp knurgn-matter.example.properties knurgn-matter.properties   # list your knot names in it
SECRET="whsec_$(openssl rand -base64 32)"
(umask 077; echo "$SECRET" > knurgn-matter-secret)
(umask 077; echo "home=$SECRET" >> ../knurgn-secrets.properties)   # wherever knurgn.properties lives
npm start
```

Then in `knurgn.properties` add the connector and put it in a knot's actions:

```properties
matter.home.url=http://127.0.0.1:5541/tap
knot.042D6B02936980.actions=home
```

On first start it prints a QR code and an 11-digit pairing code. Add it in your app as a Matter
device with that code. To add it to a second app, turn on pairing mode for "Knura taps" in the
first app and use the code that app gives you.

Fedora's firewall must let Matter in: `sudo firewall-cmd --permanent --add-service=mdns
--add-port=5540/udp && sudo firewall-cmd --reload`.

## Settings

| Setting | Default | Meaning |
| --- | --- | --- |
| `knots` | (required) | Knot names from `knurgn.properties`, comma-separated |
| `knot.<name>.label` | the knot name | Name shown in the app, up to 32 characters |
| `pulse.seconds` | `3` | How long a sensor stays open after a tap (1 to 60) |
| `listen.port` | `5541` | Port knurgN posts taps to, on 127.0.0.1 |
| `matter.port` | `5540` | Matter UDP port on your network |
| `storage.dir` | `knurgn-matter-storage` | Pairing code and Matter keys. Back it up; deleting it unpairs the bridge everywhere |
| `secret.file` | `knurgn-matter-secret` | The shared `whsec_` secret |

`KNURGN_MATTER_DEBUG=1 npm start` shows matter.js's detailed log.

## Tests

```sh
npm test
```
