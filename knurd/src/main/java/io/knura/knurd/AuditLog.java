package io.knura.knurd;

import io.knura.core.admin.PrivateFiles;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;

/**
 * Every admin API request, one JSON line each, appended to {@code admin.audit.file}: when, from
 * which address, with which token name, what was asked and how knurd answered. It never holds
 * tokens, keys or request bodies. The file is created readable only by knurd's user.
 */
final class AuditLog implements AutoCloseable {
    private final Path file;
    private final Clock clock;
    private final Writer out;

    AuditLog(Path file, Clock clock) throws IOException {
        this.file = file;
        this.clock = clock;
        if (!Files.exists(file)) {
            PrivateFiles.create(file, "");
        }
        String problem = PrivateFiles.problem(file);
        if (problem != null) {
            throw new IOException(problem);
        }
        this.out = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    Path file() {
        return file;
    }

    /**
     * Writes one entry and flushes it. Throws if it can't, and the caller then refuses the
     * request: nothing happens on the admin API without a record of it.
     *
     * @param token the token's name, or null if the request wasn't logged in
     */
    synchronized void record(String remote, String token, String method, String path, int status, String detail)
            throws IOException {
        String safePath = path.length() > 200 ? path.substring(0, 200) + "..." : path;
        String line = "{\"time\":\"" + clock.instant() + "\",\"remote\":" + Json.quote(remote)
                + ",\"token\":" + Json.quote(token) + ",\"method\":" + Json.quote(method)
                + ",\"path\":" + Json.quote(safePath) + ",\"status\":" + status
                + (detail == null ? "" : ",\"detail\":" + Json.quote(detail)) + "}\n";
        out.write(line);
        out.flush();
    }

    @Override
    public synchronized void close() throws IOException {
        out.close();
    }
}
