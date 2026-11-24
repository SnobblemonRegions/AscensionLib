# Fusion (Transcendence): lore research and recipe book (2026-10-09)

> **Superseded in part (2026-10-09):** the owner decided a Transcendent is a completely new power, not two Uniques at reduced strength. The design is in [TRANSCENDENT-POWERS.md](TRANSCENDENT-POWERS.md). The battle effect described under "What exists" is the interim fallback for the 18 signatures not yet written; recipes, lore, harmony and names below still stand.

Status: **the research, the data, the recipe resolver and the battle effect are built. The fusion action itself (profile, store, screen) is not.** Nothing here has been seen in a live game. Every number is provisional. Read with [DEPTH-DESIGN.md](DEPTH-DESIGN.md), part C.

## Rules (owner decisions, 2026-10-09)

- Two Pokemon that each hold a Unique are fused. The **left slot is the host and is kept; the right slot is the donor and is consumed.** The two Uniques become one **Transcendent** power in the host's Unique slot.
- The result is shaped by both Uniques and by both Pokemon, and is **deterministic and previewed**: there is no hidden roll.
- Recipes cover **every Cobblemon species** (1025 in Cobblemon 1.8.1), built from researched lore.

Proposed, not yet decided: the host must be Mythical with all ten upgrade credits spent (decided 2026-10-09: the first draft, every slot at rank V, needs 24 credits on six slots when only 10 can be earned); the donor Epic or higher; both the player's own and not craft-locked; a Pokemon transcends once; cost 600 dust, 30 facets, 10 cores, 150 lifetime attunement on the host and one Catalyst (about 67 keyed Tower runs of cores). The donor is permanently removed behind an explicit confirmation.

## The research

For each of the 1025 species in the Cobblemon 1.8.1 jar, the following was gathered, as research input only (the raw text is not in the repository):

1. **Species data** from the Cobblemon jar: types, labels (legendary, mythical, paradox, ultra beast, fossil, baby), evolutions.
2. **In-universe lore** from PokeAPI: the English Pokedex entries of every game version, genus, habitat and shape.
3. **Origin and biology** from Bulbapedia: what each Pokemon is based on (an animal, a myth, an object, a legend), pulled from each page's Origin and Biology sections.

Every species was then reviewed by hand, one at a time, in 11 batches. A keyword scorer proposed motifs from the text and the types; each proposal was corrected from the origin text and general knowledge of the franchise, and a short note was written **in our own words** on what the Pokemon is based on (for example, Gyarados: "carp that climbs the falls and becomes a dragon"). Limits, stated plainly: the notes are a design summary, not citations; the sources were not cross-checked species by species beyond that reading; some origins are speculative on the wiki itself ("may be based on"), and the notes follow the most plausible one. A motif or note a designer disagrees with is an ordinary edit to `design/lore/species-lore.json`.

### Motifs

A species carries one to three motifs, strongest first. There are 25: EMBER, TIDE, GALE, VERDANT, FROST, STORM, STONE, IRON, VENOM, SPIRIT, DREAM, WILD, HIVE, FAE, WYRM, SHADE, RADIANCE, CHIME, GUARD, HARVEST, COSMOS, ANCIENT, ARTISAN, TRICKSTER, BRAWL (`design/lore/motifs.json`, with a name adjective and noun for each, 39 *kindred* pairs and 8 *opposed* pairs). Primary motifs lean elemental (TIDE 135 species, VERDANT 96, STONE 80, EMBER 70) because most species are; GUARD and WILD appear mostly as secondary motifs. Every motif is used.

## How a result is chosen

Most specific first:

1. **A curated pair recipe** naming the two species (45 recipes, either order). These are the legendary and mythical rivalries and bonds (Kyogre and Groudon, Reshiram and Zekrom, Xerneas and Yveltal, Solgaleo and Lunala, ...), and the Paradox Pokemon paired with the Pokemon they are based on (Great Tusk and Donphan, Iron Moth and Volcarona, Iron Thorns and Tyranitar, ...), which comes straight from the Origin research.
2. **A curated group** both species belong to (35 groups, any two distinct members): the legendary trios and quartets, the nine starter triads, fox spirits, samurai, the dragon gate, the moon and sun courts, haunted objects, machines, fossils, desserts and more. Lists are in `tools/build_fusion_data.py`.
3. **A derived recipe** from the two species' lore, for everything else.

About 1,976 of the 1,050,625 ordered species pairs have a curated recipe. All the rest are derived, deterministically.

Every result starts from the **base Transcendent of the two Uniques** (21 of them, one per pair; `bases` in `design/transcendents.json`) and takes a **harmony**:

| Harmony | When | Benefit kept | Drawback taken |
|---|---|---:|---:|
| LINEAGE | same evolutionary family | 85% | 55% |
| PURE | same primary motif | 82% | 58% |
| KINDRED | kindred primary motifs, or a shared motif | 78% | 60% |
| NEUTRAL | no relation | 75% | 60% |
| OPPOSED | opposed primary motifs (fire and water, sun and shadow, ...) | 88% | 78% |

Better fit keeps more benefit and takes less drawback; **opposed fusions are volatile**: the most benefit and the most drawback. The principle: two benefits at about 75 to 88 percent are stronger than either Unique alone and weaker than both at full strength, and the drawbacks still bite. Across all ordered species pairs, outcomes come out about 61 percent neutral, 28 kindred, 6 pure, 5 opposed and 0.2 lineage, curated recipes included.

**Names.** A derived name is `{host adjective} {base name} of the {donor noun}`, for example "Tidal Phoenix Cinder of the Tempest" when a Gyarados host takes in a Pikachu donor (Gyarados is TIDE, Pikachu is STORM). A curated name is `{base name} of {recipe name}`, for example "Phoenix Cinder of the Primordial Clash". A lineage fusion is `{base name} of the Bloodline`. The blurb quotes both species' notes.

**Type shaping (proposed).** The host's primary type is the Transcendent's offence and the donor's its defence: a fixed typed damage bonus on the host's type and a fixed typed reduction on the donor's type, through the existing typed channels. The resolver already returns `hostType` and `donorType`; nothing applies them yet.

## What exists

- `design/lore/species-lore.json` (1025 species: Cobblemon id, evolutionary family, types, motifs, note), `design/lore/motifs.json`, `design/transcendents.json` (scales, 21 bases, 45 pairs, 35 groups).
- `tools/build_fusion_data.py` regenerates `motifs.json` and `transcendents.json` and validates every curated species name.
- `domain/v1`: `LoreCatalog`, `Transcendence` (the resolver), `CraftException.Reason.UNKNOWN_SPECIES`; `TranscendenceTest` has 15 tests, including a full 1025 by 1025 run (every pair resolves, every harmony occurs, neutral stays under 70 percent).
- **The battle effect** (built 2026-10-09, simulator-tested only). `CombatSnapshot` carries an optional `Fused` (two Unique ids, the benefit and drawback shares, host and donor type; a snapshot holds a Unique or a Transcendent, never both); `Transcendence.Transcendent.toFused()` produces it; `BattleFx` sends it as one effect `{"i":"transcendent","u":[a,b],"b":78,"d":60,"t":"Fire","r":"Water"}`. `ascension-fx.js` validates it (two different known Uniques, both shares whole percents 1 to 100, known types, otherwise the whole effect is ignored; one per Pokemon) and expands it into the two Uniques plus two internal typed effects (`transcend_offense` +10 percent on the host type in the outgoing channel, `transcend_defense` -10 percent on the donor type in the incoming channel, capped with everything else; a payload cannot name those two directly).
  - **How each Unique scales.** A drawback multiplier moves toward 1 by its share: 0.85 at a 50 percent share becomes 0.925, 1.25 becomes 1.125. Ashen Heart: burn bonus x benefit share, direct-damage penalty softened. Stormcaller: weather bonus x benefit share, no-weather penalty softened. Creeping Venom: ramp step and maximum x benefit share, Psychic penalty softened. 777: damage-taken penalty softened. Titan's Heart: extra damage x benefit share, and (1 - drawback share) of each heal lands instead of none. Last Breath: a binary benefit becomes a chance equal to the benefit share, once per battle; the healing penalty is softened. Rupture: every physical move bleeds becomes a chance equal to the benefit share; the stack weights 1, 0.8, 0.6 move toward 1.
  - **Not scaled here:** 777's item-reward bonus (a Java-side reward, to be scaled by the same benefit share when a Transcendent exists in a profile).
  - Tests: `validation/showdown/ascension_fx_test.js` 52 (7 new, tests 29 to 35), `TranscendentBattleTest` 8.

## What does not exist yet

Nothing can produce a Transcendent in a real game yet, because a profile cannot hold one:

- The **profile and store**: a Transcendent in the Unique slot is a profile schema 2, a fusion craft kind that removes the donor in the same transaction as the host change and the material debit, and the usual operation-ID replay. Not written.
- The **fusion screen** (left slot, right slot, preview of name, benefit, drawback and cost, confirmation), the eligibility and cost checks, and the recipe-book display.
- Playtesting. The harmony scales, the costs and every curated recipe are untested guesses.

## Open questions

1. Do the proposed eligibility rules and the cost feel right, or should a donor of any rarity be allowed?
2. Is a lineage fusion (a Pokemon and its own pre-evolution) allowed? It currently is, and is the best-fitting kind.
3. Should discovered recipes be recorded, so the screen shows a recipe book that fills in, or should previews always be open?
4. The 45 pair recipes and 35 groups are a first batch. Which others matter to you (rival trainers, anime pairs, regional legends)?
5. May a Transcendent ever be fused again, as host or donor? (Proposed: no. A Pokemon transcends once and its Transcendent cannot be a donor.)

## Fuser bond with Project Fusion (owner decision 2026-11-20, "permanent bond")

With Cobblemon Project Fusion installed, Transcendence is made at its Fuser, together with the physical fusion, for good.

- The Fuser fuses only two Pokemon that each hold a Unique (owner rule 2026-11-20): any other pair is refused with "Both Pokemon must hold a
  Unique to be fused." before Project Fusion takes its fee (same hook as the loan guard, `FusionService.validate`). Wild fusions are untouched.
- Onboarding: until a player dismisses the page's one-time intro, opening an idle Fuser shows "Fuse two Pokémon that each hold a Unique
  to make a Transcendent." in the Fuser screen's own status line (hook on Project Fusion's three-argument `open`). The page's intro
  (three steps, Got it, How it works) and the refusal text ("...Install one with a Unique Catalyst on the Craft page.") do the rest.
- The player picks a body and a head on the Fuser's screen and presses Fuse. AscensionLib stops the Fuser's start (refunding Project
  Fusion's fee) and opens this fusion page in Fuser mode on that pair: body = host, head = donor. Swap trades them.
- **Transcend for good:** the usual rules and price (`ProfileService.bond`, store `bond`), recorded in the `bonds` table with the price
  paid; then the Fuser starts as Project Fusion would. The donor is not consumed: it lives on as the fusion's head.
- **Plain fusion:** Project Fusion's own fee and an ordinary, separable fusion.
- A bond can never be split (Project Fusion's split is refused). Genome Shift still works and trades the Transcendent's offence and
  defence types (`CombatSnapshot.withSwappedTypes`, applied when the battle snapshot is taken).
- If the Fuser cannot start the bond is undone at once; if it never finishes (server stopped, failure) Project Fusion's refund hook
  undoes it and refunds the recorded price (`store.unbond`); the login sweep catches a bond partner found unfused in storage.
- The page alone no longer makes a Transcendent while Project Fusion is installed; without it the original consuming path is unchanged.

Code: `compat/bond/FuserBond`, `mixin/ProjectFusionBondMixin`, `mixin/ProjectFusionRefundMixin`, `compat/ProjectFusionGuard` (start-up
check: every hook must have applied or the bond stays off). Compiled against Project Fusion 2.1.0 only (`ascensionlib/libs/README.md`).

### Player experience (built 2026-11-20)

- **Inspect, Transcendence panel** (`scout/TranscendView`, `inspectTranscend`): readiness as host and donor rule by rule (`FusionRules.readiness`),
  named recipes the species belongs to (`Transcendence.recipesFor`), or for a fusion its head, rarity, Unique and whether it is bonded.
- **Fusion page:** a Swap hint when the donor would make a valid host; the price shortfall itemised with where each material comes from;
  a "named recipe" line for curated pairs.
- **Reveal on collect** (`fusion/TranscendReveal`, `transcend.html`, hook on `FuserBlockEntity.collect`): body and head meet, the name and
  power appear; "Share in chat" is the opt-in announcement (once per bond).
- **Fuser status line** after the intro: "A Transcendent bond in your party can never be separated." when the party carries a bond.
- **Telemetry:** `encounter` events carry `teamFusions` and `teamTranscendents`; session party entries carry `fusion`, `transcendent`, `bonded`.
