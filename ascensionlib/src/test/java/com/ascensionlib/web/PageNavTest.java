package com.ascensionlib.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PageNavTest {
    private final UUID player = UUID.randomUUID();
    private final List<String> opened = new ArrayList<>();

    /** Opens a page the way WebUi.open does: record it on the trail, as from a page or not. */
    private void open(String key, boolean fromPage) { opened.add(key); PageNav.opening(player, key, p -> { open(key, true); return true; }, fromPage); }

    @Test
    void anOpenFromTheItemStartsAFreshTrail() {
        open("party", false);
        assertFalse(PageNav.canGoBack(player));
    }

    @Test
    void backWalksTheTrailInReverse() {
        open("party", false);
        open("inspect:OWNED:a1", true);
        open("craft:a1", true);
        assertEquals(2, PageNav.depth(player));
        assertTrue(PageNav.back(player, null));
        assertEquals("inspect:OWNED:a1", PageNav.currentKey(player));
        assertTrue(PageNav.back(player, null));
        assertEquals("party", PageNav.currentKey(player));
        assertFalse(PageNav.canGoBack(player));
        assertFalse(PageNav.back(player, null));
    }

    @Test
    void openingFromOutsideAPageClearsTheTrail() {
        open("party", false);
        open("inspect:OWNED:a1", true);
        open("guide", false);   // a command while nothing was showing
        assertFalse(PageNav.canGoBack(player));
    }

    @Test
    void returningToAPageOnTheTrailUnwindsInsteadOfLooping() {
        open("party", false);
        open("inspect:OWNED:a1", true);
        open("craft:a1", true);
        open("party", true);   // a link back to the Dex from deep inside
        assertFalse(PageNav.canGoBack(player));
        assertEquals("party", PageNav.currentKey(player));
    }

    @Test
    void aPageThatCannotReopenIsSkipped() {
        open("party", false);
        PageNav.opening(player, "craft:gone", p -> false, true);   // its Pokemon will have been released
        open("wallet", true);
        assertTrue(PageNav.back(player, null));
        assertEquals("party", PageNav.currentKey(player));
    }

    @Test
    void aPageOutsideTheDexClearsTheTrail() {
        open("party", false);
        PageNav.opening(player, "capture.html", null, true);
        assertFalse(PageNav.canGoBack(player));
    }

    @Test
    void theTrailIsBounded() {
        open("p0", false);
        for (int i = 1; i <= 20; i++) open("p" + i, true);
        assertEquals(PageNav.MAX_DEPTH, PageNav.depth(player));
    }
}
