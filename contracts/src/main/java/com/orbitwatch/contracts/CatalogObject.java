package com.orbitwatch.contracts;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

/**
 * Static-ish catalogue metadata for an object (from CelesTrak SATCAT), published to
 * {@link Topics#CATALOG_OBJECTS}. This is the "who owns it / what is it" join table that turns a
 * bare NORAD id into something an operator recognises.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CatalogObject(
        int noradId,
        String objectName,
        String objectId,
        String objectType,      // PAY, R/B, DEB, UNK
        String opsStatusCode,   // +, -, P, B, S, X, D, ? (CelesTrak codes)
        String owner,           // e.g. US, CIS, PRC, ESA, SES...
        String launchDate,      // ISO date (string: SATCAT can be partial)
        String launchSite,
        String decayDate,
        Double rcs,             // radar cross section m², null if unknown
        Instant ingestedAt
) {
    public static final int SCHEMA_VERSION = 1;
}
