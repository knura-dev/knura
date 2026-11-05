# Signed taps: knowing which phone tapped

A tap always proves two things: the tag is genuine (its MAC checks out with the tag's key) and
the tap is new (its counter was never used). It doesn't say **who** tapped. Anyone holding a
phone to the tag, or anyone who picked up a tap link that was never used, starts the tag's
actions.

Signed taps add that missing piece. A phone paired with knurd (or with a hosted account) reads
the tag itself and sends the tap with a signature from a key that lives in the phone's secure
hardware. knurd checks the tag exactly as before, and also checks the signature, so it knows which
paired phone sent the tap. The actions then hear who tapped.

This is step 1 of letting you choose **who can use a tag**. For now every tag still works for
anyone, as before; signed taps only add the tapper's name to the tap. Per-tag "who can use this
tag" settings come next and build on this.

## What it feels like

1. Unlock your phone (Android only reads tags while the phone is unlocked).
2. Hold it to the tag.
3. The Knura app opens by itself, sends the signed tap, shows **Done** for a moment and closes.

That works for tags with a `https://tap.knura.io/...` link, which is how tags set up through the
resolver are written. With the app already open, holding any of your server's tags to the phone
works too. Someone else's tags, and taps on phones without the app, open in the browser as usual.

## The two tap keys

When the app first refreshes after pairing, it makes two EC P-256 key pairs in the Android
Keystore (StrongBox on phones that have it). The private halves never leave the phone.

- **Everyday key**: works whenever the phone is unlocked. Used for normal taps.
- **Confirm key**: the phone only lets it sign right after a fingerprint or the screen lock, every
  single time. knurd can tell which key signed, so a confirmed tap really was confirmed, without
  trusting what the app shows. (It will be used by "Ask to confirm" tags in the next step.)

The app sends both public keys over the admin API with its own token (`POST /v1/tap-keys`), the
same trusted connection it was paired over. knurd saves them in `knurd-tap-keys.properties` next
to the tokens file, together with the hash of that phone's token. A phone's keys only count while
it still has that same token, so removing a phone (dashboard, app or `knurctl admin token remove`)
also stops its signed taps, and a new phone paired under an old name can't use the old keys.
Pairing again makes new keys.

## Why a key and not the phone's token

The phone's token is an admin login: it can set tags up and change settings, so it only works on
knurd's admin port, which is never on the internet. A tap key can only say "this phone tapped",
so knurd can safely accept it on the **public** tap address (your Tailscale Funnel or other
tunnel). That means signed taps work away from home too, and the admin port stays closed.

## The request

The app sends `POST <public.url>/tap` with:

```json
{"link": "e=...&c=...", "phone": "pixel", "time": 1793000000, "key": "everyday", "sig": "..."}
```

- `link`: the query of the tag's link, exactly as read from the tag.
- `time`: the phone's clock in seconds; it must be within 60 seconds of knurd's.
- `sig`: base64 of the SHA256withECDSA signature (DER) of these five lines:

  ```
  knura-tap-v1
  <link>
  <phone>
  <time>
  <key>
  ```

A bad signature, an unknown phone or an old time gets `401 unauthorized` **before** the tag is
checked, so it can't use the tap up. Otherwise the answer is the same as for a browser tap, plus
`actions` and `tapper`:

```json
{"status":"ok","uid":"04DE5F1EACC040","counter":61,"actions":2,"tapper":{"phone":"pixel","confirmed":false}}
```

A signed tap is still a tap: its counter is used once, so sending the same signed tap twice gets
`409 replay`.

## What actions see

Webhooks and Home Assistant get a `tapper` object in the event's `data` when a phone signed the
tap, and nothing new for browser taps, so existing automations keep working:

```json
"tapper": {"phone": "pixel", "confirmed": false}
```

For example, one tag by the speaker can play each person's own playlist, or a tag by the desk can
set each person's favourite light. The dashboard's tap history (`GET /v1/taps`) shows `tapper` too.

## Limits

- This proves which **paired phone** sent the tap. It can't tell who is holding an unlocked phone.
- It doesn't stop someone from relaying a tag over the internet to a paired phone. It does make a
  harvested tap link far less useful once tags can be limited to certain people.
- iPhones can't sign taps yet (the iOS app is paused); their taps work as browser taps.
