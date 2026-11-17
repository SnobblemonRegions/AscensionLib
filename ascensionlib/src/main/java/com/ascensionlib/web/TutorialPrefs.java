package com.ascensionlib.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Which first-open tutorials each player has dismissed, e.g. {@code craft.refine} for the Refine mechanic on the Traits screen. A page shows a short coach
 * card the first time a player meets a mechanic and sends its id back once it is dismissed, so it never shows again for that player, on any device. Kept in
 * one small JSON file beside the world; a missing or unreadable file only means the tutorials show again. The ids come from the page, so they are checked
 * against a strict shape and each player keeps at most {@link #MAX_PER_PLAYER}: a modified client cannot grow the file without bound. Pure Java, tested on
 * its own.
 */
public final class TutorialPrefs {
    public static final int MAX_PER_PLAYER = 64;
    private static final Pattern ID = Pattern.compile("[a-z]{1,16}\\.[a-z_]{1,24}");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;
    private final Map<UUID, Set<String>> seen = new ConcurrentHashMap<>();

    public TutorialPrefs(Path file) {
        this.file = file;
        load();
    }

    public static boolean validId(String id) { return id != null && ID.matcher(id).matches(); }

    /** The tutorials this player has dismissed, sorted. */
    public List<String> get(UUID player) {
        var set = seen.get(player);
        return set == null ? List.of() : List.copyOf(new TreeSet<>(set));
    }

    /** Marks one tutorial as seen. Returns false, and stores nothing, for a malformed id or a player already at the limit. */
    public synchronized boolean mark(UUID player, String id) {
        if (!validId(id)) return false;
        var set = seen.computeIfAbsent(player, k -> ConcurrentHashMap.newKeySet());
        if (set.contains(id)) return true;
        if (set.size() >= MAX_PER_PLAYER) return false;
        set.add(id);
        save();
        return true;
    }

    private void load() {
        if (file == null || !Files.isRegularFile(file)) return;
        try {
            JsonObject root = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonObject.class);
            if (root == null) return;
            for (var entry : root.entrySet()) {
                try {
                    UUID player = UUID.fromString(entry.getKey());
                    Set<String> set = ConcurrentHashMap.newKeySet();
                    for (var id : entry.getValue().getAsJsonArray()) {
                        String text = id.getAsString();
                        if (validId(text) && set.size() < MAX_PER_PLAYER) set.add(text);
                    }
                    if (!set.isEmpty()) seen.put(player, set);
                } catch (IllegalArgumentException | UnsupportedOperationException | IllegalStateException skipped) {
                    // One bad entry is dropped; the rest still load.
                }
            }
        } catch (IOException | JsonParseException unreadable) {
            seen.clear();
        }
    }

    /** Written to a temporary file and moved into place, so a crash never leaves half a file. */
    private void save() {
        if (file == null) return;
        try {
            var sorted = new TreeMap<String, TreeSet<String>>();
            seen.forEach((player, ids) -> sorted.put(player.toString(), new TreeSet<>(ids)));
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, GSON.toJson(sorted), StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException failed) {
            // The choice still holds until the server restarts; saving is best effort and must never break a page.
        }
    }
}
