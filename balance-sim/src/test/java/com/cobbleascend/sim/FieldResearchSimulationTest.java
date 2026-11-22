package com.cobbleascend.sim;

import static org.junit.jupiter.api.Assertions.*;

import com.cobbleascend.domain.v1.MaterialId;
import org.junit.jupiter.api.Test;

/**
 * The Field Research prototype in the 90-day run: it must keep the books balanced, respect every research rule and craft limit, and be a pure
 * sink (research mints nothing). Switched off, the economy must be exactly the one {@link BalanceSimulationTest} checks.
 */
class FieldResearchSimulationTest {
    private static final long SEED = 20261103L;

    private static Simulation.Model on() {
        var model = new Simulation.Model();
        model.research = true;
        return model;
    }

    private static final Simulation sim = new Simulation(SEED, 250, 90, on()).run();
    private static final SimReport report = new SimReport(sim);

    static {
        System.out.println(report.markdown(sim));
    }

    @Test void theBooksBalanceWithResearchOn() {
        assertNull(report.ledgerProblem);
    }

    @Test void everyResearchRuleAndCraftLimitHolds() {
        assertNull(report.researchProblem);
        assertTrue(sim.research.researchers() > 0);
    }

    @Test void researchIsAPureSink() {
        var off = new Simulation(SEED, 250, 90, new Simulation.Model()).run();
        for (MaterialId id : MaterialId.values()) {
            assertTrue(sim.ledger().minted(id) <= off.ledger().minted(id) * 1.05 + 10,
                    id.id() + ": research must not mint (any difference is promotion timing only)");
        }
        assertTrue(sim.research.stats.dust > 0, "serums are crafted and paid for");
        long serumDust = sim.research.stats.dust;
        long extraSpent = sim.ledger().spent(MaterialId.RESONANCE_DUST) - off.ledger().spent(MaterialId.RESONANCE_DUST);
        assertTrue(extraSpent > 0, "turning research on raises the dust spent");
        assertTrue(serumDust > 0);
    }

    @Test void switchedOffTheEconomyIsUnchanged() {
        var a = new SimReport(new Simulation(SEED, 250, 90, new Simulation.Model()).run());
        var model = new Simulation.Model();
        model.research = false;
        var b = new SimReport(new Simulation(SEED, 250, 90, model).run());
        assertEquals(a.json(), b.json());
    }

    @Test void aSeedIsReproducibleWithResearchOn() {
        assertEquals(report.json(), new SimReport(new Simulation(SEED, 250, 90, on()).run()).json());
        assertEquals(report.markdown(sim), new SimReport(new Simulation(SEED, 250, 90, on()).run()).markdown(new Simulation(SEED, 250, 90, on()).run()));
    }
}
