package com.ascensionlib.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Every page keeps the native page id the compat jar gave it, so the layouts in Snobblemon GUI still match. */
class SnobbleLinkTest {

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
