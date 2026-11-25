package com.ascensionlib.web;

import com.ascensionlib.net.RateLimit;
import com.cobbleascend.domain.v1.GuideFacts;
import com.cobbleascend.domain.v1.Tunables;
import java.util.Map;

/**
 * Gives the guide pages the live values of the numbers a server owner can tune ({@link Tunables}): the daily budget, the fee, the Hourglass price, the chests and the drop
 * rates, and the completion-tier table. The page asks once when it loads ({@code ascension_guide_facts}) and replaces the defaults it shipped with.
 */
public final class GuideWeb {
    private GuideWeb() {}

    public record Live(Map<String, String> facts, Map<String, Object> tables) {}
    public record Topic(String topic) {}

    /** The topic a page asked the guide to open at, until the guide has loaded and been told it. */
    private static final Map<java.util.UUID, String> PENDING = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.regex.Pattern TOPIC = java.util.regex.Pattern.compile("[a-z]{1,16}");

    /** Opens the guide page at {@code topic} (a guide-data.js topic id; anything else opens the first topic). Back returns to the page that asked. */
    public static boolean open(net.minecraft.server.level.ServerPlayer player, String topic) {
        String t = topic != null && TOPIC.matcher(topic).matches() ? topic : "";
        if (t.isEmpty()) PENDING.remove(player.getUUID()); else PENDING.put(player.getUUID(), t);
        return WebUi.open(player, "guide.html", "guide:" + t, p -> open(p, t));
    }

    public static void register() {
        WebUi.listen("ascension_guide_facts", (player, o) -> {
            if (!RateLimit.allow(player.getUUID(), "guide.facts", 4, 2)) return;
            var tunables = Tunables.current();
            WebUi.emit(player, "guideFacts", new Live(GuideFacts.facts(tunables), Map.of("tower_tier_table", GuideFacts.towerTierTable(tunables))));
            String topic = PENDING.remove(player.getUUID());
            if (topic != null) WebUi.emit(player, "guideTopic", new Topic(topic));
        });
        WebUi.listen("ascension_guide_open", (player, o) -> {
            if (RateLimit.allow(player.getUUID(), "guide.open", 3, 1)) open(player, WebUi.str(o, "topic", 16));
        });
    }
}
