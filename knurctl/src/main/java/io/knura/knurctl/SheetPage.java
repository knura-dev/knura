package io.knura.knurctl;

import io.knura.core.backup.RecoverySheet;
import java.util.List;

/**
 * The printable recovery sheet: one A4 or Letter page, black on white, no scripts, no web fonts and
 * nothing loaded from the internet, so opening it sends the keys nowhere.
 */
final class SheetPage {
    private SheetPage() {}

    /**
     * @param owner the tap.knura.io owner ID, or null when not registered
     * @param resolverSeed the 32-byte resolver.key, or null when not registered
     */
    static String render(String keyId, String day, byte[] master, String owner, String resolverUrl, byte[] resolverSeed) {
        StringBuilder html = new StringBuilder();
        html.append("""
                <!doctype html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <meta name="referrer" content="no-referrer">
                <title>Knura recovery sheet</title>
                <style>
                  @page { margin: 12mm 14mm; }
                  * { box-sizing: border-box; }
                  body { margin: 0 auto; max-width: 180mm; padding: 16px; background: #fff; color: #000;
                         font: 10.5pt/1.4 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; }
                  h1 { font-size: 18pt; margin: 0 0 2px; letter-spacing: -0.01em; }
                  h2 { font-size: 11.5pt; margin: 14px 0 5px; padding-bottom: 4px; border-bottom: 1.5px solid #000; }
                  .meta { display: flex; flex-wrap: wrap; gap: 6px 28px; margin: 6px 0 14px; color: #222; }
                  .meta b { font-family: ui-monospace, "DejaVu Sans Mono", Menlo, Consolas, monospace; }
                  .warn { border: 2px solid #000; padding: 10px 12px; margin: 12px 0; }
                  table { border-collapse: collapse; }
                  td { padding: 3px 16px 3px 0; vertical-align: baseline; }
                  .n { color: #555; font-size: 10pt; white-space: nowrap; }
                  .row { font-family: ui-monospace, "DejaVu Sans Mono", Menlo, Consolas, monospace;
                         font-size: 15pt; letter-spacing: 0.08em; white-space: nowrap; }
                  .chk { font-family: ui-monospace, "DejaVu Sans Mono", Menlo, Consolas, monospace; font-size: 15pt;
                         color: #555; border-left: 1px solid #999; padding-left: 12px; }
                  .field { font-family: ui-monospace, "DejaVu Sans Mono", Menlo, Consolas, monospace; font-size: 13pt; }
                  ol { padding-left: 20px; margin: 6px 0; }
                  li { margin: 3px 0; }
                  code { font-family: ui-monospace, "DejaVu Sans Mono", Menlo, Consolas, monospace; font-size: 10pt; }
                  .small { font-size: 9.5pt; color: #333; }
                  .notes { height: 14mm; border: 1px solid #999; margin-top: 6px; }
                  @media print { body { padding: 0; } .noprint { display: none; } }
                  .noprint { background: #f2f2f2; padding: 8px 12px; margin-bottom: 14px; }
                </style>
                </head>
                <body>
                <p class="noprint">Print this page on paper, then delete this file. Don't save it as a PDF.</p>
                <h1>Knura recovery sheet</h1>
                """);
        html.append("<div class=\"meta\"><span>Master key ID <b>").append(esc(keyId)).append("</b></span><span>Made <b>")
                .append(esc(day)).append("</b></span></div>\n");
        html.append("""
                <div class="warn"><b>Keep this like a passport.</b> Anyone who reads it can make your tags'
                tap links and send your taps elsewhere. Never photograph it, scan it or type it into a website.
                Store it somewhere safe and away from the computer running knurd.</div>
                """);
        html.append("<h2>master.key</h2>\n");
        rows(html, "master.key", master);
        if (owner != null && resolverSeed != null) {
            html.append("<h2>tap.knura.io</h2>\n<table>\n");
            html.append("<tr><td class=\"n\">Owner</td><td class=\"field\">").append(esc(owner)).append("</td></tr>\n");
            html.append("<tr><td class=\"n\">Resolver</td><td class=\"field\">").append(esc(resolverUrl)).append("</td></tr>\n");
            html.append("</table>\n<p class=\"small\" style=\"margin:10px 0 2px\">resolver.key</p>\n");
            rows(html, "resolver.key", resolverSeed);
        } else {
            html.append("<p class=\"small\">This setup isn't registered with tap.knura.io, so there is no resolver key."
                    + " If you register later, print a new sheet.</p>\n");
        }
        html.append("""
                <h2>To use this sheet</h2>
                <ol>
                  <li>Install knurctl and knurd from the latest release on the new or repaired computer.</li>
                  <li>In an empty folder, run <code>knurctl backup restore --sheet</code> and type each row in,
                      check characters included. A typo is caught on the row it is in.</li>
                  <li>Check the master key ID it prints matches the one at the top of this page.</li>
                  <li>Set <code>public.url</code>, start knurd, run <code>knurctl resolver register</code> if you
                      use tap.knura.io, and tap a tag.</li>
                </ol>
                <p class="small">This sheet holds your keys only. Action rules, webhook secrets and phone pairings
                are not on it: an encrypted backup file (<code>knurctl backup create</code>) keeps those too.
                If you ever make a new master key, print a new sheet and destroy this one.</p>
                <h2>Notes</h2>
                <div class="notes"></div>
                </body>
                </html>
                """);
        return html.toString();
    }

    private static void rows(StringBuilder html, String label, byte[] secret) {
        List<String> rows = RecoverySheet.rows(label, secret);
        html.append("<table>\n");
        for (int i = 0; i < rows.size(); i++) {
            String row = rows.get(i).trim();
            int split = row.lastIndexOf(' ');
            html.append("<tr><td class=\"n\">Row ").append(i + 1).append("</td><td class=\"row\">")
                    .append(esc(row.substring(0, split).trim())).append("</td><td class=\"chk\">")
                    .append(esc(row.substring(split + 1))).append("</td></tr>\n");
        }
        html.append("</table>\n");
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
