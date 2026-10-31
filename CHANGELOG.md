# Changelog

## Unreleased

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
