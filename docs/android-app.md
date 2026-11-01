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

Using a hosted Knura account instead of your own knurd? Skip steps 1 and 3: install the app
(step 2), pair the phone on the account's page, and scan that code with **Scan pairing code**.
The tiles then work the same, with your keys kept on the hosted server.

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

Only install the app from that link or a Knura release page. To check a download, compare its
SHA-256 with `knura.apk.sha256` on the same page (`sha256sum knura.apk` on Linux).

Builds signed with the Knura release key install over the previous one. Older test builds were
signed with a fresh test key each time, so the first time you move to a release-signed build,
uninstall the old app first (then pair again).

## 3. Pair the phone

```sh
knurctl admin pair-phone fold7
```

Use any name for the phone. This adds the name to `admin.provision.tokens` in
`knurd.properties` and shows a QR code holding a one-time pairing code. Then:

1. Restart knurd (it reads `admin.provision.tokens` when it starts). Its first lines should say
   `Phones may set tags up with these admin tokens: fold7`.
2. Within 15 minutes, in the app, tap **Scan pairing code**, scan the code, and unlock with your
   fingerprint or screen lock. The app swaps the code for the phone's own login token (app 0.5.0
   or newer), which never appears on screen.

The code works once and only for 15 minutes, so a photo of it is useless afterwards. If it ran
out, run `pair-phone` again with the same name. If the phone is lost:
`knurctl admin token remove fold7`. knurd refuses it straight away.

## 4. Use it

The home screen greets you, shows your server with today's taps and a 7-day chart, and has a
tile for each action. Tap a tile, unlock, and hold the tag flat against the back of the phone
until the result screen comes up. That takes a few seconds. The orange button in the bottom menu
starts **Check a tap** from any tab. The result screen shows the tag's UID and what changed;
**What knurd did** has knurd's full log and **Copy details** copies it all. If nothing happens,
slide the tag around the back slowly: phones read tags at one spot only.

| Action | What it does |
| --- | --- |
| Set up a tag | Knura keys and your tap link. Turn on "Tag already has Knura keys" to re-point a tag knurd already set up (like `--from knura`). |
| Tag info | UID, link, whose keys it has, and whether the link checks out. |
| Check a tap | Reads the tag like a phone tap and checks it with your keys. |
| Reset tag | All keys back to zero, no link. Turn the switch off for a tag that still has factory keys. |
| Claim a tag | For a ready-to-use tag that came with a claim card. Scan the card's QR code or type its code, then hold the tag: knurd swaps the card's keys for your own and the card stops working (like `--from claim:CODE`). With a pack card (one card for several tags), **Claim next tag** claims the next tag without scanning the card again. |

The bottom menu has four tabs. They read what `knurctl admin` shows on the computer, after one
unlock that lasts while the app stays open:

- **Home**: your server card (tap it to refresh), the actions, and your tags with their last tap.
- **Tags**: every tag knurd has accepted, with its last counter and last tap. Tap a tag to give
  it a name. With your own knurd, names stay on the phone. With a hosted account, names are
  shared with the account's dashboard: rename a tag in either place and the other shows it the
  next time it refreshes. If a tag is renamed in both places, the newest change wins. A rename
  made while the phone is offline is sent on the next refresh, and only sticks if nobody renamed
  the tag on the dashboard after it.
- **Taps**: the latest 200 taps in your phone's time, grouped by day, filtered by Verified or
  Rejected.
- **Server**: taps today, tags, uptime, the tap link, keys, certificate and resolver, and the
  admin tokens.

Tap a tag on Home or Tags to open it: its taps this week, its tap counter, rejected taps, what
its last tap ran, and its recent taps, with **Rename** and **Reset tag**. What a tag does when
tapped is set on the server (knurgN's settings, or your hosted account's dashboard).

The bell on Home opens notifications: rejected or replayed taps, new tags, added or removed
tokens, and the server going offline and coming back. The app makes them when it checks your
server, so they appear while the app is open; alerts while it is closed need a later server
update.

With no tags yet, Home offers **Set up your first tag**, a five-step walkthrough that sets up
the tag, says where to choose its action, and ends with a test tap. It also opens after pairing
a server with no tags, and from the empty Tags tab.

The phone keeps the last answer, so the tabs still show something before you unlock. If the
server doesn't answer, Home says so and offers **Try again**.

On the **Server** tab, **Remove** next to a token removes it, like
`knurctl admin token remove`. Use it to lock out a lost laptop or another phone straight away.
Only phone tokens (the ones in `admin.provision.tokens`) may do this. If you remove this phone's
own token, the app forgets knurd and you pair again.

**Quick Settings tile and widgets.** Edit your Quick Settings and drag in **Check a tap** to
start a check from the pull-down. On the home screen, long-press, choose Widgets and find Knura:
**Last tap** (the newest tap and whether the server answered), **Check a tap** (one touch) and
**Today** (today's taps, rejected ones and the 7-day chart). Widgets show what the app last
read, because only the app can unlock the token with your fingerprint; they update every time
the app checks your server.

**Settings** (the gear on Home or Server) has your name, the theme (system, light or dark, on
Android 12 and newer), vibration, the first-tag walkthrough, clearing notifications, your
server, and links to the docs, the source code and Android's settings for the app.

The app follows the phone's light or dark mode unless you pick one in Settings. On a foldable, the home screen shows the actions
and your tags side by side when the phone is open, and folding it mid-session keeps going.

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
