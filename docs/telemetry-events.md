# Gameplay telemetry events from CobbleRaids and CobbleTowers

AscensionLib's telemetry log (`<world>/cobbleascend/telemetry/events-YYYY-MM-DD.jsonl`, see `Telemetry.java`) also carries events
that CobbleRaids and CobbleTowers write through `AscensionTelemetry.event(type, fields)`. They answer where players struggle,
wait and quit. Every line has `t` (epoch ms) and `type`. Players are written as 12-character player keys (the same keys the
library's own events use), never as UUIDs. Without AscensionLib, or with an AscensionLib older than this call, the other mods
write nothing.

`python -I tools/telemetry_report.py <folder>` summarises them (raid endings by tier, tower run endings, floors, waits).

## CobbleRaids

| type | when | fields | answers |
|---|---|---|---|
| `raid_end` | every raid ending: victory, wipe, timeout, last player withdrew / fled / disconnected, operator abort, server stop, stranded | `id` (`raid:<boss>`, joins `encounter`/`settlement`), `mode` (`party`, `duel`, `owned`), `owner` (owned only), `raid`, `tier`, `threat`, `species`, `level`, `outcome` (`victory`/`defeat`/`aborted`), `reason`, `partySize`, `activeAtEnd`, `seconds`, `timeLimit`, `bossHpLeft` (0-1), `dealers`, `players` | loss rate and difficulty per tier and boss; how close losses were; timeouts vs wipes; party size vs success |
| `raid_left` | a player leaves a raid that goes on | `id`, `mode`, `raid`, `tier`, `reason` (`withdrew`/`fled`/`disconnected`), `seconds`, `bossHpLeft`, `stillIn`, `player` | when in a fight players give up |

`owned` raids are fights another system runs as raids: CobbleTowers boss floors (`owner` `cobbletowers:floor`) and each player's
copy inside a duel raid (`owner` `cobbleraids:duel_raid`). A duel raid writes one `owned` line per copy plus its own `mode: duel`
line, so leave `owned` out of raid-tier loss rates. A duel raid that ends with nobody left takes the last player out's reason
(`wipe` if they were knocked out, else `withdrew` / `fled` / `disconnect`). The log closes after the server has stopped, so
endings during a shutdown (`server_stop`, interrupted floors, `leave`) are kept.

## CobbleTowers

| type | when | fields | answers |
|---|---|---|---|
| `tower_run_end` | a run reaches completed, cashed out, failed or abandoned | `run`, `tower`, `floor`, `threat` (entry Threat, 0 = none), `state`, `cause` (`completed`, `wiped`, `cashed_out`, `abandoned`, `left`, `disconnect` (also a floor lost with nobody online), `restart` (a run a restart parked), `parked_abandoned` (a run a fault parked), `technical`, `start_failed`, `party_rejected`), `from`, `lastBankedFloor`, `kind`, `partySize`, `players`, `seconds`, `floorsCleared`, `avgFloorSeconds` | the floor runs end on and why; Threat chosen vs outcome |
| `tower_floor` | a floor's fight ends | `run`, `tower`, `floor`, `threat`, `result` (`cleared`, `wiped`, `left`, `interrupted`), `seconds`, `partySize` | time per floor; which floors kill runs |
| `tower_arena_wait` | the party's arena is ready, or the run ends while waiting | `run`, `tower`, `floor`, `threat`, `first` (first floor or next floor), `ms`, `outcome` (`ready`/`ended`), `busyRefusals` | time on "Your arena is being prepared"; quits during the wait |
| `tower_run_parked` | a run is parked for recovery | `run`, `tower`, `floor`, `threat`, `from`, `event`, `cause` (`restart` = boot-time recovery, `technical` = a fault during play) | how often and where runs break, and how many are faults rather than restarts |
| `tower_alloc_refused` | an allocation is refused with `Reason.BUSY` | `run`, `tower`, `floor`, `reason`, `pendingReleases` | whether the cell-release backlog blocks starts |
| `tower_cell_quarantined` | a cell is taken out of service | `cell`, `reason` | how often cells break, and why |

## Aggregating (jq)

```bash
# raid loss rate by tier (party raids)
cat events-*.jsonl | jq -r 'select(.type=="raid_end" and .mode!="owned") | [.tier,.outcome] | @tsv' | sort | uniq -c
# how close the losses were: median boss HP left on defeats, by tier
cat events-*.jsonl | jq -r 'select(.type=="raid_end" and .outcome=="defeat") | [.tier,.reason,.bossHpLeft] | @tsv'
# where tower runs end and why, by entry Threat
cat events-*.jsonl | jq -r 'select(.type=="tower_run_end") | [.threat,.state,.cause,.floor] | @tsv' | sort | uniq -c
# arena wait: count, total seconds, and how many parties left while waiting
cat events-*.jsonl | jq -s '[.[]|select(.type=="tower_arena_wait")] | {n:length, s:(map(.ms)|add/1000), ended:(map(select(.outcome=="ended"))|length)}'
```

Economy sources and sinks were already logged before these events: every committed store operation is a `ledger` event with
`kind`, `detail`, `spent` and `gained`, and the report prints income by source and spending by use.
