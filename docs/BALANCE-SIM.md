# Balance simulation (dev-only)

`balance-sim` runs 90 days of the Threat economy with 250 synthetic players and no Minecraft. It is never published or
packed into the mod; CI runs it on every push.

```
./gradlew :balance-sim:test                         # invariants (CI)
./gradlew :balance-sim:test -PsimStrict=true        # also the spec's pacing and sink targets
./gradlew :balance-sim:simulate [-PsimSeed=1 -PsimPlayers=500 -PsimDays=120]   # build/balance-sim/report.{md,json}
./gradlew :balance-sim:simulate -PsimSinks=true     # also the Reclaimer and Wager Altar prototypes (below)
```

**Sink prototypes (not shipped rules).** Players finish their promotions around day 30 and then pile up materials with nothing to spend them
on. `Sinks` models two concepts so they can be sized before anything is built, both off unless `-PsimSinks=true`: the *Reclaimer* turns an
unwanted Pokemon into dust by rarity alone (3 / 10 / 35 / 110 / 400 / 1500, the first 20 a day in full), and the *Wager Altar* takes a
stake in three tiers on separate materials (Bronze 2,000 dust; Silver 3,000 dust and 150 facets; Gold 30 Cores and 60 Apex Shards) for prizes that are
not tracked materials (65% of the stake's assumed value back). Gamblers keep a reserve of each material for their remaining promotions. The prototypes draw from their own random
stream, so switching them on never changes the base economy. `Model.gambleParticipation`, `wagerPlaysPerDay` and `wagerReserve` set the appetite.

**Rules come from the shipped domain code**: promotion prices (`threatPromotions`), Tower completion pay
(`TowerCompletionBands.payoutThreat` + the mini chest), raid pay (`RaidRewardBands.pay`), the door's advice
(`ThreatAdvice`) and the daily caps (`ThreatSettings`). Change any of them and the next run shows the effect.

**What is modelled** (`Simulation.Model`, change when the phase-0 logs measure it): 45-minute Tower clears and 15-minute
raids; win chance by Threat gap (95 / 85 / 50 / 15 / 3% for -1 / 0 / +1 / +2 / +3, the spec's targets); groups of 4+ fight
one Threat lower; 20 attunement per Tower; 25% raid catch with the CobbleRaids rarity floor; one wild capture a play day.
Players: Casual, Regular, Tower-heavy, Raid-heavy, Grinder, saving Cores and Apex for their best Pokemon until it is Mythic.

**Four parts, each checked**

- Ledger: every material held equals minted minus spent; no wallet goes negative.
- Ecology: wild and raid captures replace a team's weakest Pokemon.
- Economy: pay in, promotions out, per material.
- Stability: last-30-day sink ratio (spent / minted) and median wallet at the end.
