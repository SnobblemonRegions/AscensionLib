package com.ascensionlib.telemetry;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class TelemetryFieldsTest {
    private static final Function<UUID, String> KEY = id -> "key-" + id.toString().substring(0, 4);
    private static final UUID PLAYER = UUID.fromString("abcd0000-0000-0000-0000-000000000001");

    private static JsonObject fill(Map<String, ?> fields) {
        var event = new JsonObject();
        event.addProperty("t", 1L);
        event.addProperty("type", "raid_end");
        TelemetryFields.fill(event, fields, KEY);
        return event;
    }

    @Test void onlyOtherModsNamespacesAreAcceptedAsTypes() {
        assertTrue(TelemetryFields.validType("raid_end"));
        assertTrue(TelemetryFields.validType("tower_run_end"));
        assertTrue(TelemetryFields.validType("world_boss_end"));
        assertFalse(TelemetryFields.validType("ledger"));
        assertFalse(TelemetryFields.validType("settlement"));
        assertFalse(TelemetryFields.validType("raid_"));
        assertFalse(TelemetryFields.validType("Raid_End"));
        assertFalse(TelemetryFields.validType(null));
    }

    @Test void raidEndFieldsBecomeJsonAndPlayersAreKeyedNotUuids() {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("reason", "timeout");
        fields.put("partySize", 3);
        fields.put("bossHpLeft", 0.42);
        fields.put("owned", false);
        fields.put("players", List.of(PLAYER));
        fields.put("leader", PLAYER);
        var e = fill(fields);
        assertEquals("timeout", e.get("reason").getAsString());
        assertEquals(3, e.get("partySize").getAsInt());
        assertEquals(0.42, e.get("bossHpLeft").getAsDouble());
        assertFalse(e.get("owned").getAsBoolean());
        assertEquals("key-abcd", e.getAsJsonArray("players").get(0).getAsString());
        assertEquals("key-abcd", e.get("leader").getAsString());
        assertFalse(e.toString().contains(PLAYER.toString()), "a raw UUID must never reach the log");
    }

    @Test void reservedBadOrUnknownFieldsAreLeftOut() {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("t", 99);
        fields.put("type", "ledger");
        fields.put("bad key", 1);
        fields.put("nan", Double.NaN);
        fields.put("object", new Object());
        fields.put("nested", List.of(List.of(1)));
        var e = fill(fields);
        assertEquals(1L, e.get("t").getAsLong());
        assertEquals("raid_end", e.get("type").getAsString());
        assertFalse(e.has("bad key"));
        assertFalse(e.has("nan"));
        assertFalse(e.has("object"));
        assertEquals(0, e.getAsJsonArray("nested").size(), "only one level of nesting");
    }

    @Test void sizesAreCapped() {
        var fields = new LinkedHashMap<String, Object>();
        for (int i = 0; i < 50; i++) fields.put("f" + i, i);
        fields.put("long", "x".repeat(500));
        var e = fill(fields);
        assertEquals(2 + TelemetryFields.MAX_FIELDS, e.size());
        var many = fill(Map.of("list", java.util.Collections.nCopies(100, 1), "text", "y".repeat(500)));
        assertEquals(TelemetryFields.MAX_ITEMS, many.getAsJsonArray("list").size());
        assertEquals(TelemetryFields.MAX_TEXT, many.get("text").getAsString().length());
    }

    @Test void enumsAndMapsAreWritten() {
        var e = fill(Map.of("state", java.time.DayOfWeek.MONDAY, "floors", Map.of("3", 41, "4", 63)));
        assertEquals("monday", e.get("state").getAsString());
        assertEquals(63, e.getAsJsonObject("floors").get("4").getAsInt());
    }
}
