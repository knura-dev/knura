package io.knura.link;

import java.lang.System.Logger.Level;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Keeps the home link's WebSocket to the hosted account open. It dials out, so nothing at home
 * needs to accept connections. The token goes in the {@code Authorization} header, never in the
 * address. A dropped connection is reopened after 1, 2, 4... up to 60 seconds; servers also close
 * connections after a couple of hours, which is normal. A small message every few minutes keeps
 * the connection from being closed as idle.
 */
final class LinkClient {
    private static final System.Logger LOG = System.getLogger("knura-link");
    static final Duration KEEP_ALIVE = Duration.ofMinutes(5);
    private static final Duration MAX_GAP = Duration.ofSeconds(60);
    /** A connection that stayed up this long resets the gap to 1 second. */
    private static final Duration STEADY = Duration.ofSeconds(60);
    private static final String PING = "{\"action\":\"ping\"}";

    /** Thrown when the server turns the token down: the home link was removed or re-paired. */
    static final class RefusedException extends Exception {
        private static final long serialVersionUID = 1L;

        RefusedException(int status) {
            super("the hosted account refused this home link (HTTP " + status
                    + "); it may have been removed from the dashboard. Pair it again with knura-link pair");
        }
    }

    private final LinkConfig config;
    private final TapRelay relay;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    LinkClient(LinkConfig config, TapRelay relay) {
        this.config = config;
        this.relay = relay;
    }

    /** The address with the owner ID added; the token is sent separately. */
    static URI address(LinkConfig config) {
        String url = config.url().toString();
        // A bare host, like wss://link.example.com, still needs a path in front of the query.
        String path = config.url().getRawPath() == null || config.url().getRawPath().isEmpty() ? "/" : "";
        return URI.create(url + path + "?owner=" + URLEncoder.encode(config.owner(), StandardCharsets.UTF_8));
    }

    /** Connects and stays connected until the thread is interrupted or the server refuses the token. */
    void run() throws RefusedException, InterruptedException {
        Duration gap = Duration.ofSeconds(1);
        while (!Thread.currentThread().isInterrupted()) {
            Instant opened = Instant.now();
            try {
                connectOnce();
            } catch (ExecutionException | CompletionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                if (cause instanceof WebSocketHandshakeException handshake) {
                    int status = handshake.getResponse().statusCode();
                    if (status == 401 || status == 403) {
                        throw new RefusedException(status);
                    }
                    LOG.log(Level.WARNING, "the hosted account answered HTTP {0}; trying again", status);
                } else {
                    // Name the type only: messages can quote addresses but never matter here.
                    LOG.log(Level.WARNING, "couldn''t reach the hosted account ({0}); trying again", cause.getClass().getSimpleName());
                }
            }
            if (Duration.between(opened, Instant.now()).compareTo(STEADY) > 0) {
                gap = Duration.ofSeconds(1);
            }
            LOG.log(Level.INFO, "reconnecting in {0} s", gap.toSeconds());
            Thread.sleep(gap.toMillis());
            gap = gap.multipliedBy(2).compareTo(MAX_GAP) > 0 ? MAX_GAP : gap.multipliedBy(2);
        }
        throw new InterruptedException();
    }

    private void connectOnce() throws ExecutionException, InterruptedException {
        Listener listener = new Listener();
        WebSocket socket = http.newWebSocketBuilder()
                .header("Authorization", "Bearer " + config.token())
                .connectTimeout(Duration.ofSeconds(15))
                .buildAsync(address(config), listener)
                .get();
        LOG.log(Level.INFO, "connected to {0}; waiting for taps", config.url().getHost());
        socket.sendText(relay.hello(), true).get();
        try {
            while (true) {
                try {
                    listener.closed.get(KEEP_ALIVE.toMillis(), TimeUnit.MILLISECONDS);
                    LOG.log(Level.INFO, "the hosted account closed the connection");
                    return;
                } catch (TimeoutException quiet) {
                    socket.sendText(PING, true).get(15, TimeUnit.SECONDS);
                }
            }
        } catch (TimeoutException e) {
            LOG.log(Level.WARNING, "the connection stopped answering");
        } finally {
            socket.abort();
        }
    }

    /** Gathers each text message (it may come in parts) and hands it to the relay. */
    private final class Listener implements WebSocket.Listener {
        final CompletableFuture<Void> closed = new CompletableFuture<>();
        private final StringBuilder message = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            if (message.length() + data.length() > 16_384) {
                LOG.log(Level.WARNING, "ignored a message that was too long");
                message.setLength(0);
            } else {
                message.append(data);
                if (last) {
                    String text = message.toString();
                    message.setLength(0);
                    try {
                        relay.onMessage(text);
                    } catch (RuntimeException e) {
                        LOG.log(Level.ERROR, "a tap message failed", e);
                    }
                }
            }
            socket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
            closed.complete(null);
            return null;
        }

        @Override
        public void onError(WebSocket socket, Throwable error) {
            closed.completeExceptionally(error);
        }
    }
}
