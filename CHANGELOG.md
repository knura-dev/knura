# Changelog

## Unreleased

- knurgN: Home Assistant connector (`homeassistant.<id>.url`) that starts an automation through
  its webhook trigger, with no login token. See docs/home-assistant.md.

## 0.1.0-alpha (unreleased)

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
