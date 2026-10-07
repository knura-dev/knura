# knura.io website

The knura.io home page. Plain HTML and CSS with no build step: the files in this folder are the
whole site.

| File | What it is |
| --- | --- |
| `index.html` | The home page |
| `404.html` | Shown for any address that doesn't exist |
| `favicon.svg` | Browser tab icon |
| `robots.txt` | Lets search engines index the site |

To look at it locally, open `index.html` in a browser.

## Hosting on AWS

The site is served by **CloudFront** (Amazon's CDN, which gives HTTPS) from a **private S3
bucket** (where the files live). Nobody can read the bucket directly; only CloudFront can. The
domain stays in **Route 53**.

Expected cost for a small site: Route 53 hosted zone $0.50 a month (you already pay this), the
certificate is free, S3 storage is a few cents, and CloudFront's free tier covers 1 TB of traffic
and 10 million requests a month.

Do all of this in the AWS console while signed in to the account that holds the knura.io
hosted zone.

### 1. Get an HTTPS certificate (ACM)

1. Open **Certificate Manager**. Switch the region (top right) to **US East (N. Virginia)
   us-east-1**. CloudFront only accepts certificates from that region.
2. **Request** a public certificate. Domain names: `knura.io` and `www.knura.io`. Validation:
   **DNS validation**. Keep the other defaults.
3. Open the new certificate and click **Create records in Route 53**, then **Create records**.
4. Wait until its status says **Issued** (usually a few minutes).

### 2. Make the bucket (S3)

1. Open **S3** and **Create bucket**. Name it something like `knura-io-site` (any free name
   works; it doesn't have to match the domain). Any region is fine.
2. Leave **Block all public access** turned on. Leave everything else as is and create it.
3. Open the bucket and **Upload** the four files from this folder: `index.html`, `404.html`,
   `favicon.svg` and `robots.txt`. Upload the files themselves, not the `site` folder, so
   `index.html` sits at the top of the bucket. Don't upload this README.

Don't turn on the bucket's "Static website hosting" setting. CloudFront reads the bucket
directly, which is what lets it stay private.

### 3. Put CloudFront in front (CloudFront)

1. Open **CloudFront** and **Create distribution**. If it asks you to pick a pricing plan, the
   free plan is enough.
2. **Origin**: choose the S3 bucket from the list (`knura-io-site.s3.<region>.amazonaws.com`).
   If the console offers its website address instead, don't use it.
3. **Origin access**: choose **Origin access control settings (recommended)**, then
   **Create new OAC** with the defaults. Let the console update the bucket policy if it offers
   to; otherwise step 6 covers it.
4. **Viewer protocol policy**: **Redirect HTTP to HTTPS**. Cache policy: **CachingOptimized**.
5. Settings:
   - **Alternate domain names (CNAMEs)**: add `knura.io` and `www.knura.io`.
   - **Custom SSL certificate**: pick the certificate from step 1.
   - **Default root object**: `index.html`.
   - **Web Application Firewall (WAF)**: **Do not enable security protections** (it costs extra
     and a static page doesn't need it).
   Then create the distribution.
6. If the console shows a banner saying the S3 bucket policy needs updating, click **Copy
   policy**, open the bucket's **Permissions** tab, edit **Bucket policy**, paste and save.
7. Back in the distribution, open **Error pages** and **Create custom error response** twice:
   - HTTP error code **403**, customize response **Yes**, response page path `/404.html`,
     response code **404**.
   - The same for error code **404**.
   (A private bucket answers 403 for a missing file, so both map to the 404 page.)
8. Wait until the distribution's status is no longer **Deploying**. Copy its domain name
   (`dxxxxxxxxxxxx.cloudfront.net`) and open it in a browser: you should see the site.

### 4. Point the domain at it (Route 53)

1. Open **Route 53**, **Hosted zones**, `knura.io`.
2. **Create record**: leave the name empty (that means `knura.io` itself), type **A**, turn on
   **Alias**, route traffic to **Alias to CloudFront distribution**, pick the distribution, and
   create it.
3. Repeat with type **AAAA** (the same thing for IPv6).
4. Do steps 2 and 3 again with the record name `www`.

If an A or AAAA record for `knura.io` or `www` already exists, edit it rather than adding a second.
After a few minutes, https://knura.io and https://www.knura.io both show the site.

## Updating the site

Upload the changed files to the bucket again (replacing the old ones). CloudFront keeps copies
for up to a day, so to show the change right away open the distribution, **Invalidations**,
**Create invalidation**, and enter `/*`. The first 1,000 invalidation paths each month are free.

With the [AWS CLI](https://aws.amazon.com/cli/) set up, the same from this folder:

```sh
aws s3 sync . s3://knura-io-site --delete --exclude README.md
aws cloudfront create-invalidation --distribution-id <your distribution id> --paths '/*'
```
