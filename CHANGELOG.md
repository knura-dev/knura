# Changelog

## Unreleased

- knurd: set tags up, reset and read them through a phone's NFC, for the coming Android app.
  The phone only relays messages between the tag and knurd, so tag keys never leave knurd. Off
  unless `admin.provision.tokens` names a token; see knurd.example.properties.

## 0.2.0-alpha (2026-10-22)

- knurgN: Home Assistant connector (`homeassistant.<id>.url`) that starts an automation through
  its webhook trigger, with no login token. See docs/home-assistant.md.
- knurgN: a clear error when a secrets file line is missing its connector id, without printing
  the line.
- docs: a step-by-step Home Assistant guide, linked from the release setup guide.

## 0.1.0-alpha (2026-10-19)

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
