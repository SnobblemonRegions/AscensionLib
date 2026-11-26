# Balance model results

Generated from the proposed JSON files by `python tools/analyze_balance.py`.

These are exact arithmetic/probability calculations, not measured gameplay or a Minecraft integration test.

## Capture odds

| Rarity | Chance per capture | Mean captures to first | Captures for at least 95% chance |
|---|---:|---:|---:|
| Common | 55.00% | 1.82 | 4 |
| Uncommon | 28.00% | 3.57 | 10 |
| Rare | 12.00% | 8.33 | 24 |
| Epic | 4.00% | 25.00 | 74 |
| Legendary | 0.90% | 111.11 | 332 |
| Mythical | 0.10% | 1,000.00 | 2,995 |

| Captures | Chance of at least one Mythical |
|---:|---:|
| 100 | 9.52% |
| 500 | 39.36% |
| 1,000 | 63.23% |
| 2,000 | 86.48% |
| 3,000 | 95.03% |

Expected initial affix slots per capture: **1.681**. No capture pity is assumed.

## Promotion budget

Common to Mythical: **770 Dust, 37 Facets, 7 Cores**; 90 lifetime attunement.

Rank-3-only comparison: Dust needs 49 full-reward wins; Facets need 19; attunement needs 30 qualifying wins. Starting from zero Core pity, seven Cores require at most 28 eligible top-band wins, with 95% obtained by 24 wins.

Core guarantee yields a mean wait of 2.734375 eligible wins per Core (long-run rate 36.57%), versus the displayed base roll of 25%.

With no research stipend, unlocks, losses or rerolls, 49 full rank-3 clears take 9.8 hours at the ASSUMED 12-minute cycle. At 20 full rewards/day, those full payouts span at least 3 UTC reward days. This is a reference scenario, not a minimum across overflow farming or a prediction for new players.

## Reroll odds

Refine draws uniformly including the current value. The table describes numerical values only; no useful-build probability is inferred.

| Affix | Values | Mean attempts to maximum | 95% chance by attempt |
|---|---:|---:|---:|
| Type Focus | 4–8% | 5 | 14 |
| Physical Force | 3–6% | 4 | 11 |
| Special Force | 3–6% | 4 | 11 |
| Healthy Force | 3–6% | 4 | 11 |
| Last Stand | 5–10% | 6 | 17 |
| Defiant Force | 4–8% | 5 | 14 |
| Type Ward | 3–6% | 4 | 11 |
| Healthy Guard | 3–6% | 4 | 11 |
| Steadfast Guard | 4–8% | 5 | 14 |
| Status Guard | 3–6% | 4 | 11 |
| Opening Guard | 5–10% | 6 | 17 |
| Restorative | 5–15% | 11 | 32 |
| Type Mastery | 12–20% | 9 | 26 |
| Overwhelming Force | 8–12% | 5 | 14 |
| Kindred Force | 6–10% | 5 | 14 |
| Executioner | 6–12% | 7 | 20 |
| Opening Strike | 8–14% | 7 | 20 |
| Super Effective Force | 6–12% | 7 | 20 |
| Iron Resolve | 5–9% | 5 | 14 |
| Type Bulwark | 8–14% | 7 | 20 |
| Resilient Hide | 5–10% | 6 | 17 |
| Triumphant | 5–10% | 6 | 17 |
| Physical Bulwark | 3–6% | 4 | 11 |
| Special Bulwark | 3–6% | 4 | 11 |
| Smoldering | 10–20% | 11 | 32 |
| Venomous | 10–20% | 11 | 32 |
| Rending | 10–20% | 11 | 32 |
| Keen Edge | 4–8% | 5 | 14 |
| Ensnaring | 5–10% | 6 | 17 |
| Momentum | 3–6% | 4 | 11 |
| Swift Strike | 4–8% | 5 | 14 |
| Bracing Entry | 8–16% | 9 | 26 |
| Wardstone | 5–10% | 6 | 17 |
| Stubborn | 3–6% | 4 | 11 |
| Ambush | 6–12% | 7 | 20 |
| Brawler | 3–6% | 4 | 11 |
| Sharpshooter | 3–6% | 4 | 11 |
| Giant Slayer | 10–16% | 7 | 20 |
| Relentless | 2–4% | 3 | 8 |
| Exploit Weakness | 5–10% | 6 | 17 |
| Armorbreak | 5–10% | 6 | 17 |
| Pressure Point | 15–25% | 11 | 32 |
| Searing Touch | 3–6% | 4 | 11 |
| Venom Touch | 3–6% | 4 | 11 |
| Siphon | 3–6% | 4 | 11 |
| Thorns | 2–4% | 3 | 8 |
| Purging | 8–15% | 8 | 23 |
| Fresh Legs | 5–10% | 6 | 17 |
| Stalwart | 8–15% | 8 | 23 |
| Unyielding | 8–15% | 8 | 23 |
| Watchful | 8–15% | 8 | 23 |
| Resolute | 4–8% | 5 | 14 |
| Insulated | 8–15% | 8 | 23 |
| Hardening | 2–4% | 3 | 8 |

## Damage envelope

A fully active +100% outgoing channel against zero reduction gives 2× the native direct-damage value before engine rounding. Against the capped 50% reduction it gives 1×. Reduction alone gives approximately 2× effective HP against affected direct moves. Eligible move healing is capped at +50%. These bounds exclude native multipliers, conditions, fixed/residual damage and actual engine rounding. Reaching a cap requires stacking several affixes in the same channel; additive stacking is clamped per channel.

## Validation scope

Validated tier weights, promotion chain/costs, slot growth, sufficient distinct families, unique affix identifiers, numerical ranges, example profile slots/families, and conservation of probability in the capped Core-pity model. No simulator hooks, GUI, database, capture persistence or real combat were tested.
