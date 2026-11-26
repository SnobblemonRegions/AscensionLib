# Affix expansion: 20 base affixes and 8 Uniques (2026-11-25)

Owner request: "add more affixes to the base affix pool and to the unique list for players".

**Owner answers (2026-11-25):** write all Transcendent recipes; Giant Slayer and Pressure Point are rare; Fickle Fortune ships; build the base
affixes first, then the Uniques, then the recipes.

**Status:** part A (20 base affixes) BUILT on `feat/affix-expansion`, uncommitted: catalog, rank bands, Resonance, crests, `ascension-fx.js`
(`applyExpansion` plus the damage tables), 20 simulator tests (E1-E20; the suite is 112/112 on Cobblemon's own Showdown). Not seen in a live battle.
Parts B (8 Uniques) and C (84 Transcendent recipes) BUILT the same day; see "Parts B and C as built" at the end. Values are rank I
ranges in the style of `design/affixes.json`; ranks II-V follow the existing `bandRule` in `ranked-catalog.json`. All numbers are
placeholders until the balance simulator has run them.

The base pool goes from 34 to 54 and the Unique list from 7 to 15.

## Design rules used

- **No repeats.** Each new affix differs in trigger or effect from the 34 in `affixes.json` and the 7 Uniques. Names also avoid the
  CobbleRaids renown affixes (Bloodrush, Barbed Shell and so on), since those can sit on the same caught Pokémon.
- **Same channels and caps.** Every affix uses one of the existing channels: `outgoingDamage` (cap 100), `incomingReduction` (50),
  `healing` (50), `residual` (100) or `mechanic`. The caps still bound any stack of them.
- **Paired families.** Brawler / Sharpshooter and Searing Touch / Venom Touch are mutually exclusive families. They work like
  `physical_force` / `special_force`, so a Pokémon never holds both halves of a pair.
- **No evasion or accuracy cuts, no OHKO, no stat drops a player can't answer.** These make battles feel random rather than built.
- **Any Pokémon may hold any of them.** Nothing is gated by type or move, as with the existing Uniques.

## A. Twenty base affixes (10 prefixes, 10 suffixes)

### Prefixes

| Id | Name | Family | Channel | Rank I | Weight | Effect | Resonance |
|---|---|---|---|---|---|---|---|
| `ambush` | Ambush | `entry_offense` | outgoingDamage | 6-12 | 40 | Your moves deal more damage to a target that entered the field this turn or last turn. | Tempo |
| `brawler` | Brawler | `contact_offense` | outgoingDamage | 3-6 | 100 | Your contact moves deal more damage. | none |
| `sharpshooter` | Sharpshooter | `contact_offense` | outgoingDamage | 3-6 | 100 | Your non-contact moves deal more damage. | none |
| `giant_slayer` | Giant Slayer | `underdog_offense` | outgoingDamage | 10-16 | 6 (rare) | Your moves deal more damage to a target whose max HP is higher than yours. | Predator |
| `relentless` | Relentless | `chain_offense` | outgoingDamage | 2-4 | 30 | Using the same damaging move on consecutive turns adds this much per use, up to 3 uses. Switching or changing moves resets it. | Predator |
| `exploit_weakness` | Exploit Weakness | `debuff_offense` | outgoingDamage | 5-10 | 40 | Your moves deal more damage to a target with at least one lowered stat. | Predator |
| `armorbreak` | Armorbreak | `shred_offense` | mechanic | 5-10 | 40 | Each damaging move that hits has this chance to lower the target's Defense by one stage. | Predator |
| `pressure_point` | Pressure Point | `crit_damage` | outgoingDamage | 15-25 | 6 (rare) | Your critical hits deal this much more damage. Pairs with Keen Edge. | Predator |
| `searing_touch` | Searing Touch | `status_touch` | mechanic | 3-6 | 30 | Each damaging contact move that hits has this chance to burn the target. | Toxin |
| `venom_touch` | Venom Touch | `status_touch` | mechanic | 3-6 | 30 | Each damaging contact move that hits has this chance to poison the target (regular poison, not Toxic). | Toxin |

### Suffixes

| Id | Name | Family | Channel | Rank I | Weight | Effect | Resonance |
|---|---|---|---|---|---|---|---|
| `siphon` | Siphon | `lifesteal` | healing | 3-6 | 40 | Your direct moves heal you this share of the damage they deal. | Vitality |
| `thorns` | Thorns | `contact_retaliation` | mechanic | 2-4 | 40 | A foe that hits you with a contact move loses this percent of its max HP. | Bulwark |
| `purging` | Purging | `status_cure` | mechanic | 8-15 | 40 | At the end of each turn, this chance to cure your own status condition. | Toxin |
| `fresh_legs` | Fresh Legs | `switch_recovery` | healing | 5-10 | 40 | When you switch out, you heal this percent of your max HP. | Tempo |
| `stalwart` | Stalwart | `crit_defense` | incomingReduction | 8-15 | 40 | You take less damage from critical hits. | Bulwark |
| `unyielding` | Unyielding | `stat_ward` | mechanic | 8-15 | 40 | Each stat drop a foe would cause you has this chance to fail. Your own moves' drops still apply. | Bulwark |
| `watchful` | Watchful | `priority_defense` | incomingReduction | 8-15 | 30 | You take less damage from moves with raised priority. | Tempo |
| `resolute` | Resolute | `debuffed_defense` | incomingReduction | 4-8 | 100 | You take less damage while you have at least one lowered stat. | Bulwark |
| `insulated` | Insulated | `residual_defense` | incomingReduction | 8-15 | 40 | You take less residual damage: burn, poison, bleed, weather, hazards. | Toxin |
| `hardening` | Hardening | `stacking_defense` | incomingReduction | 2-4 | 30 | Each direct hit you take reduces later hits by this much, up to 3 stacks. Switching out ends it. The mirror of Momentum. | Bulwark |

**Why these:**
- **Brawler and Sharpshooter** add a third standard prefix pair at weight 100, so the common rolls aren't only Physical/Special Force.
- **Exploit Weakness, Armorbreak and Resolute** form a small debuff game. Armorbreak and Ensnaring set it up, Exploit Weakness cashes it in,
  and Resolute is the defensive answer.
- **Searing Touch and Venom Touch** give the Toxin theme a way to apply the status that Smoldering, Venomous, Ashen Heart and Creeping
  Venom then boost. Today a player needs Will-O-Wisp or Toxic in the moveset for those to do anything.
- **Insulated and Purging** are Toxin's defensive counters, so status builds have a defence to play against.
- **Hardening** mirrors Momentum on defence and gives the Bulwark theme a reason to stay in.

### Resonance after the expansion

| Theme | Today | Added | Size |
|---|---|---|---|
| Tempo | 5 | Ambush, Fresh Legs, Watchful | 8 |
| Toxin | 5 | Searing Touch, Venom Touch, Purging, Insulated | 9 |
| Predator | 5 | Giant Slayer, Relentless, Exploit Weakness, Armorbreak, Pressure Point | 10 |
| Bulwark | 6 | Thorns, Stalwart, Unyielding, Resolute, Hardening | 11 |
| Vitality | 6 | Siphon | 7 |

Brawler and Sharpshooter stay out of every theme, like Physical/Special Force. Bigger themes make the 2- and 3-piece bonuses easier to
reach. If Bulwark at 11 is too easy to complete, move Resolute to no theme.

## B. Eight Uniques (7 -> 15)

Same rules as the first batch (`docs/UNIQUES-DESIGN.md`): one fixed power, no rank, a fixed drawback applied after the caps, any Pokémon.

| Id | Name | Benefit | Drawback | Build it enables |
|---|---|---|---|---|
| `crescendo` | Crescendo | Your direct moves deal +8% for each full turn you have stayed in, up to +40% (outgoing channel). Switching resets it. | For your first 2 turns after entering, your moves deal 20% less. | Stay-in sweeper; punishes pivoting. |
| `nightfang` | Nightfang | Your direct moves heal you 25% of the damage they deal (healing channel). | You take 30% more damage from Fire and Fairy moves. | Self-sustaining fighter with a clear weakness. |
| `iron_maiden` | Iron Maiden | A foe that hits you with a contact move loses 1/8 of its max HP (stacks with Thorns, capped at 1/6 combined). | Your own contact moves deal 20% less. | Wall that punishes physical attackers. |
| `tailwind_heart` | Tailwind Heart | Your Speed is +50%. | Your Defense and Sp. Def are -15%. | Fast, fragile attacker. |
| `huntmark` | Huntmark | Your first hit on a target marks it. Your later moves against a marked target deal +25% (outgoing channel). The mark lasts until it switches out. | You cannot land critical hits. | Duelist built to finish one target. |
| `martyrs_gift` | Martyr's Gift | When you faint, the next ally to enter heals 50% of its max HP and gets +1 Attack and +1 Sp. Atk. | You take 15% more damage from moves. | Team-play sacrifice for raids and towers. |
| `aegis_oath` | Aegis Oath | You take 30% less damage from moves (incoming channel, so the 50% cap applies). | Your moves deal 30% less damage. | Pure tank for raid parties. |
| `fickle_fortune` | Fickle Fortune | At the start of each turn a coin flip from the battle's seeded RNG: heads, your moves deal +40% this turn. | Tails: your moves deal 20% less this turn. Averages +10%, swings hard. | A gamble. Shows the coin on the overlay. |

**Why these:** the first 7 Uniques are mostly about damage-over-time and damage numbers. These 8 cover the roles that were missing:
- **Speed:** Tailwind Heart.
- **Tank:** Aegis Oath.
- **Sustain:** Nightfang.
- **Team support:** Martyr's Gift.
- **Retaliation:** Iron Maiden.
- **Momentum:** Crescendo.
- **Targeting:** Huntmark.
- **Variance:** Fickle Fortune.

Each drawback is a fixed multiplier or rule that the caps can't remove, as with the first batch.

## C. What else this touches

1. **Transcendence (decision needed).** `design/transcendents.json` has 21 bases: one recipe, with a signature, for every pair of the 7
   Uniques. With 15 Uniques there are 105 pairs, so 84 new recipes would be needed to cover them all. Options:
   - **(Recommended) New Uniques don't fuse yet.** Fusion accepts only pairs that have a recipe. New recipes are added in small themed
     batches later.
   - Write all 84 recipes now. This is a large design job on its own.

   Before building, verify that `Transcendence` refuses a pair with no recipe cleanly, rather than crashing or picking a fallback.
2. **Enemies roll them too.** `EnemyGenerator` draws from the same catalog, so tower and dungeon enemies get Thorns, Insulated, Hardening and
   so on. The pages show any catalog id with its crest, so nothing else is needed. (There is no per-affix threat cost: Threat prices promotions only.)
3. **Showdown module.** All 28 need handlers in `ascension-fx.js`. Most reuse hooks that already exist:
   - `modifyDamage`: Ambush, Giant Slayer, Exploit Weakness, Huntmark, Crescendo, Fickle Fortune.
   - The incoming multiplier: Watchful, Resolute, Stalwart, Hardening, Aegis Oath.
   - `onKo` and faint: Martyr's Gift.

   The ones that need new hooks are the switch-out heal (Fresh Legs), the stat-drop gate (Unyielding) and Martyr's Gift's next-entrant
   buff.
4. **Payload size.** `BattleFx.MAX_PAYLOAD_CHARS` is 8000. New ids are short and a Pokémon's slot count doesn't change, so the payload per
   Pokémon is about the same. Nothing changes here.
5. **Craft screen.** The Unique tab lists every Unique. 15 entries need a list that scrolls or is grouped; this is UI work under the
   `impeccable` skill.
6. **Profiles.** New ids are additive. A build older than this one will reject a profile that holds one, which is forward-only, as with
   `floor` and `sealed`.

## D. Threat model

| Exploit considered | Mitigation |
|---|---|
| Thorns + Iron Maiden kill a raid boss through chip damage alone | Combined contact chip capped at 1/6 of the attacker's max HP per hit. Raid bosses already scale residual chip through the raid pool. |
| Siphon + Nightfang + Restorative make a Pokémon unkillable | All three join the `healing` channel, capped at 50%. Nightfang's drawback applies after the cap. |
| Fickle Fortune rerolled by reconnecting | The flip uses the battle's seeded RNG, so a reconnect replays the same result. |
| Martyr's Gift fainted on purpose to buff a sweeper | Accepted: that's the design. Once per faint, and the 15% damage taken is a real cost. |
| Aegis Oath with Iron Resolve and Type Bulwark nearly immune | The incoming channel cap of 50% bounds it before the Unique's own drawback. |
| Enemy affixes too strong in early towers | Enemies roll the same weights and rank bands as players; the rarity tier sets how many slots they get. |

## E. Owner questions

1. Transcendence: should new Uniques not fuse yet (recommended), or do you want all 84 recipes written now?
2. Should any of the 20 be rare (weight 3-8) like Overwhelming Force? Pressure Point and Giant Slayer are candidates.
3. Should Fickle Fortune ship? It's the only Unique built on luck, and some players dislike that.
4. Build order: base affixes first (smaller, mostly existing hooks), then Uniques?

## Part A as built (2026-11-25)

- **Ranges trimmed so rank V fits the channel cap.** `RankedRulesTest.channelCapsLeaveRoomForEveryRankVAffix` caught Purging, Unyielding and
  Insulated reaching 60. Stalwart, Watchful, Purging, Unyielding and Insulated are now 8-15 at rank I (rank V 40-47).
- **Rare:** Giant Slayer (10-16) and Pressure Point (15-25), weight 6, are now powerhouses beside the original four.
- **Ambush** reads Showdown's `activeTurns`, which counts at the end of a turn, so leads count as "just entered" on turns 1 and 2.
- **Relentless** counts landed damaging moves; a turn where the move misses or is blocked breaks the chain.
- **Thorns** is capped at 17% of the attacker's max HP per hit. Iron Maiden will share that cap when it is built.
- **Siphon** heals through the holder's own `heal()`, so Titan's Heart and Last Breath still cut it; it is not boosted by Restorative.
- **Fresh Legs** heals during Showdown's `SwitchOut` event, while the holder still holds its slot, so the `-heal` line names it correctly.
- **Verified:** simulator tests E1-E20; domain 296, store 116 and mod 144 Java tests; `:ascensionlib:build`. **Not verified:** a live battle, the
  pages showing the new crests on a client.

## Parts B and C as built (2026-11-25)

**B. The 8 Uniques** (`ascension-fx.js`, constants `CRESCENDO_*` to `FORTUNE_*`; text in `design/ranked-catalog.json`). As designed, with these
details:

- **Crescendo** counts full turns on the field from Showdown's `activeTurns` minus one: a lead or a faint replacement is already at 1 on its
  first turn to act. The same helper (`turnsIn`) drives the composed ramp parts.
- **Tailwind Heart** lowers Defense and Sp. Def through `calculateStat`, which is what the damage formula reads (`getStat` alone missed it,
  which a simulator test caught).
- **Huntmark** refuses crits inside Showdown's `CriticalHit` event, as Battle Armor does, so the crit roll is still made and nothing else in the
  battle's random sequence moves.
- **Iron Maiden** shares Thorns' 17% per-hit cap. **Nightfang** shares Siphon's healing cap.
- **Martyr's Gift** wraps the holder's `faint` and the next `runSwitch` on its side.

**C. Transcendence: all 105 pairs.** The 21 hand-written signatures are untouched. The 84 new ones are **composed**: each is data in
`tools/transcendent_powers.py` (`COMPOSED`) from a vocabulary of 24 core parts, a clutch and 10 drawbacks. `composeSignature` in
`ascension-fx.js` implements the vocabulary once. `tools/build_fusion_data.py` writes the player text into `design/transcendents.json` **and**
the `COMPOSED_SIGNATURES` table into `ascension-fx.js` from the same data, so text and behaviour cannot drift. The 8 recipes with 777 carry an
item-reward bonus (`ItemQuantityBonus`: 15%, All In 20%).

**Verified:**
- Simulator tests U1-U9 (the Uniques) and C1-C9: every one of the 84 recognised and run in a battle, plus targeted tests of ramp, mark and
  no-crit, gamble, counter and clutch, legacy, lifesteal, incoming and type weakness, speed, frail, bleed, and struck status.
- Domain tests: 105 bases and signatures, and the 15 × 14 × 4 sample fusions all build valid payloads.

**Not verified:** a live battle, the craft screen's Unique tab with 15 entries (it may need to scroll), and how the fusion screen shows a
composed recipe.

### The 84 composed recipes (generated from the data, at 100% strength)

| Name (Uniques) | Pulse | Core | Clutch | Drawback |
|---|---|---|---|---|
| **Rising Pyre** (Ashen Heart + Crescendo) | HIT | Each turn you stay in adds 6% damage, up to 30%; burns you inflict deal 30% more. |  | On your first two turns on the field your moves deal 20% less. |
| **Cinder Fang** (Ashen Heart + Nightfang) | HIT | Your hits have a 20% chance to burn the foe; your hits heal you 15% of the damage they deal. |  | You take 25% more damage from Water and Fairy moves. |
| **Brazen Bull** (Ashen Heart + Iron Maiden) | STRUCK | Foes that hit you with a contact move have a 30% chance to be burned; foes that hit you with a contact move lose 8% of their max HP. |  | Your contact moves deal 20% less. |
| **Wildfire Gale** (Ashen Heart + Tailwind Heart) | HIT | Your Speed is 25% higher; your hits have a 15% chance to burn the foe. |  | Your Defense and Sp. Def are 12% lower. |
| **Hunter's Pyre** (Ashen Heart + Huntmark) | HIT | Your first hit marks a foe; your later moves against it deal 20% more; you deal 10% more to foes with a status condition. |  | You cannot land critical hits. |
| **Funeral Pyre** (Ashen Heart + Martyr's Gift) | STRUCK | When you faint, the next ally to enter heals 35% of its max HP and gains +1 Attack and Sp. Atk; burns you inflict deal 30% more. |  | You take 12% more damage. |
| **Hearthguard** (Ashen Heart + Aegis Oath) | STRUCK | You take 18% less damage; foes that hit you with a contact move have a 20% chance to be burned. |  | Your moves deal 20% less. |
| **Gambler's Flame** (Ashen Heart + Fickle Fortune) | HIT | Each turn a coin is flipped: heads, your moves deal 35% more that turn; your hits have a 15% chance to burn the foe. |  | Tails: your moves deal 20% less that turn. |
| **Endless Encore** (Last Breath + Crescendo) | TICK | Each turn you stay in adds 8% damage, up to 40%. | A lethal hit leaves you at 1 HP and your next move deals 20% more. | On your first two turns on the field your moves deal 20% less. |
| **Undying Thirst** (Last Breath + Nightfang) | HIT | Your hits heal you 15% of the damage they deal. | A lethal hit leaves you at 15% HP instead of fainting. | You take 25% more damage from Fire and Fairy moves. |
| **Iron Coffin** (Last Breath + Iron Maiden) | STRUCK | Foes that hit you with a contact move lose 10% of their max HP. | A lethal hit leaves you at 1 HP and the next hit you take deals 30% less. | Your contact moves deal 20% less. |
| **Phantom Step** (Last Breath + Tailwind Heart) | HIT | Your Speed is 30% higher. | A lethal hit leaves you at 1 HP. | Your Defense and Sp. Def are 15% lower. |
| **The Last Hunt** (Last Breath + Huntmark) | HIT | Your first hit marks a foe; your later moves against it deal 20% more. | A lethal hit leaves you at 1 HP and your next move deals 30% more. | Your Speed is 12% lower. |
| **Final Vigil** (Last Breath + Martyr's Gift) | STRUCK | When you faint, the next ally to enter heals 50% of its max HP and gains +1 Attack and Sp. Atk. | A lethal hit leaves you at 1 HP. | You take 15% more damage. |
| **Unbroken Oath** (Last Breath + Aegis Oath) | STRUCK | You take 20% less damage. | A lethal hit leaves you at 20% HP instead of fainting. | Your moves deal 25% less. |
| **Dead Man's Hand** (Last Breath + Fickle Fortune) | HIT | Each turn a coin is flipped: heads, your moves deal 40% more that turn. | A lethal hit leaves you at 1 HP and your next move deals 30% more. | Tails: your moves deal 20% less that turn. |
| **Slow Rot** (Creeping Venom + Crescendo) | TICK | Poison you inflict deals 25% more; each turn you stay in adds 5% damage, up to 25%. |  | On your first two turns on the field your moves deal 20% less. |
| **Venom Kiss** (Creeping Venom + Nightfang) | HIT | Your contact hits have a 25% chance to poison the foe; your hits heal you 10% of the damage they deal. |  | You take 25% more damage from Fire and Psychic moves. |
| **Toxic Thorns** (Creeping Venom + Iron Maiden) | STRUCK | Foes that hit you with a contact move have a 35% chance to be poisoned; foes that hit you with a contact move lose 6% of their max HP. |  | Your contact moves deal 20% less. |
| **Plague Wind** (Creeping Venom + Tailwind Heart) | TICK | Your Speed is 20% higher; foes with a status condition lose 3% of max HP at the end of each turn. |  | Your Defense and Sp. Def are 12% lower. |
| **Stalking Venom** (Creeping Venom + Huntmark) | HIT | Your first hit marks a foe; your later moves against it deal 15% more; poison you inflict deals 30% more. |  | You cannot land critical hits. |
| **Bitter Legacy** (Creeping Venom + Martyr's Gift) | STRUCK | When you faint, the next ally to enter heals 35% of its max HP and gains +1 Attack and Sp. Atk; foes that hit you have a 25% chance to be poisoned. |  | You take 12% more damage. |
| **Miasma Shell** (Creeping Venom + Aegis Oath) | STRUCK | You take 15% less damage; foes that hit you with a contact move have a 30% chance to be poisoned. |  | Your moves deal 20% less. |
| **Poisoned Dice** (Creeping Venom + Fickle Fortune) | HIT | Each turn a coin is flipped: heads, your moves deal 35% more that turn; your hits have a 20% chance to poison the foe. |  | Tails: your moves deal 20% less that turn. |
| **Gathering Storm** (Stormcaller + Crescendo) | TICK | Each turn you stay in adds 6% damage, up to 30%; while any weather is active your moves deal 10% more. |  | With no weather your moves deal 15% less. |
| **Blood Moon** (Stormcaller + Nightfang) | HIT | Your hits heal you 12% of the damage they deal; heal 3% of max HP at the end of each turn while any weather is active. |  | You take 25% more damage from Fire and Fairy moves. |
| **Lightning Rod** (Stormcaller + Iron Maiden) | STRUCK | Foes that hit you with a contact move have a 30% chance to be paralysed; foes that hit you with a contact move lose 6% of their max HP. |  | You take 15% more damage with no weather. |
| **Jet Stream** (Stormcaller + Tailwind Heart) | HIT | Your Speed is 25% higher; moves of the active weather's type deal 15% more. |  | Your Defense and Sp. Def are 12% lower. |
| **Storm Hunt** (Stormcaller + Huntmark) | HIT | Your first hit marks a foe; your later moves against it deal 15% more; while any weather is active your moves deal 10% more. |  | You cannot land critical hits. |
| **Rain of Remembrance** (Stormcaller + Martyr's Gift) | TICK | When you faint, the next ally to enter heals 40% of its max HP and gains +1 Attack and Sp. Atk; heal 3% of max HP at the end of each turn while any weather is active. |  | You take 15% more damage with no weather. |
| **Storm Bulwark** (Stormcaller + Aegis Oath) | STRUCK | You take 25% less damage while any weather is active. |  | Your moves deal 20% less. |
| **Thunder Lottery** (Stormcaller + Fickle Fortune) | HIT | Each turn a coin is flipped: heads, your moves deal 30% more that turn; moves of the active weather's type deal 15% more. |  | Tails: your moves deal 20% less that turn. |
| **Bloodletting** (Rupture + Crescendo) | HIT | Your physical hits cause bleeding; each turn you stay in adds 5% damage, up to 25%. |  | On your first two turns on the field your moves deal 20% less. |
| **Vampire Bite** (Rupture + Nightfang) | HIT | Your contact hits cause bleeding; your hits heal you 12% of the damage they deal. |  | You take 30% more damage from Fire and Fairy moves. |
| **Barbed Hide** (Rupture + Iron Maiden) | STRUCK | Foes that hit you with a contact move start bleeding; foes that hit you with a contact move lose 5% of their max HP. |  | Your contact moves deal 20% less. |
| **Razor Wind** (Rupture + Tailwind Heart) | HIT | Your Speed is 20% higher; your damaging hits cause bleeding. |  | Your Defense and Sp. Def are 15% lower. |
| **Blood Trail** (Rupture + Huntmark) | HIT | Your first hit marks a foe; your later moves against it deal 15% more; you deal 12% more to bleeding foes; your physical hits cause bleeding. |  | You cannot land critical hits. |
| **Blood Oath** (Rupture + Martyr's Gift) | KO | Your physical hits cause bleeding; when you faint, the next ally to enter heals 35% of its max HP and gains +1 Attack and Sp. Atk. |  | You take 12% more damage. |
| **Scarred Veteran** (Rupture + Aegis Oath) | STRUCK | You take 15% less damage; foes that hit you with a contact move start bleeding. |  | Your moves deal 20% less. |
| **Knife Toss** (Rupture + Fickle Fortune) | HIT | Each turn a coin is flipped: heads, your moves deal 30% more that turn; your damaging hits cause bleeding. |  | Tails: your moves deal 20% less that turn. |
| **Rising Colossus** (Titan's Heart + Crescendo) | HIT | Each turn you stay in adds 6% damage, up to 30%; a super-effective hit adds 6% of your max HP in damage. |  | Healing you receive is 50% lower. |
| **Giant's Hunger** (Titan's Heart + Nightfang) | HIT | A super-effective hit adds 7% of your max HP in damage; your hits heal you 10% of the damage they deal. |  | You take 25% more damage from Fire and Fairy moves. |
| **Iron Colossus** (Titan's Heart + Iron Maiden) | STRUCK | Foes that hit you with a super-effective move lose 10% of their max HP; foes that hit you with a contact move lose 5% of their max HP. |  | Your Speed is 15% lower. |
| **Falling Star** (Titan's Heart + Tailwind Heart) | HIT | Your Speed is 20% higher; a super-effective hit adds 7% of your max HP in damage. |  | Your Defense and Sp. Def are 15% lower. |
| **Giant Hunter** (Titan's Heart + Huntmark) | HIT | Your first hit marks a foe; your later moves against it deal 15% more; a super-effective hit adds 6% of your max HP in damage. |  | You cannot land critical hits. |
| **Titan's Legacy** (Titan's Heart + Martyr's Gift) | KO | A super-effective hit adds 6% of your max HP in damage; when you faint, the next ally to enter heals 40% of its max HP and gains +1 Attack and Sp. Atk. |  | Healing you receive is 50% lower. |
| **Living Fortress** (Titan's Heart + Aegis Oath) | STRUCK | You take 30% less damage from super-effective moves; you take 8% less damage. |  | Your moves deal 20% less. |
| **Titan's Wager** (Titan's Heart + Fickle Fortune) | HIT | Each turn a coin is flipped: heads, your moves deal 30% more that turn; a super-effective hit adds 6% of your max HP in damage. |  | Tails: your moves deal 20% less that turn. |
| **Compound Interest** (777 + Crescendo) | KO | Each turn you stay in adds 5% damage, up to 25%; item rewards +15%. |  | You take 15% more damage. |
| **Blood Money** (777 + Nightfang) | KO | Each KO heals you 10% of max HP; item rewards +15%. |  | You take 25% more damage from Fire and Fairy moves. |
| **Golden Cage** (777 + Iron Maiden) | STRUCK | Foes that hit you with a contact move lose 8% of their max HP; item rewards +15%. |  | Your contact moves deal 20% less. |
| **Gold Rush** (777 + Tailwind Heart) | KO | Your Speed is 20% higher; item rewards +15%. |  | Your Defense and Sp. Def are 15% lower. |
| **Bounty Hunter** (777 + Huntmark) | KO | Your first hit marks a foe; your later moves against it deal 15% more; item rewards +15%. |  | You cannot land critical hits. |
| **Inheritance** (777 + Martyr's Gift) | KO | When you faint, the next ally to enter heals 40% of its max HP and gains +1 Attack and Sp. Atk; item rewards +15%. |  | You take 15% more damage. |
| **Vault Keeper** (777 + Aegis Oath) | STRUCK | You take 15% less damage; item rewards +15%. |  | Your moves deal 20% less. |
| **All In** (777 + Fickle Fortune) | KO | Each turn a coin is flipped: heads, your moves deal 40% more that turn; item rewards +20%. |  | Tails: your moves deal 25% less that turn. |
| **Night Symphony** (Crescendo + Nightfang) | HIT | Each turn you stay in adds 6% damage, up to 30%; your hits heal you 10% of the damage they deal. |  | You take 25% more damage from Fire and Fairy moves. |
| **Tempered Steel** (Crescendo + Iron Maiden) | STRUCK | Each turn you stay in adds 5% damage, up to 25%; foes that hit you with a contact move lose 6% of their max HP. |  | On your first two turns on the field your moves deal 25% less. |
| **Accelerando** (Crescendo + Tailwind Heart) | HIT | Each turn you stay in adds 5% damage, up to 25%; your Speed is 20% higher. |  | Your Defense and Sp. Def are 12% lower. |
| **Stalker's Rhythm** (Crescendo + Huntmark) | HIT | Each turn you stay in adds 5% damage, up to 25%; your first hit marks a foe; your later moves against it deal 15% more. |  | You cannot land critical hits. |
| **Requiem** (Crescendo + Martyr's Gift) | TICK | Each turn you stay in adds 6% damage, up to 30%; when you faint, the next ally to enter heals 40% of its max HP and gains +1 Attack and Sp. Atk. |  | On your first two turns on the field your moves deal 20% less. |
| **Rising Bastion** (Crescendo + Aegis Oath) | STRUCK | You take 15% less damage; each turn you stay in adds 4% damage, up to 20%. |  | On your first two turns on the field your moves deal 30% less. |
| **Double or Nothing** (Crescendo + Fickle Fortune) | HIT | Each turn a coin is flipped: heads, your moves deal 30% more that turn; each turn you stay in adds 4% damage, up to 20%. |  | Tails: your moves deal 20% less that turn. |
| **Blood Maiden** (Nightfang + Iron Maiden) | STRUCK | Foes that hit you with a contact move lose 8% of their max HP; your hits heal you 10% of the damage they deal. |  | You take 25% more damage from Fire and Fairy moves. |
| **Night Flight** (Nightfang + Tailwind Heart) | HIT | Your Speed is 25% higher; your hits heal you 10% of the damage they deal. |  | Your Defense and Sp. Def are 15% lower. |
| **Blood Scent** (Nightfang + Huntmark) | HIT | Your first hit marks a foe; your later moves against it deal 15% more; your hits heal you 10% of the damage they deal. |  | You cannot land critical hits. |
| **Eternal Night** (Nightfang + Martyr's Gift) | KO | Each KO heals you 12% of max HP; when you faint, the next ally to enter heals 35% of its max HP and gains +1 Attack and Sp. Atk. |  | You take 25% more damage from Fire and Fairy moves. |
| **Crimson Aegis** (Nightfang + Aegis Oath) | STRUCK | You take 15% less damage; your hits heal you 8% of the damage they deal. |  | Your moves deal 20% less. |
| **Vampire's Gamble** (Nightfang + Fickle Fortune) | HIT | Each turn a coin is flipped: heads, your moves deal 30% more that turn; your hits heal you 10% of the damage they deal. |  | Tails: your moves deal 20% less that turn. |
| **Spiked Wheel** (Iron Maiden + Tailwind Heart) | HIT | Your Speed is 20% higher; foes that hit you with a contact move lose 6% of their max HP. |  | Your Defense and Sp. Def are 15% lower. |
| **Iron Trap** (Iron Maiden + Huntmark) | STRUCK | Foes that hit you with a contact move lose 8% of their max HP; your first hit marks a foe; your later moves against it deal 15% more. |  | You cannot land critical hits. |
| **Iron Martyr** (Iron Maiden + Martyr's Gift) | STRUCK | Foes that hit you with a contact move lose 8% of their max HP; when you faint, the next ally to enter heals 35% of its max HP and gains +1 Attack and Sp. Atk. |  | Your contact moves deal 20% less. |
| **Iron Aegis** (Iron Maiden + Aegis Oath) | STRUCK | You take 25% less damage from contact moves; foes that hit you with a contact move lose 6% of their max HP. |  | Your moves deal 25% less. |
| **Spiked Roulette** (Iron Maiden + Fickle Fortune) | STRUCK | Each turn a coin is flipped: heads, your moves deal 30% more that turn; foes that hit you with a contact move lose 6% of their max HP. |  | Tails: your moves deal 20% less that turn. |
| **Swift Hunt** (Tailwind Heart + Huntmark) | HIT | Your Speed is 20% higher; your first hit marks a foe; your later moves against it deal 15% more. |  | You cannot land critical hits. |
| **Last Flight** (Tailwind Heart + Martyr's Gift) | HIT | Your Speed is 25% higher; when you faint, the next ally to enter heals 35% of its max HP and gains +1 Attack and Sp. Atk. |  | Your Defense and Sp. Def are 15% lower. |
| **Gale Bulwark** (Tailwind Heart + Aegis Oath) | STRUCK | You take 15% less damage; your Speed is 15% higher. |  | Your moves deal 20% less. |
| **Fortune's Wind** (Tailwind Heart + Fickle Fortune) | HIT | Each turn a coin is flipped: heads, your moves deal 30% more that turn; your Speed is 15% higher. |  | Tails: your moves deal 20% less that turn. |
| **Pack Hunt** (Huntmark + Martyr's Gift) | HIT | Your first hit marks a foe; your later moves against it deal 20% more; when you faint, the next ally to enter heals 35% of its max HP and gains +1 Attack and Sp. Atk. |  | You cannot land critical hits. |
| **Sentinel's Watch** (Huntmark + Aegis Oath) | STRUCK | You take 15% less damage; your first hit marks a foe; your later moves against it deal 15% more. |  | You cannot land critical hits. |
| **Lucky Shot** (Huntmark + Fickle Fortune) | HIT | Each turn a coin is flipped: heads, your moves deal 30% more that turn; your first hit marks a foe; your later moves against it deal 15% more. |  | Tails: your moves deal 20% less that turn. |
| **Oathkeeper** (Martyr's Gift + Aegis Oath) | STRUCK | You take 18% less damage; when you faint, the next ally to enter heals 50% of its max HP and gains +1 Attack and Sp. Atk. |  | Your moves deal 25% less. |
| **Final Wager** (Martyr's Gift + Fickle Fortune) | HIT | Each turn a coin is flipped: heads, your moves deal 35% more that turn; when you faint, the next ally to enter heals 35% of its max HP and gains +1 Attack and Sp. Atk. |  | Tails: your moves deal 25% less that turn. |
| **Loaded Shield** (Aegis Oath + Fickle Fortune) | STRUCK | You take 15% less damage; each turn a coin is flipped: heads, your moves deal 25% more that turn. |  | Tails: your moves deal 20% less that turn. |
