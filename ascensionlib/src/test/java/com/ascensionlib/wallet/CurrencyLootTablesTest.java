package com.ascensionlib.wallet;

import static org.junit.jupiter.api.Assertions.*;

import com.cobbleascend.domain.v1.CurrencyItems;
import com.cobbleascend.domain.v1.MaterialId;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** The loot tables are generated from design/currency-items.json by tools/build_resourcepack.py; this keeps them from drifting from what the Java builds. */
class CurrencyLootTablesTest {
    @Test void everyMaterialHasALootTableMatchingItsItemSpec() throws Exception {
        for (var material : MaterialId.values()) {
            var spec = CurrencyItems.spec(material);
            var stream = getClass().getResourceAsStream("/data/ascensionlib/loot_table/currency/" + material.id() + ".json");
            assertNotNull(stream, "Missing loot table for " + material.id() + "; run tools/build_resourcepack.py");
            var table = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            var entry = table.getAsJsonArray("pools").get(0).getAsJsonObject().getAsJsonArray("entries").get(0).getAsJsonObject();
            assertEquals(spec.baseItem(), entry.get("name").getAsString());
            var components = entry.getAsJsonArray("functions").get(0).getAsJsonObject().getAsJsonObject("components");
            assertEquals(material.id(), components.getAsJsonObject("minecraft:custom_data").getAsJsonObject(CurrencyItems.TAG).get("material").getAsString());
            assertEquals(spec.model(), components.get("minecraft:custom_model_data").getAsInt());
            assertTrue(components.get("minecraft:custom_name").getAsString().contains(spec.name()));
            assertEquals(spec.lore().size() + 1, components.getAsJsonArray("minecraft:lore").size(), "the rarity line plus the lore");
            assertFalse(components.getAsJsonObject("minecraft:custom_data").getAsJsonObject(CurrencyItems.TAG).has(CurrencyItems.BATCH_KEY), "A loot table makes loot form, never a voucher");
        }
    }

    @Test void everyMaterialHasAnIcon() {
        for (var material : MaterialId.values())
            assertTrue(java.nio.file.Files.exists(java.nio.file.Path.of("..", "art", "icons", material.id() + ".png")), "Missing icon for " + material.id());
    }
}
