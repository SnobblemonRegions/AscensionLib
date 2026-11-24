package com.cobbleascend.store;

import com.cobbleascend.domain.v1.MaterialId;
import java.util.Map;
import java.util.UUID;

/**
 * A fusion made at a Project Fusion Fuser: the donor is not consumed but bonded inside the host as its head, for good. It sits beside
 * the {@link Fusion} row with the same host and donor, and records who paid what, so an interrupted Fuser can be refunded exactly.
 */
public record Bond(UUID hostId, UUID donorId, UUID playerId, UUID fuseOperationId, Map<MaterialId, Long> price, long bondedAt) {
    public Bond {
        price = Map.copyOf(price);
    }
}
