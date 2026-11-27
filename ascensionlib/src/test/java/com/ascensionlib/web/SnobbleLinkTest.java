package com.ascensionlib.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Screens go to Snobblemon GUI first, and every page keeps the native page id the compat jar gave it. */
class SnobbleLinkTest {

    @Test void snobblemonGuiIsPreferredAndWebGuiIsTheFallback() {
        assertEquals(WebUi.Backend.SNOBBLE, WebUi.choose(true, true), "with both, the native pages win");
        assertEquals(WebUi.Backend.SNOBBLE, WebUi.choose(true, false));
        assertEquals(WebUi.Backend.WEBGUI, WebUi.choose(false, true));
        assertEquals(WebUi.Backend.NONE, WebUi.choose(false, false));
    }

    @Test void everyPageMapsToTheLayoutTheCompatJarUsed() {
        for (String page : new String[] {"capture", "completion", "craft", "fusion", "guide", "hud", "inspect", "party", "transcend", "wallet"}) {
            assertEquals("ascensionlib_" + page, SnobbleLink.pageId(page + ".html"));
        }
    }

    @Test void queryFragmentCaseAndPathTricksAreDropped() {
        assertEquals("ascensionlib_inspect", SnobbleLink.pageId("inspect.html?uuid=1234#top"));
        assertEquals("ascensionlib_craft", SnobbleLink.pageId("Craft.HTML".toLowerCase()));
        assertEquals("ascensionlib_party", SnobbleLink.pageId("../party.html"));
        assertEquals("ascensionlib", SnobbleLink.pageId("index.html"), "a trailing index is the folder itself, as UrlMap has it");
    }
}
