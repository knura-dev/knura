# Set up tags with your Android phone

The Knura app sets tags up, checks them and resets them with your phone's own NFC, so you
don't need a USB reader. The phone only passes messages between the tag and knurd: knurd works
out the tag's keys from your master key and checks every reply, and the keys never reach the
phone.

```
tag ──NFC──▶ phone (Knura app) ──HTTPS on your Wi-Fi──▶ knurd admin API (holds master.key)
```

You need:

- knurd already working with your phone, up to "Tap verified" (see
  [setup-from-release.md](setup-from-release.md)). `master.key` and `public.url` must be set.
- An Android phone with NFC, Android 11 or newer, with a screen lock set.
- The phone and knurd's computer on the same network.

Run every `knurctl` command below in the folder that holds your `knurd.properties`.

## 1. Let the phone reach knurd's admin API

The admin API normally only answers this computer. The phone reaches it over your Wi-Fi, which
needs TLS.

1. Find this computer's address on your network:

   ```sh
   ip -4 addr | grep inet
   ```

   Use the one that looks like `192.168.x.x` (not `127.0.0.1`). It's best to give this computer
   a fixed address in your router, because the phone remembers it.

2. Make the admin API's TLS certificate, if you haven't yet:

   ```sh
   knurctl admin tls create
   ```

3. In `knurd.properties`, set the address from step 1:

   ```properties
   admin.listen.host=192.168.1.50
   ```

   (Creds files made earlier for `127.0.0.1` stop working. Make new ones with
   `knurctl admin token add <name> --admin-url https://192.168.1.50:8425`.)

4. Let port 8425 through the firewall. On Fedora:

   ```sh
   sudo firewall-cmd --add-port=8425/tcp --permanent && sudo firewall-cmd --reload
   ```

   Never forward this port on your router or put it on Tailscale Funnel. It's for your own
   network only, and knurd refuses requests that come through a tunnel.

## 2. Install the app

1. On the phone, open this link in the browser and download the app:
   **https://github.com/knura-dev/knura/releases/download/android-latest/knura.apk**
   (it always has the newest test build).
2. Open the download and tap **Install**.
3. Allow **Install unknown apps** for your browser when Android asks. If Play Protect warns
   about an unknown developer, choose **Install anyway**.

Each build is signed with a fresh test key, so to install a newer one, uninstall the old app
first (then pair again).

## 3. Pair the phone

```sh
knurctl admin pair-phone fold7
```

Use any name for the phone. This makes a login token that may set tags up, adds it to
`admin.provision.tokens` in `knurd.properties`, and shows a QR code. Then:

1. Restart knurd (it reads `admin.provision.tokens` when it starts). Its first lines should say
   `Phones may set tags up with these admin tokens: fold7`.
2. In the app, tap **Pair with knurd**, scan the code, and unlock with your fingerprint or
   screen lock.

The code holds the token, so don't photograph or share it. If the phone is lost:
`knurctl admin token remove fold7`. knurd refuses it straight away.

## 4. Use it

Tap one of the four tiles, tap **Start, then hold the tag**, unlock, and hold the tag flat
against the back of the phone until the result screen comes up. That takes a few seconds. The
result screen shows the tag's UID and what changed; **What knurd did** has knurd's full log and
**Copy details** copies it all. If nothing happens, slide the tag
around the back slowly: phones read tags at one spot only.

| Action | What it does |
| --- | --- |
| Set up a tag | Knura keys and your tap link. Turn on "Tag already has Knura keys" to re-point a tag knurd already set up (like `--from knura`). |
| Tag info | UID, link, whose keys it has, and whether the link checks out. |
| Check a tap | Reads the tag like a phone tap and checks it with your keys. |
| Reset tag | All keys back to zero, no link. Turn the switch off for a tag that still has factory keys. |

Under **knurd**, three rows show what `knurctl admin` shows on the computer:
**Status** (uptime, keys, public URL, resolver, knots), **Tags** (every tag knurd has accepted,
with its last counter) and **Recent taps** (the latest 20 in your phone's time, each marked
Genuine, Replay, Rejected or Malformed). They only read.

**Remove a token** lists knurd's admin tokens and removes the one you pick, like
`knurctl admin token remove`. Use it to lock out a lost laptop or another phone straight away.
Only phone tokens (the ones in `admin.provision.tokens`) may do this. If you remove this phone's
own token, the app forgets knurd and you pair again.

The app follows the phone's light or dark mode. On a foldable, the home screen shows the tag
actions and knurd side by side when the phone is open, and folding it mid-session keeps going.

Setting up keys, TLS, new tokens and the resolver stays on the computer, so a lost phone can't
open up your setup.

If the tag slips away halfway through, run the same action again. Key 0 is always changed
last, so the tag is never left in a state knurd can't fix.

## If something goes wrong

| The app says | What to do |
| --- | --- |
| Can't reach knurd at https://... | Is knurd running, the phone on the same Wi-Fi, and port 8425 open (step 1.4)? Does `admin.listen.host` match the address? |
| knurd's certificate isn't the one this phone was paired with | You made a new certificate: forget knurd in the app, run `pair-phone` with a new name, and pair again. |
| setting tags up through a phone is off | Restart knurd after `pair-phone`. |
| this token may not set tags up | The name isn't in `admin.provision.tokens`. |
| the tag refused the key | Flip the "Tag already has Knura keys" box, or check the tag with **Show tag info**. |
| another phone is setting a tag up | Wait 20 seconds for the earlier attempt to time out. |

Every phone session is written to knurd's admin audit log (`admin.audit.file`), with the token
name and the tag's UID.
