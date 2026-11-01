package com.ascensionlib.craft;

import java.util.List;

/**
 * The upgrade / refine / reforge / promote / Unique screen's data. The screen is a web page ({@code craft.html}, shown by WebGUI); these
 * records are what the server serializes to it as JSON and what it sends back. The page asks for a Pokemon's {@link View} (everything is
 * a preview, nothing is spent). A craft happens only when the page sends a {@link Confirm} carrying a fresh operation id and the two
 * revisions it was shown; the server re-checks ownership, revisions and the wallet, commits once, and answers with a {@link Done} and a
 * refreshed view. The page never decides a roll or a cost.
 */
public final class CraftPayloads {
    private CraftPayloads() {}

    /** Page to server: do it. {@code kind} is {@code upgrade}, {@code refine}, {@code reforge}, {@code promote} (no slot), {@code unique} (slotId is the Unique's id; installs or replaces), {@code assemble} (a Catalyst from Fragments), {@code respec_ranks} (an Hourglass of Undoing), {@code respec_unique} (an Unbinding Prism; slotId is the new Unique's id) or {@code buy_hourglass} (an Hourglass for Tower Marks) or {@code haunt} (a Haunting roll for Spooky Essence; no slot). */
    public record Confirm(String operationId, String pokemonId, String kind, String slotId, long profileRevision, long walletRevision) {}

    /** One occupied ordinary slot as the upgrade screen shows it. Bands are -1 where there is no next rank; {@code sealed} marks a slot that has had its one Overreach attempt; {@code overreachMin/Max} bound the value a success would give and {@code overreachChance} is its percent chance. {@code floor} is the lowest value the slot can roll now (its raised floor, else its band minimum) and {@code perfectCost} the dust to raise it one point (0 when it cannot be done). */
    public record SlotView(String slotId, String category, String name, String condition, int rank, int value, int bandMin, int bandMax,
                           int nextMin, int nextMax, String upgradeBlock, String refineBlock, String reforgeBlock, int reforgePool,
                           String perfectBlock, int perfectCost, int floor, String overreachBlock, boolean sealed, int overreachDust,
                           int overreachFacets, int overreachChance, int overreachMin, int overreachMax) {}

    /** A material cost: dust, facets, cores, and Apex Shards (only priced while the Threat system is on). */
    public record Price(int dust, int facets, int cores, int apex) {
        public Price(int dust, int facets, int cores) { this(dust, facets, cores, 0); }
    }

    /**
     * The next promotion: {@code toRarity} is empty at the top rarity. {@code block} is the reason it cannot be done now (empty when it
     * can); attunement is the Pokemon's lifetime total against what the next rarity requires.
     */
    public record Promotion(String toRarity, Price price, int attunement, int attunementNeeded, String block) {}

    /** One Unique power a Catalyst can install: its text and whether the Pokemon holds it now. */
    public record UniqueOption(String id, String name, String benefit, String drawback, boolean current) {}

    /**
     * The Unique tab: what the Pokemon holds ({@code currentId}, empty for none), the player's Catalysts and Fragments, the Fragments one
     * Catalyst takes, the powers on offer and the reason an install or replace is blocked (empty when it can be done).
     */
    public record UniqueState(String currentId, long catalysts, long fragments, int fragmentsNeeded, List<UniqueOption> options, String block) {}

    /** One slot a Rank Respec would reset: its name, its rank now and its perfected floor (0 for none). */
    public record ResetLine(String name, int rank, int floor) {}

    /**
     * The Respec tab (docs/RESPEC-DESIGN.md): how many Hourglasses of Undoing and Unbinding Prisms the player holds and their Tower Marks, the Marks an
     * Hourglass costs, what a Rank Respec would reset ({@code resets}, with the credits it would return in {@code spent}) and the reason each item
     * cannot be used on this Pokemon now (empty when it can).
     */
    public record Respec(long hourglasses, long prisms, long marks, int price, int spent, String hourglassBlock, String prismBlock, List<ResetLine> resets) {}

    /**
     * Server to page: the screen's data (the page asks for it once it has loaded, and gets a fresh one after every action). {@code open} is unused. {@code message} is a one-line notice (an error from the last action, or empty).
     */
    public record View(String pokemonId, String name, String speciesId, List<String> aspects, int level, String rarityId, String uniqueName,
                       int pending, int nextMilestone, long profileRevision, long walletRevision, long dust, long facets, long cores, long scouters, long marks,
                       Price refine, Price reforge, Promotion promotion, UniqueState unique, Respec respec, List<SlotView> slots, String message, boolean open, HauntView haunt,
                       long apex, int overreachApex) {}

    /** One Haunting a roll can land on: its text, whether it is a Rare, and its chance in percent. */
    public record HauntOption(String id, String name, boolean rare, String effect, double chance) {}

    /**
     * The Haunting row (Halloween 2026): {@code show} is false when the screen should not draw it. {@code id} is empty while the Pokemon has none; {@code rolls}
     * counts the rolls it has had, {@code cost} is the Spooky Essence the next one takes and {@code block} the reason it cannot be rolled now (empty when it can).
     */
    public record HauntView(boolean show, long essence, String id, String name, boolean rare, String effect, int rolls, long cost, String block,
                            List<HauntOption> all) {}

    /** Server to page: how a confirmed craft ended. On success the slot before and after; on failure only the reason. */
    public record Done(String operationId, boolean ok, String message, String slotId, String oldName, int oldRank, int oldValue,
                       String newName, int newRank, int newValue, boolean replayed) {}
}
