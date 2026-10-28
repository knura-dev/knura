# Contributing to Knura

Thanks for helping. Knura is alpha and small, so a short note before a big change saves
everyone time.

## Before you start

- **Questions and ideas** go in [Discussions](https://github.com/knura-dev/knura/discussions).
- **Bugs** go in [issues](https://github.com/knura-dev/knura/issues): what you did, what you
  expected, what happened, and your version or commit.
- **Security problems** are reported privately, see [SECURITY.md](SECURITY.md). Please don't
  open a public issue for them.
- For anything bigger than a small fix, open a Discussion or issue first so we can agree on the
  approach.

New to the code? Read [docs/architecture.md](docs/architecture.md) first (one page), then
[docs/how-we-review.md](docs/how-we-review.md).

## Build and test

You need JDK 21. Node 22 only for `knurgn-matter` and `knura-resolver`; the Android SDK only
for `android/`.

```sh
./gradlew build                                   # Java modules: compile and all tests
./gradlew :knura-core:test                        # one module
(cd knurgn-matter && npm ci && npm test)          # Matter bridge
(cd knura-resolver && npm ci && npm test)         # tap.knura.io resolver
./gradlew -p android testDebugUnitTest            # Android app
```

You don't need a tag or reader to run the tests: `FakeNtag424` in knura-core's test fixtures
simulates a tag, and the crypto tests use NXP's published example keys. To try a real tap, follow
[Getting started](README.md#getting-started).

## Making a change

1. Fork the repo (or make a branch if you have access) from `main`.
2. Keep each pull request to one change. Add or update tests with it.
3. Run `./gradlew build` (and the Node or Android tests if you touched those).
4. Update the README, `docs/` and `CHANGELOG.md` (under **Unreleased**) if users will notice.
5. Open a pull request and fill in the template. CI must be green before merging.

`main` is protected: every change, including the maintainer's, goes through a pull request.

## Code style

- Match the code around you: plain Java 21, small classes, clear names, short comments that say
  why rather than what.
- **knura-core and knurd use the JDK only.** Please don't add runtime dependencies there; they
  are the parts everything else trusts, and keeping them small keeps them reviewable.
- Docs and messages use plain, short sentences.

## Rules that protect users

- **Never commit keys.** No real or development keys in the repo; tests use NXP's published
  examples. `*.key` and local `.properties` files are git-ignored, and push protection blocks
  commits that look like they hold a secret.
- **Don't change key derivation** (`TagKeyDerivation`). Its values are pinned by tests;
  changing them would break every tag already set up.
- **Only verified taps start actions.** knurgN must only ever get taps knurd returned `ok` for,
  and the action must come from the tag's verified UID, never from the link.
- **No sensitive actions** (locks, garage doors, alarms) until approved-phone confirmation
  exists.
- Changes to tag commands should come with tests against recorded APDU traces, and ideally a
  try on a real tag.

## Using AI tools

Knura itself is built with AI coding tools (see [docs/how-we-review.md](docs/how-we-review.md)),
so AI-assisted contributions are welcome. The rule is the same as for any code: you understand
what you're submitting, you've run it, and you can answer questions about it in review. Please
say in the PR if a large part was generated.

## License

By contributing you agree that your work is licensed under the [Apache License 2.0](LICENSE),
the same as the rest of Knura.
