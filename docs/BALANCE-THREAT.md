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
| Promotions | Priced from `balance.json` `threatPromotions`: Epic to Legendary needs 800 dust, 2 Cores and 4 Apex Shards; Legendary to Mythic 1,500 dust, 4 Cores and 8 Apex Shards |
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

**Tower completion at band T** (Silver quality; Bronze, Gold and Mythic multiply as before): `100 + 75(T-1)` dust, `round(1.5(2+T)) - 1` facets, the usual fragments, Scouters and Marks, and one Core roll at `min(95, 20 + 12T)` percent. The crown never rolls a Core while Threat is on. After a player's `fullCoreRollsPerDay` completions in a UTC day, the Core chance is cut to `reducedPercent` of itself. From Threat III up a completion also pays Apex Shards, on average 0.3, 0.9, 1.8, 2.4 and 2.7 at Threat III to VII (the whole part, plus a seeded chance of one more), cut the same way, so Tower-first players are not gated on raids.

**Raid win** (full share), per tier: Apex, Facets, Core chance, attunement, dust.

| Tier | Apex | Facets | Core | Attunement | Dust |
| --- | --- | --- | --- | --- | --- |
| starter | 0.30 | 2.5 | 45% | 3 | 100 |
| powerhouse | 0.60 | 2.5 | 42% | 5 | 120 |
| legendary | 1.20 | 2.5 | 45% | 8 | 150 |
| mythical | 1.80 | 3.0 | 66% | 12 | 200 |
| world boss | 3.00 | 5.0 | 100% | 15 | 350 |

A player's **first raid payout of the UTC day pays every material double** (not attunement): a catch-up for players who raid a few times a week. It reads the same daily Apex counter as the cap, so it needs no new storage. A fractional Apex or Facet amount is a seeded chance of one more. Raids pay Facets and Cores so a player who never runs a Tower can still promote. Everything scales by `share x players`, clamped to 0.5-1.5 (the Core chance by at most 1.0); a player under a quarter of an even split is paid nothing. After `fullApexPayoutsPerDay` raid wins in a UTC day, Apex, Facets and the Core chance are cut to `reducedPercent`.

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

## Physical rewards

With `threat.physicalRewards` on (the default, whether or not Threat itself is on), every encounter payout (Tower chests, raids, World Boss,
trials, dungeons) reaches the player as voucher items instead of going straight into the wallet. The items carry the material's name,
rarity line and lore, and say how to deposit them:

- **Right-click** a voucher to deposit that stack, or use **Deposit** in the Wallet tab of the AscensionDex.
- Vouchers are tradable and can be stored. A batch redeems only up to what it was minted with, so a duplicated stack credits nothing.
- No withdrawal fee: depositing a reward gives back all of it.
- Items arrive within a few seconds of the payout, or at the next login for an offline player. A full inventory drops them at the player's feet.
- Bound materials (Tower Marks) still go straight into the wallet.

The store pays the wallet first and then moves the payout into vouchers in a second transaction, so a crash in between leaves the reward in the
wallet: never lost, never doubled. Set `"physicalRewards": false` to go back to direct wallet credit.
