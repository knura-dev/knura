package io.knura.knurd;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * The page a phone shows after a tap. Browsers ask for HTML, so a tap opened straight from the
 * tag gets this page instead of knurd's JSON. It has no scripts, images or links, and its
 * Content-Security-Policy allows only its own inline style, pinned by hash. The one link is the
 * "Open in Knura" button on a refused tap, which hands the tap to the Knura app to sign.
 *
 * <p>The tag UID appears only when the MAC checked out ({@code ok} and {@code replay}); the
 * verifier's detail text is never shown, so a failed tap tells the visitor nothing about why.
 */
final class TapPage {
    private static final String STYLE = """
            :root{color-scheme:light dark;--bg:#f6f5f2;--card:#fff;--ink:#1d1d1f;--muted:#6e6e73;\
            --ok:#1f8a4c;--warn:#b26a00;--bad:#c4302b}
            @media (prefers-color-scheme:dark){:root{--bg:#111;--card:#1c1c1e;--ink:#f2f2f7;\
            --muted:#a1a1a6;--ok:#34c770;--warn:#ffb340;--bad:#ff6961}}
            *{box-sizing:border-box}
            body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;\
            padding:24px 16px;background:var(--bg);color:var(--ink);\
            font:17px/1.45 -apple-system,system-ui,"Segoe UI",Roboto,sans-serif}
            main{width:100%;max-width:380px;background:var(--card);border-radius:20px;padding:32px 24px;\
            text-align:center;box-shadow:0 1px 3px rgba(0,0,0,.08)}
            .mark{width:72px;height:72px;margin:0 auto 16px;border-radius:50%;display:flex;\
            align-items:center;justify-content:center;font-size:38px;font-weight:700;color:#fff}
            .ok .mark{background:var(--ok)}.warn .mark{background:var(--warn)}.bad .mark{background:var(--bad)}
            h1{margin:0 0 8px;font-size:24px}
            p{margin:0;color:var(--muted)}
            .tag{margin-top:20px;font-size:14px;font-family:ui-monospace,Menlo,monospace}
            footer{margin-top:24px;font-size:13px;color:var(--muted)}
            .btn{display:block;margin-top:20px;padding:14px 18px;border-radius:14px;background:#f28c3a;\
            color:#1d1d1f;font-weight:600;text-decoration:none}
            """;

    /** For every HTML response: no scripts, no requests, no framing, only the style above. */
    static final String CONTENT_SECURITY_POLICY = "default-src 'none'; style-src '" + sha256(STYLE)
            + "'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";

    private TapPage() {}

    /**
     * @param actionsStarted how many knurgN actions the tap started; only used when it was ok
     */
    static String render(TapResult result, int actionsStarted) {
        return render(result, actionsStarted, null);
    }

    /**
     * @param query the tap link's query, for the "Open in Knura" button of a refused tap; null for none
     */
    static String render(TapResult result, int actionsStarted, String query) {
        return switch (result.status()) {
            case OK -> page("ok", "&#10003;", "Tap verified", actionsStarted == 0
                    ? "Genuine tag. No action is set up for it yet."
                    : actionsStarted == 1
                            ? "Genuine tag. Running its action now."
                            : "Genuine tag. Running its " + actionsStarted + " actions now.",
                    result);
            case REPLAY -> page("warn", "&#8635;", "Link already used",
                    "Each tap makes a new one-time link, and this one has been used. Tap the tag again.",
                    result);
            case REJECTED -> page("bad", "&#10005;", "Not verified",
                    "This server could not confirm the tag is genuine, so nothing was run.", result);
            case REFUSED -> page("warn", "&#9679;", "Use the Knura app for this tag",
                    "This tag only works for the owner's phones, tapping with the Knura app. Nothing was run yet."
                            + openInApp(query), result);
            case MALFORMED -> page("bad", "?", "Not a tap link",
                    "This page opens when you tap a Knura tag. Nothing was run.", result);
        };
    }

    /**
     * A button that opens the Knura app with this tap, so a paired phone can sign it. Phones often
     * open a tag in the browser even with the app installed; the tap wasn't used up, so the app can
     * still send it. Only a plain tap query is passed on.
     */
    static String openInApp(String query) {
        if (query == null || query.isEmpty() || query.length() > 512 || !query.matches("[A-Za-z0-9=&_.~-]+")) {
            return "";
        }
        String q = java.net.URLEncoder.encode(query, StandardCharsets.UTF_8);
        return "</p><a class=\"btn\" href=\"intent://tap?q=" + escape(q)
                + "#Intent;scheme=knura;package=io.knura.android;end\">Open in Knura</a><p>";
    }

    /** For a tap knurd could not check at all. */
    static String error() {
        return page("bad", "!", "Something went wrong", "This tap could not be checked, so nothing was run.", null);
    }

    private static String page(String tone, String mark, String title, String message, TapResult result) {
        String tag = result == null || result.uid() == null
                ? ""
                : "<p class=\"tag\">Tag " + escape(result.uid()) + " &middot; tap #" + result.counter() + "</p>";
        return """
                <!doctype html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <meta name="robots" content="noindex">
                <title>%s</title>
                <style>%s</style>
                </head>
                <body class="%s">
                <main>
                <div class="mark" aria-hidden="true">%s</div>
                <h1>%s</h1>
                <p>%s</p>
                %s
                <footer>Knura</footer>
                </main>
                </body>
                </html>
                """.formatted(title, STYLE, tone, mark, title, message, tag);
    }

    static String escape(String s) {
        StringBuilder out = new StringBuilder();
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '&' -> out.append("&amp;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(ch);
            }
        }
        return out.toString();
    }

    private static String sha256(String s) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return "sha256-" + Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JDK has SHA-256", e);
        }
    }
}
