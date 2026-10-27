# Ready-to-use tags and claim codes

A ready-to-use tag arrives locked with its own **claim keys**, not the all-zero factory keys,
together with a claim card. Claiming the tag swaps those keys for keys from your own
`master.key`. After that only your knurd can verify or change the tag, and the card is useless.

Anyone can sell ready-to-use tags; this page describes the open format they use.

## The claim card

The card shows three things:

- the tag's UID, e.g. `04DE5F1EACC040`
- the claim code: 28 letters and digits in groups of four, e.g.
  `0004-1061-050R-3GG2-8A1C-60T3-GFQS`
- a QR code holding `knura://claim?uid=<UID>&code=<code>`

Keep the card until the tag is claimed. If the seal over the code was already broken when the
card arrived, don't claim that tag: someone may have read the code first. If a claim says the
code doesn't open a tag you've never claimed, someone else may have claimed it already: stop,
keep the card and the tag, and contact the seller with the tag's UID.

### Pack cards

A pack of several tags can come with one **pack card** instead of a card per tag. Every tag in
the pack was locked with the same claim code, and the card says how many tags it claims instead
of showing a UID. Its QR code holds `knura://claim?code=<code>`, with no UID. Each tag still has
keys of its own, because claim keys also depend on the tag's UID (see below), so claiming one tag
doesn't touch the others. Claim every tag of the pack before you throw the card away: an
unclaimed tag can't be claimed without it.

## Claim a tag with the phone app

In the Knura app, tap **Claim a tag**, then **Start**. Scan the QR code on the card (or tap
**Type code** and type it), unlock, and hold the tag to the phone. The app checks the code for
typos and, with the QR code, that you're holding the card's own tag. knurd works out the keys
and swaps them; the code goes to knurd only, over the pinned connection, and knurd forgets it
when the job ends. See [android-app.md](android-app.md).

With a pack card, the result screen offers **Claim next tag** (app 0.3.3 or newer): unlock and
hold the next tag, without scanning the card again. It counts the tags claimed with the card. A
tag that was already claimed says the code doesn't open it; that's expected.

## Claim a tag with knurctl

Put the tag on the reader and run, with your own code:

```
knurctl provision --from claim:0004-1061-050R-3GG2-8A1C-60T3-GFQS
```

`--from claim:` also takes the whole link, in quotes because of its `&`
(`--from 'claim:knura://claim?uid=...&code=...'`), and then checks that the tag on the
reader is the one the card was made for. A pack card's link has no UID, so it works for every
tag of the pack: run the same command once per tag. Case, spaces and dashes don't matter, and O, I and L
are read as 0, 1 and 1. A typo is caught before knurctl touches the tag.

`knurctl reset --from claim:CODE` puts an unclaimed tag back to factory keys instead.

## How claim keys work

The claim code holds a random 16-byte secret, plus two check characters (the first 10 bits of
the secret's SHA-256) to catch typos. It is written in Crockford base32.

All five tag keys come from that secret with AN10922 diversification, using the input
`"Knura" || 0x03 || keyNo || UID`. Every key depends on the tag's UID, so a card only opens its
own tag. The code is in `io.knura.core.claim.ClaimCode` and `TagKeySet.claim`. Its output is
pinned by tests: printed cards depend on it, so it never changes.

The seller sets each tag up from factory keys to claim keys with an `https://` starter link,
for example `https://knura.io/new`. The buyer's knurd then moves it from claim keys to its own
keys. The seller should not keep the claim codes once the cards are printed.
