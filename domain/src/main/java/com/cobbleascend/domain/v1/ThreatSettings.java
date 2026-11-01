package com.cobbleascend.domain.v1;

import com.google.gson.JsonObject;

/**
 * The Threat system's switches and daily limits (balance phase 1, docs/BALANCE-THREAT.md), under {@code "threat"} in
 * {@code config/ascensionlib.json}. Off by default: with {@code enabled} false every encounter fights and pays exactly as
 * before, so the whole system can ship dark and be switched on by an owner.
 *
 * @param enabled               legacy enemy tiers fight as their Threat bands, and Threat-aware rewards and the rarity aura apply
 * @param fullCoreRollsPerDay   tower completions a player rolls Cores at full odds per UTC day; later ones roll at {@code reducedPercent}
 * @param fullApexPayoutsPerDay raid wins a player is paid Apex Shards in full per UTC day; later ones pay {@code reducedPercent}
 * @param reducedPercent        what a capped Core chance or Apex payout keeps, 0-100
 */
public record ThreatSettings(boolean enabled, int fullCoreRollsPerDay, int fullApexPayoutsPerDay, int reducedPercent) {
    public static final ThreatSettings DEFAULTS = new ThreatSettings(false, 3, 4, 25);

    public ThreatSettings {
        if (fullCoreRollsPerDay < 0 || fullCoreRollsPerDay > 1000) throw new IllegalArgumentException("fullCoreRollsPerDay is 0-1000");
        if (fullApexPayoutsPerDay < 0 || fullApexPayoutsPerDay > 1000) throw new IllegalArgumentException("fullApexPayoutsPerDay is 0-1000");
        if (reducedPercent < 0 || reducedPercent > 100) throw new IllegalArgumentException("reducedPercent is 0-100");
    }

    public JsonObject toJson() {
        var o = new JsonObject();
        o.addProperty("enabled", enabled);
        o.addProperty("fullCoreRollsPerDay", fullCoreRollsPerDay);
        o.addProperty("fullApexPayoutsPerDay", fullApexPayoutsPerDay);
        o.addProperty("reducedPercent", reducedPercent);
        return o;
    }

    /** Reads the section over {@code base}; throws on a value of the wrong kind or out of range, so the caller can fall back. */
    public static ThreatSettings fromJson(JsonObject o, ThreatSettings base) {
        boolean on = base.enabled;
        var e = o.get("enabled");
        if (e != null && !e.isJsonNull()) {
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException("enabled must be true or false");
            on = e.getAsBoolean();
        }
        for (var key : o.keySet())
            if (!java.util.Set.of("enabled", "fullCoreRollsPerDay", "fullApexPayoutsPerDay", "reducedPercent").contains(key) && !key.startsWith("_"))
                throw new IllegalArgumentException("unknown setting \"" + key + "\"");
        return new ThreatSettings(on, Tunables.intOf(o, "fullCoreRollsPerDay", base.fullCoreRollsPerDay),
                Tunables.intOf(o, "fullApexPayoutsPerDay", base.fullApexPayoutsPerDay), Tunables.intOf(o, "reducedPercent", base.reducedPercent));
    }
}
