package com.ascensionlib.fusion;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TranscendRevealTest {
    @Test void aSharedTranscendentNamesThePlayerBothPokemonAndThePower() {
        var view = new TranscendReveal.View("Tidal Phoenix Cinder of the Tempest", "Gyarados", "cobblemon:gyarados", "Pikachu", "cobblemon:pikachu",
                "", "core", "clutch", "drawback", "neutral", 75, 60, false);
        assertEquals("Ash bonded Pikachu into Gyarados and made a Transcendent: Tidal Phoenix Cinder of the Tempest.",
                TranscendReveal.shareLine("Ash", view));
    }
}
