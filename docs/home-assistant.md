# Home Assistant

knurgN's Home Assistant connector starts a Home Assistant automation each time a knot is tapped.
It uses Home Assistant's **webhook trigger**, so it needs no login token: knurgN can start the
automations that listen on your webhook id, and nothing else in your home.

```
tag ──tap──▶ knurd (verifies) ──▶ knurgN ──POST /api/webhook/<id>──▶ Home Assistant automation
```

## 1. Make a webhook id

The webhook id is the key, so make a long random one instead of using a word:

```sh
openssl rand -hex 24
```

Keep the output; you need it twice, in Home Assistant and in knurgN's secrets file.

## 2. Add the automation in Home Assistant

In Home Assistant, go to **Settings → Automations & scenes → Create automation → Create new
automation**, open the **⋮** menu, choose **Edit in YAML**, and paste this, putting your webhook
id and your knot's name in:

```yaml
alias: Knura desk-candle tap
description: Runs when the desk-candle knot is tapped (sent by knurgN)
mode: single
triggers:
  - trigger: webhook
    webhook_id: PASTE_YOUR_WEBHOOK_ID_HERE
    allowed_methods:
      - POST
    local_only: true
conditions:
  # Only this knot, and only taps from the last minute.
  - condition: template
    value_template: >
      {{ trigger.json.data.knot == 'desk-candle'
         and (now() - as_datetime(trigger.json.data.tappedAt)).total_seconds() | abs < 60 }}
actions:
  - action: persistent_notification.create
    data:
      title: Knura
      message: "{{ trigger.json.data.knot }} was tapped (tap {{ trigger.json.data.counter }})"
```

Save it. The notification is just a safe first test; swap it for any action you like (a light,
a scene, a script). The tap's details are in `trigger.json.data`: `knot`, `uid`, `counter`,
`tappedAt`, `id` and `action`.

Keep `local_only: true` unless Home Assistant is on a different network from knurd: it makes
Home Assistant ignore the webhook when it arrives from the internet.

## 3. Point knurgN at it

In `knurgn.properties`, add a connector with Home Assistant's address (no path) and add its id
to the knot's actions. Use the address you open Home Assistant with, but with its IP address in
place of `homeassistant.local`; keep the port if it has one (often `:8123`, while newer Home
Assistant OS installs answer on plain port 80):

```properties
knot.042D6B02936980.name=desk-candle
knot.042D6B02936980.actions=living-room
homeassistant.living-room.url=http://192.168.1.50:8123
```

and put the webhook id in `knurgn-secrets.properties` (still chmod 600):

```properties
living-room=PASTE_YOUR_WEBHOOK_ID_HERE
```

Restart knurd and tap the tag. knurd's log says `delivered` and the notification shows up in
Home Assistant's sidebar.

## Rules and limits

- **The webhook id is a secret.** Anyone who has it can start that automation, so knurgN keeps
  it in the secrets file and never logs it (nor the full address). Don't paste it into chats,
  screenshots or Git. If it leaks, make a new one and change it in both places.
- **Home Assistant can't check signatures.** Unlike knurgN's signed webhooks, the request has
  no signature that Home Assistant could verify, which is why the id must be long and random
  (knurgN refuses ids under 32 characters) and why the automation should check `tappedAt`.
- **Address rules:** `http://` is allowed only for this computer or a private network address
  written as numbers (`192.168.x.x`, `10.x.x.x`, `172.16-31.x.x`). Use the IP address, not
  `homeassistant.local`: knurgN won't send a key over plain HTTP to a name it would have to
  trust the network to look up. Anything else needs `https://`. Redirects are not followed.
- **Give Home Assistant a fixed address** (a DHCP reservation in your router, or a static IP in
  Home Assistant under Settings → System → Network), or the address in `knurgn.properties` will
  stop working when it changes.
- **Home Assistant answers 200 OK even for a wrong webhook id** (so nobody can probe for ids).
  knurd will log `delivered` even if the id doesn't match; if nothing happens, compare the ids
  and check Home Assistant's log (Settings → System → Logs) for "unregistered webhook".
- Retries and expiry are the same as other connectors: network errors and 5xx are retried for
  up to 30 seconds after the tap. A retry carries the same `trigger.json.data.id`, so an
  automation that must never run twice can remember the last id it handled.
- No sensitive actions (locks, garage doors, alarms) in v1: a tap proves the tag is genuine,
  not who tapped it.
