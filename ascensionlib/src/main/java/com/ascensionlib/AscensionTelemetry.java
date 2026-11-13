package com.ascensionlib;

import com.ascensionlib.telemetry.Telemetry;
import com.ascensionlib.telemetry.TelemetryFields;
import java.util.Map;

/**
 * Lets CobbleRaids and CobbleTowers write their own gameplay events (how a raid ended, where a tower run stopped, how long a
 * player waited) into the library's telemetry log, next to the ledger and encounter events, so one folder answers where players
 * struggle and quit. Reached by reflection, so it takes only {@code java.*} types; pinned by {@code ContractSignaturesTest}.
 *
 * <p>Cheap on the server thread: when telemetry is off it returns at once, otherwise it builds one small JSON object and hands the
 * line to the writer thread. It never throws.
 */
public final class AscensionTelemetry {
    private AscensionTelemetry() {}

    /**
     * Writes one event.
     *
     * @param type   {@code raid_*}, {@code tower_*}, {@code dungeon_*} or {@code world_boss_*} (lower case, digits and {@code _}); any other
     *               type is refused, so a caller cannot write the library's own events
     * @param fields the event's fields, as {@link TelemetryFields} accepts them; a {@link java.util.UUID} value is a player and is written as
     *               their player key, so pass any other id (a run, a raid) as a string
     * @return whether the event was handed to the log (false when telemetry is off or the type is refused)
     */
    public static boolean event(String type, Map<String, ?> fields) {
        if (!Telemetry.enabled() || !TelemetryFields.validType(type)) return false;
        Telemetry.log(type, e -> TelemetryFields.fill(e, fields, Telemetry::playerKey));
        return true;
    }
}
