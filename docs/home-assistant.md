# Use Knura with Home Assistant

Tap a tag with your phone and run a Home Assistant automation: turn on a light, start a scene,
send a notification. This guide takes you from a working Knura setup to your first tap in Home
Assistant, in about 15 minutes.

```
tag ──tap──▶ phone ──▶ knurd (checks the tap) ──▶ knurgN ──webhook──▶ Home Assistant automation
```

knurgN uses Home Assistant's **webhook trigger**, so it needs no Home Assistant login or access
token. It can start the automations that listen on your webhook id, and nothing else in your home.

> **Alpha.** Knura is early software. Don't use it for locks, garage doors or alarms: a tap proves
> the tag is genuine, not who tapped it.

## Before you start

- **Knura works with your phone.** Follow [setup-from-release.md](setup-from-release.md) up to
  step 6, until tapping a tag shows "Tap verified" on your phone.
- **Home Assistant is running** on your home network: Home Assistant OS, Container, or a virtual
  machine. Any version from 2024.10 on.
- **knurd has the Home Assistant connector.** It's in the code after v0.1.0-alpha. Until the next
  release, build knurd from source (needs `git` and Java 21):

  ```sh
  git clone https://github.com/knura-dev/knura.git ~/knura/knura
  cd ~/knura/knura
  ./gradlew :knurctl:installDist :knurd:installDist
  alias knurd="$HOME/knura/knura/knurd/build/install/knurd/bin/knurd"
  alias knurctl="$HOME/knura/knura/knurctl/build/install/knurctl/bin/knurctl"
  ```

  Add the two `alias` lines to `~/.bashrc` to keep them. Keep running knurd and knurctl from your
  settings folder (`~/knura/home` in the setup guide), where `knurd.properties` is.

## 1. Find Home Assistant's address

knurgN needs Home Assistant's **IP address**, not `homeassistant.local`. Find it in Home
Assistant under **Settings → System → Network**, or on the console screen of Home Assistant OS
("IPv4 addresses"). It looks like `192.168.1.50`.

Then find the port. Use the address you open Home Assistant with in your browser: if it ends in
`:8123`, keep `:8123`; if it has no port, leave it off (newer Home Assistant OS installs answer on
port 80). Check it from the computer that runs knurd. It should print `200`:

```sh
curl -s -o /dev/null -w '%{http_code}\n' http://192.168.1.50:8123/   # or http://192.168.1.50/
```

**Give Home Assistant a fixed address** so it never changes: a DHCP reservation in your router,
or **Settings → System → Network → IPv4 → Static** in Home Assistant.

<details>
<summary>Running Home Assistant in VirtualBox?</summary>

VirtualBox's default network (NAT) hides the virtual machine from your computer, so knurd can't
reach it. Shut the VM down, open **Settings → Network → Adapter 1**, set **Attached to** to
**Bridged Adapter** and pick the network card your computer uses. Start the VM again; its console
shows the new address.

If you can `ping` the address but `curl` says "Connection refused", Home Assistant is still
installing itself (the console says "Home Assistant Core: landingpage"). The first start can take
20 minutes.
</details>

## 2. Make a webhook id

The webhook id works like a password: anyone who has it can start the automation. Make a long
random one:

```sh
openssl rand -hex 24
```

Keep it handy; you paste it in two places. Don't share it in chats, screenshots or Git.

## 3. Add the automation in Home Assistant

In Home Assistant, go to **Settings → Automations & scenes → Create automation → Create new
automation**, open the **⋮** menu (top right), choose **Edit in YAML**, replace everything with
this, and put in your webhook id:

```yaml
alias: Knura desk-tag tap
description: Runs when the desk-tag knot is tapped (sent by Knura)
mode: single
triggers:
  - trigger: webhook
    webhook_id: PASTE_YOUR_WEBHOOK_ID_HERE
    allowed_methods:
      - POST
    local_only: true
conditions:
  # Only this tag, and only taps from the last minute.
  - condition: template
    value_template: >
      {{ trigger.json.data.knot == 'desk-tag'
         and (now() - as_datetime(trigger.json.data.tappedAt)).total_seconds() | abs < 60 }}
actions:
  - action: persistent_notification.create
    data:
      title: Knura
      message: "{{ trigger.json.data.knot }} was tapped (tap {{ trigger.json.data.counter }})"
```

Save it. `desk-tag` is the name you'll give the tag in step 4; change it in both places if you
like another name. The notification is a safe first test that changes nothing in your home.

Keep `local_only: true`: Home Assistant then ignores the webhook if it comes from the internet.

## 4. Tell knurgN about Home Assistant

In your settings folder, get the tag's UID with the tag on the reader:

```sh
knurctl info          # UID: 14 characters, like 04406B02936980
```

Create `knurgn.properties` (`nano knurgn.properties`), with your UID in both knot lines and your
address from step 1:

```properties
knot.04406B02936980.name=desk-tag
knot.04406B02936980.actions=living-room
homeassistant.living-room.url=http://192.168.1.50:8123
secrets.file=knurgn-secrets.properties
```

`living-room` is just a name for this connection; pick any. If you already have a
`knurgn.properties` from the setup guide, add the `homeassistant.` line to it and add
`living-room` to your knot's `actions` (comma-separated, like `actions=test-hook,living-room`).

Save the webhook id next to it. The line must start with the connection name and `=`:

```sh
(umask 077; echo 'living-room=PASTE_YOUR_WEBHOOK_ID_HERE' >> knurgn-secrets.properties)
```

If knurd doesn't use actions yet, switch them on (once):

```sh
grep -q '^actions.file' knurd.properties || echo 'actions.file=knurgn.properties' >> knurd.properties
```

## 5. Tap

Restart knurd (Ctrl+C, then `knurd`). It should print `knurgN: 1 knot(s) with actions`. Tap the
tag with your phone:

- the phone shows **Tap verified**
- knurd prints `action=living-room: delivered`
- Home Assistant shows a notification under the bell in the sidebar: "desk-tag was tapped"

## 6. Make it do something

Edit the automation and replace the `actions:` part. For example, toggle a light:

```yaml
actions:
  - action: light.toggle
    target:
      entity_id: light.living_room
```

Any Home Assistant action works the same way: scenes (`scene.turn_on`), scripts, media players.
For more tags, give each one its own `knot.<UID>.name` and either its own automation (with its
own webhook id) or one automation that checks `trigger.json.data.knot`.

The automation can use everything about the tap in `trigger.json.data`: `knot` (name), `uid`,
`counter` (goes up by one every tap), `tappedAt` (time), `id` (`UID:counter`) and `action`.

## Troubleshooting

| What you see | What to do |
| --- | --- |
| knurd: `no secret for homeassistant living-room` | `knurgn-secrets.properties` has no `living-room=...` line. Each line needs the name and `=` in front of the id. |
| knurd: `has a line that isn't <connector id>=<secret>` | You pasted the id without `living-room=` in front. |
| knurd: `the Home Assistant webhook id ... must be 32 to 128 letters` | The id is too short or has odd characters. Make one with `openssl rand -hex 24`. |
| knurd: `must use https://, except for ... a private network address` | Use the IP address (`http://192.168.1.50`), not `homeassistant.local`. |
| knurd: `knot.YOUR_TAG_UID: a tag UID is 14 hex digits` | Put your tag's real UID from `knurctl info` in `knurgn.properties`. |
| knurd says `delivered`, but nothing happens | Home Assistant answers OK even for a wrong id (so nobody can guess ids). Check the id is the same in both places, and the knot name in the automation matches `knot.<UID>.name`. Home Assistant's log (Settings → System → Logs) mentions "unregistered webhook" when the id is wrong. |
| knurd: `expired` (ConnectException or HttpTimeoutException) | knurd can't reach Home Assistant. Check the address and port with the `curl` in step 1. |
| Phone: "unable to connect" | The tag's link isn't your public address. `knurctl info` should show `https://...`; if it shows `http://127.0.0.1`, set `public.url` in `knurd.properties`, restart knurd and run `knurctl provision --from knura`. |

## How it stays safe

- **Only real, first-time taps reach Home Assistant.** knurd checks each tap's code against the
  tag's keys and refuses copied or reused links before knurgN sees them. The link can't choose
  the action; knurgN picks it by the tag's verified UID.
- **No login token.** knurgN holds only the webhook id, which can start the automations that
  listen on it and nothing else.
- **The webhook id is a secret.** knurgN keeps it in the secrets file (knurd refuses to start if
  other users can read it), never logs it or the full address, and refuses ids under 32
  characters. If it leaks, make a new one and change it in both places.
- **Home Assistant can't check signatures**, unlike knurgN's signed webhooks. That's why the id
  must be long and random, and why the automation checks `tappedAt` and keeps `local_only: true`.
- **Plain `http://` only on your own network:** to this computer or a private IP address written
  as numbers (`192.168.x.x`, `10.x.x.x`, `172.16-31.x.x`). knurgN won't send the id over plain
  HTTP to a name it would have to trust the network to look up. Anything else needs `https://`.
  Redirects are never followed.
- **Retries:** network errors and 5xx answers are retried for up to 30 seconds after the tap,
  then dropped rather than run late. A retry carries the same `trigger.json.data.id`, so an
  automation that must never run twice can remember the last id it handled.
- **No sensitive actions in v1.** Locks, garage doors and alarms wait for approved-phone
  confirmation.
