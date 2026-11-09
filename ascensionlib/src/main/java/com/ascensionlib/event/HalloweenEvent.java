package com.ascensionlib.event;

import java.time.Instant;

/**
 * The Halloween 2026 window as the pages see it (docs/HALLOWEEN-EVENT-DESIGN.md): it turns the spooky theme on and decides when the Haunting row shows
 * in the upgrade screen. The same window is the default of CobbleRaids' {@code world_boss.json}; Eastern time, written in UTC because the clocks change
 * on 1 November. Nothing here gates what a player may do: Spooky Essence and Hauntings stay usable after the event.
 *
 * <p>For a look before the 31st, start the server with {@code -Dascensionlib.halloween=true} (or {@code false} to force it off).
 */
public final class HalloweenEvent {
    public static final Instant START = Instant.parse("2026-10-31T04:00:00Z");
    public static final Instant END = Instant.parse("2026-11-12T05:00:00Z");
    private static final String OVERRIDE = "ascensionlib.halloween";

    private HalloweenEvent() {}

    /** Whether {@code now} is inside the window (start included, end excluded). */
    public static boolean isOpen(Instant now) { return !now.isBefore(START) && now.isBefore(END); }

    /** The window now, honouring the {@code ascensionlib.halloween} override. */
    public static boolean isOpen() {
        String forced = System.getProperty(OVERRIDE);
        if (forced != null) return Boolean.parseBoolean(forced);
        return isOpen(Instant.now());
    }

    /**
     * Whether the upgrade screen shows the Haunting row for this player's Pokemon.
     *
     * @param essence      the Spooky Essence the player holds
     * @param holdsHaunting this Pokemon already has a Haunting
     * @param eventOpen    the window is open now
     */
    public static boolean showHaunting(long essence, boolean holdsHaunting, boolean eventOpen) {
        // During the event everyone sees it. Afterwards a Haunting stays readable and leftover Essence stays spendable, so it shows for exactly those
        // players; anyone who never took part sees no extra row.
        return eventOpen || holdsHaunting || essence > 0;
    }
}
