package com.orbitwatch.ingest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cache of the last raw payload per source. Purpose: (1) be a good citizen — never re-fetch inside
 * the provider's minimum interval, even across restarts; (2) keep the pipeline alive when the
 * provider is unreachable (serve stale with a flag rather than fail closed).
 *
 * <p><b>The cache can never fail an ingest run.</b> It is an optimisation over data that has already
 * been fetched successfully. If the directory cannot be created or written (a root-owned volume, a
 * full disk, a read-only mount), the payload is kept in memory instead: the run still publishes,
 * the provider interval is still enforced for the life of the process, and the failure is surfaced
 * as a WARN, a metric ({@code orbitwatch.ingest.cache.write_failures}) and a health detail. Only
 * persistence across restarts is lost. (Found 2026-09-10: a root-owned Docker volume turned a
 * successful CelesTrak fetch into a failed run and an empty catalogue, with the container healthy.)
 */
public final class SourceCache {
    private static final Logger log = LoggerFactory.getLogger(SourceCache.class);

    private final Path dir;
    private final Map<String, Entry> memory = new ConcurrentHashMap<>();
    private final AtomicLong writeFailures = new AtomicLong();
    private volatile boolean diskUsable;
    private volatile String lastWriteError;

    public SourceCache(Path dir) {
        this.dir = dir;
        boolean usable;
        try {
            Files.createDirectories(dir);
            usable = true;
        } catch (IOException e) {
            usable = false;
            lastWriteError = e.toString();
            writeFailures.incrementAndGet();
            log.warn("cache directory {} unusable ({}); caching in memory only — provider interval still enforced, "
                    + "but not across restarts", dir, e.toString());
        }
        this.diskUsable = usable;
    }

    /** A cached payload: on disk ({@code bytes == null}) or in memory. */
    public record Entry(Path path, byte[] bytes, Instant fetchedAt) {
        public boolean isFresh(Duration maxAge) { return fetchedAt.plus(maxAge).isAfter(Instant.now()); }
        public boolean persisted() { return bytes == null; }
        public InputStream open() throws IOException {
            return bytes != null ? new ByteArrayInputStream(bytes) : Files.newInputStream(path);
        }
    }

    public Optional<Entry> get(String key) {
        Entry mem = memory.get(key);
        Entry disk = null;
        Path p = dir.resolve(safe(key));
        try {
            if (Files.isRegularFile(p)) disk = new Entry(p, null, Files.getLastModifiedTime(p).toInstant());
        } catch (IOException ignored) {
            // unreadable disk entry: fall through to memory
        }
        if (mem == null) return Optional.ofNullable(disk);
        if (disk == null) return Optional.of(mem);
        return Optional.of(mem.fetchedAt().isAfter(disk.fetchedAt()) ? mem : disk);
    }

    /**
     * Store a freshly fetched payload. Never throws for a storage problem: the payload is read fully
     * first, so a failed write degrades to an in-memory entry. Only a failure to read {@code data}
     * itself (the network stream) propagates.
     */
    public Entry put(String key, InputStream data) throws IOException {
        byte[] bytes = data.readAllBytes();
        Instant now = Instant.now();
        if (diskUsable) {
            Path p = dir.resolve(safe(key));
            Path tmp = dir.resolve(safe(key) + ".tmp");
            try {
                Files.write(tmp, bytes);
                try {
                    Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
                }
                memory.remove(key);
                log.info("cached {} ({} bytes)", key, bytes.length);
                return new Entry(p, null, Files.getLastModifiedTime(p).toInstant());
            } catch (IOException e) {
                writeFailures.incrementAndGet();
                lastWriteError = e.toString();
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
                log.warn("could not write cache {} to {} ({}); keeping it in memory — check volume ownership/space",
                        key, dir, e.toString());
            }
        }
        Entry mem = new Entry(null, bytes, now);
        memory.put(key, mem);
        return mem;
    }

    public long writeFailures() { return writeFailures.get(); }

    /** True when the last write (or directory creation) succeeded, or nothing has been written yet. */
    public boolean persistent() { return diskUsable && memory.isEmpty(); }

    public String lastWriteError() { return lastWriteError; }

    public Path dir() { return dir; }

    private static String safe(String key) {
        return key.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
