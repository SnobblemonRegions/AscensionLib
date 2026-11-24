package com.ascensionlib.fusion;

import java.util.List;

/**
 * The fusion messages. The screen is a web page shown by WebGUI, so everything travels as JSON through {@link FusionWeb}
 * (the records below are what the server serializes; the page only displays the text it is sent). The page asks to see one pairing
 * ({@code ascension_fusion_preview}, answered by {@link Detail}) and confirms it ({@code ascension_fusion_confirm}, answered by
 * {@link Result}). Everything is a preview until the server accepts a confirmation: the server resolves the Transcendent, applies the
 * rules and the price.
 */
public final class FusionPayloads {
    private FusionPayloads() {}

    /** One Pokemon the player owns, as the picker and the chambers show it; {@code block} is why it cannot take the asked-for role now (empty when it can). */
    public record Entry(String id, String name, String speciesId, String rarityId, String uniqueName, boolean inParty, boolean canHost, String block) {}

    /** The most entries a pick list carries: a full party plus the first page of the PC. */
    public static final int MAX_PICK = 36;

    /** Server to page: the host for the host chamber; {@code hostBlock} is why it cannot be a host (empty when it can). */
    /** {@code fuserDonor} is set when a Fuser opened the page; {@code introSeen} is whether this player already dismissed the Fuser intro. */
    public record Candidates(Entry host, String hostBlock, Entry fuserDonor, boolean introSeen) {
        public Candidates(Entry host, String hostBlock) { this(host, hostBlock, null, true); }
    }

    /** Server to page: the Pokemon a chamber could hold: party first, then the first page of the PC. */
    public record PickList(boolean hostRole, List<Entry> entries) {}

    /**
     * What a pairing would make, as text. {@code block} is empty when it can be done. The price and the balances are plain numbers
     * (dust, facets, cores, catalysts); the three revisions are what a confirmation must carry back.
     */
    public record Detail(String hostId, String donorId, String block, String name, String fromUniques, String harmony, int benefit, int drawback,
                         String blurb, String core, String clutch, String drawbackText, String twistName, String twistText, String riderText,
                         String hostType, String donorType, long[] price, long[] balances, long hostRevision, long donorRevision, long walletRevision,
                         boolean namedRecipe) {}

    /** Server to page: how a confirmation ended. */
    public record Result(String operationId, boolean ok, String message, String name) {}
}
