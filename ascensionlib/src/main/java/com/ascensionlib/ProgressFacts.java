package com.ascensionlib;

import com.cobbleascend.domain.Rarity;
import com.cobbleascend.domain.v1.ProfileV1;
import com.cobbleascend.domain.v1.RankedRules;
import com.cobbleascend.domain.v1.Tunables;

/**
 * The two goals a page shows for a Pokemon's growth, worked out the same way everywhere: the level of its next upgrade milestone, and the attunement
 * its next promotion asks for. The Traits screen (CraftNet), Inspect (OwnedInspectNet) and the Dex home (PartyWeb) read them from here.
 */
public final class ProgressFacts {
    private ProgressFacts() {}

    /** The next level that earns an upgrade credit (every 10, up to 100), or 0 when every milestone is reached. */
    public static int nextMilestone(ProfileV1 profile) {
        for (int m = 10; m <= 100; m += 10) if (m > profile.highestLevelObserved()) return m;
        return 0;
    }

    /** The attunement the next promotion needs, or 0 at Radiant, where there is no next promotion. */
    public static int attunementNeeded(RankedRules rules, ProfileV1 profile) {
        if (profile.rarity() == Rarity.MYTHICAL) return 0;
        return rules.base().promotion(profile.rarity(), Tunables.current().threat().enabled()).attunement();
    }
}
