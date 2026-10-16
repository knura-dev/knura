package io.knura.knurctl;

import io.knura.core.admin.CertFingerprint;
import java.io.IOException;
import java.net.ConnectException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Map;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;

/**
 * Talks to knurd's admin API with a {@link Creds}. It connects directly (never through a
 * proxy, since the admin API is only on this computer or the local network), never follows
 * redirects (so the token goes nowhere else), and with {@code knurd.fingerprint} set trusts only
 * the certificate with that fingerprint.
 */
final class AdminClient {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final Creds config;
    private final HttpClient http;

    AdminClient(Creds config) {
        this.config = config;
        HttpClient.Builder builder = HttpClient.newBuilder()
                .proxy(HttpClient.Builder.NO_PROXY)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(TIMEOUT);
        if (config.fingerprint() != null) {
            builder.sslContext(pinned(config.fingerprint()));
        }
        this.http = builder.build();
    }

    /** GETs {@code path} and returns the parsed JSON answer. */
    @SuppressWarnings("unchecked")
    Map<String, Object> get(String path) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(config.url() + path))
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .GET();
        if (config.token() != null) {
            builder.header("Authorization", "Bearer " + config.token());
        }
        HttpRequest request = builder.build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        } catch (SSLHandshakeException e) {
            throw new IOException(config.fingerprint() == null
                    ? "TLS with " + config.url() + " failed (" + e.getMessage() + "). For knurd's own certificate,"
                            + " the creds file needs knurd.fingerprint"
                    : "knurd at " + config.url() + " showed a certificate that doesn't match knurd.fingerprint. If knurd's"
                            + " admin certificate was remade, make a new creds file; otherwise something else is"
                            + " pretending to be knurd", e);
        } catch (HttpConnectTimeoutException | ConnectException e) {
            throw new IOException("can't reach knurd's admin API at " + config.url()
                    + ". Is knurd running with admin.tokens.file set, and is this the right address?", e);
        } catch (IOException e) {
            throw new IOException("talking to knurd at " + config.url() + " failed (" + e.getMessage() + ")."
                    + (config.url().getScheme().equals("http") ? " If knurd's admin API uses TLS, knurd.url must start with https://." : ""), e);
        }
        Object json;
        try {
            json = MiniJson.parse(response.body());
        } catch (IllegalArgumentException e) {
            throw new IOException("knurd answered " + response.statusCode() + " with something that isn't JSON."
                    + " Is " + config.url() + " knurd's admin port (not its tap port)?", e);
        }
        if (!(json instanceof Map<?, ?>)) {
            throw new IOException("knurd answered with unexpected JSON");
        }
        Map<String, Object> body = (Map<String, Object>) json;
        int status = response.statusCode();
        if (status == 200) {
            return body;
        }
        String detail = body.get("detail") instanceof String s ? s : String.valueOf(body.get("status"));
        throw new IOException(switch (status) {
            case 401 -> config.token() == null
                    ? "knurd wants a login token, but knurd.properties here says admin.security=plaintext. Restart"
                            + " knurd after changing admin.security, or pass --creds"
                    : "knurd doesn't accept this token. It may have been removed (knurctl admin token list"
                    + " on knurd's computer), or this creds file is for another knurd";
            case 429 -> "too many bad tokens from this computer; knurd is refusing it for a few minutes";
            default -> "knurd answered " + status + ": " + detail;
        });
    }

    /** An SSL context that trusts exactly the certificate with this SHA-256 fingerprint. */
    static SSLContext pinned(String fingerprint) {
        byte[] want = fingerprint.getBytes(StandardCharsets.US_ASCII);
        X509ExtendedTrustManager trust = new X509ExtendedTrustManager() {
            private void check(X509Certificate[] chain) throws CertificateException {
                if (chain == null || chain.length == 0) {
                    throw new CertificateException("no certificate");
                }
                byte[] got = CertFingerprint.of(chain[0]).getBytes(StandardCharsets.US_ASCII);
                if (!MessageDigest.isEqual(want, got)) {
                    throw new CertificateException("certificate fingerprint " + new String(got, StandardCharsets.US_ASCII)
                            + " is not the one in knurd.fingerprint");
                }
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                check(chain);
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
                    throws CertificateException {
                check(chain);
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                    throws CertificateException {
                check(chain);
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                throw new CertificateException("knurctl is not a server");
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
                    throws CertificateException {
                throw new CertificateException("knurctl is not a server");
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                    throws CertificateException {
                throw new CertificateException("knurctl is not a server");
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] {trust}, null);
            return context;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("TLS is not available", e);
        }
    }
}
