package com.ascensionlib.telemetry;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Turns the fields another mod hands to {@link com.ascensionlib.AscensionTelemetry#event} into JSON, with limits, so a caller that
 * passes too much or the wrong thing gets a smaller event rather than a broken log. Minecraft-free, so it is tested directly.
 *
 * <p>Accepted values: {@link Boolean}, finite {@link Number}, {@link CharSequence} and {@link Enum} (as lower-case text, cut to
 * {@link #MAX_TEXT} characters), {@link UUID} (always a player, written as their {@link Telemetry#playerKey player key}, never as the
 * UUID), a {@link Collection} of those (an array, at most {@link #MAX_ITEMS}), and a {@link Map} with string keys of those (an object,
 * at most {@link #MAX_ITEMS}). Anything else, and any field past {@link #MAX_FIELDS}, is left out.
 */
public final class TelemetryFields {
    public static final int MAX_FIELDS = 32, MAX_ITEMS = 32, MAX_TEXT = 96;
    /** Event types other mods may write: their own namespace, never one of the library's own events. */
    static final Pattern TYPE = Pattern.compile("(raid|tower|dungeon|world_boss)_[a-z0-9_]{1,40}");
    private static final Pattern KEY = Pattern.compile("[a-zA-Z][a-zA-Z0-9_]{0,31}");

    private TelemetryFields() {}

    public static boolean validType(String type) { return type != null && TYPE.matcher(type).matches(); }

    /** Adds {@code fields} to {@code event}; {@code t} and {@code type} are never overwritten. */
    public static void fill(JsonObject event, Map<String, ?> fields, Function<UUID, String> playerKey) {
        if (fields == null) return;
        int added = 0;
        for (var entry : fields.entrySet()) {
            if (added >= MAX_FIELDS) break;
            String key = entry.getKey();
            if (key == null || !KEY.matcher(key).matches() || key.equals("t") || key.equals("type")) continue;
            var value = value(entry.getValue(), playerKey, true);
            if (value == null) continue;
            event.add(key, value);
            added++;
        }
    }

    private static com.google.gson.JsonElement value(Object value, Function<UUID, String> playerKey, boolean nest) {
        if (value instanceof Boolean b) return new com.google.gson.JsonPrimitive(b);
        if (value instanceof Number n) {
            double d = n.doubleValue();
            return Double.isFinite(d) ? new com.google.gson.JsonPrimitive(n) : null;
        }
        if (value instanceof UUID id) return new com.google.gson.JsonPrimitive(playerKey.apply(id));
        if (value instanceof Enum<?> e) return text(e.name().toLowerCase(java.util.Locale.ROOT));
        if (value instanceof CharSequence s) return text(s.toString());
        if (!nest) return null;
        if (value instanceof Collection<?> items) {
            var array = new JsonArray();
            for (Object item : items) {
                if (array.size() >= MAX_ITEMS) break;
                var element = value(item, playerKey, false);
                if (element != null) array.add(element);
            }
            return array;
        }
        if (value instanceof Map<?, ?> map) {
            var object = new JsonObject();
            for (var entry : map.entrySet()) {
                if (object.size() >= MAX_ITEMS) break;
                if (!(entry.getKey() instanceof String key) || key.length() > MAX_TEXT) continue;
                var element = value(entry.getValue(), playerKey, false);
                if (element != null) object.add(key, element);
            }
            return object;
        }
        return null;
    }

    private static com.google.gson.JsonPrimitive text(String s) {
        return new com.google.gson.JsonPrimitive(s.length() > MAX_TEXT ? s.substring(0, MAX_TEXT) : s);
    }
}
