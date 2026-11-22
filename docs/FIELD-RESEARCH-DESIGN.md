# Field Research (design, 2026-11-22)

Status: **design only, nothing built.** Every number is provisional until the owner approves it and it has run through the balance sim
(`-PsimSinks`). Background: `reports/Research crafting across companion mods.md` (decisions 1-19) and its notes in
`research_notes/Research crafting across companion mods/`.

Field Research is a progression system **separate from Ascension profiles and affixes**. A player researches Pokemon families; research
unlocks craftable serums for wild catches, raid intel and a better raid catch chance, and ties Cobblemon, CobbleRaids, CobbleTowers and the
Abandoned Laboratories together.

## Owner decisions (2026-11-22)

| # | Decision |
|---|---|
| 1 | Field Research, as below. |
| 2 | Serums may set a floor on IVs **at the moment a wild Pokemon is caught**, once. Owned Pokemon are never edited afterwards. Needs a live test of Cobblemon's capture event before building (DESIGN.md's "no permanent edits to native combat stats" is relaxed only for this). |
| 3 | One meter per **evolution family**, fed by every species in it, each species capped at its share, **weighted toward later stages**, so every stage must be studied to max the family. |
| 4 | Research is **never spent**: it unlocks things; crafting costs materials. |
| 5 | Research comes from the **Research Bench, raids and Laboratories** only (not wild catches, not towers). |
| 6 | **All three serums** at launch: Floor, Focus, Prime. |
| 7 | No serum on raid catches, gifts, eggs, legendaries, mythicals, Ultra Beasts or **Ditto**. |
| 8 | Serums are **tradable vouchers** (the wallet's voucher system). |
| 9 | A serum catch gets the persistent key `ascensionlib_serum` (never rename). |
| 10 | The Bench is a **block with a capsule that shows the Pokemon**: a display copy; the real Pokemon stays in the party/PC, locked, with instant recall. |
| 11 | Raid wins give **Boss Samples**; research level 2 unlocks the **Boss Dossier**. |
| 12 | **Research Beacon** in the last phase: a solo boss fight with one attempt; a group may fight only if every participant has that species fully researched; Beacons and Boss Samples are **not tradable**; **one Beacon per player per day**. |
| 13 | Towers: the Prime serum costs a Tower Mark; no tower reveals. |
| 14 | Request the CobbleTowers changes, in order (scored-trial flag, Field Kit starting relic, Specimen Challenge modifier). |
| 15 | Full Laboratory tie-in. |
| 16 | Not applicable (no wild research). |
| 17 | **Keep the old names**: tables `research_awards` and `research_days` (from docs/ARCHITECTURE.md); the old daily "research" capture payment stays in the docs, unbuilt. |
| 18 | Build order: rules first (below). |
| 19 | Analyzed Boss Samples raise the raid catch chance for that **species**: `min(base x (1 + boss research), 25%)`. |

## Research

- **Family meter:** 0 to 200 points; levels at **10, 40, 100, 200**. Each species in the family can add at most its share of 200:
  one stage 100%; two stages 40/60%; three stages 25/30/45% (Gible 50, Gabite 60, Garchomp 90). Families with branches (Eevee) split the
  last-stage share evenly between branches. Research a species beyond its share and the excess is wasted (the page says so before a study).
- **Sources** (to the studied species' share):

| Source | Points |
|---|---|
| Bench quick study (20 min) | +3 (was +10) |
| Bench standard study (8 h; 6 h for a Pokemon caught in a Laboratory, i.e. carrying `ascensionlib_zone_tier`) | +10 (was +40) |
| Raid win against the boss (starter / powerhouse / legendary / mythical) | +5 / +8 / +10 / +13 (was 20/30/40/50), plus one Boss Sample |
| Laboratory Research Terminal: Data Drive (first search, per player) | +10 (was +40) to any species the player chooses |
| Laboratory Specimen Locker (first search, per player) | +4 (was +15) to a lab-guard family (Porygon, Magnemite, Voltorb, Klink, Ditto) |

- **Boss research** (separate, per species, 0 to 100%): analyzing one Boss Sample at the Bench (a 20-minute analysis) adds **+10%**.

## What research unlocks

| Level | Unlock |
|---|---|
| 1 (10) | The family's research page: lore, habitat notes |
| 2 (40) | Floor Serum recipe; Boss Dossier for the family's raid bosses (type weaknesses and the fixed four-move set, read from the raid definition) |
| 3 (100) | Focus Serum recipe |
| 4 (200) | Prime Serum recipe; Research Beacon (last phase) |

## Serums

Crafted at the Bench page, delivered as tradable vouchers. Only a player with the recipe level on that family can arm one (follow-up 1). Armed for one family; used up on the player's **next wild catch** of that family
(any species in it). Values only go up and apply after Cobblemon's own bait effects.

| Serum | Recipe level | Effect | Cost | Craft limit |
|---|---|---|---|---|
| Floor | 2 | One chosen stat at least 20 | 60 dust | 5 a day |
| Focus | 3 | Two chosen stats at least 25 | 150 dust + 3 Facets | 2 a day |
| Prime | 4 | One chosen stat 31 + one random stat 31 | 300 dust + 1 Boss Sample + 1 Tower Mark | 3 a week |

The catch gets `ascensionlib_serum` (which serum). Not on the exclusions in decision 7. Breeding (Destiny Knot) will spread serum IVs:
accepted with the Ditto exclusion; watch it in telemetry.

## Raid catch bonus (needs CobbleRaids)

Before CobbleRaids rolls a capture, it asks AscensionLib (by reflection, fail-soft) for a multiplier `1 + boss research` (1.0 to 2.0) for
that player and species, multiplies its chance and caps it at 25%. Today's odds (base 8/6/4/2%, perfect play 12/10/7/4%) become at most
16/12/8/4% and 24/20/14/8%. Raid catches keep fresh random IVs; Raid Points are never spent; boss HP is never touched.

## Research Bench

A Polymer block (optional dependency, like the Reclaimer and Altar) that opens a WebGUI page. Putting a Pokemon on it writes a job row and
a lock in one store transaction; the real Pokemon stays in the party/PC, locked from battle, trade, release, fusion, the Reclaimer, auction
listing and crafting, and can be recalled at any time (a recalled study pays nothing: follow-up decision 3).
The capsule shows a **display copy**: a client-rendered Pokemon entity with no AI, not saved with the chunk, not catchable or battleable,
removed when the study ends or the chunk unloads. One study per player at a time at launch. Finished studies wait; no reminders.
Recipe: Laboratory Terminal items (Up-Grade, Dubious Disc, Electirizer) plus vanilla parts, crafted on a page (vanilla recipes cannot use
custom-data items; Cobblemon's items are registered items, so a server recipe may work: check).

## Research Beacon (last phase)

Unlocked at family level 4; consumed to start an AscensionLib-owned raid (CobbleRaids encounter API) against a researched species. Solo,
one attempt. A group may join only if every participant has that species fully researched (family level 4 and 100% boss research). Pays research and a Boss Sample, **not** raid
loot (`raidRewards` off). Beacons and Boss Samples are bound (not tradable). One Beacon per player per day (`research_days`).

## Storage

`research_awards` (player, species, points; player, species, boss research) and `research_days` (player, day, counters for serum crafts,
Beacons) plus a `research_jobs` table for Bench studies, each created `IF NOT EXISTS` without a schema bump; every change is one transaction
keyed by operation ID (retries replay). Serums use the voucher tables.

## Upstream requests (owner approval needed before anyone edits those mods)

- **CobbleRaids:** the capture-multiplier hook above.
- **CobbleTowers**, in order: (1) a scored-trial flag on `declareEnemy`; (2) a Field Kit starting relic; (3) a Specimen Challenge modifier.

## Build order

1. Domain rules (`FieldResearch`: shares, levels, serum effects, caps) and a balance-sim run of the costs.
2. Store tables.
3. Earning: Bench jobs, raid wins (`declareEnemy`/`settleRaidAttunement`), Laboratory claims; the research page.
4. Serums, after a live test of writing IVs in Cobblemon's capture event.
5. The Bench block with its capsule.
6. Boss Dossier; Boss Sample analysis; the CobbleRaids catch hook (upstream).
7. Research Beacon and the CobbleTowers requests.

## Follow-up decisions (owner, 2026-11-22)

1. **Using a serum needs the research too.** A traded serum voucher can only be armed by a player who has the serum's recipe level on that
   family (Floor: level 2, Focus: level 3, Prime: level 4). Research gates both crafting and use.
2. **"Fully researched" for a group Beacon** means both: family level 4 **and** 100% boss research on that species, for every participant.
3. **Recalling a Pokemon mid-study** loses that study's progress (and any fee).

## Step 1 as built (2026-11-22, branch `feature/field-research`, uncommitted)

Pure domain rules, no Minecraft, in `domain/src/main/java/com/cobbleascend/domain/v1`:

- `ResearchFamilies`: families built from each species' pre-evolution; stage weights 100 / 40-60 / 25-30-45 (longer chains in proportion);
  shares always sum to exactly 200; branches split a stage evenly (remainder to the last, sorted). Never throws on odd data: an unknown
  pre-evolution makes a root, a cycle makes each member a root; both are reported for the log. Order of input never changes the result.
- `ResearchLedger`: one player's points per species and boss research per species. Credits cap at the species' share (excess reported as
  wasted, ledger unchanged); a family is always recomputed from capped species, so a catalog change can never leave a meter above 200;
  values are never lowered; stored data is cleaned on load (case variants merged by the largest, never summed; negatives dropped; boss
  research clamped to 100). An analysis at 100% throws so the caller refuses before a sample is consumed.
- `FieldResearch`: levels (10/40/100/200), sources and amounts, raid research by tier, deterministic store operation IDs per (kind, key,
  player) with length-prefixed parts (no key can forge another event's ID), the raid catch chance (`min(base x (1 + boss%), 25%)`, never
  lowers a chance, a base above 25% is left alone, NaN/negative treated as 0), the Dossier gate, Beacon rules (owner needs family level 4 +
  100% boss research + a Beacon left today; every group member fully researched; a player listed twice is refused), the Specimen Locker's
  guard species (seeded per container and player), UTC days and Monday-start UTC weeks.
- `Serums`: kinds with ids `floor_serum`, `focus_serum`, `prime_serum` (never rename), craft gate (recipe level on any family), per-player
  limits per UTC day/week with overflow-safe counting, arm gate (recipe level on the target family, right number of distinct stats, one armed
  per family, excluded families refused), and the capture-time floor: only wild catches, never raid/gift/egg, never legendary, mythical,
  Ultra Beast or Ditto, family and level checked again at the capture, IVs clamped before use, values only go up to at most 31, Prime's
  random stat drawn among the others below 31 from a seed of the capture (a retried capture writes the same IVs). An eligible capture uses
  the serum even when the floors were already met, so one serum cannot ride through many captures.
- `BenchStudies`: Quick (20 min, +10), Standard (8 h, 6 h for a Laboratory catch, +40), Analysis (20 min, one Boss Sample of the species,
  +10% boss research). One study per player and per Pokemon; a full share or 100% boss research is refused before any time or sample is
  spent; the reward is fixed at the start; server clock only (a clock running backwards only delays); an end time that would overflow is
  refused; paid once per job by its operation.

Tests: `ResearchFamiliesTest`, `ResearchLedgerTest` (including 20,000 random operations that must never lower a meter or pass a cap),
`FieldResearchTest`, `SerumsTest`, `BenchStudiesTest`: 46 new, 347 domain tests pass. Two real bugs were caught while writing them: the
operation ID could be forged by a key containing the separator, and `List.of(...).contains(null)` throws (a stat list with a null crashed
the arm check instead of refusing).

**For step 2 (store):** unique keys for one study per player and per Pokemon; craft counters in `research_days` inside the craft
transaction; arming takes the serum out of the wallet in the same transaction; a **disarm** that returns the serum (an armed serum whose
family disappears in a Cobblemon update would otherwise be stuck); the capture consumes the serum once, keyed by the caught Pokemon.

### Balance sim (`./gradlew :balance-sim:simulate -PsimResearch=true [-PsimResearchScale=25]`)

`ResearchSink` runs the real domain rules: 60 synthetic three-stage families (40 with a raid boss), 60% of players researching, one
overnight study a play day, 1.5 quick studies or analyses an hour of play, 0.7 Laboratories a week, a serum for up to 3 wild catches a
play day, crafted above a reserve (1,500 dust, 30 facets, 5 Tower Marks). Every meter, share and craft limit held; the ledger balances;
research mints nothing.

| Research grants | First level 2 | First level 3 | First level 4 | Level-4 families at day 90 | Spent per researcher per day |
|---|---|---|---|---|---|
| 100% (current numbers) | day 1 | day 4 | day 7 | 15.8 | 188 dust, 2.2 facets, 0.18 Marks |
| 50% | day 2 | day 6 | day 11 | 9.0 | 187 dust |
| 25% | day 4 | day 11 | day 21 | 4.4 | 185 dust |
| 10% | day 11 | day 27 | day 52 | 1.2 | 170 dust |

- Research raises the late-game sink ratio (last 30 days, spent / minted) from 0.04 to **0.20 for dust** and 0.04 to **0.16 for facets**;
  the median dust held at day 90 falls from about 50,000 to 33,000. Promotion pacing is unchanged (players promote first).
- The sink does not depend on the research pace (serum demand sets it), so the pace can be slowed without weakening the sink.
- At the current numbers research is **too fast**: a family reaches level 4 in a week, before a Regular player's first Mythic (day 11).
- Raids waste most of their research: a raid boss is a final-stage species (share 90), which fills after a few raids; 933,000 points were
  wasted in the 100% run.

### Decisions this raised: answered (owner, 2026-11-22)

1. **Research pace: 1a.** Every grant is a quarter of the first numbers: Bench Standard 10, Quick 3, raid 5/8/10/13 by tier, Data Drive
   10, Specimen Locker 4. Rerun at the new defaults: first family at level 2 on day 4, level 3 on day 11, level 4 on day 21; 4.4 families
   at level 4 per researcher by day 90; the sink is unchanged (dust 0.20, facets 0.16).
2. **Raid research on a full share: 2b.** It is wasted, as built (a raid boss is a final stage; its share fills first).
3. **Prime's Boss Sample: 3a.** Any species' sample, as built.

The tables in "Research" and "Step 1" above show the first numbers; the code now uses these.

## Step 2 as built (2026-11-22, uncommitted)

**Serum items.** Materials `floor_serum`, `focus_serum`, `prime_serum` (`MaterialId`, never rename), tradable as vouchers like every other
material. Bases are only crafting ingredients, with no right-click use, not furnace fuel and not brewing ingredients: popped chorus fruit
(Floor, model 8115), rabbit hide (Focus, 8116), turtle scute (Prime, 8117); vouchers 9115-9117. Dragon's breath was ruled out because
Cobblemon brews it into an Ability Capsule. Art: `tools/pixel_art.py` `serum()`, a corked vial told apart by colour, one/two/three measuring
bands and Prime's sparkle (first pass; concepts welcome). Generated with `tools/build_resourcepack.py`; only the new files were kept.

**Store** (`ProgressionStore`, section "Field Research"; tables created when missing, no schema bump, so an older build still opens the file):

| Table | Holds | Guarantee |
|---|---|---|
| `research_awards` | points and boss research per (player, species) | `CHECK` points >= 0, boss 0..100 |
| `research_days` | serum crafts and Beacons per (player, counter, UTC day or week) | counted inside the craft transaction |
| `research_items` | bound Boss Samples (per species) and Data Drives | `CHECK` count >= 0; a take that would go below 0 refuses |
| `research_jobs` | one running study | `UNIQUE` player and `UNIQUE` Pokemon |
| `research_armed` | one armed serum per (player, family) | primary key |
| `research_serum_uses` | the IVs a serum promised a caught Pokemon, and whether they were written | primary key = the Pokemon: one serum per capture |

Calls (each one transaction; refusals throw `ResearchException` before anything is written, so nothing is recorded and the request can be
made again): `grantResearch`, `settleResearchRaid` (research + 1 Boss Sample, operation per boss entity and player), `grantResearchItem`,
`useDataDrive` (refused on a full share, drive kept), `startResearchStudy` (facts read from the database, an analysis takes its sample at the
start), `collectResearchStudy` (server clock; paid once under the study's own operation), `recallResearchStudy` (pays nothing),
`craftSerum` (research gate, period limit, wallet revision, samples from species already at 100% first), `armSerum` (research on that
family; the serum leaves the wallet), `disarmSerum` (always allowed, also for a vanished family), `useArmedSerum` (consumes the serum and
records the IVs, keyed by the Pokemon), `unprojectedSerumUses` / `markSerumUseProjected` (a crash between the commit and the IV write is
repaired by writing again; the write only raises values). Reads: `researchLedger`, `researchItems`, `researchStudy`,
`researchStudyOfPokemon`, `pokemonInResearch` (for the runtime's locks), `armedSerums`, `researchCounter`, `serumUse`.

Tests: `ResearchStoreTest` (26): a raid reported twice pays once; a refused request records nothing; a reused operation for a different
request is refused; studies pay once on the server clock, one per player and per Pokemon; full shares and complete boss research are refused
before time or a sample is spent; the daily limit holds across calls and under 8 threads crafting at once; a short wallet or a stale one
changes nothing; Prime's weekly limit and sample order; crashes before the commit leave nothing and after it replay; a bought serum is
useless without the research; disarm is idempotent; a capture consumes the serum once and repeats return the same IVs; another family, a
raid catch or another player's catch leaves it armed; data survives a restart with the schema version unchanged.

**Open for later steps:** the runtime must refuse battle, trade, release, fusion, Reclaimer and auction listing for `pokemonInResearch()`;
the IV write at capture still needs its live test; withdrawing one serum as a voucher costs a second one (the 3% fee rounds up to 1, as for
every material).

## Step 3 as built (2026-11-22, uncommitted): research in the running game

New package `ascensionlib/src/main/java/com/ascensionlib/research`:

- `ResearchCatalog`: the family catalog from Cobblemon's species (resource path ids, after datapacks), rebuilt on data-pack reload; species
  labels (legendary, mythical, ultra_beast) for later steps. Any id a mod passes (`cobblemon:garchomp`, `Garchomp`) is normalised.
- `FieldResearchRuntime`:
  - **Raids.** `AscensionRewards.settleRaidAttunement` (CobbleRaids' existing call on every ordinary win) and the Threat path of `settleRaid`
    call `raidWon`. It reads the boss species and level that CobbleRaids declared for the same encounter (`raid:<boss UUID>`, checked in
    CobbleRaids' `AscensionLibArming`; the settlement runs before the encounter ends) and pays each participant research by tier plus one
    Boss Sample, keyed by (encounter, player). CobbleRaids does not pass its tier, so it is inferred: level up to 40 starter, up to 75
    powerhouse, above that mythical when Cobblemon labels the species mythical, otherwise legendary (renowned bosses are ten levels higher).
    No CobbleRaids change.
  - **Laboratories.** `LabContainers.search` calls `labSearched` on every search, first or repeated: the Research Terminal gives a Data Drive
    and a Specimen Locker research on a guard species, each keyed by (container, player), so a repeat search, or one after a crash, never pays twice.
  - **Bench studies** (`start`, `collect`, `recall`; the Bench block will call them, step 5). Start checks what needs the game: the Pokemon is
    the player's, in their party or PC, not a battle clone, not lent, and the player is not in a battle. Collect first checks the Pokemon is still
    the player's: if it left (sold, moved by another mod) the study ends unpaid.
- `ResearchLocks`: the Pokemon on the Bench (mirrored from `research_jobs`, loaded at start) cannot start a battle (the battle is refused with a
  message), be sent out, ride a shoulder, be traded, released or fused (`ProfileService.fuse`/`bond`, the fusion page). Crafting upgrades stays
  allowed: the study's research is fixed at its start. The locks are not what stops farming; the store's one study per player and per Pokemon is.
- `ResearchCommands`: `/ascend research` (families, items, the running study), `/ascend research family <species>`,
  `/ascend research drive <species>`; operators: `/ascend admin research grant|item|study <quick|standard|analysis> <slot>|collect|recall`
  (studies by command until the Bench block exists).

Tests: `FieldResearchRuntimeTest` (ids, names, raid tiers, locks). Full build green. **Not run live yet:** a real raid win paying research,
a Laboratory search paying a drive, a refused battle and trade while a Pokemon is studied.

**Next:** the research page (WebGUI, through the `impeccable` design skill per the owner's instructions); step 4, serums at capture (needs the live
test of writing IVs in Cobblemon's capture event); step 5, the Bench block and its capsule.

### Species from datapacks and addons (2026-11-22)

The catalog (`ResearchCatalog`, `ResearchFamilies.fromLinks`) follows whatever species the server has:

- **Ids keep their namespace** except Cobblemon's own: `garchomp`, but `mymod:fakemon`, so two mods' species of the same name never share
  research. A bare name from outside (a raid definition, a command, an evolution result) resolves to Cobblemon's species of that name, or else to
  the one species of that name in another namespace; an ambiguous bare name resolves to nothing.
- **Only implemented species count**, so every member of a family can be obtained and a family meter can always be filled. A missing or
  unimplemented evolution just makes the family smaller (Gible and Gabite share the 200 while Garchomp is absent).
- **Both link directions are read:** a child's `preEvolution` and a parent's `evolutions` list, since datapacks and addons use either. A declared
  pre-evolution wins over a conflicting listing; a child listed by two parents joins the smallest id; both are logged. Links to species the
  server does not have are ignored and logged.
- **Cross-namespace evolutions** (an addon evolution of Eevee) join the existing family; cycles and odd data never throw.
- **Rebuilt after every data-pack reload.** Research stored for a species that later disappears stays in the store and counts again when it returns.
- Commands take namespaced species (`/ascend research family mymod:fakemon`); messages show a species by its name alone.

Tests: `ResearchFamiliesTest` (parent-side links, conflicts, missing species, namespaces, cross-namespace evolutions) and
`FieldResearchRuntimeTest.aCatalogBuiltFromCobblemonDatapacksAndAddonsTogether`.

### Live check (2026-11-22, integration server 25601, bots + RCON; no client)

- Startup: `Field Research: 900 species in 433 families (125 not implemented, left out)`, no species-data conflicts.
- `trade-bots/research-test.mjs`: admin grant capped at the share (70 on Gible gave 50); unknown species refused; Data Drive +10 to Gabite, a
  second use refused; the Laboratory's Research Terminal gave materials and a Data Drive on the first search and only "already searched" on the
  second (no second drive); summary and family views correct (Gible 50/50, Gabite 13/60, Garchomp 0/90 = 63); a study refused a second start
  (`PLAYER_BUSY`) and an early collect ("20 min left"); after a real 20 minutes the quick study paid +3 once, a second collect found nothing;
  an analysis took its Boss Sample at the start and a recall ended it with nothing learned.
- `trade-bots/raid-research-test.mjs` with the test seam `/ascendtestraidresearch` (only under `-Dascensionlib.testResearch=true`), which makes
  CobbleRaids' calls in its order (`declareEnemy("raid:<uuid>", ..., "boss", true, null, "cobblemon:<species>", level)`, `settleRaidAttunement`
  twice, `end`): powerhouse Garchomp L50 and renowned L60 +8 each, legendary Mewtwo L100 +10, mythical Mew L100 +13 (Cobblemon's label),
  renowned starter Charmander L35 +5, each with one Boss Sample, every doubled settlement paid once; an unknown species paid nothing.
  CobbleRaids passes the full identifier (`cobblemon:garchomp`, from `RaidFactory`), which the catalog resolves.
- Found: a species argument in a command must be lower case (Minecraft's resource-location rule, as for `/give`).
- **Still not live:** a real raid battle in a client (the seam stands in for CobbleRaids' calls, whose order was read in its source), and the
  battle, trade and release locks (bots cannot battle or trade).
