package com.ascensionlib.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ascensionlib.CraftLocks;
import java.lang.reflect.Method;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class ProjectFusionGuardTest {
    private static CompoundTag lent() {
        var tag = new CompoundTag();
        tag.putBoolean(CraftLocks.TAG, true);
        return tag;
    }

    @Test void twoOwnPokemonMayFuse() {
        assertEquals(Optional.empty(), ProjectFusionGuard.refusal(new CompoundTag(), new CompoundTag()));
    }

    @Test void aLentHeadIsRefused() {
        assertEquals(Optional.of(ProjectFusionGuard.REASON), ProjectFusionGuard.refusal(new CompoundTag(), lent()));
    }

    @Test void aLentBodyIsRefused() {
        assertEquals(Optional.of(ProjectFusionGuard.REASON), ProjectFusionGuard.refusal(lent(), new CompoundTag()));
    }

    @Test void anUnsetOrFalseMarkIsNotALoan() {
        var cleared = new CompoundTag();
        cleared.putBoolean(CraftLocks.TAG, false);
        assertEquals(Optional.empty(), ProjectFusionGuard.refusal(cleared, null));
    }

    @Test void onlyTwoUniqueHoldersMayBeFused() {
        assertEquals(Optional.empty(), ProjectFusionGuard.uniqueRefusal(true, true));
        assertEquals(Optional.of(ProjectFusionGuard.NO_UNIQUE), ProjectFusionGuard.uniqueRefusal(true, false));
        assertEquals(Optional.of(ProjectFusionGuard.NO_UNIQUE), ProjectFusionGuard.uniqueRefusal(false, true));
        assertEquals(Optional.of(ProjectFusionGuard.NO_UNIQUE), ProjectFusionGuard.uniqueRefusal(false, false));
    }

    // Mixin merges a handler under a prefixed name that still contains the original, such as handler$zza000$ascensionlib$refuseLoaned.
    @SuppressWarnings("unused") private static void handler$zza000$ascensionlib$refuseLoaned() {}
    @SuppressWarnings("unused") private static void validate() {}

    @Test void theStartupCheckFindsAMergedHandler() throws NoSuchMethodException {
        Method merged = ProjectFusionGuardTest.class.getDeclaredMethod("handler$zza000$ascensionlib$refuseLoaned");
        Method other = ProjectFusionGuardTest.class.getDeclaredMethod("validate");
        assertTrue(ProjectFusionGuard.guarded(new Method[] {other, merged}));
        assertFalse(ProjectFusionGuard.guarded(new Method[] {other}));
    }
}
