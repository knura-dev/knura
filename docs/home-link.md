# Home link

The home link runs your knurgN actions at home for taps that a **hosted Knura account** checks.
The hosted account holds your tag keys and verifies each tap; the home link receives the taps it
found genuine and new, and hands them to knurgN, which runs your rules: Home Assistant, the
Matter bridge, signed webhooks.

It dials out to the hosted account over a WebSocket (`wss://`), so your home network needs no
open ports, port forwarding or tunnel. If you run your own knurd instead, you don't need the
home link: knurd runs knurgN itself.

```
phone tap -> tap.knura.io -> hosted account (checks the tap) ==wss==> knura-link -> knurgN -> Home Assistant
```

## What it needs

- Java 21 on a computer that stays on at home, on the same network as Home Assistant
  (a Fedora box, a Raspberry Pi 4 or 5, a NAS).
- A hosted Knura account on a plan that includes the home link.
- Your knurgN rules in `knurgn.properties`, written exactly as for knurd (see the README's
  knurgn section and [home-assistant.md](home-assistant.md)).

## Set it up

1. On the account's dashboard, open **Home link**, give it a name (like `fedora`) and press
   **Add home link**. The dashboard shows a pairing code once. It holds the home link's login,
   so don't share it.
2. On the computer at home, in the folder you want to keep its settings in:

   ```sh
   mkdir -p ~/knura/home-link && cd ~/knura/home-link
   knura-link pair
   ```

   Paste the code when asked (it doesn't show while you paste). This writes `link.properties`
   and `link-secrets.properties` (readable by you only).
3. Put your rules next to them in `knurgn.properties`, with secrets in
   `knurgn-secrets.properties` (`chmod 600`). For example, for Home Assistant:

   ```properties
   # knurgn.properties
   knot.04406B02936980.name=desk-tag
   knot.04406B02936980.actions=home
   homeassistant.home.url=http://192.168.1.234
   ```

   ```sh
   (umask 077; printf 'home=%s\n' "<your Home Assistant webhook id>" > knurgn-secrets.properties)
   ```

4. Start it:

   ```sh
   knura-link
   ```

   It prints `connected to ...; waiting for taps`, and the dashboard shows the home link as
   connected. Tap a tag: the log shows `tap 04406B02936980:62 from the hosted account: 1
   action(s) started`, then knurgN's own line saying whether Home Assistant took it.

## Keep it running

As a systemd user service, so it starts with the computer:

```ini
# ~/.config/systemd/user/knura-link.service
[Unit]
Description=Knura home link
After=network-online.target

[Service]
WorkingDirectory=%h/knura/home-link
ExecStart=%h/knura/knura-link/bin/knura-link
Restart=on-failure
RestartSec=30

[Install]
WantedBy=default.target
```

```sh
systemctl --user daemon-reload
systemctl --user enable --now knura-link
loginctl enable-linger "$USER"   # keep it running when you're logged out
journalctl --user -u knura-link -f
```

## How it stays safe

- The home link only connects out, to a `wss://` address, and logs in with a token in the
  `Authorization` header. The token lives in `link-secrets.properties`, which it refuses to read
  if other users can, and it is never logged.
- Each message names a tag UID, its counter and the tap time, nothing else. What a tag does is
  decided by your local `knurgn.properties`; the hosted account can't choose or change an
  action, and your connector secrets (Home Assistant webhook ids, webhook keys) never leave home.
- A tap that arrives twice runs once, and a tap that arrives more than 30 seconds late runs
  nothing, so a link that reconnects after an outage doesn't replay old taps.
- Removing the home link on the dashboard cuts it off at once; `knura-link` then stops with a
  message to pair again.

## Troubleshooting

- **`the hosted account refused this home link (HTTP 403)`**: the home link was removed on the
  dashboard, or the account's plan no longer includes it. Add it again, delete
  `link.properties` and `link-secrets.properties`, and run `knura-link pair`.
- **`reconnecting in N s` over and over**: the computer can't reach the internet, or the
  address in `link.properties` is wrong.
- **Connected, but a tap does nothing**: check the tag's UID in `knurgn.properties` (`no knot
  rule for this tag` in the log), and the connector's own log line for the answer it got.
