package com.cobbleascend.domain.v1;

import static org.junit.jupiter.api.Assertions.*;

import com.cobbleascend.domain.Cost;
import com.cobbleascend.domain.Rarity;
import com.cobbleascend.domain.Rules;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Balance phase 1: with the Threat system on, the top promotions, Overreach and Fusion also cost Apex Shards; with it off nothing changes. */
class ThreatCostsTest {
    private final Rules rules = Rules.defaults();

    @AfterEach void restoreDefaults() { Tunables.use(Tunables.defaults()); }

    private static void threat(boolean on) {
        var d = Tunables.defaults();
        Tunables.use(new Tunables(d.budget(), d.withdrawalFeePercent(), d.respecMarksPrice(), d.attunementPerVictory(), d.towerCompletion(),
                d.miniChest(), d.zones(), new ThreatSettings(on, 3, 4, 25)));
    }

    @Test void threatOffKeepsEveryPromotionPrice() {
        for (var from : new Rarity[] {Rarity.COMMON, Rarity.UNCOMMON, Rarity.RARE, Rarity.EPIC, Rarity.LEGENDARY})
            assertEquals(rules.promotion(from), rules.promotion(from, false));
        assertEquals(new Cost(400, 20, 5, 0), rules.promotion(Rarity.LEGENDARY).cost(), "the shipped price is unchanged");
    }

    @Test void threatPricesNeedApexShardsForTheTopTwoSteps() {
        assertEquals(new Cost(20, 0, 0, 0), rules.promotion(Rarity.COMMON, true).cost());
        assertEquals(new Cost(150, 6, 0, 0), rules.promotion(Rarity.RARE, true).cost());
        assertEquals(new Cost(300, 12, 2, 4), rules.promotion(Rarity.EPIC, true).cost());
        assertEquals(new Cost(500, 20, 5, 10), rules.promotion(Rarity.LEGENDARY, true).cost());
        assertEquals(rules.promotion(Rarity.EPIC).attunement(), rules.promotion(Rarity.EPIC, true).attunement(), "attunement needs are the same");
        assertEquals(10L, MaterialId.of(rules.promotion(Rarity.LEGENDARY, true).cost()).get(MaterialId.APEX_SHARD));
        assertNull(MaterialId.of(rules.promotion(Rarity.LEGENDARY, false).cost()).get(MaterialId.APEX_SHARD));
    }

    @Test void overreachAndFusionAddApexOnlyWithThreatOn() {
        threat(false);
        assertNull(RankedProgression.overreachCost().get(MaterialId.APEX_SHARD));
        assertNull(FusionRules.cost().get(MaterialId.APEX_SHARD));
        threat(true);
        assertEquals(1L, RankedProgression.overreachCost().get(MaterialId.APEX_SHARD));
        assertEquals(12L, FusionRules.cost().get(MaterialId.APEX_SHARD));
        assertEquals(60L, RankedProgression.overreachCost().get(MaterialId.RESONANCE_DUST), "the rest of the price is unchanged");
    }

    @Test void aThreatTableMustPriceEveryStep() {
        var balance = com.google.gson.JsonParser.parseString(readBalance()).getAsJsonObject();
        balance.getAsJsonArray("threatPromotions").remove(0);
        var affixes = com.google.gson.JsonParser.parseString(readResource("affixes.json")).getAsJsonObject();
        assertThrows(IllegalArgumentException.class, () -> Rules.parse(balance, affixes));
    }

    private static String readBalance() { return readResource("balance.json"); }

    private static String readResource(String name) {
        try (var in = Rules.class.getResourceAsStream("/cobbleascend/" + name)) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
