package com.ascensionlib;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import net.fabricmc.loader.api.FabricLoader;

/**
 * One line in the log at server start saying which companion mods are installed and what that means: so a server owner (or a bug report) shows at a glance whether the screens,
 * the battle effects and the earning mods are all there, and a Cobblemon outside the versions this build was verified on is called out. Reading only; it changes nothing.
 */
final class CompatReport {
    /** The Cobblemon versions this build was compiled and booted against (see docs/COMPATIBILITY.md). */
    static final List<String> VERIFIED_COBBLEMON = List.of("1.8.1", "1.8.2");

    private CompatReport() {}

    /** The companions, with what each one gives: id, display name, and what is lost without it. */
    private static final LinkedHashMap<String, String[]> COMPANIONS = new LinkedHashMap<>();
    static {
        COMPANIONS.put("cobblemon", new String[] {"Cobblemon", "required"});
        COMPANIONS.put("snobblemon_server", new String[] {"Snobblemon GUI", "no screens: every page needs it"});
        COMPANIONS.put("cobbleraids", new String[] {"CobbleRaids", "modifiers do not act in battle"});
        COMPANIONS.put("cobbletowers", new String[] {"CobbleTowers", "no Tower rewards"});
        COMPANIONS.put("ascensiondungeons", new String[] {"ascensionDungeons", "no dungeon rewards"});
        COMPANIONS.put("polymer-core", new String[] {"Polymer", "the resource pack is not hosted for you"});
    }

    /** The report text and the warnings it implies, from a lookup of installed versions (empty when a mod is absent). */
    static List<String> lines(Function<String, Optional<String>> versionOf) {
        var out = new ArrayList<String>();
        var present = new ArrayList<String>();
        var missing = new ArrayList<String>();
        COMPANIONS.forEach((id, info) -> versionOf.apply(id).ifPresentOrElse(v -> present.add(info[0] + " " + v), () -> missing.add(info[0] + " (" + info[1] + ")")));
        out.add("Companions found: " + String.join(", ", present) + (missing.isEmpty() ? "" : ". Not installed: " + String.join("; ", missing)));
        versionOf.apply("cobblemon").ifPresent(v -> {
            String base = v.contains("+") ? v.substring(0, v.indexOf('+')) : v;
            if (!VERIFIED_COBBLEMON.contains(base))
                out.add("WARNING: Cobblemon " + base + " is not one of the versions this AscensionLib was verified on (" + String.join(", ", VERIFIED_COBBLEMON) + "); it may still work, but report anything odd.");
        });
        return out;
    }

    /** Logs the report for the running game. */
    static void log(org.slf4j.Logger log) {
        var loader = FabricLoader.getInstance();
        for (var line : lines(id -> loader.getModContainer(id).map(c -> c.getMetadata().getVersion().getFriendlyString()))) {
            if (line.startsWith("WARNING")) log.warn(line); else log.info(line);
        }
    }
}
