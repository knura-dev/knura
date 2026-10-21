# Set up Knura from the release downloads

This guide takes you from the files on the [Releases page](https://github.com/knura-dev/knura/releases)
to a tag that a phone can tap and that triggers an action. You don't need to build anything.

The same guide is on the website as an interactive page that fills in your own details:
[knura.io/get-started.html](https://knura.io/get-started.html).

> **Alpha.** Knura is early software. Don't use it to protect anything that matters yet.

## What you need

- **NTAG 424 DNA tags.** Stickers, cards or key fobs with this exact chip. Plain NTAG 213/215/216
  tags won't work.
- **A USB NFC reader that speaks PC/SC**, such as the ACS ACR122U. You need it only to set up
  tags; phones do the tapping afterwards.
- **A computer to run knurd** (Linux, macOS or Windows) that stays on while you want taps to work.
  A home server or an always-on desktop is fine.
- **Java 21 or newer** on that computer.

## 1. Install Java and the reader driver

**Fedora**

```sh
sudo dnf install java-21-openjdk pcsc-lite pcsc-lite-ccid
sudo systemctl enable --now pcscd.socket
```

**Debian / Ubuntu / Raspberry Pi OS**

```sh
sudo apt install openjdk-21-jre pcscd libccid
sudo systemctl enable --now pcscd.socket
```

**macOS**: install Java 21 (for example `brew install openjdk@21`, or the installer from
[adoptium.net](https://adoptium.net)). The reader driver is built in.

**Windows**: install Java 21 from [adoptium.net](https://adoptium.net) (tick "Set JAVA_HOME").
The reader driver is built in. Windows hasn't been tested much yet; please report problems.

Check Java with `java -version`. It should say 21 or higher.

## 2. Download and unzip

From the [latest release](https://github.com/knura-dev/knura/releases), download the two files
under **Assets**:

| File | What it is |
| --- | --- |
| `knurctl-<version>.zip` | The tag setup tool. Needed on the computer with the USB reader. |
| `knurd-<version>.zip` | The server that checks taps and runs actions (knurgN is built in). |

You can ignore "Source code (zip)" and "Source code (tar.gz)": GitHub adds those to every release.

Unzip both, and make one folder for your settings. knurctl and knurd both read `knurd.properties`
from the folder you run them in, so always run them from this folder.

```sh
mkdir -p ~/knura && cd ~/knura
unzip ~/Downloads/knurctl-*.zip
unzip ~/Downloads/knurd-*.zip
mkdir -p home && cd home          # your settings live here

# shortcuts for this terminal (add them to ~/.bashrc to keep them)
alias knurctl="$HOME/knura/knurctl-0.1.0-alpha/bin/knurctl"
alias knurd="$HOME/knura/knurd-0.1.0-alpha/bin/knurd"
```

On Windows, unzip with Explorer and run `knurctl-0.1.0-alpha\bin\knurctl.bat` and
`knurd-0.1.0-alpha\bin\knurd.bat` from a Command Prompt opened in your settings folder.

## 3. Make your master key

```sh
knurctl init
```

This writes `knurd.properties` with a new random **master key**. Every tag you set up gets its own
keys worked out from it.

**Back this file up somewhere safe and private** (a password manager, an encrypted USB stick).
Without it you can't verify or reset your tags. Never share it or put it in Git.

## 4. Set up a tag

Plug in the reader, then:

```sh
knurctl readers        # should list your reader
knurctl provision      # put a new tag on the reader when asked
knurctl tap            # reads the tag like a phone does and checks the link
```

`provision` gives the tag its own keys and writes a tap link onto it, then reads it back to check.
Leave the tag on the reader until it says it's done. `knurctl info` shows a tag's UID (its ID) and
link at any time; you'll need the UID in step 7.

For now the link points at `http://127.0.0.1:8424/tap`, which only works on this computer. Step 6
changes that so phones can reach it.

## 5. Start knurd and check a tap

```sh
knurd
```

It prints `knurd listening on http://127.0.0.1:8424`. Leave it running, open a second terminal in
the same folder, and check a tap:

```sh
knurctl tap                          # prints the link the tag just wrote
curl '<the link knurctl printed>'    # {"status":"ok","uid":"...","counter":...}
curl '<the same link again>'         # {"status":"replay",...}: a link only works once
```

That second answer is the point of Knura: a copied or reused link is refused.

## 6. Let phones reach knurd

A phone opens the link on the tag, so knurd needs an HTTPS address the phone can reach, and the
address must never change (it's written onto the tag). The free, simplest way is
[Tailscale Funnel](https://tailscale.com/kb/1223/funnel):

1. Install Tailscale on knurd's computer and sign in. Turn on HTTPS and Funnel for your tailnet when
   it asks.
2. Start the funnel to knurd's port:
   ```sh
   sudo tailscale funnel --bg 8424
   ```
   It prints your own address, ending in `.ts.net` (`tailscale funnel status` shows it again later).
3. Tell knurd its address: open `knurd.properties` and set `public.url` to **your** address from
   step 2, for example:
   ```properties
   public.url=https://<your-computer>.<your-tailnet>.ts.net
   ```
   Don't copy the example as it is: whatever you put here is written onto your tags.
4. Restart knurd (Ctrl+C, then `knurd`).
5. Rewrite the tag's link to use the new address:
   ```sh
   knurctl provision --from knura
   ```

Now tap the tag with your phone. You should see a "Tap verified" page; refresh it and you'll see
"Link already used".

A new Funnel address can take up to 15 minutes to work from phones. If the phone shows a blank page,
wait and try again.

### Optional: tags that survive a move (tap.knura.io)

If you might move knurd to another computer later, register with the free tap.knura.io forwarder.
Your tags then carry a tap.knura.io link, and moving means registering the new address instead of
rewriting every tag.

```sh
knurctl resolver register     # creates your owner ID and key
# restart knurd, then:
knurctl resolver register     # points tap.knura.io at your public.url
knurctl provision --from knura
knurctl resolver check        # shows where your taps go
```

Back up `knurd.properties` again afterwards: it now holds your resolver key too.

## 7. Make a tap do something

knurgN, built into knurd, runs an action for each verified tap. The first connector is a signed
webhook: a JSON POST to an address you choose. To try it, open [webhook.site](https://webhook.site)
and copy your unique URL.

In your settings folder, create `knurgn.properties` (use your tag's UID from `knurctl info`):

```properties
knot.04AABBCCDDEEFF.name=front-door
knot.04AABBCCDDEEFF.actions=test-hook
webhook.test-hook.url=https://webhook.site/your-unique-id
secrets.file=knurgn-secrets.properties
```

Make the signing secret (only you may read this file, or knurd refuses to start):

```sh
(umask 077; printf 'test-hook=whsec_%s\n' "$(openssl rand -base64 32)" > knurgn-secrets.properties)
echo 'actions.file=knurgn.properties' >> knurd.properties
```

Restart knurd. It should print `knurgN: 1 knot(s) with actions`. Tap the tag with your phone, and
webhook.site shows the event. A replayed link sends nothing.

Point the webhook at your own code, Node-RED or a home server when you're ready. The
[README](../README.md#knurgn) explains the event format and how to check the signature, and
[`examples/webhook-receiver`](../examples/webhook-receiver) has a receiver in one file.
For smart-home apps (Apple Home, Google Home, Alexa), see the
[Matter bridge](../knurgn-matter/README.md), which you run from the source download. To run
Home Assistant automations from taps, see [home-assistant.md](home-assistant.md).

## 8. Keep knurd running

knurd only works while it's running. On Linux, a systemd user service starts it with your computer:

```sh
mkdir -p ~/.config/systemd/user
cat > ~/.config/systemd/user/knurd.service <<EOF
[Unit]
Description=Knura tap server

[Service]
WorkingDirectory=$HOME/knura/home
ExecStart=$HOME/knura/knurd-0.1.0-alpha/bin/knurd
Restart=on-failure

[Install]
WantedBy=default.target
EOF
systemctl --user daemon-reload
systemctl --user enable --now knurd
sudo loginctl enable-linger $USER     # keep it running when you're logged out
journalctl --user -u knurd -f         # watch its log
```

## Upgrading

Download the new zips, unzip them next to the old ones, and update your shortcuts (and the systemd
service, if you made one). Your settings folder stays as it is: the master key, counters and rules
carry over, and tags keep working.

## Troubleshooting

| What you see | What to do |
| --- | --- |
| `knurctl readers` lists nothing | Check the reader is plugged in and `pcscd` is running (`systemctl status pcscd`). |
| `SELECT NDEF file failed with status 6A82` (or another card error) | The tag moved or hadn't settled. Leave it flat on the reader and run the command again. If `knurctl info` works but `tap` keeps failing, open an issue. |
| `java: command not found` or a version error | Install Java 21 and open a new terminal. |
| knurd says it can't find `knurd.properties` | Run knurd from your settings folder, where `knurctl init` wrote it. |
| knurd refuses a secrets or key file | Only you may read it: `chmod 600 <file>`. |
| Every tap is `rejected` | The tag was set up with a different master key. Use the `knurd.properties` it was provisioned with. |
| Phone says "address not found" | The tag's link has the wrong address. Check `public.url` against `tailscale funnel status`, restart knurd, and run `knurctl provision --from knura` again. |
| Phone shows a blank page | A new Funnel address can take up to 15 minutes to appear. Check `curl https://<your address>/health` from another network. |
| Phone says "Link already used" on the first tap | Something opened the link before you did, such as a link preview. Tap again. |

Found a bug? Open an issue. Found a security problem? Please report it privately, as described in
[SECURITY.md](../SECURITY.md).
