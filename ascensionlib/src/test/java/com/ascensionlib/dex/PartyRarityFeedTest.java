package com.ascensionlib.dex;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PartyRarityFeedTest {

    @Test
    void sendsEachProfiledPokemonsRarityIdInPartyOrder() {
        UUID a = UUID.fromString("00000000-0000-0000-0000-00000000000a");
        UUID b = UUID.fromString("00000000-0000-0000-0000-00000000000b");
        Map<UUID, String> party = new LinkedHashMap<>();
        party.put(b, "mythical");
        party.put(a, "common");
        assertEquals("{\"00000000-0000-0000-0000-00000000000b\":\"mythical\",\"00000000-0000-0000-0000-00000000000a\":\"common\"}", PartyRarityFeed.json(party));
    }

    @Test
    void anEmptyPartyIsAnEmptyObject() {
        assertEquals("{}", PartyRarityFeed.json(new LinkedHashMap<>()));
    }
}
