package com.orbitwatch.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.orbitwatch.contracts.GpElementSet;
import com.orbitwatch.contracts.Json;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/** Parses CelesTrak GP JSON (CCSDS OMM field names) into {@link GpElementSet} records. */
public final class GpJsonParser {
    private GpJsonParser() {}

    public static List<GpElementSet> parse(InputStream in, String source, Instant ingestedAt) throws IOException {
        JsonNode root = Json.MAPPER.readTree(in);
        if (!root.isArray()) throw new IOException("expected a JSON array of GP records");
        List<GpElementSet> out = new ArrayList<>(root.size());
        for (JsonNode n : root) {
            GpElementSet e = toElementSet(n, source, ingestedAt);
            if (e != null) out.add(e);
        }
        return out;
    }

    static GpElementSet toElementSet(JsonNode n, String source, Instant ingestedAt) {
        if (!n.hasNonNull("NORAD_CAT_ID") || !n.hasNonNull("EPOCH") || !n.hasNonNull("MEAN_MOTION")) return null;
        // CelesTrak epochs are UTC without a zone designator, microsecond precision
        Instant epoch = LocalDateTime.parse(n.get("EPOCH").asText()).toInstant(ZoneOffset.UTC);
        GpElementSet base = new GpElementSet(
                n.get("NORAD_CAT_ID").asInt(),
                text(n, "OBJECT_NAME"),
                text(n, "OBJECT_ID"),
                epoch,
                n.get("MEAN_MOTION").asDouble(),
                n.path("ECCENTRICITY").asDouble(),
                n.path("INCLINATION").asDouble(),
                n.path("RA_OF_ASC_NODE").asDouble(),
                n.path("ARG_OF_PERICENTER").asDouble(),
                n.path("MEAN_ANOMALY").asDouble(),
                n.path("BSTAR").asDouble(),
                n.path("MEAN_MOTION_DOT").asDouble(),
                n.path("MEAN_MOTION_DDOT").asDouble(),
                n.path("ELEMENT_SET_NO").asInt(),
                n.path("REV_AT_EPOCH").asInt(),
                text(n, "CLASSIFICATION_TYPE"),
                n.path("EPHEMERIS_TYPE").asInt(),
                source, ingestedAt, null);
        return withFingerprint(base);
    }

    private static String text(JsonNode n, String field) {
        return n.hasNonNull(field) ? n.get(field).asText() : null;
    }

    /**
     * Fingerprint of the orbit-defining fields only (not names, not ingest time) so that a re-fetch
     * of an unchanged element set is recognised as unchanged.
     */
    public static GpElementSet withFingerprint(GpElementSet e) {
        String material = String.format(Locale.ROOT, "%d|%s|%.8f|%.8f|%.4f|%.4f|%.4f|%.4f|%.6e|%.6e|%d",
                e.noradId(), e.epoch(), e.meanMotion(), e.eccentricity(), e.inclination(), e.raOfAscNode(),
                e.argOfPericenter(), e.meanAnomaly(), e.bstar(), e.meanMotionDot(), e.elementSetNo());
        String fp = sha256Hex(material).substring(0, 16);
        return new GpElementSet(e.noradId(), e.objectName(), e.objectId(), e.epoch(), e.meanMotion(), e.eccentricity(),
                e.inclination(), e.raOfAscNode(), e.argOfPericenter(), e.meanAnomaly(), e.bstar(), e.meanMotionDot(),
                e.meanMotionDdot(), e.elementSetNo(), e.revAtEpoch(), e.classificationType(), e.ephemerisType(),
                e.source(), e.ingestedAt(), fp);
    }

    static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
