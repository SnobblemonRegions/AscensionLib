package com.ascensionlib.auction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuctionGuardTest {
    private record Mon(UUID uuid) {}

    private static final UUID LISTED = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void aCopyCountsOnlyWhenItSitsInASlot() {
        var copy = new Mon(LISTED);
        // A party is slots, some empty: the listed Pokemon is found where it sits.
        assertEquals(copy, AuctionGuard.inSlot(Arrays.asList(null, new Mon(UUID.randomUUID()), copy), Mon::uuid, LISTED));
        // Only Cobblemon's UUID index still names it (no slot holds it): nothing to remove, nothing to report.
        assertNull(AuctionGuard.inSlot(List.of(new Mon(UUID.randomUUID())), Mon::uuid, LISTED));
        assertNull(AuctionGuard.inSlot(List.of(), Mon::uuid, LISTED));
        assertNull(AuctionGuard.inSlot(null, Mon::uuid, LISTED), "a seller with no PC loaded has no copy");
    }

    @Test
    void aCopyThatCannotBeRemovedIsReportedOncePerRun() {
        var reported = new HashSet<String>();
        var listing = new AuctionListings.Held("402e527d8212", UUID.randomUUID(), LISTED, "ACTIVE");
        assertTrue(AuctionGuard.firstFailure(reported, listing), "the first failure is backed up and reported");
        for (int check = 0; check < 88; check++)
            assertFalse(AuctionGuard.firstFailure(reported, listing), "every later check of the same listing stays quiet");
        var other = new AuctionListings.Held("31beba126b71", listing.seller(), UUID.randomUUID(), "ACTIVE");
        assertTrue(AuctionGuard.firstFailure(reported, other), "another listing is still reported");
    }
}
