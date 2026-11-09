package com.ascensionlib.event;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class HalloweenEventTest {
    @Test void theWindowOpensAtMidnightEasternOnHalloweenAndClosesAtTheEndOfTheEleventhOfNovember() {
        assertFalse(HalloweenEvent.isOpen(Instant.parse("2026-10-31T03:59:59Z")));
        assertTrue(HalloweenEvent.isOpen(Instant.parse("2026-10-31T04:00:00Z")));
        assertTrue(HalloweenEvent.isOpen(Instant.parse("2026-11-12T04:59:59Z")));
        assertFalse(HalloweenEvent.isOpen(Instant.parse("2026-11-12T05:00:00Z")));
    }

    @Test void theHauntingRowShowsDuringTheEvent() {
        assertTrue(HalloweenEvent.showHaunting(0, false, true));
    }

    @Test void afterTheEventTheRowStaysForAHauntingOrLeftoverEssenceOnly() {
        assertTrue(HalloweenEvent.showHaunting(0, true, false));
        assertTrue(HalloweenEvent.showHaunting(2, false, false));
        assertFalse(HalloweenEvent.showHaunting(0, false, false));
    }
}
