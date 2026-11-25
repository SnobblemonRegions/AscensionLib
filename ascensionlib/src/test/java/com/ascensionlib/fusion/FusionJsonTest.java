package com.ascensionlib.fusion;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The page reads these field names; renaming one in a record silently breaks fusion.html, so they are pinned here. */
class FusionJsonTest {
    private final Gson gson = new Gson();

    private static FusionPayloads.Entry entry() {
        return new FusionPayloads.Entry("00000000-0000-0000-0000-000000000001", "Mewtwo", "cobblemon:mewtwo", "mythical", "Overmind", true, true, "");
    }

    @Test void candidatesCarryTheHostEntryAndItsBlock() {
        var json = JsonParser.parseString(gson.toJson(new FusionPayloads.Candidates(entry(), "Only a Radiant Pokémon can be the host."))).getAsJsonObject();
        var host = json.getAsJsonObject("host");
        for (var key : List.of("id", "name", "speciesId", "rarityId", "uniqueName", "inParty", "canHost", "block")) assertTrue(host.has(key), key);
        assertEquals("Only a Radiant Pokémon can be the host.", json.get("hostBlock").getAsString());
    }

    @Test void pickListCarriesTheRoleAndEntries() {
        var json = JsonParser.parseString(gson.toJson(new FusionPayloads.PickList(true, List.of(entry())))).getAsJsonObject();
        assertTrue(json.get("hostRole").getAsBoolean());
        assertEquals(1, json.getAsJsonArray("entries").size());
    }

    @Test void detailKeepsPriceBalancesAndRevisionsAsNumbers() {
        var detail = new FusionPayloads.Detail("h", "d", "", "Name", "A + B", "mild", 70, 30, "blurb", "core", "clutch", "drawback", "Twist", "text",
                "rider", "fire", "water", new long[] {600, 30, 10, 1}, new long[] {1, 2, 3, 4}, 5, 6, 7, true);
        var json = JsonParser.parseString(gson.toJson(detail)).getAsJsonObject();
        assertEquals(4, json.getAsJsonArray("price").size());
        assertEquals(600, json.getAsJsonArray("price").get(0).getAsLong());
        for (var key : List.of("hostId", "donorId", "block", "name", "fromUniques", "harmony", "benefit", "drawback", "blurb", "core", "clutch", "drawbackText",
                "twistName", "twistText", "riderText", "hostType", "donorType", "balances", "hostRevision", "donorRevision", "walletRevision"))
            assertTrue(json.has(key), key);
        assertEquals(7, json.get("walletRevision").getAsLong());
    }

    @Test void resultCarriesTheOperationAndOutcome() {
        var json = JsonParser.parseString(gson.toJson(new FusionPayloads.Result("op", false, "nope", ""))).getAsJsonObject();
        assertEquals("op", json.get("operationId").getAsString());
        assertFalse(json.get("ok").getAsBoolean());
    }
}
