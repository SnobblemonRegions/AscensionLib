package com.ascensionlib.web;

import static org.junit.jupiter.api.Assertions.*;

import com.ascensionlib.craft.CraftPayloads;
import com.ascensionlib.scout.CaptureRevealPayload;
import com.ascensionlib.scout.OwnedInspectPayload;
import com.ascensionlib.scout.ScoutPayloads;
import com.google.gson.Gson;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The pages read these field names; renaming a record component silently breaks a page, so they are pinned here. */
class PageJsonTest {
    private final Gson gson = new Gson();

    private static void has(com.google.gson.JsonObject o, String... keys) { for (var key : keys) assertTrue(o.has(key), key); }

    @Test void craftViewCarriesEverythingCraftHtmlReads() {
        var price = new CraftPayloads.Price(1, 2, 3);
        var slot = new CraftPayloads.SlotView("p1", "prefix", "Burn Boost", "cond", 2, 12, 10, 14, 15, 19, "", "", "", 4, "Needs rank V", 0, 10,
                "Needs rank V", false, 60, 2, 45, 35, 40);
        var unique = new CraftPayloads.UniqueState("", 1, 2, 3, List.of(new CraftPayloads.UniqueOption("u1", "777", "b", "d", false)), "");
        var view = new CraftPayloads.View("id", "Name", "cobblemon:mewtwo", List.of(), 50, "mythical", "777", 1, 60, 7, 8, 100, 20, 3, 4, 2,
                price, price, new CraftPayloads.Promotion("", price, 10, 90, ""), unique,
                new CraftPayloads.Respec(1, 2, 90, 90, 5, "", "", List.of(new CraftPayloads.ResetLine("Burn Boost", 3, 0))), List.of(slot), "", false,
                new CraftPayloads.HauntView(true, 3, "wisp", "Wisp", false, "x", 1, 3, "", List.of(new CraftPayloads.HauntOption("wisp", "Wisp", false, "x", 17.3))),
                5, 1);
        var json = JsonParser.parseString(gson.toJson(view)).getAsJsonObject();
        has(json, "pokemonId", "name", "speciesId", "level", "rarityId", "uniqueName", "pending", "nextMilestone", "profileRevision", "walletRevision",
                "dust", "facets", "cores", "scouters", "refine", "reforge", "promotion", "unique", "respec", "slots", "message", "haunt", "apex", "overreachApex");
        has(json.getAsJsonObject("refine"), "dust", "facets", "cores", "apex");
        has(json.getAsJsonObject("haunt"), "show", "essence", "id", "name", "rare", "effect", "rolls", "cost", "block", "all");
        has(json.getAsJsonObject("haunt").getAsJsonArray("all").get(0).getAsJsonObject(), "id", "name", "rare", "effect", "chance");
        has(json.getAsJsonObject("respec"), "hourglasses", "prisms", "marks", "price", "spent", "hourglassBlock", "prismBlock", "resets");
        has(json.getAsJsonObject("respec").getAsJsonArray("resets").get(0).getAsJsonObject(), "name", "rank", "floor");
        has(json.getAsJsonObject("promotion"), "toRarity", "price", "attunement", "attunementNeeded", "block");
        has(json.getAsJsonObject("unique"), "currentId", "catalysts", "fragments", "fragmentsNeeded", "options", "block");
        has(json.getAsJsonArray("slots").get(0).getAsJsonObject(), "slotId", "category", "name", "condition", "rank", "value", "bandMin", "bandMax",
                "nextMin", "nextMax", "upgradeBlock", "refineBlock", "reforgeBlock", "reforgePool", "perfectBlock", "perfectCost", "floor", "overreachBlock", "sealed", "overreachDust", "overreachFacets", "overreachChance", "overreachMin", "overreachMax");
    }

    @Test void craftDoneAndConfirmKeepTheirNames() {
        var done = JsonParser.parseString(gson.toJson(new CraftPayloads.Done("op", true, "", "p1", "A", 1, 2, "B", 3, 4, false))).getAsJsonObject();
        has(done, "operationId", "ok", "message", "slotId", "oldName", "oldRank", "oldValue", "newName", "newRank", "newValue", "replayed");
    }

    @Test void scoutStateTurnsAnOptionalIntoAnAbsentOrPlainDetail() {
        var detail = new ScoutPayloads.Detail(30, List.of("fire"), List.of(1, 2, 3, 4, 5, 6), "epic", "", List.of(new ScoutPayloads.Slot("prefix", "Burn", 2, 9)));
        var state = new ScoutPayloads.State("enc", 3, List.of(new ScoutPayloads.Entry("s1", "Arcanine", Optional.of(detail)),
                new ScoutPayloads.Entry("s2", "Growlithe", Optional.empty())));
        var json = JsonParser.parseString(gson.toJson(Views.state(state))).getAsJsonObject();
        has(json, "encounterId", "scouters", "entries");
        var first = json.getAsJsonArray("entries").get(0).getAsJsonObject();
        has(first, "subjectId", "name", "detail", "speciesId");
        has(first.getAsJsonObject("detail"), "level", "types", "baseStats", "rarityId", "uniqueName", "slots", "resonance");
        assertFalse(json.getAsJsonArray("entries").get(1).getAsJsonObject().has("detail"), "An unscouted enemy sends no detail at all");
    }

    @Test void ownedViewAndRevealCarryWhatThePagesRead() {
        var detail = new ScoutPayloads.Detail(30, List.of("fire"), List.of(1, 2, 3, 4, 5, 6), "epic", "777", List.of());
        var extra = new OwnedInspectPayload.Extra("cobblemon:arcanine", List.of(), "wild_capture", "rare", 40, 2, 3, 3, false);
        var owned = JsonParser.parseString(gson.toJson(Views.owned(new OwnedInspectPayload.Result("id", "Arcanine", "Arcanine", 1, 5, Optional.of(detail), extra), 2))).getAsJsonObject();
        has(owned, "pokemonId", "name", "species", "pending", "attunement", "detail", "extra");
        has(owned.getAsJsonObject("extra"), "speciesId", "origin", "initialRarity", "highestLevel", "milestones", "prefixCap", "suffixCap", "craftLocked");
        var reveal = JsonParser.parseString(gson.toJson(new CaptureRevealPayload("id", "Arcanine", "cobblemon:arcanine", List.of(), detail, "reveal-id"))).getAsJsonObject();
        assertEquals("reveal-id", reveal.get("revealId").getAsString());
        has(reveal, "pokemonId", "name", "speciesId", "detail");
        var team = JsonParser.parseString(gson.toJson(new Views.TeamView("id", "A", "cobblemon:a", "epic", 5))).getAsJsonObject();
        has(team, "id", "name", "speciesId", "rarityId", "level");
    }
}
