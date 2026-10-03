package com.orbitwatch.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.orbitwatch.contracts.CatalogObject;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class SatcatCsvParserTest {

    @Test
    void parsesSampleFile() throws Exception {
        List<CatalogObject> rows;
        try (var in = getClass().getResourceAsStream("/satcat-sample.csv")) {
            rows = SatcatCsvParser.parse(in, Instant.EPOCH);
        }
        assertThat(rows).hasSize(399);
        CatalogObject sputnik = rows.get(1);
        assertThat(sputnik.noradId()).isEqualTo(2);
        assertThat(sputnik.objectName()).isEqualTo("SPUTNIK 1");
        assertThat(sputnik.objectType()).isEqualTo("PAY");
        assertThat(sputnik.owner()).isEqualTo("CIS");
        assertThat(sputnik.launchDate()).isEqualTo("1957-10-04");
        assertThat(sputnik.decayDate()).isEqualTo("1958-01-03");
        assertThat(sputnik.rcs()).isNull();
        assertThat(rows.get(0).rcs()).isEqualTo(20.42);
    }

    @Test
    void handlesQuotedCommasAndColumnReordering() throws Exception {
        String csv = "OWNER,NORAD_CAT_ID,OBJECT_NAME,OBJECT_TYPE\nUS,25544,\"ISS, (ZARYA)\",PAY\n";
        List<CatalogObject> rows = SatcatCsvParser.parse(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), Instant.EPOCH);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).objectName()).isEqualTo("ISS, (ZARYA)");
        assertThat(rows.get(0).owner()).isEqualTo("US");
        assertThat(rows.get(0).launchSite()).isNull();
    }
}
