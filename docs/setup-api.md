# Setup API

The admin API's requests for the setup page: the web page knurd serves so people can set Knura
up and change it without a terminal.

## The page

With the admin API on, knurd serves the page at `/setup` (and `/`) on the admin port, so on
knurd's own computer it is **http://127.0.0.1:8425/setup**. The page's files live in
`knurd/src/main/resources/io/knura/knurd/setup/` and are served from the jar: `index.html`,
`app.css`, `app.js` and `words.js` (the 2,048-word BIP-39 English list, used to pick the backup
passphrase in the browser). They need no sign-in, since they hold no settings; every request the
page then makes goes through the rules below.

The page loads nothing from the internet. It is sent with

```
Content-Security-Policy: default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline';
  img-src 'self' data: blob:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'
X-Content-Type-Options: nosniff
X-Frame-Options: DENY
Referrer-Policy: no-referrer
```

It keeps only the furthest step reached and whether setup is finished in the browser's
`localStorage`; everything else comes from knurd each time.

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
| `GET /v1/settings` | | `{"name":"Our house","passwordSet":true,"phones":["pixel-9"]}` |
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

### Reaching knurd

`GET /v1/reach` asks `<public.url>/health` from knurd's computer, as a phone would:

```json
{"publicUrl":"https://desk.tail1234.ts.net","resolverOwner":null,"reachable":true,"detail":"answered"}
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

### Connections

| Request | Body | What it does |
| --- | --- | --- |
| `GET /v1/connections` | | `{"connections":[{"type":"webhook","id":"ifttt","url":"https://...","usedBy":["04DE5F1EACC040"]}]}` |
| `PUT /v1/connections/<type>/<id>` | `{"url":"https://..."}` | Adds the connector, or changes its address. |
| `DELETE /v1/connections/<type>/<id>` | | Removes it and its secret. 409 while a tag still uses it. |

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
