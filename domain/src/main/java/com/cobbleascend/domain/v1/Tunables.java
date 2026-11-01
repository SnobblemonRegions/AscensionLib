package com.cobbleascend.domain.v1;

import com.google.gson.JsonObject;
import java.util.List;

/**
 * The numbers a server owner may tune without rebuilding the mod, read from {@code config/ascensionlib.json} at start and by {@code /ascend admin reload}. Everything
 * here is an economy dial (what a chest pays, how often a rare item drops, what a fee costs), never a rule: the rules and the catalog stay in code and in the design
 * data. A missing or invalid value falls back to the default with a warning, so a typo in the file can never break the economy. {@link #current()} is what the rest of
 * the mod reads; the defaults are the owner-approved numbers.
 */
public record Tunables(DailyRewardBudget budget, int withdrawalFeePercent, int respecMarksPrice, int attunementPerVictory,
                       TowerCompletionBands towerCompletion, MiniChest miniChest, ZoneSettings zones, ThreatSettings threat) {
    public static final int DEFAULT_FEE_PERCENT = 3;
    public static final int DEFAULT_RESPEC_MARKS_PRICE = 90;
    public static final int DEFAULT_ATTUNEMENT_PER_VICTORY = 3;

    /** The floor-5 boss chest (docs/TOWER-COMPLETION-REWARDS.md): a flat pile and a small Core chance in permille. */
    public record MiniChest(int dust, int facets, int fragments, int scouters, int marks, int corePermille) {
        public static final MiniChest DEFAULTS = new MiniChest(200, 1, 1, 1, 1, 50);

        public MiniChest {
            if (dust < 0 || facets < 0 || fragments < 0 || scouters < 0 || marks < 0) throw new IllegalArgumentException("Amounts must be nonnegative");
            if (corePermille < 0 || corePermille > 1000) throw new IllegalArgumentException("A Core chance is 0-1000 permille");
        }
    }

    public Tunables {
        if (withdrawalFeePercent < 0 || withdrawalFeePercent > 50) throw new IllegalArgumentException("The withdrawal fee is 0-50 percent");
        if (respecMarksPrice < 1) throw new IllegalArgumentException("The Hourglass price is at least 1 Mark");
        if (attunementPerVictory < 0 || attunementPerVictory > 50) throw new IllegalArgumentException("Attunement per victory is 0-50");
    }

    /** Every setting before the Threat system, with Threat at its defaults (off). */
    public Tunables(DailyRewardBudget budget, int withdrawalFeePercent, int respecMarksPrice, int attunementPerVictory,
                    TowerCompletionBands towerCompletion, MiniChest miniChest, ZoneSettings zones) {
        this(budget, withdrawalFeePercent, respecMarksPrice, attunementPerVictory, towerCompletion, miniChest, zones, ThreatSettings.DEFAULTS);
    }

    public static Tunables defaults() {
        return new Tunables(DailyRewardBudget.DEFAULTS, DEFAULT_FEE_PERCENT, DEFAULT_RESPEC_MARKS_PRICE, DEFAULT_ATTUNEMENT_PER_VICTORY,
                TowerCompletionBands.DEFAULTS, MiniChest.DEFAULTS, ZoneSettings.DEFAULTS, ThreatSettings.DEFAULTS);
    }

    private static volatile Tunables current = defaults();

    /** What the mod uses right now. */
    public static Tunables current() { return current; }

    /** Replaces what the mod uses (at start, on reload, and by a test, which must put {@link #defaults()} back). */
    public static void use(Tunables tunables) { current = java.util.Objects.requireNonNull(tunables); }

    // ---- the file --------------------------------------------------------------------------------------------------------------------

    /** The whole configuration as the file shows it (a template for a new install, and what {@code /ascend admin reload} reports against). */
    public JsonObject toJson() {
        var root = new JsonObject();
        var daily = new JsonObject();
        daily.addProperty("fullChestsPerDay", budget.fullPerDay());
        daily.addProperty("reducedDustPercent", budget.reducedDustPercent());
        root.add("dailyBudget", daily);
        root.addProperty("withdrawalFeePercent", withdrawalFeePercent);
        root.addProperty("hourglassMarksPrice", respecMarksPrice);
        root.addProperty("attunementPerVictory", attunementPerVictory);
        root.add("towerCompletion", towerCompletion.toJson());
        var mini = new JsonObject();
        mini.addProperty("dust", miniChest.dust());
        mini.addProperty("facets", miniChest.facets());
        mini.addProperty("fragments", miniChest.fragments());
        mini.addProperty("scouters", miniChest.scouters());
        mini.addProperty("marks", miniChest.marks());
        mini.addProperty("corePermille", miniChest.corePermille());
        root.add("towerMiniChest", mini);
        root.add("zones", zones.toJson());
        root.add("threat", threat.toJson());
        return root;
    }

    /**
     * Reads a configuration. Anything missing keeps its default; anything invalid keeps its default too and is named in {@code problems} (a whole section falls back
     * together when its numbers do not fit, such as a crown table whose weights are all zero). Never throws.
     */
    public static Tunables parse(JsonObject root, List<String> problems) {
        var base = defaults();
        DailyRewardBudget budget = base.budget;
        var daily = obj(root, "dailyBudget");
        if (daily != null) {
            try {
                budget = new DailyRewardBudget(intOf(daily, "fullChestsPerDay", base.budget.fullPerDay()), intOf(daily, "reducedDustPercent", base.budget.reducedDustPercent()));
            } catch (RuntimeException bad) {
                problems.add("dailyBudget: " + bad.getMessage() + " (using the default)");
            }
        }
        int fee = pick(root, "withdrawalFeePercent", base.withdrawalFeePercent, 0, 50, problems);
        int price = pick(root, "hourglassMarksPrice", base.respecMarksPrice, 1, 100_000, problems);
        int attunement = pick(root, "attunementPerVictory", base.attunementPerVictory, 0, 50, problems);
        TowerCompletionBands towers = base.towerCompletion;
        var tower = obj(root, "towerCompletion");
        if (tower != null) {
            try {
                towers = TowerCompletionBands.fromJson(tower, base.towerCompletion);
            } catch (RuntimeException bad) {
                problems.add("towerCompletion: " + bad.getMessage() + " (using the default)");
            }
        }
        MiniChest mini = base.miniChest;
        var m = obj(root, "towerMiniChest");
        if (m != null) {
            try {
                var d = base.miniChest;
                mini = new MiniChest(intOf(m, "dust", d.dust()), intOf(m, "facets", d.facets()), intOf(m, "fragments", d.fragments()),
                        intOf(m, "scouters", d.scouters()), intOf(m, "marks", d.marks()), intOf(m, "corePermille", d.corePermille()));
            } catch (RuntimeException bad) {
                problems.add("towerMiniChest: " + bad.getMessage() + " (using the default)");
            }
        }
        ZoneSettings zoneSettings = base.zones;
        var z = obj(root, "zones");
        if (z != null) {
            try {
                zoneSettings = ZoneSettings.fromJson(z, base.zones);
            } catch (RuntimeException bad) {
                problems.add("zones: " + bad.getMessage() + " (using the default)");
            }
        }
        ThreatSettings threatSettings = base.threat;
        var th = obj(root, "threat");
        if (th != null) {
            try {
                threatSettings = ThreatSettings.fromJson(th, base.threat);
            } catch (RuntimeException bad) {
                problems.add("threat: " + bad.getMessage() + " (using the default)");
            }
        }
        for (var key : root.keySet()) {
            if (!List.of("dailyBudget", "withdrawalFeePercent", "hourglassMarksPrice", "attunementPerVictory", "towerCompletion", "towerMiniChest", "zones", "threat").contains(key) && !key.startsWith("_"))
                problems.add("unknown setting \"" + key + "\" (ignored)");
        }
        return new Tunables(budget, fee, price, attunement, towers, mini, zoneSettings, threatSettings);
    }

    static JsonObject obj(JsonObject parent, String key) {
        var e = parent.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    /** An integer member, the default when absent; throws when present but not a whole number. */
    static int intOf(JsonObject o, String key, int fallback) {
        var e = o.get(key);
        if (e == null || e.isJsonNull()) return fallback;
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException(key + " must be a number");
        double d = e.getAsDouble();
        if (d != Math.rint(d) || Math.abs(d) > Integer.MAX_VALUE) throw new IllegalArgumentException(key + " must be a whole number");
        return (int) d;
    }

    private static int pick(JsonObject root, String key, int fallback, int min, int max, List<String> problems) {
        try {
            int value = intOf(root, key, fallback);
            if (value < min || value > max) {
                problems.add(key + " must be " + min + " to " + max + ", got " + value + " (using " + fallback + ")");
                return fallback;
            }
            return value;
        } catch (RuntimeException bad) {
            problems.add(key + ": " + bad.getMessage() + " (using " + fallback + ")");
            return fallback;
        }
    }
}
