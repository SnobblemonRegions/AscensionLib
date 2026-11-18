package com.ascensionlib.web;

import com.ascensionlib.net.RateLimit;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerPlayer;

/**
 * The AscensionDex's Back button. WebGUI shows one page at a time and each page is its own document, so going from the Dex to Inspect to Traits
 * replaces the page; without this, Close on Traits shut the whole Dex. Each page opened <em>from another page</em> remembers the one it replaced,
 * and {@code ascension_back} reopens it with the same arguments (through that page's own open method, so every ownership check runs again).
 *
 * <p>"From another page" is read from {@link GuiPresence}: a page beats while it is up, so an open while one is showing is navigation, and any
 * other open (the Dex item, a command, the overlay) starts a fresh trail. Pages that are not part of the Dex (the capture card, the tower
 * completion) open without a reopen and clear the trail. Server thread only.
 */
public final class PageNav {
    /** Reopens a page for the player; false when it can no longer open (the Pokemon was released or traded, say). */
    public interface Reopen { boolean open(ServerPlayer player); }

    record Entry(String key, Reopen reopen) {}

    /** Deep enough for Dex, Inspect, Traits, Fusion and back with room to spare; older steps fall off the bottom. */
    static final int MAX_DEPTH = 8;

    private static final Map<UUID, Deque<Entry>> TRAIL = new ConcurrentHashMap<>();
    private static final Map<UUID, Entry> CURRENT = new ConcurrentHashMap<>();
    private static boolean goingBack;

    private PageNav() {}

    public static void register() {
        WebUi.listen("ascension_back", (player, o) -> {
            if (RateLimit.allow(player.getUUID(), "nav.back", 6, 3)) back(player.getUUID(), player);
        });
    }

    /**
     * Records that {@code key} is about to open for the player. {@code key} names the page and its arguments ("inspect:owned:<id>"), so reopening
     * a page already on the trail unwinds to it rather than growing a loop. {@code fromPage}: whether one of our pages was showing.
     */
    static void opening(UUID player, String key, Reopen reopen, boolean fromPage) {
        Entry next = reopen == null ? null : new Entry(key, reopen);
        if (goingBack) { put(player, next); return; }
        Deque<Entry> trail = TRAIL.computeIfAbsent(player, k -> new ArrayDeque<>());
        Entry current = CURRENT.get(player);
        if (!fromPage || next == null) trail.clear();
        else if (current != null && !current.key().equals(key)) {
            if (trail.stream().anyMatch(e -> e.key().equals(key))) {
                while (!trail.isEmpty() && !trail.pop().key().equals(key)) { /* unwind to the earlier visit */ }
            } else {
                trail.push(current);
                while (trail.size() > MAX_DEPTH) trail.removeLast();
            }
        }
        put(player, next);
    }

    private static void put(UUID player, Entry entry) {
        if (entry == null) CURRENT.remove(player); else CURRENT.put(player, entry);
    }

    /** Whether the open page has somewhere to go back to (the page shows its Back button only then). */
    public static boolean canGoBack(UUID player) {
        Deque<Entry> trail = TRAIL.get(player);
        return trail != null && !trail.isEmpty();
    }

    /** Reopens the page before this one, skipping any that can no longer open. Returns whether one opened. */
    static boolean back(UUID id, ServerPlayer player) {
        Deque<Entry> trail = TRAIL.get(id);
        while (trail != null && !trail.isEmpty()) {
            Entry entry = trail.pop();
            goingBack = true;
            try {
                if (entry.reopen().open(player)) return true;
            } finally {
                goingBack = false;
            }
        }
        return false;
    }

    public static void forget(UUID player) { TRAIL.remove(player); CURRENT.remove(player); }

    /** The key of the page the player is on, for tests. */
    static String currentKey(UUID player) { Entry e = CURRENT.get(player); return e == null ? null : e.key(); }

    static int depth(UUID player) { Deque<Entry> t = TRAIL.get(player); return t == null ? 0 : t.size(); }
}
