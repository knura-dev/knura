# Install Knura

The installers bundle Java, so there is nothing else to install. Get them from the
[latest release](https://github.com/knura-dev/knura/releases). After installing, open the setup
page and follow its seven steps.

| Computer | File | What it sets up |
| --- | --- | --- |
| Ubuntu, Debian, Linux Mint (x86-64) | `knura_<version>_amd64.deb` | a system service |
| Raspberry Pi OS 64-bit, ARM64 Linux | `knura_<version>_arm64.deb` | a system service |
| Fedora (x86-64) | `knura-<version>-1.x86_64.rpm` | a system service |
| Windows 10 and 11 | `Knura-<version>.msi` | a Knura app that starts at sign-in |
| Mac with Apple silicon | `Knura-<version>.pkg` | a Knura app that starts at sign-in |

## Linux and Raspberry Pi

```sh
sudo apt install ./knura_<version>_arm64.deb     # or: sudo dnf install ./knura-<version>-1.x86_64.rpm
```

This installs Knura to `/opt/knura` and makes a `knura` system user. It also starts the `knura`
service now and at every boot (`systemctl status knura`, logs in `journalctl -u knura` and
`/var/lib/knura/logs/`). Settings live in `/var/lib/knura`, readable only by that user. The first
start makes a new master key there, and step 2 of the setup page backs it up.

- On the Knura computer, open <http://127.0.0.1:8425/setup>.
- From another computer or phone on the same network, for a Pi without a screen:
  1. Open `https://<the Pi's address>:8426/setup`. The browser warns about the certificate. Knura
     makes its own, so continue.
  2. Enter the setup code with your first password. Read it with
     `sudo cat /var/lib/knura/knurd-setup-code`, or find it in `journalctl -u knura`. It works
     once.

`knurctl` is installed too. Run it as the service's user, in its folder:
`cd /var/lib/knura && sudo -u knura knurctl status`. Removing the package stops the service and
keeps `/var/lib/knura`, because that folder holds the master key every tag depends on.

On Fedora, let phones on your Wi-Fi reach port 8426 to pair:
`sudo firewall-cmd --add-port=8426/tcp --permanent && sudo firewall-cmd --reload`.

## Windows and Mac

Install, then open **Knura** from the Start menu or Applications. It starts Knura in the
background, sets it to start each time you sign in, and opens the setup page. Open it again
whenever you want the page.

- Windows: settings in `%LOCALAPPDATA%\KnuraData`; starts through the `Knura` entry under
  Settings > Apps > Startup.
- Mac: settings in `~/Library/Application Support/Knura`; starts through
  `~/Library/LaunchAgents/io.knura.knurd.plist`, which also restarts it if it stops.

Knura only runs while you're signed in. Before uninstalling, stop it starting at sign-in:

- Windows: run `Knura.exe --no-start-at-sign-in` in the folder Knura was installed to (usually
  `%LOCALAPPDATA%\Knura`).
- Mac: run `/Applications/Knura.app/Contents/MacOS/Knura --no-start-at-sign-in`.

The installers aren't signed yet:

- Windows SmartScreen may say "Windows protected your PC". Choose More info, then Run anyway.
- On a Mac, if the .pkg won't open, go to System Settings > Privacy & Security and choose Open
  Anyway.

## What the installers run

Each runs `knurd --home <settings folder>`. On Linux they add `--lan`. On the first start this
makes the folder (owner-only) and a starter `knurd.properties` with:

- a new master key,
- the tap port on 127.0.0.1:8424,
- the admin API and setup page on 127.0.0.1:8425,
- a log file in `logs/`.

`--lan` also makes the admin TLS certificate, turns on the Wi-Fi listener (`admin.lan.port=8426`)
and makes the setup code. Later starts leave the folder alone. Build an installer yourself with
`packaging/build-installer.sh <version> <deb|rpm|msi|pkg>` on that system.
