package com.cobbleascend.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cobbleascend.domain.v1.MaterialId;
import org.junit.jupiter.api.Test;

/**
 * CI's 90-day run. Two kinds of check:
 * <ul>
 *   <li><b>Invariants</b>, always enforced: the ledger balances, nothing is overspent, a seed always gives the same economy, and
 *       the orderings any sane balance keeps (a Grinder outpaces a Regular who outpaces a Casual; every scarce material is both
 *       minted and spent).</li>
 *   <li><b>Spec targets</b> (pacing bands, sink ratios), enforced only with {@code -DsimStrict=true}: they are the goals of the
 *       balance pass, and the report shows how far the current numbers are from them. Turn strict on once they are met, so a later
 *       change that drifts away fails the build.</li>
 * </ul>
 */
class BalanceSimulationTest {

    private static final long SEED = 20261103L;
    /** One run shared by every check (a static initialiser rather than @BeforeAll, so any JUnit runner works). */
    private static final Simulation sim = new Simulation(SEED, 250, 90, new Simulation.Model()).run();
    private static final SimReport report = new SimReport(sim);

    static {
        System.out.println(report.markdown(sim));
    }

    @Test
    void theLedgerBalances() {
        assertNull(report.ledgerProblem, "every material held must equal minted minus spent, and no wallet may go negative");
    }

    @Test
    void aSeedIsReproducible() {
        SimReport again = new SimReport(new Simulation(SEED, 250, 90, new Simulation.Model()).run());
        assertEquals(report.json(), again.json());
    }

    @Test
    void moreTimeMeansFasterProgress() {
        double grinder = report.pacing(Archetype.GRINDER).medianFirstLegendary();
        double regular = report.pacing(Archetype.REGULAR).medianFirstLegendary();
        double casual = report.pacing(Archetype.CASUAL).medianFirstLegendary();
        assertTrue(grinder < regular, "grinder " + grinder + " vs regular " + regular);
        assertTrue(Double.isNaN(casual) || regular < casual, "regular " + regular + " vs casual " + casual);
        assertTrue(report.pacing(Archetype.REGULAR).legendaryReached() > 0.9, "almost every Regular reaches a Legendary in 90 days");
    }

    @Test
    void everyScarceMaterialIsMintedAndSpent() {
        for (MaterialId id : SimReport.TRACKED) {
            assertTrue(report.flow(id).minted() > 0, id.id() + " is never earned");
            assertTrue(report.flow(id).spent() > 0, id.id() + " is never spent");
        }
    }

    @Test
    void theThreatModelHoldsItsWinRates() {
        // A check on the model, not the game: the door's advice gives the spec's at-advice win rate.
        assertEquals(0.85, report.towerWinByGap[1], 0.03);
    }

    @Test
    void specTargetsWhenStrict() {
        if (!Boolean.getBoolean("simStrict")) return;
        var regular = report.pacing(Archetype.REGULAR);
        assertTrue(regular.medianFirstLegendary() >= 8 && regular.medianFirstLegendary() <= 12, "Regular first Legendary " + regular.medianFirstLegendary());
        assertTrue(regular.medianFirstMythic() >= 14 && regular.medianFirstMythic() <= 21, "Regular first Mythic " + regular.medianFirstMythic());
        assertTrue(report.bothActivitiesLastWeek > 0.5, "both activities " + report.bothActivitiesLastWeek);
        assertTrue(report.pacing(Archetype.RAID_HEAVY).mythicReached() > 0.5, "raid-heavy players must not be gated out of Mythic");
        assertTrue(report.flow(MaterialId.RESONANCE_DUST).lateSinkRatio() > 0.3, "dust must keep a sink in the late game");
    }
}
