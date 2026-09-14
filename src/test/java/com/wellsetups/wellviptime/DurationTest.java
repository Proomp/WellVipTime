package com.wellsetups.wellviptime;

import static org.junit.jupiter.api.Assertions.*;

import com.wellsetups.wellviptime.vip.*;

import org.junit.jupiter.api.Test;

class DurationTest {

    @Test
    void parsesCompoundUnits() {
        assertEquals(
                (7 * 86400L + 12 * 3600 + 30 * 60) * 1000,
                DurationParser.parseMillis("7d12h30m", 365));
    }

    @Test
    void rejectsAmbiguousMalformedAndOverflowingDurations() {
        for (String input :
                new String[] {
                    "",
                    "0s",
                    "1M",
                    "-1d",
                    "1h2d",
                    "1d1d",
                    "1.5h",
                    "1d ",
                    "1w",
                    "999999999999999999999999d",
                    "366d"
                }) {
            assertThrows(DomainFailure.class, () -> DurationParser.parseMillis(input, 365), input);
        }
    }
}
