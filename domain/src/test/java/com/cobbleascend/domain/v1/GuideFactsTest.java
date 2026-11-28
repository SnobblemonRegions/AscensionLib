package com.cobbleascend.domain.v1;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** The guide generator (tools/build_guide.py) and {@link GuideFacts} compute the same numbers; if one changes without the other this fails. */
class GuideFactsTest {
    /** The guide the generator writes ({facts, topics}); SnobbleGUIOverhaul syncs its in-game guide from it. A test input in build.gradle. */
    private static final Path DATA = Path.of("..", "design", "guide-generated.json");

    private static com.google.gson.JsonObject guide() throws Exception {
        return JsonParser.parseString(Files.readString(DATA)).getAsJsonObject();
    }

    private static java.util.List<String> toList(com.google.gson.JsonArray array) {
        var out = new java.util.ArrayList<String>();
        array.forEach(e -> out.add(e.getAsString()));
        return out;
    }

    @Test void theShippedDefaultsEqualWhatTheServerSendsForTheDefaults() throws Exception {
        var shipped = guide().getAsJsonObject("facts");
        var live = GuideFacts.facts(Tunables.defaults());
        assertEquals(shipped.keySet(), live.keySet(), "the same set of tunable numbers");
        for (var key : live.keySet()) assertEquals(shipped.get(key).getAsString(), live.get(key), key);
    }

    @Test void theShippedCompletionTableEqualsTheOneTheServerBuilds() throws Exception {
        com.google.gson.JsonObject shipped = null;
        for (var topic : guide().getAsJsonArray("topics"))
            for (var block : topic.getAsJsonObject().getAsJsonArray("blocks"))
                if (block.getAsJsonObject().has("live")) shipped = block.getAsJsonObject();
        assertNotNull(shipped, "the guide marks the completion table as live");
        var built = GuideFacts.towerTierTable(Tunables.defaults());
        assertEquals(built.get("head").toString(), toList(shipped.getAsJsonArray("head")).toString());
        var rows = (java.util.List<?>) built.get("rows");
        assertEquals(rows.size(), shipped.getAsJsonArray("rows").size());
        for (int i = 0; i < rows.size(); i++)
            assertEquals(rows.get(i).toString(), toList(shipped.getAsJsonArray("rows").get(i).getAsJsonArray()).toString(), "row " + i);
    }

    @Test void aTunedServerSeesItsOwnNumbers() {
        var tuned = Tunables.parse(JsonParser.parseString("{\"dailyBudget\": {\"fullChestsPerDay\": 7}, \"hourglassMarksPrice\": 45}").getAsJsonObject(), new java.util.ArrayList<>());
        assertEquals("7", GuideFacts.facts(tuned).get("budget.full"));
        assertEquals("45", GuideFacts.facts(tuned).get("respec.price"));
        assertEquals("20", GuideFacts.facts(Tunables.defaults()).get("budget.full"));
    }
}
