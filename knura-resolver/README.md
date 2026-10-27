# knura-resolver (tap.knura.io)

A tag provisioned through the resolver carries

```
https://tap.knura.io/<owner>?e=<encrypted UID and counter>&c=<MAC>
```

instead of its owner's own server address. When a phone opens it, the resolver looks up which
server that owner registered and sends the phone there:

```
phone ──GET tap.knura.io/<owner>?e=..&c=..──▶ resolver ──302──▶ <server>/tap?e=..&c=.. ──▶ knurd checks the tap
```

So a knurd can move (new computer, new tunnel, a hosted server) by registering its new address,
instead of rewriting every tag. Products built on Knura with many tags per owner need the same.

It is a small Node.js function for AWS Lambda behind API Gateway, with owner records in a
DynamoDB table. It has no dependencies of its own (the AWS SDK comes with Lambda).

## Security

**A pipe, not a judge.** The resolver holds no tag keys. It can't read `e=` (the tag's UID is
encrypted in it), can't tell a genuine tap from a forged one, and can't make a tap knurd would
accept. knurd checks every tap itself, exactly as without the resolver. A tap passes through
unchanged: only an `e=` of 32 hex digits and a `c=` of 16, nothing else, or the phone gets an
error page instead of a redirect.

**Only the owner can move their taps.** Each owner has an Ed25519 key pair made by
`knurctl resolver register`. The private half (`resolver.key`) stays in `knurd.properties`; the
resolver keeps only the public half. The first registration claims the owner ID for that key, and
every later change must be signed by it. Each record carries a `seq` that must go up, so an old
signed record (say, one pointing at a server you gave up) can't be replayed. Owner IDs are 10
random characters (50 bits), so nobody can claim yours before you do.

**No open redirect.** Before accepting a server, the resolver asks it
`GET <server>/.well-known/knura-owner` and requires `{"owner":"<that owner>"}`, with no
redirects followed. knurd answers that only for its own `resolver.owner`. So nobody can point
tap.knura.io at a site they don't run. Servers must be `https://` with a host name: no IP
addresses, `localhost`, `.local`, user names, queries or fragments.

**Changes don't go unnoticed.** Records are public at `GET /api/v1/owners/<owner>` with their
signature. knurd fetches its own record when it starts and every 6 hours, checks it is signed by
its key and points at its `public.url`, and logs a warning otherwise. `knurctl resolver check`
does the same on demand. Because knurd checks the signature itself, even someone who edits the
DynamoDB table directly can't move taps without knurd noticing.

**What it can't protect against.** Whoever runs the resolver could send phones somewhere else
while showing knurd the right record; no check from outside can catch that. They still couldn't
forge a tap, but they could swallow taps or show a fake page. If you don't want to trust a
resolver, provision with your own address (`knurctl provision --base-url <public.url>/tap`) or run
your own copy of this one. Also, the redirect means the resolver sees each tap's `e=` and `c=`;
it could use a tap before the phone does, which counts as that tap (knurd's counter lets each
tap through once), but it can never make new ones.

**Abuse limits.** API Gateway throttles requests (registration much harder than taps), one
address can register at most 5 new owner IDs an hour (moves don't count), request bodies are
capped at 2 KB, a server check times out after 5 seconds and reads at most 1 KB, and the function
logs only the owner ID and outcome of each request, never `e=` or `c=`. Logs are kept 14 days.

**Suspending a misused owner ID.** If someone uses tap.knura.io links to dress up a phishing
site, the operator opens the owner's item in DynamoDB (**Explore items**) and adds a String
attribute `suspended` with a short reason, like `phishing, reported 2026-10-27`. Its taps then
get a "Link suspended" page instead of a redirect, and the record can't be moved. Deleting the
attribute lifts it. Reports go through [SECURITY.md](../SECURITY.md).

**If a resolver.key is lost or stolen.** The resolver has no account recovery: there is no email
or password to reset. Keep an encrypted backup of `knurd.properties`. If the key is gone or
someone else has it:

1. Make a new key: in an empty folder, `touch new.properties` and run
   `knurctl resolver register --config new.properties`. It writes a new `resolver.publicKey` and
   `resolver.key` (and a new owner ID you won't use) to `new.properties`.
2. In `knurd.properties`, replace the old `resolver.publicKey` and `resolver.key` lines with the
   new ones, keeping your `resolver.owner`. Delete `new.properties`.
3. Ask the operator through [SECURITY.md](../SECURITY.md) for the owner ID to move to your new
   public key. They confirm it's you another way, then in DynamoDB set the item's `publicKey` to
   the new key.
4. Restart knurd and run `knurctl resolver register`. It signs with the new key and a higher `seq`.

If someone moved your taps with a stolen key, knurd warns about it at its next check (within 6
hours); suspend the owner ID first (above) so no taps go to their server meanwhile.

## API

| Request | Answer |
| --- | --- |
| `GET /<owner>?e=<32 hex>&c=<16 hex>` | 302 to `<server>/tap?e=..&c=..`; 404 page for an unknown owner, 400 page for anything that isn't a tag's link |
| `GET /api/v1/owners/<owner>` | 200 `{"owner","server","seq","publicKey","signature","updatedAt"}`, or 404 |
| `PUT /api/v1/owners/<owner>` with `{"server","seq","publicKey","signature"}` | 200 with the record; 400 malformed, 403 bad signature or another key's owner ID, 409 `seq` not higher, 422 server didn't confirm the owner |
| `GET /` | 302 to https://knura.io |

The signature is Ed25519 over this text (UTF-8, each line ending in `\n`), with keys and
signatures as lowercase hex:

```
knura-resolver-v1
owner=<owner>
server=<server>
seq=<seq>
```

`knura-core`'s `io.knura.core.resolver` signs and checks the same format; both sides test against
one shared example (RFC 8032's first test key).

## Try it on your computer

```sh
cd knura-resolver
npm install      # only for the tests' copy of the AWS SDK
npm test
npm run dev      # resolver on http://127.0.0.1:8425, records in memory
```

The dev server accepts `http://127.0.0.1` servers, which the real one never does. Point a test
knurd at it with `knurctl resolver register --resolver http://127.0.0.1:8425`.

## Host it on AWS

About 30 minutes of clicking in the [AWS console](https://console.aws.amazon.com). Pick one region
for everything below (the menu at the top right), for example **US East (N. Virginia)
us-east-1**, and stay in it.

### 1. The table (DynamoDB)

1. Open **DynamoDB**, **Tables**, **Create table**.
2. Table name `knura-resolver-owners`, partition key `owner`, type **String**. No sort key.
3. Table settings: **Customize settings**. Capacity mode: **On-demand**. Turn on
   **Deletion protection**. Leave the rest and **Create table**.
4. Open the table and copy its **Amazon Resource Name (ARN)** from **General information**
   (`arn:aws:dynamodb:us-east-1:123456789012:table/knura-resolver-owners`). You need it in step 2.

### 2. The function (Lambda)

1. Build the upload on your computer: `cd knura-resolver && npm run package`. This makes
   `knura-resolver.zip`.
2. Open **Lambda**, **Create function**, **Author from scratch**.
   - Function name `knura-resolver`, runtime **Node.js 22.x**, architecture **arm64**.
   - Leave **Create a new role with basic Lambda permissions** selected. **Create function**.
3. In **Code**, **Upload from**, **.zip file**, choose `knura-resolver.zip`, **Save**.
4. Still in **Code**, scroll to **Runtime settings**, **Edit**, set Handler to
   `src/index.handler`, **Save**.
5. **Configuration**, **Environment variables**, **Edit**, **Add environment variable**: key
   `TABLE_NAME`, value `knura-resolver-owners`. **Save**.
6. **Configuration**, **General configuration**, **Edit**: Memory `256` MB, Timeout `0 min 10 sec`.
   **Save**.
7. **Configuration**, **Permissions**: click the role name (opens IAM). **Add permissions**,
   **Create inline policy**, switch to **JSON**, paste this with your ARN from step 1, then
   **Next**, name it `knura-resolver-table`, **Create policy**:

   ```json
   {
     "Version": "2012-10-17",
     "Statement": [
       {
         "Effect": "Allow",
         "Action": ["dynamodb:GetItem", "dynamodb:PutItem"],
         "Resource": "arn:aws:dynamodb:us-east-1:123456789012:table/knura-resolver-owners"
       }
     ]
   }
   ```

   That is all the function can do: read and write that one table.
8. Optional cost cap: **Configuration**, **Concurrency**, **Edit**, **Reserve concurrency** `10`.
   New AWS accounts sometimes refuse this (their whole limit is 10); then skip it.

### 3. The web address (API Gateway)

1. Open **API Gateway**, **Create API**, **HTTP API**, **Build**.
2. **Add integration**: **Lambda**, pick `knura-resolver`. API name `knura-resolver`. **Next**.
3. Routes: change the one route to method **ANY**, path `$default`. **Add route**: method **PUT**,
   path `/api/v1/owners/{owner}`, same integration. **Next**.
4. Stages: keep `$default` with auto-deploy. **Next**, **Create**.
5. Copy the **Invoke URL** (`https://abc123.execute-api.us-east-1.amazonaws.com`) and open it: it
   should send you to knura.io. Open `<invoke URL>/api/v1/owners/aaaaaaaaaa`: it should say
   `{"error":"no such owner"}`.
6. Left menu **Throttling** (under Protect). Stage `$default`:
   - **Default route throttling**, **Edit**: burst `100`, rate `50`. **Save**.
   - **Route throttling**: select `PUT /api/v1/owners/{owner}`, **Edit**: burst `5`, rate `1`.
     **Save**.

### 4. tap.knura.io (Certificate Manager, API Gateway, Route 53)

1. Open **Certificate Manager** (same region), **Request**, **Request a public certificate**,
   domain `tap.knura.io`, DNS validation, **Request**. Open it, **Create records in Route 53**,
   **Create records**. Wait until the status is **Issued** (a few minutes).
2. Back in **API Gateway**, **Custom domain names**, **Create**: domain `tap.knura.io`, endpoint
   **Regional**, the certificate from step 1. **Create domain name**.
3. In that domain, **API mappings**, **Configure API mappings**, **Add new mapping**: API
   `knura-resolver`, stage `$default`, path empty. **Save**.
4. Copy the domain's **API Gateway domain name** (`d-xxxxxxxx.execute-api.us-east-1.amazonaws.com`).
5. Open **Route 53**, **Hosted zones**, `knura.io`, **Create record**: name `tap`, type **A**,
   turn on **Alias**, **Alias to API Gateway API**, your region, pick the `d-xxxxxxxx` domain.
   **Create records**.
6. After a few minutes, https://tap.knura.io sends you to knura.io. Optionally, back in the API's
   settings, turn on **Disable default endpoint** so only tap.knura.io reaches it.

### Updating

`npm run package`, then in Lambda **Code**, **Upload from**, **.zip file** again.

### Cost

At home-project volume, close to nothing: Lambda's free tier is a million requests a month,
HTTP APIs cost about $1 per million requests, an on-demand DynamoDB table with a few records costs
cents, and the certificate is free. Logs go to CloudWatch. Set their retention to 14 days
(**CloudWatch**, **Log groups**, `/aws/lambda/knura-resolver`, **Actions**, **Edit retention
setting**): they show when an owner's tags were tapped, so keep them no longer than needed.

### Alarms

So you hear when the resolver breaks (every resolver tag stops working with it):

1. **CloudWatch**, **Alarms**, **Create alarm**, **Select metric**: **Lambda**, **By Function
   Name**, `knura-resolver` **Errors**. Statistic **Sum**, period **5 minutes**, threshold
   **Greater than 0**. Notification: create an SNS topic with your email, and confirm the email
   AWS sends. Name it `knura-resolver-errors`.
2. The same for **ApiGateway**, the `knura-resolver` API's **5xx**, named `knura-resolver-5xx`.
3. **Billing and Cost Management**, **Budgets**, **Create budget**, a monthly cost budget (for
   example $10) that emails you at 80%.
