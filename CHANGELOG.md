# Changelog

## Unreleased

- **Dashboard** at http://127.0.0.1:8425/, signed in with the setup page's password: Home (is
  everything working, health checks that link to the fix, a genuine-taps chart, your last tap),
  Tags (each tag's week of taps; rename, icon, switch actions on and off, send a test), Taps (the
  history, filtered by result, tag and time, downloads as CSV or JSON) and Settings (rename, change
  the password, light or dark). The setup page's last step opens it, and so does the desktop app.
- Taps are kept for a year in `knurd-taps.log` when the admin API is on, so the history survives
  restarts. `GET /v1/taps` takes `uid=` and `result=ok|stopped` and up to 1000 taps; new
  `GET /v1/taps/days` counts them per day.
- **Installers** with Java included (docs/install.md), built for each release by the new
  Installers workflow: `.deb` for Ubuntu/Debian and Raspberry Pi (x86-64 and ARM64) and `.rpm` for
  Fedora, which run Knura as a `knura` system service with settings in `/var/lib/knura`; `.msi`
  for Windows and `.pkg` for Mac (paused for now: not in releases yet), which add a Knura app that starts Knura at sign-in and opens the
  setup page. Build one yourself with `packaging/build-installer.sh`.
- `knurd --home <folder> [--lan]`: makes the folder and a starter knurd.properties (new master key,
  admin API and setup page on, log file) on the first start. `--lan` also turns on the Wi-Fi
  listener and makes a one-time **setup code**, so a Raspberry Pi with no screen can be set up from
  a laptop or phone: the first password from another device needs it.
- Setup page step 3: the Tailscale permission command names the user Knura runs as (`knura` for
  the Linux service) instead of `$USER`.

- Setup page, step 4: **pair your phone from the page.** Name the phone and press "Show pairing
  code": knurd makes its TLS certificate, starts answering phones on your home Wi-Fi (a new
  `admin.lan.port` listener, 8426, TLS only, while this computer keeps plain HTTP on
  127.0.0.1:8425), lets the phone set tags up, and shows a QR code for the Knura app. No terminal
  and no restart, and it's all saved for the next start (`POST /v1/phones/pair`). If the app can't
  connect, the page offers the firewall command and this computer's other network addresses. A
  phone only counts as paired once the app has used its code. QR codes come from a small encoder
  in knura-core, so knurd still has no third-party dependencies.
- Android app 0.8.2: the pairing screen mentions the setup page as well as `knurctl admin pair-phone`.
- The setup page's tab icon is the plain Knura mark, without the dark square behind it.

- Setup page, step 6: **Make a new webhook id** for a Home Assistant connection whose id you
  lost. The automation to paste updates with the new one, and the old one stops working at once
  (`POST /v1/connections/<type>/<id>/secret`). Reloading the page on step 6 now asks for the tag
  again instead of carrying on without one.

- Setup page, step 6: for Home Assistant, choose what the tap does. "Show a notification" (the
  default, nothing to set up first) or "Turn a light on or off" with your light's entity id. The
  automation to paste follows your choice.

- Setup page, step 5: the "Tap verified" rings are centred and no longer cut off, and the
  animation plays once when the tap arrives instead of again each time you pick an icon.

- Setup page, step 3 now sets up Tailscale for you: it checks Tailscale is installed and signed
  in, turns on Funnel for the tap port with one button (`POST /v1/tailscale/funnel`), and saves
  the Funnel address as `public.url`. If Tailscale needs something first, the page shows the
  link to allow Funnel or the one command to run. Or type your own https address. Step 5 now
  warns when there's no address yet (a tag set up then can't be tapped from a phone), explains
  taps that arrived but failed (wrong key, a reused link, a broken link) instead of waiting, and
  tells you how to test with `knurctl tap` on a USB reader.

- The setup page. With the admin API on, knurd serves a seven-step setup page at
  `http://127.0.0.1:8425/setup`: name this Knura and set its password, save the recovery kit
  (required before the first tag), check that phones can reach `public.url`, pick a phone or
  reader, tap a first tag and name it, connect Home Assistant, Matter or a web service and send a
  test. It remembers where you got to, signs in again after a restart, and works on a phone-sized
  screen. The page is static files inside knurd with a strict Content-Security-Policy and nothing
  loaded from the internet. New admin requests for it: `POST /v1/backup/file` (a
  passphrase-locked backup of the settings folder, the same file `knurctl backup create` makes),
  `POST /v1/backup/sheet` (the printable recovery sheet), `GET /v1/reach` (asks `public.url`
  for `/health` from this computer) and `phones` in `GET /v1/settings`. Both downloads are
  recorded like knurctl's, so the backup reminder stops. Tailscale help, pairing a phone from the
  page and installers come next.
- The admin API can now change things, for the coming setup page. A browser signs in with a
  password (`POST /v1/password` sets the first one, from the Knura computer only; `POST
  /v1/session` signs in), kept as a PBKDF2-SHA256 hash in `knurd-admin-password` (chmod 600), with
  an `HttpOnly`, `SameSite=Strict` session cookie. Signed-in browsers, plaintext mode and the
  tokens in `admin.provision.tokens` may name this Knura (`knura.name`), label tags and give them
  icons (`knurd-tags.properties`), add, change and remove webhook, Home Assistant and Matter
  connectors (a new one's secret is made for you and shown once), choose what each tag does, run
  a tag's actions as a test (`POST /v1/tags/<UID>/test`, counter 0) and reload hand-edited actions
  files. Changes are written into the usual files, keeping comments, and checked by loading them
  as knurd would; a bad change is undone and refused. Good changes take effect at once, with no
  restart. Requests that change something or carry the session cookie need `X-Knura-Request: 1`
  and a matching `Origin`, so other web sites can't use a signed-in browser. Tag labels are sent
  with each tap as `"name"`. See docs/setup-api.md.
- Android app 0.8.1: with a hosted account, tag names are shared both ways. A rename in the app
  shows on the account's dashboard and a rename there shows in the app on its next refresh. The
  newest change wins; a rename made offline is sent later and only sticks if the tag wasn't
  renamed on the dashboard since. Names the app had before stay, unless the dashboard already
  named that tag. With your own knurd, names stay on the phone as before.
- Keeping the master key safe. `knurctl backup create` packs the settings folder into one
  passphrase-locked `.knurabackup` file (AES-256-GCM, key from PBKDF2-HMAC-SHA256 with 600,000
  rounds, JDK only, so no gpg), leaving out logs. `backup verify FILE` checks a copy opens and
  holds this folder's master key; `backup restore FILE` unpacks it into an empty folder, every
  file chmod 600, and never over an existing settings file. `backup sheet` writes a printable
  one-page recovery sheet (to `/dev/shm` when it exists, so it never touches the disk) with
  `master.key` and the tap.knura.io key as short hex rows, each with a two-character check that
  catches a typo on the row it is in; `backup restore --sheet` types it back in and rebuilds
  `knurd.properties`. `knurctl backup` says when this master key was last backed up, recorded in
  `knura-backup.properties` by a short key ID (no secrets). `init` now asks for a backup, and
  `provision` and knurd's startup log remind you until there is one. knurd's `/v1/status` gains
  `"backup":{"file":..,"sheet":..}` in master-key mode, and the Android app shows it under Server
  (Key backup). knurctl now also reads `master.key.file`, like knurd. New in knura-core:
  `io.knura.core.backup` (`BackupFile`, `RecoverySheet`, `BackupRecord`) and `OwnerKey.fromSeed`.
- Android app 0.8.0: a Check a tap tile for Quick Settings, and three home screen widgets: Last
  tap, Check a tap and Today (with the 7-day chart). Widgets show what the app last read and
  update whenever it checks the server.
  A Settings screen (gear on Home and Server): your name, theme (system, light or dark),
  vibration, the first-tag walkthrough, clearing notifications, the server, and links to the
  docs, source and Android's app settings. Fix: with a hosted account the app now gets its taps
  (it asked for 200, more than the account's limit of 100, so the chart and today stayed at 0).
- Android app 0.7.0: a tag screen (taps this week, tap counter, rejected taps, what the last tap
  ran, recent taps, rename and reset), notifications made by the app when it checks the server
  (rejected and replayed taps, new tags, tokens added or removed, server offline and back), and
  an animated five-step walkthrough for setting up a first tag.
- Android app 0.6.0: a new look. A bottom menu with Home, Tags, Taps and Server, and an orange
  button that starts Check a tap from any tab. Home greets you by time of day and shows the
  server with today's taps and a 7-day chart, the actions, and your tags with their last tap.
  Tags can be named on the phone. Taps are grouped by day and filter by Verified or Rejected.
  The tabs read the admin API after one unlock that lasts while the app is on screen, and the
  last answer is kept for next time. Welcome, pairing and the scan screen have a slowly moving
  orange glow and an animated Knura mark; results animate a check or a cross. Offline and empty
  screens, a two-pane home on open foldables, and vibration on tag found and on the result.
- Logging: knurd and knura-link take `log.level` (error, warn, info, debug, trace) and
  `log.file` in their config, and `KNURA_LOG_LEVEL` overrides the level for one run. Lines are
  now one per message with a time and level (no time under systemd, whose journal adds it). The
  log file keeps its name and rolls over at 10 MB, keeping 4 old files. Every line passes a
  filter that hides keys, tokens, claim codes and tap link MACs. Tap lines now say why a tap
  failed, where it came from and how long it took, and debug shows every action attempt.
  Startup messages go to the log too. See docs/logs.md. No new dependencies: this is the JDK's
  own logging, set up by `io.knura.core.log.Logs`.
- Home link: a tap message may carry the tag's dashboard name in `"name"`, and every action's
  event then has it too (`"name":"Front door"` after `"knot"`). `"knot"` is unchanged, so
  renaming a tag never breaks an automation. knura-link drops control characters and cuts the
  name to 40 characters. Webhook and Home Assistant bodies now JSON-escape the knot and name.
  `TapEvent` gains `name()`, and knurgN gains `onVerifiedTap` overloads that take it.
- Home link: actions chosen on the hosted account's dashboard. knura-link tells the account the
  ids of its connectors (`dashboard.connectors` in link.properties picks which, or `none`), and
  a tap message may name chosen connectors in `"actions"`; those run instead of the tag's knot
  rule, and only if offered. knurgN gains `connectorIds()` and
  `onVerifiedTap(uid, counter, tappedAt, connectorIds)`, and keeps connectors no knot uses.
  A home link address with no path (`wss://link.example.com`) now works.
- Home link (`knura-link`): runs your knurgN rules at home for taps a hosted Knura account has
  checked. It connects out over `wss://` with a token from the account's dashboard
  (`knura-link pair`), so no ports need opening; each message carries only the tag UID, counter
  and tap time, and your rules and connector secrets stay at home. Duplicate and late taps run
  nothing. See docs/home-link.md. knurgN gains `onVerifiedTap(uid, counter, tappedAt)` for taps
  verified elsewhere.
- Android app 0.4.0: pairs with a hosted Knura account as well as a knurd. A pairing code from a
  hosted account has no certificate fingerprint and an account address
  (`https://<host>/o/<owner>`); the app then checks the certificate the usual way. A hosted
  server answers the same admin API requests as knurd, so every tile works the same.
- Ready-to-use tags: a tag can ship locked with claim keys and a claim card, and
  `knurctl provision --from claim:CODE` moves it onto your own master key. The claim code
  format and keys are in knura-core (`ClaimCode`, `TagKeySet.claim`). See docs/claim-tags.md.
- Android app 0.3.2: "Claim a tag" claims a ready-to-use tag from its claim card, by QR code or
  typed code. knurd's phone relay takes `"from":"claim"` with the card's code in `"claim"`.
- Android app 0.3.3: pack cards. A claim card whose QR code has no UID
  (`knura://claim?code=...`) claims every tag of a pack; after each tag, **Claim next tag**
  goes on with the same card and counts the tags claimed. See docs/claim-tags.md.
- Android app 0.3.0: a new look in the Knura colors, with light and dark modes. Tap tiles pick
  the action, a "hold the tag" screen shows each step, and results get their own screen with the
  full log under "What knurd did". Status, tags and recent taps show as lists (taps marked
  Genuine, Replay, Rejected or Malformed, in the phone's time). Two columns on an unfolded
  foldable, a Knura launcher icon, and "Forget" now asks first.
- Android app 0.3.1: while the app is open it catches every tag itself, so a tag held to the
  phone before Start or during the unlock prompt no longer opens its link in the browser and
  cuts the session off.

## 0.3.0-alpha (2026-10-22)

- knurd: set tags up, reset and read them through a phone's NFC, for the Android app.
  The phone only relays messages between the tag and knurd, so tag keys never leave knurd. Off
  unless `admin.provision.tokens` names a token; see knurd.example.properties.
- Knura Android app (`android/`): set tags up, check and reset them with the phone's NFC. CI
  builds a test APK, and each release carries one. See docs/android-app.md.
- Android app: knurd status, tags and recent taps, like `knurctl admin status|tags|taps`.
- knurd admin API: `GET /v1/tokens` lists token names; `DELETE /v1/tokens/NAME` removes one
  (phone tokens only). The Android app uses it for "Remove a token".
- knurctl: `admin pair-phone NAME` makes a token for the app and shows a QR code to pair it.

## 0.2.0-alpha (2026-10-22)

- knurgN: Home Assistant connector (`homeassistant.<id>.url`) that starts an automation through
  its webhook trigger, with no login token. See docs/home-assistant.md.
- knurgN: a clear error when a secrets file line is missing its connector id, without printing
  the line.
- docs: a step-by-step Home Assistant guide, linked from the release setup guide.

## 0.1.0-alpha (2026-10-19)

First public alpha.

- knura-core: SDM (SUN) tap verification, per-tag keys from one master key (NXP AN10922), and
  NTAG 424 DNA commands (EV2 authentication and secure messaging) tested against real-tag traces.
- knurctl: set up, check and reset tags over a PC/SC reader; admin commands for knurd.
- knurd: tap endpoint with replay protection that survives restarts, tap pages for phones, and a
  separate read-only admin API with tokens, pinned TLS and an audit log.
- knurgN: signed webhooks (Standard Webhooks) with retries and a 30 second cutoff.
- knurgn-matter: Matter bridge showing tags as contact sensors in Apple Home, Google Home, Alexa
  and SmartThings.
- knura-resolver: tap.knura.io, so tags keep working when their server moves.
- Helm chart and container images for Kubernetes.
