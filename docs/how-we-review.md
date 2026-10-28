# How Knura is built and checked

Knura is built by one person, Zack, working with AI coding tools (mostly Claude). This page is
open about what that means and how each change is checked, so you can judge the code for
yourself.

## How code gets written

- Zack decides what to build and how it should behave. Code is drafted with an AI assistant,
  then read, questioned and changed by hand before it is merged.
- Every change goes through a pull request, even from Zack. The PR says what changed, why, and
  how it was tested. Nothing goes straight onto `main`.
- Security-sensitive parts (the tag crypto, key derivation, replay checks) are built against
  NXP's published specs and examples, not written from memory, and are pinned by tests.

## What runs on every pull request

[CI](../.github/workflows/ci.yml) runs on each PR and on `main`:

| Job | What it checks |
| --- | --- |
| Java build and tests | `./gradlew build` for knura-core, knurctl, knurd, knurgn and knura-link |
| Android app | unit tests and a debug build of the app |
| Node tests | knurgn-matter and knura-resolver |
| Helm chart on kind | installs the chart on a throwaway Kubernetes cluster and checks a real tap |

That is 300+ automated tests. The ones that matter most:

- **Tag crypto** against NXP's published examples: AN12196 (tap verification), AN10922 (key
  diversification), plus RFC 4493 for AES-CMAC.
- **Tag commands** byte for byte against APDU traces recorded from a real tag on an ACR122U.
- **Pinned key derivation**, so an accidental change fails the build instead of breaking
  everyone's tags.
- **Replay and forgery**: replayed counters, bad MACs and unknown tags are refused, and forged
  taps don't use up counters.

GitHub Actions are pinned to exact commits, and Dependabot raises security alerts and weekly
dependency updates.

## Testing on real hardware

Before a release, changes that touch tags, phones or connectors are tried on real NTAG 424 DNA
tags, an ACR122U reader, an Android phone and a real Home Assistant, following written test
steps. Automated tests catch a lot, but they don't replace a real tap.

## Security review

In October 2026 Knura had a full security pass:

1. **A threat model** of every part (tags, knurd, admin API, phone app, resolver, releases,
   GitHub), using STRIDE per element on data-flow diagrams and OWASP risk rating. It found
   that the tap crypto and replay protection were sound, and that the bigger risks were at the
   edges: the GitHub account, release signing, pairing and abuse limits.
2. **An audit** of the code against that model, fixed in batches through normal PRs, including
   one-time pairing codes for knurd and the app, tap flood limits, resolver abuse limits,
   signed releases with checksums, a stable app signing key, pinned Actions and recovery docs.
   The changes are in the [changelog](../CHANGELOG.md) and the PR history.
3. **Account hardening**: passkey sign-in on the GitHub account, `main` protected so every
   change needs a pull request and can't be force-pushed, and secret scanning with push
   protection so a commit holding a key is blocked.

What is still open is listed under [Known limits](../README.md#known-limits). Found something?
Please report it privately as described in [SECURITY.md](../SECURITY.md).

## Releases

Releases are built by the [release workflow](../.github/workflows/release.yml) from a tag on
`main`, never on someone's laptop. Each one comes with a `SHA256SUMS` file and a signed build
provenance record (check a download with `gh attestation verify FILE -R knura-dev/knura`). Knura is
alpha: expect breaking changes, and don't use it to protect anything that matters yet.

## Where this is going

The aim is a small, solid base that more developers can join. If you want to help, start with
[CONTRIBUTING.md](../CONTRIBUTING.md) and [architecture.md](architecture.md).
