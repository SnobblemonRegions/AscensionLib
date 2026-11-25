# Transcendent powers: 21 signatures, 25 twists, 25 riders (design for approval, 2026-10-09)

> 2026-11-25: 84 more signatures, one for every pair that includes a second-batch Unique, are **composed** from data. See docs/AFFIX-EXPANSION-DESIGN.md, part C.

Status: **approved by the owner 2026-10-09. The pulse system, the effect vocabulary, all 25 motif twists and all 80 bespoke recipe twists, all 25 riders and three pilot signatures (Phoenix Cinder, Eye of the Storm, Jackpot Titan) are built and simulator-tested. All 21 signatures are now coded in the simulator (`TRANSCEND_SIGNATURES`; see `BUILD-STATUS.md`, 2026-10-09), and a profile can hold a Transcendent (Fusion); the fallback to the two Uniques remains only for a signature id the module does not know.** Every number is provisional. It replaces, signature by signature, the battle effect first built for fusion (two Uniques at reduced strength; see [FUSION-DESIGN.md](FUSION-DESIGN.md)). Recipes, lore, harmony and names are unchanged.

## What the owner decided (2026-10-09)

1. A Transcendent is **a completely new power**. The two source Uniques' behaviours **vanish**. The item hover **shows the recipe** ("Ashen Heart + Last Breath") so players can see what it was made from.
2. The result keeps the **harmony bonus**.
3. It is shaped by the Pokemon: the **host** bends the power with a **twist**, and the **donor** adds a **small rider**.

## The shape of a Transcendent

```
Transcendent = Signature (from the pair of Uniques)
             + Twist     (from the host's primary motif)      fires on the signature's pulse
             + Rider     (from the donor's primary motif)     a small standing effect
             + Typed offence on the host's type, typed defence on the donor's type   (already built)
             all scaled by Harmony
```

- **Signature** (21, one per pair of Uniques): a new power with a core effect, a drawback, and a **pulse**, the one recurring event it is built around. 21 designs.
- **Twist** (25, one per motif): what happens on every pulse. It plugs into any signature, so 25 designs give 21 x 25 = 525 distinct combinations without 525 designs.
- **Rider** (25, one per motif): a small fixed effect built from an affix that already exists, so it needs no new simulator code.
- **Harmony** (unchanged table): the benefit share **P** (75 to 88 percent) scales the signature's core numbers, the twist magnitude and the rider; the drawback share **S** (55 to 78 percent) scales the drawback. Lineage and Pure fusions are the strongest and safest; Opposed are the strongest and most volatile.

Reference numbers below are at P = S = 100 percent. A typical Neutral fusion runs at 75 percent benefit and 60 percent drawback.

### The five pulses

| Pulse | Fires when | Hook already in the module |
|---|---|---|
| HIT | the holder lands a damaging move that deals damage | `spreadDamage` |
| STRUCK | the holder takes a damaging hit | `modifyDamage` / `holder.damage` |
| KO | the holder scores a KO | `faintMessages` |
| LAST | a lethal hit is about to faint the holder (once per battle) | `holder.damage` |
| TICK | the end of every turn | `residualEvent` |

A pulse fires the twist **at most once per turn** (a multi-hit move pulses once), so twists cannot chain into runaway stacking. Where a twist needs a target (the foe involved), HIT and STRUCK use the opponent in the exchange; KO uses the fallen foe's side; TICK uses every active foe.

## The 25 twists (host primary motif)

Magnitudes are at 100 percent and scale with P. A "stage" is a normal Showdown stat stage, applied through the engine's boost path so Clear Body, Mist, Contrary and the rest apply natively.

| Motif | Twist | On every pulse |
|---|---|---|
| EMBER | Scorch | 30% chance to burn the foe involved (if it has no status) |
| TIDE | Wash | cleanse the holder's status condition |
| GALE | Tailwind | +1 Speed stage for the holder (at most +2 from this) |
| VERDANT | Bloom | heal the holder 4% of max HP |
| FROST | Chill | -1 Speed stage for the foe involved |
| STORM | Static | 30% chance to paralyse the foe involved |
| STONE | Brace | +1 Defense stage for the holder (at most +2) |
| IRON | Plating | the holder takes 15% less damage from the next hit |
| VENOM | Taint | 30% chance to poison the foe involved |
| SPIRIT | Haunt | drain 4% of the foe's max HP into the holder |
| DREAM | Daze | 25% chance to confuse the foe involved |
| WILD | Frenzy | +1 Attack stage for the holder (at most +2) |
| HIVE | Sting | chip damage: 3% of max HP to every foe |
| FAE | Charm | -1 Attack stage for the foe involved |
| WYRM | Dragon's Breath | the holder's next damaging move deals 15% more |
| SHADE | Blind | -1 accuracy stage for the foe involved |
| RADIANCE | Dawn | +1 Sp. Atk stage for the holder (at most +2) |
| CHIME | Dissonance | -1 Sp. Def stage for the foe involved |
| GUARD | Ward | the holder shrugs off the next status condition inflicted on it |
| HARVEST | Feast | for 2 turns the holder's own healing moves heal 20% more |
| COSMOS | Warp | the holder's next damaging move cannot be resisted (treated as neutral at worst) |
| ANCIENT | Primeval Hide | the holder takes 10% less damage for 2 turns |
| ARTISAN | Refine | the signature's drawback is 20% weaker for 2 turns |
| TRICKSTER | Mimic | copy the foe's highest positive stat stage onto the holder (at most +2) |
| BRAWL | Break Guard | remove the foe's positive Defense and Sp. Def stages |

A twist whose condition is not met (nothing to burn, no status to cleanse, a stage already capped) does nothing and costs nothing.

## The 25 riders (donor primary motif)

Each rider is one **existing affix at a small fixed value** (scaled by P), added to the holder. The numbers are deliberately small: a rider should be noticed, not decisive. Riders do not count toward theme resonance (that is for ordinary slots only) and join the usual capped channels.

| Motif | Rider (existing affix) | Value | Motif | Rider (existing affix) | Value |
|---|---|---:|---|---|---:|
| EMBER | Smoldering | 10 | SPIRIT | Stubborn | 5 |
| TIDE | Restorative | 8 | DREAM | Status Guard | 4 |
| GALE | Swift Strike | 4 | WILD | Keen Edge | 5 |
| VERDANT | Steadfast Guard | 5 | HIVE | Rending | 8 |
| FROST | Opening Guard | 6 | FAE | Bracing Entry | 8 |
| STORM | Ensnaring | 6 | WYRM | Overwhelming Force | 3 |
| STONE | Physical Bulwark | 4 | SHADE | Opening Strike | 6 |
| IRON | Special Bulwark | 4 | RADIANCE | Healthy Force | 4 |
| VENOM | Venomous | 10 | CHIME | Momentum | 3 |
| GUARD | Iron Resolve | 4 | HARVEST | Triumphant | 8 |
| COSMOS | Super Effective Force | 4 | ANCIENT | Resilient Hide | 4 |
| ARTISAN | Kindred Force | 5 | TRICKSTER | Wardstone | 6 |
| BRAWL | Executioner | 6 | | | |

## The 21 signatures

Format: **Core** (benefits, scaled by P), **Clutch** (a once-per-battle save, where present), **Drawback** (scaled by S), **Pulse**. Drawback types are spread on purpose so that no two read alike: healing caps and cuts, self-damage, type weaknesses, weather dependence, slowness, damage taken, and a delayed price.

| # | Name (Uniques) | Pulse | Core | Clutch | Drawback |
|---|---|---|---|---|---|
| 1 | **Phoenix Cinder** (Ashen Heart + Last Breath) | STRUCK | Attackers that hit you while you are below 50% HP are burned (if unstatused). | A lethal hit leaves you at 20% HP, not 1. | Healing cannot raise you above 70% of max HP. |
| 2 | **Plague Pyre** (Ashen Heart + Creeping Venom) | TICK | Burned or poisoned foes take an extra 3% of max HP each turn. | | Your direct damage is 10% lower. |
| 3 | **Wildfire Crown** (Ashen Heart + Stormcaller) | HIT | Fire moves +20%; while any weather is active all your moves +10%. | | Water and Ice moves deal 25% less. |
| 4 | **Brand of Ruin** (Ashen Heart + Rupture) | HIT | A damaging hit Brands the foe for 3 turns: it takes 10% more from you and 30% more from burn and bleed ticks. | | A new Brand costs you 3% of max HP. |
| 5 | **Titan's Forge** (Ashen Heart + Titan's Heart) | HIT | A super-effective hit adds 8% of your max HP in damage and has a 25% chance to burn. | | You lose 3% of max HP each turn while above 60% HP. |
| 6 | **Gilded Ember** (Ashen Heart + 777) | KO | Burn damage you inflict +30%; each KO adds +10% damage (3 stacks, lost on switching out); item rewards +10%. | | You take 15% more from Water moves. |
| 7 | **Dying Bloom** (Last Breath + Creeping Venom) | STRUCK | Attackers that hit you while you are below 50% HP are poisoned; your poison ramps twice as fast then. | Survive a lethal hit at 1 HP. | Healing you receive is 35% lower. |
| 8 | **Eye of the Storm** (Last Breath + Stormcaller) | TICK | While any weather is active, heal 3% of max HP each turn and take 12% less damage. | Survive a lethal hit at 1 HP. | With no weather you take 12% more damage. |
| 9 | **Martyr's Edge** (Last Breath + Rupture) | HIT | Physical hits bleed; you deal 12% more to bleeding foes. | A lethal hit leaves 1 HP and your next hit is a guaranteed critical. | Your bleeds are weaker (stack weights 1, 0.8, 0.6). |
| 10 | **Colossus Vow** (Last Breath + Titan's Heart) | STRUCK | You take 15% less from super-effective hits and counter any super-effective hit for 6% of your max HP. | Survive a lethal hit at 1 HP. | Your Speed is 15% lower. |
| 11 | **Last Gamble** (Last Breath + 777) | KO | Item rewards +15%. | A lethal hit leaves you at 30% HP and your next 2 moves deal +30%. | After the clutch fires you take 25% more damage for the rest of the battle. |
| 12 | **Miasma Front** (Creeping Venom + Stormcaller) | TICK | While any weather is active every foe takes 2.5% of max HP each turn, rising 1% a turn to 5%; poison you inflict +20%. | | With no weather you lose 2% of max HP each turn. |
| 13 | **Festering Gash** (Creeping Venom + Rupture) | HIT | Physical hits bleed; each turn a foe bleeds its bleed damage rises 15% (to 60%); you heal 25% of the bleed damage dealt. | | You take 20% more from Psychic moves. |
| 14 | **Blighted Giant** (Creeping Venom + Titan's Heart) | HIT | A super-effective hit adds 7% of your max HP and poisons the foe; poison damage you inflict +25%. | | Healing you receive is 60% lower. |
| 15 | **Fortune's Rot** (Creeping Venom + 777) | TICK | Foes you have poisoned lose an extra 3% of max HP each turn; item rewards +15%. | | You take 20% more damage. |
| 16 | **Lightning Rend** (Stormcaller + Rupture) | HIT | Moves of the active weather's type, or Electric moves, deal +25% and cause a bleed whatever their category. | | With no weather your direct damage is 20% lower. |
| 17 | **Tempest Colossus** (Stormcaller + Titan's Heart) | HIT | Weather-type moves +20%; a super-effective hit adds 7% of your max HP. | | Your Speed is 12% lower. |
| 18 | **Skyfall Fortune** (Stormcaller + 777) | KO | Weather-type moves +25%; each KO while a weather is active adds +12% damage (3 stacks); item rewards +15%. | | With no weather you take 20% more damage. |
| 19 | **Breaker's Might** (Rupture + Titan's Heart) | HIT | Physical hits bleed; a super-effective hit adds 8% of your max HP, and +10% more if the foe is bleeding. | | Healing you receive is 50% lower. |
| 20 | **Wager of Blood** (Rupture + 777) | KO | Physical hits bleed; each KO restores 10% of your max HP; item rewards +15%. | | Every physical move you use costs you 2% of max HP. |
| 21 | **Jackpot Titan** (Titan's Heart + 777) | KO | A super-effective hit adds 10% of your max HP; item rewards +20%. | | You take 22% more damage. |

### Worked example

A Charizard host (EMBER) fused with a Gyarados donor (TIDE), Neutral harmony, making **Phoenix Cinder**: when something hits it below half health the attacker burns; a lethal blow once per battle leaves it at 15% (20% x 75%); healing is capped at 82% of max HP (the full 30-point cap at a 60% drawback share is 18 points). Every time it is struck, the **Scorch** twist adds a 22% chance (30% x 75%) to burn the attacker even above half health; as a **Tide** donor it gains **Restorative** at 6, and the usual 10% typed offence on Fire moves and typed defence against Water moves. Its name is "Cinder Phoenix Cinder of the Tide", which the fusion screen should simply display from the recipe.

### Power budget

- A signature's core should be worth about one Unique (about the strength of a rank V rare affix plus a clutch), a twist about one ordinary affix at rank II or III, a rider about one affix at rank I. The harmony table then moves the total between about 75 and 88 percent of the reference.
- Every number goes through the channels and caps that already exist (outgoing 100, incoming 50, healing 50, residual 100, proc 50). Percent-of-max-HP effects are bounded per turn so a stack of ticks cannot exceed 10 percent of a foe's HP in one turn.
- The harness measures each signature: its core in isolation, its drawback in isolation, and its scaling at P = S = 100 and at a Neutral and an Opposed share.

## What the player sees

- The Transcendent shows as one entry in the Unique row, in its own colour, with the name from the recipe.
- **Hover** shows, in order: the name; **"Fused from: Ashen Heart + Last Breath"** (the recipe, as decided); the signature's core, clutch and drawback with the numbers after harmony; the **twist** line ("On every strike: 22% to burn the attacker (Scorch)"); the **rider** line ("Restorative 6, from the Tide donor"); the typed offence and defence; and the harmony ("Neutral: 75% benefit, 60% drawback") with the species names and their lore notes.
- The fusion preview shows the same panel before the player confirms.

## How it is built

1. **Data** (`design/transcendents.json`): add `signatures` (id, pulse, core text and parameters, clutch, drawback, text), `twists` (25) and `riders` (25, each naming an existing affix id and value). `Transcendence.Transcendent` carries the signature id, twist and rider; `LoreCatalog`/tests prove every signature, motif twist and motif rider exists.
2. **Snapshot and payload:** `CombatSnapshot.Fused` carries the signature id, the twist motif, the shares, and the types; the rider is expanded into an ordinary affix effect on the Java side, so the module only needs the signature, the twist and the shares. The payload stays a single `transcendent` effect.
3. **Simulator:** a pulse dispatcher (the five hooks above, one place that decides "did a pulse happen this turn") and a table of signature handlers and twist handlers in `ascension-fx.js`, replacing the composed-Unique expansion. Signatures are written in batches; **a pair without a signature yet falls back to today's scaled composition**, so nothing breaks while the 21 are written.
4. **Tests:** a harness test per signature (core, drawback, clutch, scaling), one test per twist run against one signature, a matrix test that all 21 x 25 combinations load and stay inside the caps, and domain tests for the data. The simulator-only caveat stays: none of it is seen in a live battle until it is played.
5. **Rewards:** the item-reward parts of #6, #11, #15, #18, #20 and #21 are Java-side, scaled by P like the old 777 (they need the profile to hold a Transcendent).
6. **Order:** pulse dispatcher and the 25 twists with three pilot signatures (#1 Phoenix Cinder, #8 Eye of the Storm, #21 Jackpot Titan: one each for STRUCK, TICK and KO), then the remaining signatures in batches of about six, then the profile, store and screen.

## Owner answers (2026-10-09)

1. No second drawback: each signature has exactly one.
2. One twist per turn is fine.
3. A rider may double an affix the player already owns.
4. Twist and rider come from the primary motif only, never a secondary one.
5. **Every curated recipe (all 80) gets its own bespoke twist**, which replaces the motif twist. Listed below.

## The 80 bespoke recipe twists

Each is one or two effects from the same sixteen-effect vocabulary as the motif twists, so they are data in `tools/transcendent_powers.py` (written to `design/transcendents.json`), not code. Magnitudes are at 100 percent and scale with the harmony benefit share.

| Recipe | Twist | On every pulse |
|---|---|---|
| `primordial_clash` | Cataclysm | 4% max HP chip to every foe; +1 Defense stage (to +2) |
| `hand_of_creation` | Fold of Space-Time | next hit taken -20%; next move cannot be resisted |
| `taijitu` | Yin and Yang | drain 4% of the foe's max HP; next move +10% |
| `white_boundary` | Truth Frozen | 30% to burn the foe; -1 Speed stage for the foe |
| `black_boundary` | Ideal Frozen | 30% to paralyse the foe; -1 Speed stage for the foe |
| `crowned_pair` | Sword and Shield | next move +15%; next hit taken -15% |
| `wheel_of_life` | Life and Death | heal 4%; drain 4% of the foe's max HP |
| `eclipse` | Totality | -1 accuracy stage for the foe; +1 Sp. Atk stage (to +2) |
| `dusk_mane` | Prism Feed | +1 Attack stage (to +2); next move +10% |
| `dawn_wings` | Prism Veil | cleanse your status; -1 accuracy stage for the foe |
| `eon_bond` | Shared Dream | heal 4%; cleanse your status |
| `rainbow_tower` | Seven Colours | heal 3%; +1 Sp. Atk stage (to +2) |
| `original_and_copy` | Perfect Copy | copy the foe's best stat stage; strip the foe's Defense and Sp. Def stages |
| `ice_rider` | Rider of Ice | -1 Speed stage for the foe; next move +15% |
| `shadow_rider` | Rider of Shadow | -1 accuracy stage for the foe; drain 4% of the foe's max HP |
| `past_and_future` | Time Fold | -10% damage taken for 2 turns; next move +15% |
| `origin_and_shadow` | Distortion | strip the foe's Defense and Sp. Def stages; 25% to confuse the foe |
| `elder_tusk` | Mammoth Charge | +1 Attack stage (to +2); -10% damage taken for 2 turns |
| `wheel_of_donphan` | Rolling Armour | +1 Defense stage (to +2); next move +10% |
| `tusk_and_tread` | Tusk and Tread | next hit taken -15%; +1 Attack stage (to +2) |
| `lullaby_unbound` | Scream Lullaby | 25% to confuse the foe; -1 Sp. Def stage for the foe |
| `gift_of_tomorrow` | Gift of Tomorrow | heal 4%; -1 Speed stage for the foe |
| `sumo_machine` | Piston Slap | next move +15%; -1 Speed stage for the foe |
| `iron_hydra` | Three Heads Online | 3% max HP chip to every foe; strip the foe's Defense and Sp. Def stages |
| `foo_fighter` | Sun Probe | 30% to burn the foe; -1 accuracy stage for the foe |
| `dawn_of_moths` | Sun Dust | 3% max HP chip to every foe; 20% to burn the foe |
| `moth_across_ages` | Dust of Ages | -10% damage taken for 2 turns; 20% to burn the foe |
| `mecha_kaiju` | Stomp Protocol | next hit taken -15%; +1 Attack stage (to +2) |
| `paladin_oath` | Knight's Pledge | ward off the next status; next hit taken -15% |
| `paladin_blade` | Knight's Edge | ward off the next status; next move +15% |
| `flying_head` | Wail | 25% to confuse the foe; drain 3% of the foe's max HP |
| `ironsand_age` | Magnetic Storm | 30% to paralyse the foe; -1 Speed stage for the foe |
| `mold_beneath` | Spore Rot | 30% to poison the foe; heal 3% |
| `dragon_of_the_dream` | Dream Wyrm | +1 Speed stage (to +2); next move +15% |
| `purifying_wake` | Clear Tide | cleanse your status; heal 4% |
| `shining_blades` | Julienne | next move +20%; -1 Defense stage for the foe |
| `regenerated_pyre` | Reborn Flame | 30% to burn the foe; heal 3% |
| `thunder_lizard` | Thunderous Step | 30% to paralyse the foe; 3% max HP chip to every foe |
| `blade_of_the_cavern` | Spade Rush | +1 Defense stage (to +2); next move +15% |
| `crown_of_iron` | Crowned Resolve | next hit taken -15%; strip the foe's Defense and Sp. Def stages |
| `prehistoric_skies` | Ancient Updraft | +1 Speed stage (to +2); -10% damage taken for 2 turns |
| `two_golems` | Clay and Stone | +1 Defense stage (to +2); next hit taken -15% |
| `borrowed_face` | Borrowed Smile | copy the foe's best stat stage; heal 3% |
| `blade_and_pincer` | Mantis Cut | next move +15%; -1 Defense stage for the foe |
| `moon_shadow` | Moonlit Mimic | copy the foe's best stat stage; -1 accuracy stage for the foe |
| `weather_pact` | Pact of Sky, Sea and Land | next move cannot be resisted; 3% max HP chip to every foe |
| `legendary_flight` | Wings Unfurled | +1 Speed stage (to +2); -1 Speed stage for the foe |
| `roaming_beasts` | Brass Tower Rebirth | heal 4%; +1 Speed stage (to +2) |
| `titan_golems` | Seal Unbroken | next hit taken -20%; +1 Defense stage (to +2) |
| `lake_guardians` | Triple Lake | cleanse your status; ward off the next status |
| `swords_of_justice` | Sworn Blades | next move +15%; next hit taken -15% |
| `forces_of_nature` | Tempest Cycle | 25% to paralyse the foe; +1 Speed stage (to +2) |
| `guardian_deities` | Island Challenge | ward off the next status; heal 3% |
| `treasures_of_ruin` | Ruinous Aura | 3% max HP chip to every foe; -1 Sp. Def stage for the foe |
| `beyond_the_wormhole` | Ultra Space | next move cannot be resisted; -1 accuracy stage for the foe |
| `retainers_of_the_ogre` | Reversed Tale | 30% to poison the foe; copy the foe's best stat stage |
| `pseudo_summit` | Summit Power | +1 Attack stage (to +2); -10% damage taken for 2 turns |
| `kanto_triad` | First Partners | heal 4%; +1 Attack stage (to +2) |
| `johto_triad` | Johto Bond | heal 3%; next hit taken -15% |
| `hoenn_triad` | Hoenn Surge | +1 Speed stage (to +2); next move +10% |
| `sinnoh_triad` | Sinnoh Unity | +1 Defense stage (to +2); heal 3% |
| `unova_triad` | Unova Resolve | next move +15%; cleanse your status |
| `kalos_triad` | Kalos Elegance | +1 Sp. Atk stage (to +2); -1 accuracy stage for the foe |
| `alola_triad` | Alola Pride | +1 Attack stage (to +2); next hit taken -10% |
| `galar_triad` | Galar Champion | next move +15%; +1 Speed stage (to +2) |
| `paldea_triad` | Paldea Spirit | heal 4%; next move +10% |
| `kitsune_court` | Nine Tails | 25% to confuse the foe; 25% to burn the foe |
| `bushido_court` | Way of the Blade | next move +15%; strip the foe's Defense and Sp. Def stages |
| `dragon_gate` | Leap the Falls | +1 Attack stage (to +2); heal 4% |
| `moon_court` | Moonlight | heal 4%; -1 accuracy stage for the foe |
| `sun_court` | Sunlight | +1 Sp. Atk stage (to +2); 20% to burn the foe |
| `animated_earth` | Clay Awakened | next hit taken -20%; +1 Defense stage (to +2) |
| `halloween_hollow` | Trick and Treat | 25% to confuse the foe; heal 3% |
| `tea_ceremony` | Steeped | healing moves +20% for 2 turns; cleanse your status |
| `dessert_table` | Sweet Tooth | healing moves +25% for 2 turns; -1 Speed stage for the foe |
| `machine_choir` | Synchronised | 25% to paralyse the foe; +1 Speed stage (to +2) |
| `three_wise_monkeys` | Hear No Evil | ward off the next status; -1 Sp. Def stage for the foe |
| `fossil_revival` | Revived Might | -10% damage taken for 2 turns; +1 Attack stage (to +2) |
| `galar_fossils` | Mismatched Parts | copy the foe's best stat stage; 3% max HP chip to every foe |
| `mythical_dawn` | Elusive | -1 accuracy stage for the foe; ward off the next status |

## What is built (2026-10-09)

- **Data:** `design/transcendents.json` (schema 2) holds 21 signatures (pulse, core, clutch, drawback text), 25 motif twists, 25 motif riders, and a bespoke `twist` on each of the 45 pair and 35 group recipes. `tools/transcendent_powers.py` is the source and validates the effect vocabulary; `tools/build_fusion_data.py` writes the JSON.
- **Domain:** `PulseOp` (the validated effect vocabulary), `Transcendence` (resolves signature, twist and rider; a curated recipe's twist replaces the host motif's), `CombatSnapshot.Fused` (signature, twist, rider, shares, types), `BattleFx` (the rider as an ordinary affix at `value x benefit share`, the Transcendent as one effect with `sg` and `tw`). Tests: `TranscendentPowersTest` (9) and `TranscendentBattleTest` (8); domain 166 in all.
- **Simulator** (`ascension-fx.js`): the pulse dispatcher (HIT and STRUCK after `spreadDamage`, KO after `faintMessages`, TICK after the native residual; LAST is the clutch inside a signature), the sixteen effects, per-holder state for the next-exchange effects (shield, boost, unresisted, hide, heal boost, refine, ward), and the three pilot signatures. Payload validation: the signature must be one the module implements and the twist must pass the vocabulary check, otherwise the signature falls back (unknown signature) or the whole Transcendent is ignored (bad twist or shares).
- **Harness tests 36 to 44** (61 in all, all passing against the real unbundled simulator): Phoenix Cinder burn and clutch and healing cap, Eye of the Storm weather effects, Jackpot Titan damage, drawback and KO pulse, every effect, the next-exchange states, the once-per-turn cap, bad payloads, and that an effect armed by a move's own hit waits for the next move.

- **All 21 signatures** are implemented in `TRANSCEND_SIGNATURES` (harness tests 45 to 62, 79 in all, all passing). They share three pieces of infrastructure: the bleed engine (Rending, Rupture and the signatures bleed through one `inflictBleed`, with per-bleed stack weights, a per-turn growth and a leech share), hooks into the burn/poison tick (`statusBoosts` raises the inflictor's tick, `statusVictimMult` is what the victim makes of it, used by Brand of Ruin), and a forced critical hit for Martyr's Edge. A signature declares `hooks: {status, bleed}` so the module only installs the wrappers a battle needs. A Fire-type holder's burn chance is naturally zero (Titan's Forge on a Fire foe never burns), which is the engine's own rule.

## Still to build

1. A live test of everything fusion: the screen, donor removal, a Transcendent in a real battle, the loot scaling.
2. A hover on the Unique line of the inspect screen with the full text (today the name carries "(fused from A + B)" and the detail is on the fusion screen).
3. Client display of resonance and themes, and icons or text for the 7 mechanic affixes.
