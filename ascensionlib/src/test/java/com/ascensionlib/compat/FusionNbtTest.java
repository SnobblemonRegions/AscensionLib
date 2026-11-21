package com.ascensionlib.compat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

/** Project Fusion's data as AscensionLib reads it, in the shape Project Fusion 2.1.0 writes it. */
class FusionNbtTest {
    private static CompoundTag fusion(boolean shifted) {
        var saved = new CompoundTag();
        saved.putInt("version", 1);
        saved.put("body", new CompoundTag());
        saved.put("head", new CompoundTag());
        if (shifted) saved.putBoolean("shifted", true);
        var data = new CompoundTag();
        data.put(FusionNbt.KEY, saved);
        return data;
    }

    @Test void aFusionIsKnownByItsSavedPartners() {
        assertTrue(FusionNbt.isFusion(fusion(false)));
        assertFalse(FusionNbt.isFusion(new CompoundTag()));
        assertFalse(FusionNbt.isFusion((CompoundTag) null));
        var wrongShape = new CompoundTag();
        wrongShape.putString(FusionNbt.KEY, "not a compound");
        assertFalse(FusionNbt.isFusion(wrongShape));
    }

    @Test void theHeadIsReadFromTheSavedPartner() {
        var data = fusion(false);
        var head = data.getCompound(FusionNbt.KEY).getCompound("head");
        var id = java.util.UUID.randomUUID();
        head.putUUID("UUID", id);
        head.putString("Species", "cobblemon:pikachu");
        var read = FusionNbt.headOf(data).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(id, read.id());
        org.junit.jupiter.api.Assertions.assertEquals("cobblemon:pikachu", read.speciesId());
        org.junit.jupiter.api.Assertions.assertEquals("", read.nickname());
        assertFalse(FusionNbt.headOf(fusion(false)).isPresent(), "a head with no id is unreadable, not guessed");
        assertFalse(FusionNbt.headOf(new CompoundTag()).isPresent());
    }

    @Test void genomeShiftIsReadFromTheFlag() {
        assertTrue(FusionNbt.shifted(fusion(true)));
        assertFalse(FusionNbt.shifted(fusion(false)));
        assertFalse(FusionNbt.shifted(new CompoundTag()));
    }

    // The start-up check finds each bond hook by the name Mixin keeps inside the merged method.
    @SuppressWarnings("unused") private static void handler$zzb000$ascensionlib$bondAtFuser() {}
    @SuppressWarnings("unused") private static void handler$zzb001$ascensionlib$lockBond() {}

    @Test void theStartupCheckFindsEachBondHook() throws NoSuchMethodException {
        Method[] both = {FusionNbtTest.class.getDeclaredMethod("handler$zzb000$ascensionlib$bondAtFuser"),
                FusionNbtTest.class.getDeclaredMethod("handler$zzb001$ascensionlib$lockBond")};
        assertTrue(ProjectFusionGuard.has(both, ProjectFusionGuard.BOND_HANDLER));
        assertTrue(ProjectFusionGuard.has(both, ProjectFusionGuard.SPLIT_HANDLER));
        assertFalse(ProjectFusionGuard.has(both, ProjectFusionGuard.REFUND_HANDLER));
    }
}
