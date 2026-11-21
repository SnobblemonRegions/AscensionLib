# Tower loot: rewarding and exclusive (research and proposal)

Status: **proposal, 2026-10-16, nothing built.** Written for the owner's question: how do we make Tower loot both rewarding and exclusive, so people want to grind the Tower for
the items they need? Every number is provisional. Sources are listed at the end; the web material is mostly secondary (interviews, guides, forum threads), so it is used for
shape, not for figures.

> **Update 2026-10-16: owner decisions.** Marks (5.3): yes. Region Crests and themed reforging (5.4): **no**, attunement already makes players grind the Tower. Core pity (5.5): **later**.
> Difficulty scaling and the daily budget (5.5b): yes. Trading controls (5.6): yes. Dungeon fragments at a lower rate (5.1): yes. New brief: each Tower completion (about an hour) must be
> very rewarding; the amounts and quality are in `TOWER-COMPLETION-REWARDS.md`, which supersedes the numbers in sections 5 and 6 here.

## 1. Constraints that already hold

From CobbleTowers' own design (`docs/design/engagement-roadmap.md`, P21, P24, P31, P36) and from this repo:

- **Skill and teamwork over grind; rewards bounded** (Towers TDS #9: hard-capped, diminishing; nothing repeatable on a button). Streaks buy cosmetics, titles and season points, not
  ever-growing piles. **Reward, never remove** (a season ends by adding, never taking away). **Never punish with lost Pokemon.** Opponents are not catchable.
- The wallet in the store is the source of truth. As of 2026-10-16 every currency can also be a **tradable voucher item** (an auction house will trade them), so **ownership is no
  longer exclusive; only the source and the gating can be.** This is the most important new fact for this question.
- Cores gate Legendary/Mythical promotion, Fusion and more, and the **Tower is the only core source** (trials and dungeons pay none, by rule).
- Dungeons (ascensionDungeons) now pay dust, facets, fragments and a Sigil chance per run (`settleDungeonRun`): they are the *broad* source.

## 2. What the Tower pays today, and how long goals take

Per player, per clean F10 run (two bosses): about 35 dust, 1.5 facets, 0.15 cores (7.5 percent per boss), 2.9 fragments (1.43 per boss), a Sigil chance (2 to 8 percent per boss
floor band) and Scouter drops, plus Towers' own items (candy, vitamins, balls, CobbleCards packs, Raid Points, CobbleDollars, armor pieces) and relics during the run.

| Goal (mean, no luck) | Runs |
|---|---:|
| 7 cores (Common to Mythic promotion) | 47 |
| 10 cores (one Fusion) | 67 |
| 17 cores (a Mythic that is then fused) | 113 |
| 40 fragments (one Catalyst; 100 until 12 Nov 2026) | 14 |

At about 14 keyed runs a week, a Mythic is about 3.3 weeks and a fused Mythic about 8. The Tower already *is* a long grind; the problem is how that grind **feels**.

## 3. What the research says (and what it means here)

1. **Acquisition is random by design, and deterministic crafting is deliberately worse than the random route** (Path of Exile's "Harvest Crafting" manifesto: PoE is a game
   about acquiring powerful items, acquisition is random, and choosable crafting outcomes are usually worse than what could have rolled). Takeaway: keep the chase random; if we
   let players *target*, price it above the random route.
2. **The industry is moving toward targeted farming, not away from it** (Diablo 4 is adding targeted Tempering, removing random rolls there, and raising Unique drop rates).
   Takeaway: players want to point their effort at *a* thing. Our Tower has three regions; a region should mean a different thing to farm.
3. **Bounded risk with a budget** (Last Epoch: each item has a limited Forging Potential; the Rune of Corruption makes unpredictable changes and ends further crafting). This is
   the same shape as our Overreach and seal. Takeaway: end-of-ladder gambles need a stop and a cost.
4. **Pity systems cap the downside but dilute the chase** (SWTOR's Bad Luck Prevention is per character and covers only protected loot; critics ask what a chase item is for if it
   is guaranteed). Takeaway: guarantee late and visibly, only for the few items players *need*, never for chase items.
5. **Binding is how top-end gear stays scarce** (making the best gear bind on pickup means the only way to get it is to earn it; equip-binding was a response to hand-me-down
   economies). Sinks that scale as a percentage hold up across a game's life where flat ones go meaningless; listing fees and taxes are sinks that also discourage spam.
6. **Variable rewards drive engagement, with a known downside** (variable-ratio schedules are the strongest conditioners, which is exactly why they need a ceiling and a guardrail).

## 4. Diagnosis: why Tower loot may not yet "pull"

1. **Droughts on the one thing that matters.** A core arrives about every 6.7 runs on average, and 4.4 percent of players go 20 runs (40 bosses) without one. Most runs end with
   nothing toward the long goal, and nothing shows progress.
2. **Exclusivity rests on one fact** ("cores come from the Tower") and ownership is now tradable, so a player can buy rather than run. That is fine for the economy (the Tower still
   *makes* the supply) but weakens the reason to run for anyone with currency.
3. **Every region pays the same kind of thing.** Regional towers add flavour items and armor, but nothing the player *needs* differs by region, so there is no targeted farming.
4. **Reward size does not follow skill.** The run's difficulty (modifier cards, Ascension depth) changes how hard it is, but not clearly what it pays.
5. **Dungeons will compete.** Facets, fragments and Sigils also come from dungeon runs; without a clear split the Tower is "the same, but slower".

## 5. Proposal

### 5.1 Give each source a role
- **Dungeons: volume and variety.** Dust, facets, items, captures; chest loot; no cores. Pay fragments only from a boss and at a lower rate than the Tower (today the contract pays 1 to
  2 per boss win; consider 1 and a lower chance).
- **Tower: curated, skill-gated power.** Cores, the bulk of fragments, Tower Marks and Region Crests (below), armor, relics.

### 5.2 An exclusivity ladder: four kinds, each doing one job
| Kind | What | Why it creates grind |
|---|---|---|
| **Source-exclusive, tradable fuel** | cores, fragments, region crests (only the Tower pays them; vouchers let them trade) | the Tower keeps making the supply; traders fund grinders |
| **Bound proof** | **Tower Marks**: earned only by your own clears, **cannot be withdrawn or traded** | a purchase cannot replace running the Tower at the top end |
| **Skill-gated tiers** | higher drop tiers behind Ascension depth, mastery rank or run difficulty | veterans have a reason to climb; newcomers cannot skip |
| **Seasonal / cosmetic** | titles, banners, set cosmetics (Towers' seasons plan) | prestige that never inflates power ("reward, never remove") |

### 5.3 Tower Marks (the bound item)
A new wallet material, `tower_mark`, **excluded from voucher withdrawal** (a per-material `tradable` flag). Earned by your own clears: 1 for a F5 boss, 3 for the F10 champion, plus a
bonus per Ascension cycle and per extra challenge card, capped per week. Spent where the *end of the ladder* is: **Overreach (1 Mark), a Fusion (5 Marks), installing a
Catalyst Unique (1 Mark)**. Cores, facets and dust stay tradable, so the economy flows, but the top rungs always need a Tower clear by that player. Sizing: about 4 Marks a run
(two bosses plus bonuses) keeps a Fusion at roughly 1.5 runs of Marks on top of its 67 runs of cores, so Marks never become the bottleneck but are always required.

### 5.4 Region Crests and themed reforging (targeted farming)
Each regional tower pays its own **crest** (Tideforge, Rootvale, Duskvale; tradable). A **themed reforge** spends 1 crest plus the normal reforge cost and draws only from the affixes
of one Resonance theme the crest's region offers (one random affix of that theme, so it stays random inside the theme, and costs more than the random reforge, per the Harvest
lesson). Proposed mapping (to match each region's doctrine): Tideforge offers Tempo and Predator; Rootvale Vitality and Bulwark; Duskvale Toxin and Predator. A player who wants a
Toxin build farms Duskvale; a Vitality build, Rootvale. Crests also feed Towers' planned Armor Forge stars, so one currency serves both. This reuses Resonance, which already
exists, and gives the three regions different reasons to run.

### 5.5 Visible progress and bounded pity
- **Core meter.** Show "Core luck" on the run summary and the wallet page. Guarantee a core after **30 bosses without one** (about 15 runs): 9.6 percent of players ever reach it, the
  mean wait falls only from 6.7 to 6.0 runs, and the worst case becomes 15 runs. Reset on a natural core. Per player, persisted. No pity on Sigils or on chase items.
- **Catalyst progress** is already a count; show it as a bar ("23 of 40 fragments").
- **Every run moves something:** Marks, a crest, a meter, or items. The run report lists what moved.

| Pity at (bosses) | Players who reach it | Mean runs per core | Worst case (runs) |
|---:|---:|---:|---:|
| none | n/a | 6.7 | unbounded (4.4 percent exceed 20) |
| 40 | 4.4 percent | 6.4 | 20 |
| 30 | 9.6 percent | 6.0 | 15 |
| 25 | 14.2 percent | 5.7 | 12 |

### 5.5b Reward follows difficulty, not speed
Scale **chances and amounts** of dust, facets, fragments and Marks (not the pity guarantee) by the run's difficulty score: for example +8 percent per challenge card up to +40, and
+5 percent per Ascension cycle up to +25. Keep the daily full-reward budget idea for plain clears (20 full per day, then 25 percent of dust) so repeating the easiest run is worth
less than a harder one: skill, not button-mashing, is what pays more. Both caps are data, not code.

### 5.6 Trading controls (since vouchers are now real)
- A per-material `tradable` flag (Marks: no; everything else: yes by default), settable in `design/currency-items.json`.
- A small **withdrawal fee** (suggest 3 percent of the amount, rounded up, taken in the same material): a soft sink that also discourages micro-flipping and mule chains. Percentage-based,
  so it scales with the economy.
- Ask the auction mod for a listing fee or tax: the cleanest sink for trade volume.
- Watch the price of a core. If it trades below the value of a Tower run, the faucet is too generous; if it never trades, the drop is too scarce or the fee too high.

### 5.7 Presentation
A milestone reveal that shows the *meters moving* (core luck, Marks, crests, fragments), a run report card, and a party chat line when someone gets a core or a Catalyst. Loot rolls stay
per player (no ninja-looting) as they are today.

## 6. Effects on pacing (proposed values, same assumptions as section 2)
- Mythic (7 cores): about 42 runs with pity at 30 (from 47), with a hard worst case instead of a tail.
- Fusion: 10 cores plus 5 Marks; Marks arrive at about 4 per run, so they gate access without adding runs.
- Targeted builds: a themed reforge needs a crest; a Tower run pays 0 to 1, and about 8 to 12 crests reforge a full six-slot build once, which is a few weeks of focused farming of one region.

## 7. Risks
- **Guardrail tension.** "Skill over grind" and "grind the tower for items" pull apart. Section 5.5b is the compromise: difficulty, not volume, scales rewards; daily caps stay.
- **Pity weakens the chase.** Mitigated by applying it only to cores, only late, and keeping Sigils, crests and armor un-pitied.
- **Mules and alts.** A voucher chain could funnel cores from many accounts. The withdrawal fee, Marks (bound, personal) and per-account Tower keys limit it; monitor, do not block.
- **Complexity.** Four new material ids (Marks and three crests) and a per-material `tradable` flag. All wallet materials are generic, so this is data plus a contract change.
- **No live data.** Every figure is arithmetic. The first step after building is to read Towers' `masteryadmin counters` and real runs before tuning.
- **Two repos.** Tower payouts and the Tower reward tables live in CobbleTowers; the wallet, bands, crafting and trading live here. The AscensionLib side is a few new materials and
  a contract method; the rest is Tower reward-table data.

## 8. Build plan (if approved)
1. **AscensionLib:** `tower_mark` and the three crests as materials (with art), a per-material `tradable` flag honoured by `withdraw`, a withdrawal fee, `settleTowerBoss` extended
   (or a new `settleTowerRun`) to pay Marks and crests and to track core pity (store column, schema 4), a meter payload for the pages, the themed reforge craft, Marks as a cost on
   Overreach, Fusion and Catalyst install. Tests: pity guarantee and reset, replay does not double-pay, Marks never withdrawable, themed reforge pool, fee rounding.
2. **CobbleTowers (its own repo, its own review):** reward-table entries for crests by region, a difficulty-score multiplier in the payout call, the run-report meters. Contract changes
   are additive reflection methods, as today.
3. **Tuning pass** from live counters and a market check; update `tools/analyze_balance.py` to model the new faucets.

## 9. Decisions for the owner
1. Tower Marks as the bound proof: yes or no, and which crafts require them (Overreach, Fusion, Catalyst install)?
2. Crests and themed reforging: approve the region-to-theme mapping, or choose your own?
3. Core pity at 30 bosses with a visible meter?
4. Difficulty scaling (+8 percent per challenge card, +5 percent per Ascension cycle, capped) and the 20-per-day full-reward budget?
5. A 3 percent withdrawal fee, and a request to the auction mod for a listing fee?
6. Do dungeons keep paying fragments (at a lower rate) or none?

## Sources
- [Development Manifesto: Harvest Crafting (Path of Exile)](https://web.poecdn.com/forum/view-thread/3069670/page/476)
- [Interview: Chris Wilson on Path of Exile 2's origins](https://www.fandom.com/articles/2050-interview-chris-wilson-on-path-of-exile-2s-origins)
- [GDC: Designing Path of Exile (80.lv)](https://80.lv/articles/gdc-designing-path-of-exile)
- [Diablo 4 Season of Reckoning rewards (Icy Veins)](https://www.icy-veins.com/d4/news/diablo-4-season-of-reckoning-rewards-get-a-massive-upgrade/)
- [Diablo 4 season 10 PTR: loot and tempering changes (PC Games Hardware, German)](https://www.pcgameshardware.de/Diablo-4-Spiel-55956/News/Season-10-PTR-Start-Aenderungen-Kaempfe-Loot-Beute-1484511/)
- [Last Epoch crafting explained (Gfinity)](https://www.gfinityesports.com/guides/last-epoch-crafting-explained) and [Maxroll beginner crafting guide](https://maxroll.gg/last-epoch/resources/beginner-crafting-guide)
- [Blizzard forum thread on bad luck protection for Ubers](https://us.forums.blizzard.com/en/d4/t/blizz-looking-at-bad-luck-protection-for-ubers-and-better-accessibility-for-boss-summoning-mats-in-future/144692) (unofficial) and the SWTOR Bad Luck Prevention description it cites
- [Pity System (glossary)](https://track360.io/glossary/pity-system)
- [Game Wisdom: the anatomy of engagement](https://game-wisdom.com/general/anatomy-engagement-modern-rpgs-borrowed-classic-slot-architecture)
- [GameDev Stack Exchange: bind on pickup and economy](https://gamedev.stackexchange.com/a/1792) and [Designing Game Economies (Medium)](https://medium.com/@msahinn21/designing-game-economies-inflation-resource-management-and-balance-fa1e6c894670)
