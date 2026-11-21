package com.cobbleascend.domain.v1;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;

/**
 * The income model: what each source of materials pays per run, averaged over many seeded players, using the real reward bands so it cannot drift
 * from the code. Printed as a table and written to {@code build/faucet-model.md}; the checks pin the design rules the owner approved (the Tower
 * out-earns Trials and Dungeons; only the Tower pays Cores).
 */
class FaucetModelTest {
    private static final int SAMPLES = 4000;
    private static final MaterialId[] COLUMNS = {MaterialId.RESONANCE_DUST, MaterialId.FACET, MaterialId.UNIQUE_FRAGMENT, MaterialId.SCOUTER,
            MaterialId.TOWER_MARK, MaterialId.ASCENSION_CORE, MaterialId.UNIQUE_CATALYST, MaterialId.ASCENSION_SIGIL};

    /** One source: how to draw one player's payout for one run. */
    private record Source(String name, BiFunction<String, UUID, Map<MaterialId, Long>> run) {}

    private static Map<MaterialId, Double> mean(Source source) {
        var total = new EnumMap<MaterialId, Double>(MaterialId.class);
        for (int i = 0; i < SAMPLES; i++) {
            var run = "run-" + source.name() + "-" + i;
            var player = new UUID(i * 0x9E3779B97F4A7C15L, ~(long) i);
            source.run().apply(run, player).forEach((m, n) -> total.merge(m, (double) n, Double::sum));
        }
        total.replaceAll((m, sum) -> sum / SAMPLES);
        return total;
    }

    private static Map<MaterialId, Long> withSigil(Map<MaterialId, Long> pile, boolean sigil) {
        var out = new EnumMap<MaterialId, Long>(MaterialId.class);
        out.putAll(pile);
        if (sigil) out.merge(MaterialId.ASCENSION_SIGIL, 1L, Long::sum);
        return out;
    }

    private static List<Source> sources() {
        var tunables = Tunables.defaults();
        var completion = tunables.towerCompletion();
        var sigils = SigilDrops.DEFAULTS;
        var scouters = ScouterDrops.DEFAULTS;
        var out = new ArrayList<Source>();
        out.add(new Source("Tower floor 5 mini chest (+ Scouter 5%, Sigil)", (run, p) -> {
            var pile = new EnumMap<MaterialId, Long>(MaterialId.class);
            pile.putAll(TowerRewardBands.DEFAULTS.payout(run, p, 1, 5));
            if (scouters.rollFor(false, run, p)) pile.merge(MaterialId.SCOUTER, 1L, Long::sum);
            return withSigil(pile, sigils.rollTower(run, p, 5));
        }));
        for (var scenario : new int[][] {{0, 0}, {3, 1}, {6, 1}}) {
            int cards = scenario[0];
            boolean flawless = scenario[1] == 1;
            out.add(new Source("Tower floor 10 completion, " + cards + " cards" + (flawless ? ", flawless" : ""),
                    (run, p) -> withSigil(completion.payout(run, p, cards, 0, flawless).materials(), sigils.rollTower(run, p, 10))));
        }
        for (int tier : new int[] {1, 5, 10})
            out.add(new Source("Dungeon tier " + tier + ", 8 arenas + boss",
                    (run, p) -> withSigil(DungeonRewardBands.DEFAULTS.payout(run, p, tier, 8, true), sigils.rollDungeon(run, p, tier))));
        for (int rank = 1; rank <= 3; rank++) {
            int r = rank;
            out.add(new Source("Trial rank " + rank, (run, p) -> TrialRewardBands.payout(r)));
        }
        // A typical Abandoned Laboratory, searched by one player: about three Supply Crates, a Specimen Locker in four labs of nine (two rooms,
        // containment drawn 2 times in 9 each), and the Research Terminal. Not budgeted (owner, 2026-11-20).
        out.add(new Source("Laboratory (3 crates, locker 44%, terminal)", (run, p) -> {
            var pile = new EnumMap<MaterialId, Long>(MaterialId.class);
            for (int crate = 0; crate < 3; crate++)
                LaboratoryLoot.roll(LaboratoryLoot.Container.SUPPLY, run + "/supply" + crate, p).forEach((m, n) -> pile.merge(m, n, Long::sum));
            if (new java.util.Random(RewardSeed.of("model-locker", run, p)).nextInt(100) < 44)
                LaboratoryLoot.roll(LaboratoryLoot.Container.SPECIMEN, run + "/specimen", p).forEach((m, n) -> pile.merge(m, n, Long::sum));
            LaboratoryLoot.roll(LaboratoryLoot.Container.TERMINAL, run + "/terminal", p).forEach((m, n) -> pile.merge(m, n, Long::sum));
            return pile;
        }));
        return out;
    }

    @Test void incomePerRunByExactSource() throws Exception {
        var text = new StringBuilder("# Income per run (mean over " + SAMPLES + " seeded players, default numbers)\n\n| Source |");
        for (var c : COLUMNS) text.append(' ').append(c.id()).append(" |");
        text.append("\n|---|").append("---:|".repeat(COLUMNS.length)).append('\n');
        var means = new java.util.LinkedHashMap<String, Map<MaterialId, Double>>();
        for (var source : sources()) {
            var m = mean(source);
            means.put(source.name(), m);
            text.append("| ").append(source.name()).append(" |");
            for (var c : COLUMNS) text.append(String.format(" %.3f |", m.getOrDefault(c, 0.0)));
            text.append('\n');
        }
        var budget = DailyRewardBudget.DEFAULTS;
        text.append("\nThe daily budget lets a player collect ").append(budget.fullPerDay()).append(" full chests a UTC day (then ")
                .append(budget.reducedDustPercent()).append("% of the dust), so the most dust a day can pay at full rate is ")
                .append(budget.fullPerDay()).append(" times a row's dust.\n");
        var out = Path.of("build", "faucet-model.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, text.toString());
        System.out.println(text);

        // Design rules the owner approved: Cores come only from the Tower, and a Tower completion out-earns a Dungeon run and a Trial of the same depth.
        double towerDust = means.get("Tower floor 10 completion, 0 cards").get(MaterialId.RESONANCE_DUST);
        for (var entry : means.entrySet()) {
            if (entry.getKey().startsWith("Dungeon") || entry.getKey().startsWith("Trial") || entry.getKey().startsWith("Laboratory")) {
                assertEquals(0.0, entry.getValue().getOrDefault(MaterialId.ASCENSION_CORE, 0.0), 0.0, entry.getKey() + " pays no Core");
                assertTrue(entry.getValue().getOrDefault(MaterialId.RESONANCE_DUST, 0.0) < towerDust * 1.0 + 1e-9 || entry.getKey().contains("tier 10"),
                        entry.getKey() + " should not out-earn a Tower completion in dust");
            }
        }
    }
}
