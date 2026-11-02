# Troubleshooting with logs

knurd and knura-link write a line for everything that matters: each tap and whether it passed,
each action and whether Home Assistant or your webhook took it, and problems reaching the tap
resolver or the hosted account. This page shows where to find those lines and how to get more
of them.

Knura never logs keys, admin or home link tokens, claim codes, or the MAC and encrypted data from
a tag's link. Every line passes through a filter that hides anything shaped like one, so a log is
safe to paste into an issue. Tag UIDs and counters stay visible, since they are what you look for.

## Where the log is

**Running in a terminal:** the lines appear right there.

**As a systemd service** (the setup guides use one): systemd keeps the log in its journal.

```sh
journalctl --user -u knurd -f                   # follow it live; Ctrl+C stops
journalctl --user -u knurd --since "10 min ago" # just the last ten minutes
journalctl --user -u knurd -p warning           # only warnings and errors
journalctl --user -u knurd -b                   # since the computer started
journalctl --user -u knurd | grep 04356B02936980  # one tag's taps
```

For knura-link, use `-u knura-link` instead.

**Docker or Kubernetes:** `docker logs -f <container>` or `kubectl logs -f deploy/<name>`.

### Also write a log file

Add `log.file` to `knurd.properties` (or `link.properties`) and restart:

```properties
log.file=logs/knurd.log
```

The path is relative to the config file's folder. Follow it with `tail -F logs/knurd.log`. At
10 MB the file moves to `knurd.log.1` and a new one starts under the same name; four old files
are kept, so it never takes more than about 50 MB.

## Get more detail

`log.level` picks how much is written. The dashboard's Settings page changes it without a restart
(**Log detail**), and its Logs page shows the end of the log file:

| Level | What you get |
| --- | --- |
| `error` | Only things that failed. |
| `warn` | Also things that need a look, like an action that didn't go through. |
| `info` | The default: every tap, every action's result, startup and connection messages. |
| `debug` | Also each action attempt (including retries) and how long it took. |
| `trace` | Everything, mostly for Knura developers. |

To turn on debug for one run without editing the file, set `KNURA_LOG_LEVEL`:

```sh
KNURA_LOG_LEVEL=debug knurd
```

For a systemd service, add it to the service and restart:

```sh
systemctl --user edit knurd     # add the two lines below, save, close
#   [Service]
#   Environment=KNURA_LOG_LEVEL=debug
systemctl --user restart knurd
```

Remove those lines again when you're done, since debug writes more.

## Reading the lines

```
2026-10-28 22:30:01.123 INFO  knurd: tap ok uid=04356B02936980 counter=12 from 100.101.102.103 (9 ms)
2026-10-28 22:30:01.310 INFO  knurgn: tap 04356B02936980:12 knot=front-door action=living-room: delivered after 1 attempt(s) (HTTP 200)
```

- **`tap ok`**: the tag is genuine and the link was fresh. Its actions start next.
- **`tap rejected ...: MAC does not match`**: the tag was set up with different keys than the
  ones knurd has. Check you're using the `knurd.properties` it was provisioned with.
- **`tap rejected ...: unknown tag`**: knurd has no key for this tag.
- **`tap replay ...: counter already used`**: this exact link was opened before, often by a link
  preview or a second tab. Tap the tag again for a fresh link.
- **`tap malformed`**: the link is missing parts. The tag may not be set up for Knura yet.
- **`no knot rule for this tag`**: the tap was fine, but no rule in `knurgn.properties` names
  this tag's UID, so nothing ran.
- **`action=...: refused (HTTP 401)`** or `HTTP 404`: the receiver turned the event down; check
  the webhook id or secret. `failed` with `ConnectException` means it couldn't be reached; check
  its address and that it's running. `expired` means it kept failing for 30 seconds.

After lots of failed taps from one address in a minute, knurd stops logging each one and writes
a count instead, so a flood can't fill the disk.
