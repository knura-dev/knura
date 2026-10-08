package io.knura.core.resolver;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * Talks to a tap resolver such as {@code https://tap.knura.io}: reads an owner's record and
 * registers or moves it. Never sends any tag key; the resolver only ever sees the owner's public
 * key and signed records.
 */
public final class ResolverClient {
    public static final String DEFAULT_URL = "https://tap.knura.io";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final String baseUrl;
    private final HttpClient http;

    /** Throws {@link IllegalArgumentException} unless {@code baseUrl} is https (or http on this computer). */
    public ResolverClient(String baseUrl) {
        this.baseUrl = checkUrl(baseUrl);
        this.http = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public String baseUrl() {
        return baseUrl;
    }

    /** The tap URL base a tag gets for this owner: {@code <resolver>/<owner>}. */
    public String tapBase(String owner) {
        return baseUrl + "/" + owner;
    }

    /** True if this resolver runs on this computer, which is for testing only. */
    public boolean isLocal() {
        return OwnerRecord.isLoopback(URI.create(baseUrl).getHost());
    }

    /** The owner's record, or empty if the resolver has none. */
    public Optional<OwnerRecord> fetch(String owner) throws IOException {
        HttpResponse<String> response = send(HttpRequest.newBuilder(ownerUri(owner)).GET());
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        if (response.statusCode() != 200) {
            throw new IOException(describe(response));
        }
        try {
            return Optional.of(OwnerRecord.fromJson(response.body()));
        } catch (IllegalArgumentException e) {
            throw new IOException(baseUrl + " sent a record knurctl can't read: " + e.getMessage(), e);
        }
    }

    /** Registers or moves the owner's record. Throws with the resolver's reason if it refuses. */
    public void put(OwnerRecord record) throws IOException {
        HttpResponse<String> response = send(HttpRequest.newBuilder(ownerUri(record.owner()))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(record.updateJson(), StandardCharsets.UTF_8)));
        if (response.statusCode() != 200) {
            throw new IOException(describe(response));
        }
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws IOException {
        try {
            return http.send(
                    request.timeout(TIMEOUT).header("Accept", "application/json").build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while talking to " + baseUrl, e);
        } catch (IOException e) {
            throw new IOException("could not reach " + baseUrl + ": " + e.getMessage(), e);
        }
    }

    private URI ownerUri(String owner) {
        if (!OwnerIds.isValid(owner)) {
            throw new IllegalArgumentException("not an owner ID: " + owner);
        }
        return URI.create(baseUrl + "/api/v1/owners/" + owner);
    }

    private String describe(HttpResponse<String> response) {
        String reason;
        try {
            reason = FlatJson.parse(response.body()).get("error") instanceof String s ? s : response.body();
        } catch (IllegalArgumentException e) {
            reason = response.body();
        }
        if (reason.length() > 300) {
            reason = reason.substring(0, 300) + "...";
        }
        return baseUrl + " answered HTTP " + response.statusCode() + (reason.isBlank() ? "" : ": " + reason);
    }

    private static String checkUrl(String url) {
        String trimmed = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("resolver URL is not valid: " + url, e);
        }
        String host = uri.getHost();
        boolean ok = host != null
                && uri.getRawQuery() == null
                && uri.getRawFragment() == null
                && uri.getRawUserInfo() == null
                && ("https".equals(uri.getScheme())
                        || ("http".equals(uri.getScheme()) && OwnerRecord.isLoopback(host)));
        if (!ok) {
            throw new IllegalArgumentException("resolver URL must look like " + DEFAULT_URL + ": " + url);
        }
        return trimmed;
    }
}
