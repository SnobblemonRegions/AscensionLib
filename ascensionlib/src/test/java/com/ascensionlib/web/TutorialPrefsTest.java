package com.ascensionlib.web;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TutorialPrefsTest {
    @TempDir Path dir;

    @Test void aNewPlayerHasSeenNothing() {
        assertEquals(List.of(), new TutorialPrefs(dir.resolve("t.json")).get(UUID.randomUUID()));
    }

    @Test void seenTutorialsSurviveARestartPerPlayer() {
        var file = dir.resolve("world/t.json");
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        var prefs = new TutorialPrefs(file);
        assertTrue(prefs.mark(a, "craft.refine"));
        assertTrue(prefs.mark(a, "craft.overreach"));
        assertTrue(prefs.mark(a, "craft.refine"));
        var reopened = new TutorialPrefs(file);
        assertEquals(List.of("craft.overreach", "craft.refine"), reopened.get(a));
        assertEquals(List.of(), reopened.get(b));
    }

    @Test void malformedIdsAreRefused() {
        var prefs = new TutorialPrefs(dir.resolve("t.json"));
        var p = UUID.randomUUID();
        for (String bad : new String[] { "", "refine", "Craft.refine", "craft.refine!", "craft.<b>", "a".repeat(20) + ".x", null }) {
            assertFalse(prefs.mark(p, bad), String.valueOf(bad));
        }
        assertEquals(List.of(), prefs.get(p));
    }

    @Test void aPlayerCannotGrowTheFileWithoutBound() {
        var prefs = new TutorialPrefs(dir.resolve("t.json"));
        var p = UUID.randomUUID();
        for (int i = 0; i < TutorialPrefs.MAX_PER_PLAYER; i++) assertTrue(prefs.mark(p, "spam." + "x".repeat(1 + i % 23) + (char) ('a' + i / 23)));
        assertFalse(prefs.mark(p, "craft.refine"));
        assertEquals(TutorialPrefs.MAX_PER_PLAYER, prefs.get(p).size());
    }

    @Test void aCorruptFileMeansTutorialsShowAgainAndSavingWorks() throws Exception {
        var file = dir.resolve("t.json");
        Files.writeString(file, "{ not json");
        var prefs = new TutorialPrefs(file);
        var p = UUID.randomUUID();
        assertEquals(List.of(), prefs.get(p));
        prefs.mark(p, "craft.unique");
        assertEquals(List.of("craft.unique"), new TutorialPrefs(file).get(p));
    }
}
