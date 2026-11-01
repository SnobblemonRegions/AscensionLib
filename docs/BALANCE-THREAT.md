# The Threat system (balance phase 1)

Threat makes ascension the thing that decides how hard a player can go. Every Tower difficulty and raid tier stays open to everyone; higher Threat bands field enemies built for higher-rarity teams, so a team that has not ascended can enter and will lose. It ships **off**. With `threat.enabled` false, every encounter fights and pays exactly as in 1.0.0.

The full design (power curve, pacing simulation, modifier pass, 8-player duel raids) is in the balance spec owned by the server; this file is what AscensionLib implements and how the other mods call it.

## Switching it on

`config/ascensionlib.json`:

```json
"threat": {
  "enabled": false,
  "fullCoreRollsPerDay": 3,
  "fullApexPayoutsPerDay": 4,
  "reducedPercent": 25
}
```

`/ascend admin reload` applies a change. Turning it on changes, at once:

| Area | With Threat on |
| --- | --- |
| Enemy tiers | `trial_rank_1`-`3` fight as `threat_1`-`3`, `boss` as `threat_6` (`design/enemy-tiers.json`, `threatAliases`) |
| Battle | Every profiled Pokemon fights with its rarity aura: +0/4/8/13/19/26% Atk, SpA, Def, SpD, Common to Mythic (`balance.json` `auraPercent`), applied after the affix caps |
| Promotions | Priced from `balance.json` `threatPromotions`: Epic to Legendary needs 4 Apex Shards, Legendary to Mythic 10 |
| Overreach, Fusion | +1 and +12 Apex Shards |
| Tower completion | Paid by Threat band (when CobbleTowers passes one), Cores from one roll per completion |
| Raids | Paid Apex Shards, attunement and dust by tier and damage share (when CobbleRaids calls `settleRaid`) |

Owners can replace the enemy tiers with `config/ascensionlib-enemy-tiers.json` (same format as `design/enemy-tiers.json`); a bad file is logged and ignored.

## Threat bands

| Band | Enemy rarities | Rank credits | Boss | Built for |
| --- | --- | --- | --- | --- |
| `threat_1` | 70% Common, 30% Uncommon | 1 | +2 credits | Common teams |
| `threat_2` | 60% Uncommon, 40% Rare | 3 | +2 credits | Uncommon |
| `threat_3` | 60% Rare, 40% Epic | 5 | +2 credits | Rare |
| `threat_4` | 70% Epic, 30% Legendary | 7 | +2 credits | Epic |
| `threat_5` | 75% Legendary, 25% Mythic | 9 | +1 credit (capped at 10), a Unique | Legendary |
| `threat_6` | Mythic | 10 | a Unique | Mythic |
| `threat_7` | Mythic, every slot at its band top, every enemy a Unique | 10 | a Unique | Mythic with Uniques (Ascension cycles only) |

No enemy spends more than the ten credits a player can earn, and 777 is never an enemy Unique.

## Rewards

**Tower completion at band T** (Silver quality; Bronze, Gold and Mythic multiply as before): `100 + 75(T-1)` dust, `round(1.5(2+T)) - 1` facets, the usual fragments, Scouters and Marks, and one Core roll at `min(95, 20 + 12T)` percent. The crown never rolls a Core while Threat is on. After a player's `fullCoreRollsPerDay` completions in a UTC day, the Core chance is cut to `reducedPercent` of itself.

**Raid win** (full share): starter 0.3 Apex, 3 attunement, 60 dust; powerhouse 0.6, 5, 60; legendary 1.2, 8, 60; mythical 1.8, 12, 60; world boss 3.0, 15, 120. A fractional Apex amount is a seeded chance of one more. Everything scales by `share x players`, clamped to 0.5-1.5; a player under a quarter of an even split is paid nothing. After `fullApexPayoutsPerDay` raid wins in a UTC day, Apex is cut to `reducedPercent`.

The daily counters live in the store's `threat_daily` table, created when missing. The store's schema version is unchanged, so an older AscensionLib still opens the world.

## API for CobbleTowers and CobbleRaids

All are static methods looked up by reflection, as today; each caller falls back to the older call when a method is missing. `ContractSignaturesTest` pins them.

| Method | For | Notes |
| --- | --- | --- |
| `AscensionEncounters.threatEnabled()` | Towers, Raids | Show Threat UI only when true |
| `AscensionEncounters.recommendedThreat(Collection<UUID>)` | Towers door | 1-6 from the party's mean rarity; advice, never a lock |
| `AscensionEncounters.declareEnemy(..., "threat_N", ...)` | Towers, Raids | Threat tier IDs are ordinary tier IDs |
| `AscensionRewards.settleTowerBoss(..., int threat)` | Towers | Same mini chest; records the band |
| `AscensionRewards.settleTowerCompletion(..., int threat)` | Towers | Threat chest when on, the old chest when off |
| `AscensionRewards.towerCompletionExtras(..., int threat)` | Towers | Must match the settle call's form, so the armor and tickets agree |
| `AscensionRewards.settleRaid(String id, String outcome, String tier, Map<UUID, Double> shares)` | Raids | Replaces `settleRaidAttunement`; with Threat off it awards the old attunement and answers `THREAT_OFF` |

## Tests

Domain (`RarityAuraTest`, `ThreatCostsTest`, `ThreatRewardsTest`, `EnemyGeneratorTest`, `TunablesTest`) and store (`ThreatCountersTest`, `ProgressionStoreTest`) cover the rules. `validation/showdown/aura_upstream_test.js` checks the aura in the real upstream Showdown simulator.
