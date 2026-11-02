# Setup API

The admin API's requests for the setup page and the dashboard: the web pages knurd serves so
people can set Knura up, use it and change it without a terminal.

## The pages

With the admin API on, knurd serves two pages on the admin port:

- The **setup page** at `/setup`, so on knurd's own computer **http://127.0.0.1:8425/setup**.
  Its files live in `knurd/src/main/resources/io/knura/knurd/setup/`: `index.html`, `app.css`,
  `app.js` and `words.js` (the 2,048-word BIP-39 English list, used to pick the backup passphrase
  in the browser). Its last step opens the dashboard.
- The **dashboard** at `/` (also `/dashboard`), **http://127.0.0.1:8425/**. Its files live in
  `knurd/src/main/resources/io/knura/knurd/dashboard/`: `index.html`, `app.css` and `app.js`. It
  sends you to `/setup` while no password is set. Its sections are hash links (`#home`, `#tags`,
  `#tag-<UID>`, `#taps`, `#config`), and it asks for new taps every 15 seconds while it is open.

Both are served from the jar and need no sign-in, since they hold no settings; every request a
page then makes goes through the rules below.

The page loads nothing from the internet. It is sent with

```
Content-Security-Policy: default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline';
  img-src 'self' data: blob:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'
X-Content-Type-Options: nosniff
X-Frame-Options: DENY
Referrer-Policy: no-referrer
```

The setup page keeps only the furthest step reached and whether setup is finished in the
browser's `localStorage`, and the dashboard only its light or dark choice; everything else comes
from knurd each time.

Everything here goes through the admin listener (port 8425), with the same rules as the rest of
the admin API: this computer or the local network only, never through a proxy or tunnel, and
every request written to the audit log. Passwords, session ids, secrets and webhook addresses
never are.

## Who may use it

| Caller | How it signs in | May change settings |
| --- | --- | --- |
| A browser on the setup page | The password, then a session cookie | Yes |
| Plaintext mode (`admin.security=plaintext`, this computer only) | Nothing | Yes |
| A token in `admin.provision.tokens` (your phones) | `Authorization: Bearer` | Yes |
| Any other token | `Authorization: Bearer` | No (403), reading still works |

### Stopping other web sites

A browser sends the session cookie with every request to knurd, even one a different web site
started. So every request that changes something, and every request that carries a session
cookie, must have the header `X-Knura-Request: 1`, and if it has an `Origin` header it must be
knurd's own address. A web page on another site can't add that header without the browser asking
knurd first, and knurd never agrees. The cookie is also `HttpOnly` and `SameSite=Strict`, and
`Secure` when the admin API uses TLS.

knurctl and the phone app never send cookies, so their existing requests are unchanged. New
requests that change settings need the header from them too.

## The password

From another device (not loopback), the first password also needs the one-time setup code:
`POST /v1/password` with `{"password":"...","setupCode":"ABCD-EFGH-JKMN"}`. knurd makes the code
only when started with `--home <folder> --lan` and no password is set yet. It keeps the code in
`knurd-setup-code` (owner-only) and prints it in its log, and deletes it once used. Without a code,
the first password can only be set on the Knura computer. Wrong codes count towards the sign-in
lockout. From another device, `GET /v1/session` adds `"needsSetupCode":true` and
`"setupCodeWaiting"` while no password is set.

There is one password, for signing in to the setup page. knurd keeps only a salted
PBKDF2-SHA256 hash (310,000 rounds) in `knurd-admin-password` next to `knurd.properties`, chmod
600. If anyone else can read or change that file, every password is refused until you run
`chmod 600` on it.

The **first** password can only be set from the Knura computer itself (a loopback address), so
nobody else on your Wi-Fi can get there first. A Raspberry Pi with no screen will get a setup
code for this instead, in a later change.

Five wrong passwords from one address within 5 minutes lock that address out for 5 minutes.

Sessions are kept in memory only, so restarting knurd signs everyone out. A session ends after 12
hours without use, or 7 days after signing in. Changing the password signs everyone else out.

## Requests

Bodies are flat JSON objects of strings. Answers are JSON.

### Signing in

| Request | Body | Answer |
| --- | --- | --- |
| `GET /v1/session` | | `{"passwordSet":true,"signedIn":false}` |
| `POST /v1/password` (no password yet, this computer only) | `{"password":"..."}` | `{"signedIn":true}` and a cookie |
| `POST /v1/session` | `{"password":"..."}` | `{"signedIn":true}` and a cookie; 401 wrong, 409 none set, 429 locked out |
| `DELETE /v1/session` | | `{"signedIn":false}` |
| `POST /v1/password` (signed in) | `{"current":"...","password":"..."}` | `{"changed":true}` and a new cookie |

Passwords need 8 to 256 characters. Tokens and plaintext mode change the password without
`current`.

### This Knura's name

| Request | Body | Answer |
| --- | --- | --- |
| `GET /v1/settings` | | `{"name":"Our house","passwordSet":true,"phones":["pixel-9"],"publicUrl":null}` |
| `PUT /v1/settings` | `{"name":"Our house"}` | the same |

The name is saved as `knura.name` in `knurd.properties`. `/v1/status` includes it.

`phones` lists the tokens in `admin.provision.tokens`, so the page can see when a phone is paired.

### Recovery kit

| Request | Body | Answer |
| --- | --- | --- |
| `POST /v1/backup/file` | `{"passphrase":"six words from the list"}` | the backup file, as a download |
| `POST /v1/backup/sheet` | | the recovery sheet, an HTML page to print |

The backup file is the same as `knurctl backup create` makes: the settings folder, without logs,
locked with the passphrase (at least 10 characters). The sheet is the same as `knurctl backup
sheet`. Both are recorded in `knura-backup.properties`, so `/v1/status` and knurd's start-up
reminder know the key is backed up. The passphrase is never kept or logged. Both need a caller
that may change settings, since they carry the master key.

### The address phones use

`PUT /v1/settings` also takes `{"publicUrl":"https://desk.tail1234.ts.net"}` and saves it as
`public.url`, checked the same way as at start-up (https, or http only for this computer).
`knurctl provision` reads it straight away, so tags set up from then on get the new link. knurd
itself keeps the address it started with until a restart, which only matters for phone pairing
and tap.knura.io; `/v1/reach` says `"restart":true` until then.

### Tailscale

| Request | Answer |
| --- | --- |
| `GET /v1/tailscale` | `{"state":"Running","loginUrl":null,"dnsName":"desk.tail1234.ts.net","url":"https://desk.tail1234.ts.net","httpsCerts":true,"funnel":false,"detail":null}` |
| `POST /v1/tailscale/funnel` | the same, plus `fixUrl`, `fixCommand` and `funnelDetail` when it couldn't turn Funnel on |

knurd finds the `tailscale` command on the PATH, or where the Mac and Windows apps install it, and
only ever runs `tailscale status --json`, `tailscale funnel status --json` and `tailscale funnel
--bg <listen.port>`, with no input from the request. Funnel shares the tap port only, never the
admin port. `state` is Tailscale's own (`Running`, `NeedsLogin`, `Stopped`...), or `NotInstalled`
or `NotRunning`. When Funnel can't be turned on yet, `fixUrl` is the Tailscale page that allows it
for your network, or `fixCommand` is the one command to run first (on Linux,
`sudo tailscale set --operator=<the user knurd runs as>`, `knura` for the Linux service, so knurd's user may share a port). Once Funnel is on and
`public.url` isn't set, knurd sets it to the Funnel address.

### Pairing a phone

`POST /v1/phones/pair` with `{"name":"pixel-9"}` gets knurd ready for a phone and makes its
pairing code, as `knurctl admin pair-phone` does, with no restart:

1. If knurd doesn't answer the home network yet, it makes a self-signed TLS certificate
   (`knurd-admin.p12` and its password file, both chmod 600) and starts a second admin listener on
   every network on `admin.lan.port` (8426), over TLS only. This computer keeps using plain HTTP on
   `127.0.0.1:8425`. If `admin.listen.host` is already a LAN address with TLS, that is used instead.
2. It adds the name to `admin.provision.tokens`, so the phone may set tags up as soon as it pairs.
3. It saves `admin.tls.keystore`, `admin.tls.password.file`, `admin.lan.port` and
   `admin.provision.tokens` in knurd.properties, so all of it is still on after a restart.
4. It writes a one-time pairing code (15 minutes) to the pairing file and answers:

```json
{"name":"pixel-9","link":"knura://pair?url=https%3A%2F%2F192.168.1.50%3A8426&fp=...&code=knurp_...",
 "qr":["1111111001...", "..."],"url":"https://192.168.1.50:8426","address":"192.168.1.50",
 "addresses":["192.168.1.50","10.0.0.7"],"fingerprint":"...","expires":"2026-11-01T22:15:00Z",
 "firewall":"sudo firewall-cmd --add-port=8426/tcp --permanent && sudo firewall-cmd --reload"}
```

`qr` is the QR code for `link`, one string of 0s and 1s per row (1 is dark, no quiet zone), drawn
by knurd's own encoder so the page needs no outside library. `addresses` are this computer's home
network addresses (192.168.x.x first, skipping Docker, VM and VPN interfaces); send `"address"`
with one of them to put another in the code. `firewall` is the command that lets the port in on
Linux with firewalld or ufw, or null. The code holds no token: the app swaps it for one at `POST
/v1/pair` on the Wi-Fi listener, pinning the certificate by `fp`. `GET /v1/settings` lists the
phone under `phones` once that has happened.

It answers 409 with `status` saying what to fix first: `no_address` (no `public.url` yet, step
3), `no_network` (no home network address), `name_taken`, `no_master_key` or `plaintext`
(`admin.security=plaintext` has no logins).

### Reaching knurd

`GET /v1/reach` asks `<public.url>/health` from knurd's computer, as a phone would:

```json
{"publicUrl":"https://desk.tail1234.ts.net","resolverOwner":null,"restart":false,"reachable":true,"detail":"answered"}
```

Asking from inside your network can say yes when phones outside still can't get in, for example
while a new Tailscale Funnel address is spreading. Without `public.url` the answer has
`"reachable":false` and `"detail":"public.url is not set"`.

### Tags

`GET /v1/tags` now lists every tag knurd knows of, from its counters, labels and actions:

```json
{"tags":[{"uid":"04DE5F1EACC040","lastCounter":61,"label":"Front door","icon":"door","actions":["living-room"]}]}
```

| Request | Body | What it does |
| --- | --- | --- |
| `PUT /v1/tags/<UID>` | `{"label":"Front door","icon":"door"}` | Sets the label and icon. Leave one out to clear it. |
| `PUT /v1/tags/<UID>/actions` | `{"actions":"living-room,ifttt"}` | Sets which connectors the tag runs, in order. `""` takes its rule away. |
| `POST /v1/tags/<UID>/test` | | Runs the tag's actions now: `{"started":1}` |

Labels are free text, up to 40 characters, and are sent with each tap as `"name"`. They are kept
in `knurd-tags.properties`. Icons are short names like `door`, made of lowercase letters,
digits and `-`.

A test sends the same event a real tap would, with counter 0, which no real tap has (counters
start at 1). Receivers that drop repeated event ids will only see the first test, since every test
of a tag has the id `<UID>:0`.

### Taps

`GET /v1/taps` lists taps newest first, as knurctl's `admin taps` shows them:

```json
{"taps":[{"time":"2026-11-02T00:46:00Z","status":"ok","uid":"04DE5F1EACC040","counter":43,"actions":1}]}
```

`status` is `ok`, `replay` (a copied link), `rejected` (the signature didn't match) or
`malformed` (a broken link, with `uid` null). It takes `limit` (default 50, at most 1000),
`uid=<UID>` for one tag, and `result=ok` or `result=stopped` (everything but `ok`).

`GET /v1/taps/days?days=14` counts them per day (at most 90 days), oldest first, in this
computer's time zone, for the dashboard's charts. `tags` has each tag's genuine taps per day:

```json
{"zone":"Europe/Paris","days":[{"day":"2026-11-01","ok":12,"stopped":0},{"day":"2026-11-02","ok":3,"stopped":1}],
 "tags":{"04DE5F1EACC040":[5,2]}}
```

With the admin API on, knurd keeps a year of taps (at most 100,000) in `knurd-taps.log` next to
`knurd.properties`, owner-only, one line per tap: time, status, UID (`-` when unknown), tap number
and how many actions started. Only the result is kept, never the tap link. It survives restarts,
but like the other `.log` files it isn't put in backups. Without the admin API only the last 200
taps are kept, in memory.

### Connections

| Request | Body | What it does |
| --- | --- | --- |
| `GET /v1/connections` | | `{"connections":[{"type":"webhook","id":"ifttt","url":"https://...","usedBy":["04DE5F1EACC040"]}]}` |
| `PUT /v1/connections/<type>/<id>` | `{"url":"https://..."}` | Adds the connector, or changes its address. |
| `DELETE /v1/connections/<type>/<id>` | | Removes it and its secret. 409 while a tag still uses it. |
| `POST /v1/connections/<type>/<id>/secret` | | Replaces its secret with a new one, shown once as `"secret"`. The old one stops working at once. |

`<type>` is `webhook`, `homeassistant` or `matter`. A new connector gets a fresh secret, which the
answer shows **once** as `"secret"`: a `whsec_` signing secret, or a 48-character Home Assistant
webhook id to paste into the automation. Changing the address keeps the secret, and the answer
has `"secret":null`.

| Request | What it does |
| --- | --- |
| `POST /v1/actions/reload` | Reads the actions files again after you edited them by hand: `{"knots":2}` |

## How changes are saved

Changes go into the same files you'd edit by hand, so the page and a text editor always agree:
`knurd.properties`, the knurgN actions file (`actions.file`) and its secrets file. Only the lines
that change are touched; comments and everything else stay as they were.

The first time a connector or a tag's actions are set, knurd makes `knurgn.properties` and
`knurgn-secrets.properties` (chmod 600) next to `knurd.properties`, and adds
`actions.file=knurgn.properties` to `knurd.properties`.

Every change to the actions files is checked by loading them as knurd does at start-up. If that
fails, both files are put back as they were and the answer is a 400 with the reason, such as an
`http://` address on the internet. A change that passes is used straight away, with no restart.
Actions already on their way finish first.

`knurd.properties` and the secrets file are rewritten in one step and stay chmod 600.
