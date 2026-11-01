# Balance simulation (dev-only)

`balance-sim` runs 90 days of the Threat economy with 250 synthetic players and no Minecraft. It is never published or
packed into the mod; CI runs it on every push.

```
./gradlew :balance-sim:test                         # invariants (CI)
./gradlew :balance-sim:test -PsimStrict=true        # also the spec's pacing and sink targets
./gradlew :balance-sim:simulate [-PsimSeed=1 -PsimPlayers=500 -PsimDays=120]   # build/balance-sim/report.{md,json}
```

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
