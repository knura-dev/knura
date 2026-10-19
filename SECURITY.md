# Security

Knura is alpha software. Please don't use it yet to protect anything where a failure would hurt
people or property.

## Reporting a vulnerability

Please report security problems privately, not in a public issue:

- On GitHub: the repository's **Security** tab → **Report a vulnerability**.

Include what you found, how to reproduce it, and which version or commit you tested. You'll get
an answer within a week. Once a fix is out, we're happy to credit you in the release notes.

## What counts

Anything that lets someone forge or replay a tap, start an action from a tap that wasn't
verified, read or change keys or secrets, reach the admin API from where it shouldn't be
reachable, or make tap.knura.io send a phone somewhere its owner didn't register.

## Supported versions

Only the latest release gets security fixes while Knura is pre-1.0.
