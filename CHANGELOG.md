# Changelog

## Unreleased (balance/phase-1): the Threat system, off by default

- **Threat bands.** `threat_1` to `threat_7` enemy tiers. With the new `threat.enabled` setting on, legacy tiers fight as their bands. Bosses get bonus rank credits, and from Threat V a Unique. Threat VII enemies are Perfected, and each has a Unique. Owners can override the tiers with `config/ascensionlib-enemy-tiers.json`.
- **Rarity aura.** With Threat on, every profiled Pokemon fights with +0 to +26% Atk, SpA, Def and SpD by rarity, applied after the affix caps.
- **Apex Shard.** A new tradable material, earned only from raids. With Threat on, the two top promotions, Overreach and Fusion also cost Apex Shards.
- **Threat rewards.** Tower completions pay by band, with one Core roll each. Raids pay Apex Shards, attunement and dust by tier and damage share. Daily caps apply to Core rolls and Apex payouts.
- **New API** for CobbleTowers and CobbleRaids: `settleTowerCompletion(..., threat)`, `settleTowerBoss(..., threat)`, `towerCompletionExtras(..., threat)`, `settleRaid`, `threatEnabled` and `recommendedThreat`. Older callers are unaffected.
- **Telemetry.** Encounter events carry the team's rarity, and raids log an encounter event.
- See `docs/BALANCE-THREAT.md`. With Threat off, every encounter fights and pays as in 1.0.0.

## 1.0.0+fabric

The first full release (Fabric build). Server-side Fabric mod for Minecraft 1.21.1 with Cobblemon 1.8.1 or 1.8.2; clients need WebGUI.

### Commands
- Players need only `/ascend dex` (the AscensionDex reaches every page), `/ascend guide`, `/ascend status` and `/ascend scout`. `inspect`, `craft`, `fuse`, `sigil` and `wallet withdraw|deposit` are operator-only under `/ascend admin`; `help`, `party`, `ui` and `wallet gui` are gone (the Dex tabs and the page headers cover them).

### Item art
- With Polymer installed, the mod adds its item models and textures to the server resource pack by itself and merges its item model files with other packs' (no manual zip, no lost sprites when another pack uses the same base item). Polymer stays optional.

### Progression
- Every captured or hatched Pokémon gets a rarity (Common to Mythic) and modifiers; 34 modifiers with ranks I to V.
- Upgrade, Refine, Reforge, Perfect (a raised floor), Overreach (a one-shot gamble that seals the slot) and Promote.
- Uniques (Catalyst plus a Tower Mark) and Fusion into Transcendents.
- Attunement goes to the Pokémon that actually fought, and a Pokémon with no profile is told why and can be given one with an Ascension Sigil.
- Rare respec items: the Hourglass of Undoing (resets ranks, returns credits; refused on a Pokémon that has Overreached) and the Unbinding Prism (a free Unique swap).

### Wild Pokémon and where profiles act
- Profiles act against every wild Pokémon and every trainer's Pokémon, in every dimension, and everything you catch or hatch gets a profile. Fights between players are native for both sides.
- Each dimension has a tier: 1 is the ordinary rarity table, higher tiers shift toward rarer (tier 5 gives 7% Mythic and 19% Legendary, against 0.1% and 0.9% at tier 1), tier 0 switches profiles off. The default tier, per-dimension tiers and the rarity table are in `config/ascensionlib.json` (`zones`).
- Pokémon you already own keep no profile until you use an Ascension Sigil.

### Economy
- A per-player wallet of eight materials, also as real items with resource-pack art: tradable vouchers (a 3% withdrawal fee, rounded up) and loot that joins the wallet when carried. Tower Marks are bound.
- Tower completion chests: a guaranteed pile, a quality tier from challenge cards, Ascension cycle and a clean run, and a crown of notable items; a floor-5 mini chest; a daily limit of 20 full chests, then 25% dust.
- The Hourglass can be bought with 90 Tower Marks.

### Battle overlay
- Momentum shows as a standing badge with its stacks, and Wardstone and Ensnaring flash when they act, for both sides, next to the bleed badge.

### Logging and measurement
- A telemetry log for balancing (every economy operation, crafts and refusals, new profiles, encounter results, sessions, material supply) and for performance (server tick, store writes, page requests, and the pages' own render times). See `docs/PERFORMANCE.md` and `tools/telemetry_report.py`.

### Screens (WebGUI pages)
- Craft, Fusion, Inspect, Wallet, the AscensionDex (party, wallet and guide tabs), the completion reveal and an overlay (capture cards, scouting panel, bleed badge).
- Pages scale to the screen and each player sizes them (60 to 160 percent, saved on the server; the − and + in each page header).
- An in-game guide (`/ascend guide`) and a wiki handoff, generated from one source with every number read from the code.

### Items and tooltips
- Hand-drawn 32x32 art for all nine items (drawn in code, reproducible), rarity-coloured tooltips, the AscensionDex reskin.
- A crest for every modifier on the craft, inspect and overlay pages: the plate's shape shows the slot (hexagon prefix, shield suffix) and its colour the theme; Unique powers have their own, and the four type-bound modifiers (Type Focus, Mastery, Ward, Bulwark) take the colour of their chosen type.

### Safety and operations
- The progression store is backed up before any schema upgrade (and the upgrade is refused if the copy fails), at server start and daily (the newest 14 kept), and on demand with `/ascend admin backup`.
- Store writes are measured at 0.6 ms each on an ordinary disk (see `docs/PERFORMANCE.md`).
- Runs on Cobblemon 1.8.1 and 1.8.2.

### Licence
- Released under the MIT licence.

### Known limits
- Tooltip frames and backgrounds by rarity need Minecraft 1.21.2 or later.
- Core pity is not built (deferred by design).
