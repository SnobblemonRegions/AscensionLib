package com.cobbleascend.sim;

import com.cobbleascend.domain.v1.MaterialId;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** What a finished {@link Simulation} adds up to: the pacing, the ledger, the economy and its stability, as JSON and Markdown. */
public final class SimReport {

    /** Materials worth tracking: the four promotions spend. */
    public static final MaterialId[] TRACKED = {MaterialId.RESONANCE_DUST, MaterialId.FACET, MaterialId.ASCENSION_CORE, MaterialId.APEX_SHARD};

    public record Pacing(Archetype archetype, int players, double legendaryReached, double medianFirstLegendary,
                         double mythicReached, double medianFirstMythic) {}

    public record Flow(MaterialId material, long minted, long spent, double lateSinkRatio, long medianHeld) {}

    public final String ledgerProblem;
    public final List<Pacing> pacing = new ArrayList<>();
    public final List<Flow> flows = new ArrayList<>();
    /** Win rate by Threat gap -1..+2 (index 0..3 of the runs at gap -1, 0, +1, +2), Tower runs. */
    public final double[] towerWinByGap = new double[4];
    public final double bothActivitiesLastWeek;
    public final double coresPerApex;

    public SimReport(Simulation sim) {
        ledgerProblem = sim.ledger().check(sim.players());
        for (Archetype a : Archetype.values()) {
            List<SimPlayer> group = sim.players().stream().filter(p -> p.archetype == a).toList();
            if (group.isEmpty()) continue;
            pacing.add(new Pacing(a, group.size(),
                    share(group, p -> p.firstLegendaryDay >= 0), median(group, true),
                    share(group, p -> p.firstMythicDay >= 0), median(group, false)));
        }
        int late = Math.max(0, sim.days() - 30);
        for (MaterialId id : TRACKED) {
            long mintedLate = 0, spentLate = 0;
            for (int d = late; d < sim.days(); d++) {
                mintedLate += sim.ledger().mintedOn(d, id);
                spentLate += sim.ledger().spentOn(d, id);
            }
            List<Long> held = new ArrayList<>();
            for (SimPlayer p : sim.players()) held.add(p.wallet.getOrDefault(id, 0L));
            Collections.sort(held);
            flows.add(new Flow(id, sim.ledger().minted(id), sim.ledger().spent(id),
                    mintedLate == 0 ? 1.0 : (double) spentLate / mintedLate, held.isEmpty() ? 0 : held.get(held.size() / 2)));
        }
        long[] runs = new long[5], wins = new long[5];
        for (SimPlayer p : sim.players()) for (int g = 0; g < 5; g++) { runs[g] += p.runsByGap[g]; wins[g] += p.winsByGap[g]; }
        for (int g = 0; g < 4; g++) towerWinByGap[g] = runs[g + 1] == 0 ? Double.NaN : (double) wins[g + 1] / runs[g + 1];
        bothActivitiesLastWeek = share(sim.players(), p -> p.towerLastWeek && p.raidLastWeek)
                / Math.max(1e-9, share(sim.players(), p -> p.towerLastWeek || p.raidLastWeek));
        long apex = sim.ledger().minted(MaterialId.APEX_SHARD);
        coresPerApex = apex == 0 ? Double.NaN : (double) sim.ledger().minted(MaterialId.ASCENSION_CORE) / apex;
    }

    public Pacing pacing(Archetype a) {
        return pacing.stream().filter(p -> p.archetype() == a).findFirst().orElseThrow();
    }

    public Flow flow(MaterialId id) {
        return flows.stream().filter(f -> f.material() == id).findFirst().orElseThrow();
    }

    private static double share(List<SimPlayer> group, java.util.function.Predicate<SimPlayer> test) {
        return group.isEmpty() ? 0 : (double) group.stream().filter(test).count() / group.size();
    }

    /** Median first day (1-based) among those who got there; NaN if nobody did. */
    private static double median(List<SimPlayer> group, boolean legendary) {
        List<Integer> daysTo = new ArrayList<>();
        for (SimPlayer p : group) {
            int d = legendary ? p.firstLegendaryDay : p.firstMythicDay;
            if (d >= 0) daysTo.add(d + 1);
        }
        if (daysTo.isEmpty()) return Double.NaN;
        Collections.sort(daysTo);
        int n = daysTo.size();
        return n % 2 == 1 ? daysTo.get(n / 2) : (daysTo.get(n / 2 - 1) + daysTo.get(n / 2)) / 2.0;
    }

    public String json() {
        JsonObject root = new JsonObject();
        root.addProperty("ledger", ledgerProblem == null ? "balanced" : ledgerProblem);
        JsonArray p = new JsonArray();
        for (Pacing x : pacing) {
            JsonObject o = new JsonObject();
            o.addProperty("archetype", x.archetype().name());
            o.addProperty("players", x.players());
            o.addProperty("legendaryReached", x.legendaryReached());
            o.addProperty("medianFirstLegendaryDay", x.medianFirstLegendary());
            o.addProperty("mythicReached", x.mythicReached());
            o.addProperty("medianFirstMythicDay", x.medianFirstMythic());
            p.add(o);
        }
        root.add("pacing", p);
        JsonArray f = new JsonArray();
        for (Flow x : flows) {
            JsonObject o = new JsonObject();
            o.addProperty("material", x.material().id());
            o.addProperty("minted", x.minted());
            o.addProperty("spent", x.spent());
            o.addProperty("last30SinkRatio", x.lateSinkRatio());
            o.addProperty("medianHeldAtEnd", x.medianHeld());
            f.add(o);
        }
        root.add("economy", f);
        JsonArray w = new JsonArray();
        for (double v : towerWinByGap) w.add(v);
        root.add("towerWinByGapMinus1To2", w);
        root.addProperty("bothActivitiesLastWeek", bothActivitiesLastWeek);
        root.addProperty("coresPerApex", coresPerApex);
        return new GsonBuilder().setPrettyPrinting().serializeSpecialFloatingPointValues().create().toJson(root);
    }

    public String markdown(Simulation sim) {
        StringBuilder md = new StringBuilder();
        md.append("# Balance simulation\n\n");
        md.append(String.format(Locale.ROOT, "Seed %d, %d players, %d days. Ledger: **%s**.%n%n", sim.seed(), sim.players().size(), sim.days(),
                ledgerProblem == null ? "balanced" : ledgerProblem));
        md.append("## Pacing (target: Regular first Legendary 8-12 days, first Mythic 14-21)\n\n");
        md.append("| Archetype | Players | Reached Legendary | Median day | Reached Mythic | Median day |\n|---|---|---|---|---|---|\n");
        for (Pacing x : pacing) md.append(String.format(Locale.ROOT, "| %s | %d | %.0f%% | %s | %.0f%% | %s |%n", x.archetype(), x.players(),
                100 * x.legendaryReached(), num(x.medianFirstLegendary()), 100 * x.mythicReached(), num(x.medianFirstMythic())));
        md.append("\n## Economy and stability (last-30-day sink ratio: spent / minted)\n\n");
        md.append("| Material | Minted | Spent | Last 30 days sink | Median held at end |\n|---|---|---|---|---|\n");
        for (Flow x : flows) md.append(String.format(Locale.ROOT, "| %s | %d | %d | %.2f | %d |%n", x.material().id(), x.minted(), x.spent(),
                x.lateSinkRatio(), x.medianHeld()));
        md.append(String.format(Locale.ROOT, "%nTower win rate by Threat gap -1 / 0 / +1 / +2: %s / %s / %s / %s (model: 95 / 85 / 50 / 15%%).%n",
                pct(towerWinByGap[0]), pct(towerWinByGap[1]), pct(towerWinByGap[2]), pct(towerWinByGap[3])));
        md.append(String.format(Locale.ROOT, "Active players doing both Towers and raids in the last week: %s (target over 50%%).%n", pct(bothActivitiesLastWeek)));
        md.append(String.format(Locale.ROOT, "Cores minted per Apex Shard minted: %.2f.%n", coresPerApex));
        return md.toString();
    }

    private static String num(double v) { return Double.isNaN(v) ? "never" : String.format(Locale.ROOT, "%.1f", v); }

    private static String pct(double v) { return Double.isNaN(v) ? "n/a" : String.format(Locale.ROOT, "%.0f%%", 100 * v); }
}
