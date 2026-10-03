package com.orbitwatch.ingest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.zip.GZIPInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Thin HTTP client for CelesTrak. Identifies itself (providers ask for that), accepts gzip, and
 * treats anything but 200 as a fetch failure — the caller decides whether to serve from cache.
 */
@Component
public class CelestrakClient {
    private static final Logger log = LoggerFactory.getLogger(CelestrakClient.class);
    // HTTP/1.1 explicitly: the JDK client's default is HTTP/2 with ALPN, and a provider or middlebox
    // that mishandles the upgrade shows up as "Remote host terminated the handshake" rather than an
    // HTTP error. One less variable when a fetch fails from someone else's network.
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** Attempts per fetch. A single transient reset should not cost a whole ingest cycle. */
    private static final int ATTEMPTS = 3;

    public static final class FetchException extends IOException {
        public FetchException(String msg, Throwable cause) { super(msg, cause); }
        public FetchException(String msg) { super(msg); }
    }

    public InputStream fetch(String url) throws FetchException {
        FetchException last = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                return fetchOnce(url);
            } catch (FetchException e) {
                last = e;
                // Only transport failures are retried; an HTTP status error (no cause) will not fix itself.
                if (e.getCause() == null) throw e;
                if (attempt < ATTEMPTS) {
                    log.warn("fetch attempt {}/{} failed ({}); retrying", attempt, ATTEMPTS, e.getMessage());
                    try {
                        Thread.sleep(1000L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                }
            }
        }
        throw last;
    }

    private InputStream fetchOnce(String url) throws FetchException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(120))
                .header("User-Agent", "OrbitWatch/0.1 (+https://github.com/sirfrostingham/SatelliteTelemetrySignals)")
                .header("Accept-Encoding", "gzip")
                .GET().build();
        try {
            long t0 = System.nanoTime();
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) {
                throw new FetchException("GET " + url + " -> HTTP " + resp.statusCode());
            }
            byte[] body = resp.body();
            log.info("fetched {} bytes from {} in {} ms", body.length, url, (System.nanoTime() - t0) / 1_000_000);
            boolean gz = resp.headers().firstValue("Content-Encoding").map(v -> v.contains("gzip")).orElse(false);
            InputStream in = new ByteArrayInputStream(body);
            return gz ? new GZIPInputStream(in) : in;
        } catch (FetchException e) {
            throw e;   // an HTTP status error: already described, and not a transport failure to retry
        } catch (IOException e) {
            // e.getMessage() is null for several transport failures (an unknown host carries only the
            // host name, a reset carries nothing) — name the exception type so the log says what broke.
            String why = e.getMessage() == null || e.getMessage().isBlank()
                    ? e.getClass().getSimpleName() : e.getClass().getSimpleName() + ": " + e.getMessage();
            throw new FetchException("GET " + url + " failed: " + why, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FetchException("interrupted", e);
        }
    }
}
