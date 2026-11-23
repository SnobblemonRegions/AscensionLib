# AscensionLib — domain language

A turn-based Pokémon progression overhaul in which captures acquire potential, battles test builds, and crafting develops individual Pokémon. The mod is AscensionLib (mod ID `ascensionlib`); CobbleAscend was the working title and survives only in stored data names (the `<world>/cobbleascend/` directory, the `cobbleascend` Pokémon NBT keys) and the `com.cobbleascend` domain/store packages.

## Pokémon progression

**Ascension rarity**: The Common, Uncommon, Rare, Epic, Legendary, or Mythical progression grade of an individual Pokémon. It is unrelated to a species being Legendary or Mythical.
_Avoid_: Species rarity, spawn rarity, power level.

**Affix**: A persistent, rolled modifier on one Pokémon that changes a defined aspect of battle performance.
_Avoid_: Enchantment, ability (which already has a Pokémon meaning).

**Prefix**: An offensive affix occupying one of the Pokémon's prefix slots.

**Suffix**: A defensive or recovery affix occupying one of the Pokémon's suffix slots.

**Affix family**: A group of mutually exclusive affixes that cannot coexist on the same Pokémon.

**Affix rank**: The quality band of an affix's numerical roll, advanced by spending milestone upgrades on its slot.
_Avoid_: Tier when referring to affix strength.

**Milestone upgrade**: A pending, Pokémon-specific choice earned at each ten-level milestone, spent to advance one selected affix slot by one rank.

**Pending upgrades**: Earned milestone upgrades that have not yet been assigned to affix slots.

**Ordinary affix slot**: A stable prefix or suffix position whose rank survives reforging; promotion unlocks additional positions.

**Unique affix**: A crafted, build-defining power occupying the Pokémon's single separate Unique slot, independent of ascension rarity and ordinary slot ranks.
_Avoid_: Seventh rarity, native ability.

**Unique classification**: The additional display designation of a Pokémon with an installed Unique affix, such as Rare · Unique.

**Promotion**: Permanent advancement by one ascension rarity, retaining existing affixes and unlocking an additional slot.

**Attunement**: Battle-earned progress toward the next promotion, tied to the individual Pokémon.
_Avoid_: Pokémon experience, friendship.

**Legacy Pokémon**: A Pokémon already owned when the overhaul is installed and lacking ascension data.

## Encounters and rewards

**Trial**: An overhaul-owned repeatable PvE challenge whose difficulty and rewards are declared before entry.

**Expedition**: A planned post-launch sequence of Trials with encounter modifiers and a final reward.
_Avoid_: Map (which also means a Minecraft item or world).

**Raid**: An encounter owned or executed by CobbleRaids, using its raid battle and shared-health rules.

**Encounter modifier**: A rule applied to an encounter, independent of an individual Pokémon's affixes.

**Reward entitlement**: A recorded right for one participant to claim the rewards for one completed encounter exactly once.

**Research stipend**: A small daily crafting-material reward for distinct species captured by a player.

## Crafting

**Resonance Dust**: The frequent crafting material consumed by rerolls and promotions.

**Facet**: The less frequent crafting material consumed by targeted reforging and higher promotions.

**Ascension Core**: The endgame crafting material consumed by Legendary and Mythical promotions.

**Unique Catalyst**: A scarce crafting material consumed to install or replace one selected compatible Unique affix.

**Unique fragment**: A slowly earned crafting material assembled into a Unique Catalyst.

**Reforge**: Replacing one selected ordinary affix with a newly rolled eligible affix and numerical value while preserving its slot rank and pending milestone upgrades.

**Refine**: Rerolling only the numerical value of one affix within its displayed range.

**Sealed reward**: An encounter reward committed to a claimable ledger before delivery.

## Scouting

**Scouter**: A one-use wallet material that reveals one chosen enemy's build for a single encounter. Obtained only as a CobbleTowers drop; it is spent only on a valid reveal.

**Reveal**: The in-memory disclosure of an enemy's level, types, base stats, rarity, Unique and modifiers (never IVs, EVs or moves). A boss reveal is shared with the whole party; any other reveal is for the user only. It ends with the encounter.

**Wild rating**: The rarity and slots of a wild Pokémon, derived from a server secret and the Pokémon's UUID so a preview equals the capture result and fleeing cannot reroll it.

## Storage and integration

**Wallet**: A player's account-bound balance of the crafting materials and the Scouter. There are no physical items.

**Progression store**: The per-world SQLite database that is authoritative for profiles, wallets and operations. The Pokémon's NBT is only a projection of its last committed revision.
_Avoid_: Save file, Pokémon data (as the source of truth).

**Authority**: The identity of one world's store, recorded in `authority.id`. A missing, mismatched or corrupt store disables progression instead of resetting it.

**Operation ID**: A caller-supplied key under which a mutation is recorded once. A repeated request returns the recorded result; the same ID with different parameters is rejected.

**Craft lock**: The persistent-data flag `ascensionlib_craft_locked` marking a lent Pokémon (such as a Towers rental). It can be inspected but not crafted.

**Profiler**: An opt-in timing aid, off by default, that logs screen and server-path timings.

## Releases

A version bump in build.gradle needs a matching newest entry in ascensionlib/src/main/resources/ascensionlib/CHANGELOG.md. It ships in the jar at ascensionlib/CHANGELOG.md and is shown to players in game by Snobblemon Server, so write it for players (what they will notice, no config keys or class names) and keep to its four line kinds: the '# AscensionLib changelog' title, '## <version> (YYYY-MM-DD)', '### New', '### Changed' or '### Fixed', and '- ' bullets. tools/validate_changelog.py enforces this. Call it the Player Changelog, not patch notes or release notes.
