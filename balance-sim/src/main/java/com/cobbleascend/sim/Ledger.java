package com.cobbleascend.sim;

import com.cobbleascend.domain.Cost;
import com.cobbleascend.domain.v1.MaterialId;
import java.util.EnumMap;
import java.util.Map;

/**
 * Every material that enters the economy (minted by a payout) and leaves it (spent on a promotion), globally and per day. The
 * simulation's books must balance: for every material, what players hold is exactly minted minus spent, and no wallet goes
 * negative. {@link #check} says where they do not.
 */
public final class Ledger {

    private final EnumMap<MaterialId, Long> minted = new EnumMap<>(MaterialId.class);
    private final EnumMap<MaterialId, Long> spent = new EnumMap<>(MaterialId.class);
    private final long[][] mintedByDay;
    private final long[][] spentByDay;

    public Ledger(int days) {
        mintedByDay = new long[days][MaterialId.values().length];
        spentByDay = new long[days][MaterialId.values().length];
    }

    public void mint(SimPlayer player, int day, Map<MaterialId, Long> materials) {
        materials.forEach((id, amount) -> {
            if (amount <= 0) return;
            player.wallet.merge(id, amount, Long::sum);
            minted.merge(id, amount, Long::sum);
            mintedByDay[day][id.ordinal()] += amount;
        });
    }

    public boolean canAfford(SimPlayer player, Cost cost) {
        return have(player, MaterialId.RESONANCE_DUST) >= cost.dust() && have(player, MaterialId.FACET) >= cost.facets()
                && have(player, MaterialId.ASCENSION_CORE) >= cost.cores() && have(player, MaterialId.APEX_SHARD) >= cost.apex();
    }

    public void spend(SimPlayer player, int day, Cost cost) {
        if (!canAfford(player, cost)) throw new IllegalStateException("overspend by " + player.id + ": " + cost);
        take(player, day, MaterialId.RESONANCE_DUST, cost.dust());
        take(player, day, MaterialId.FACET, cost.facets());
        take(player, day, MaterialId.ASCENSION_CORE, cost.cores());
        take(player, day, MaterialId.APEX_SHARD, cost.apex());
    }

    /** A sink that is not a promotion (the Wager Altar's stake): the player must hold it. */
    public void burn(SimPlayer player, int day, MaterialId id, long amount) {
        if (have(player, id) < amount) throw new IllegalStateException("overspend by " + player.id + ": " + amount + " " + id.id());
        take(player, day, id, amount);
    }

    private void take(SimPlayer player, int day, MaterialId id, long amount) {
        if (amount <= 0) return;
        player.wallet.merge(id, -amount, Long::sum);
        spent.merge(id, amount, Long::sum);
        spentByDay[day][id.ordinal()] += amount;
    }

    private static long have(SimPlayer player, MaterialId id) { return player.wallet.getOrDefault(id, 0L); }

    public long minted(MaterialId id) { return minted.getOrDefault(id, 0L); }

    public long spent(MaterialId id) { return spent.getOrDefault(id, 0L); }

    public long mintedOn(int day, MaterialId id) { return mintedByDay[day][id.ordinal()]; }

    public long spentOn(int day, MaterialId id) { return spentByDay[day][id.ordinal()]; }

    /** Null when the books balance, else the first discrepancy. */
    public String check(Iterable<SimPlayer> players) {
        EnumMap<MaterialId, Long> held = new EnumMap<>(MaterialId.class);
        for (SimPlayer player : players) {
            for (var entry : player.wallet.entrySet()) {
                if (entry.getValue() < 0) return player.id + " holds " + entry.getValue() + " " + entry.getKey().id();
                held.merge(entry.getKey(), entry.getValue(), Long::sum);
            }
        }
        for (MaterialId id : MaterialId.values()) {
            long expected = minted(id) - spent(id);
            if (held.getOrDefault(id, 0L) != expected) return id.id() + ": held " + held.getOrDefault(id, 0L) + ", minted - spent " + expected;
        }
        return null;
    }
}
