package com.ascensionlib;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CompatReportTest {
    private static java.util.List<String> report(Map<String, String> installed) {
        return CompatReport.lines(id -> Optional.ofNullable(installed.get(id)));
    }

    @Test void namesWhatIsInstalledAndWhatIsLostWithoutTheRest() {
        var lines = report(Map.of("cobblemon", "1.8.2+1.21.1", "snobblemon_server", "2.22.0"));
        assertEquals(1, lines.size(), "no warning for a verified Cobblemon");
        assertTrue(lines.get(0).contains("Cobblemon 1.8.2+1.21.1") && lines.get(0).contains("Snobblemon GUI 2.22.0"));
        assertTrue(lines.get(0).contains("CobbleRaids (modifiers do not act in battle)"));
        assertTrue(lines.get(0).contains("CobbleTowers (no Tower rewards)"));
    }

    @Test void aCobblemonOutsideTheVerifiedVersionsIsCalledOut() {
        var lines = report(Map.of("cobblemon", "1.8.9+1.21.1"));
        assertEquals(2, lines.size());
        assertTrue(lines.get(1).startsWith("WARNING") && lines.get(1).contains("1.8.9") && lines.get(1).contains("1.8.1, 1.8.2"));
    }

    @Test void everythingPresentMeansNothingIsReportedMissing() {
        var lines = report(Map.of("cobblemon", "1.8.1+1.21.1", "snobblemon_server", "2.22.0", "cobbleraids", "0.8.166", "cobbletowers", "0.29.0", "ascensiondungeons", "1.0", "polymer-core", "0.9.19"));
        assertFalse(lines.get(0).contains("Not installed"));
    }
}
