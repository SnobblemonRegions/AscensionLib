"""Summarises AscensionLib's telemetry files for balancing; Python stdlib only.

usage: python -I tools/telemetry_report.py <world>/cobbleascend/telemetry [--days N]

Reads every events-YYYY-MM-DD.jsonl in the folder (or only the newest N days) and prints: material income by source and spending by use,
craft outcomes by kind, refusals, how new profiles roll by origin and tier, win rate by encounter kind, the money supply over time, and the
slowest performance metrics, and the raid and tower events CobbleRaids and CobbleTowers write (docs/telemetry-events.md):
raid endings by tier, tower run endings by Threat, floors and arena waits. The event list is documented in BalanceLog.java.
"""
import json
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path


RARITIES = ["common", "uncommon", "rare", "epic", "legendary", "mythical"]


def load(folder, days):
    files = sorted(Path(folder).glob("events-*.jsonl"))
    for path in files[-days:] if days else files:
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if not line.strip():
                continue
            try:
                yield json.loads(line)
            except json.JSONDecodeError:
                print(f"skipped {path.name}:{number} (not JSON)", file=sys.stderr)


def source_of(detail):
    """'encounter <uuid> tower_boss (reduced)' -> 'tower_boss'; anything else is its own source."""
    match = re.match(r"encounter \S+ (\S+)", detail or "")
    return match.group(1) if match else (detail or "?")


def table(title, rows, head):
    print(f"\n== {title}")
    if not rows:
        print("   (none)")
        return
    widths = [max(len(str(r[i])) for r in rows + [head]) for i in range(len(head))]
    for row in [head] + rows:
        print("   " + "  ".join(str(c).ljust(w) for c, w in zip(row, widths)))


def main(argv):
    if not argv or argv[0].startswith("-"):
        print(__doc__)
        return 2
    days = int(argv[argv.index("--days") + 1]) if "--days" in argv else 0
    income, spending = defaultdict(Counter), defaultdict(Counter)
    crafts, refused, acquires, settlements = defaultdict(list), Counter(), Counter(), defaultdict(Counter)
    by_team = defaultdict(Counter)  # (kind, team rarity) -> encounters seen, from encounter events with teamRarity
    perf, supply, sessions, players = {}, [], [], set()
    raid_ends, raid_left = defaultdict(Counter), Counter()  # (mode, tier) -> outcome:reason; (tier, reason) -> leavers
    raid_hp = defaultdict(list)  # tier -> boss HP left on each defeat
    tower_ends, tower_floors, waits = Counter(), defaultdict(list), []
    total = 0
    for e in load(argv[0], days):
        total += 1
        kind = e.get("type")
        if "player" in e:
            players.add(e["player"])
        if kind == "ledger":
            for material, amount in e.get("gained", {}).items():
                income[source_of(e.get("detail") or e["kind"].lower())][material] += amount
            for material, amount in e.get("spent", {}).items():
                spending[e["kind"].lower() + (":" + e["detail"] if e.get("detail") else "")][material] += amount
        elif kind == "craft":
            crafts[e["kind"]].append(e)
        elif kind == "craft_refused":
            refused[(e["kind"], e["reason"])] += 1
        elif kind == "acquire":
            acquires[(e["origin"], e["tier"], e["rarity"])] += 1
        elif kind == "encounter" and "teamRarity" in e:
            by_team[(e["kind"], RARITIES[min(5, round(e["teamRarity"]))])][e.get("outcome", "settled")] += 1
        elif kind == "settlement":
            for status in e["players"].values():
                settlements[e["kind"]][(e["outcome"], status)] += 1
        elif kind == "economy":
            supply.append(e)
        elif kind == "leave" and "seconds" in e:
            sessions.append(e["seconds"])
        elif kind == "raid_end":
            raid_ends[(e.get("mode", "?"), e.get("tier", "?"))][e.get("reason", "?")] += 1
            if e.get("outcome") == "defeat" and "bossHpLeft" in e:
                raid_hp[(e.get("mode", "?"), e.get("tier", "?"))].append(e["bossHpLeft"])
        elif kind == "raid_left":
            raid_left[(e.get("tier", "?"), e.get("reason", "?"))] += 1
        elif kind == "tower_run_end":
            tower_ends[(e.get("threat", 0), e.get("state", "?"), e.get("cause", "?"))] += 1
        elif kind == "tower_floor":
            tower_floors[(e.get("floor", 0), e.get("result", "?"))].append(e.get("seconds"))
        elif kind == "tower_arena_wait":
            waits.append(e)
        elif kind == "perf":
            for label, m in e["metrics"].items():
                p = perf.setdefault(label, {"n": 0, "sum": 0.0, "p95": 0.0, "max": 0.0, "unit": m.get("unit", "ms")})
                p["n"] += m["n"]; p["sum"] += m["avg"] * m["n"]; p["p95"] = max(p["p95"], m["p95"]); p["max"] = max(p["max"], m["max"])

    print(f"{total} events, {len(players)} players, {len(sessions)} finished sessions"
          + (f", average {sum(sessions) / len(sessions) / 60:.0f} min" if sessions else ""))
    table("income by source (materials gained)", [[s, sum(c.values()), dict(c)] for s, c in sorted(income.items())], ["source", "total", "by material"])
    table("spending by use (materials spent)", [[s, sum(c.values()), dict(c)] for s, c in sorted(spending.items())], ["use", "total", "by material"])
    rows = []
    for kind, items in sorted(crafts.items()):
        gains = [i["valueAfter"] - i["valueBefore"] for i in items if "valueAfter" in i and "valueBefore" in i]
        rows.append([kind, len(items), f"{sum(gains) / len(gains):+.2f}" if gains else "-", sum(1 for g in gains if g > 0), sum(1 for g in gains if g == 0), sum(1 for g in gains if g < 0)])
    table("crafts (value change of the slot)", rows, ["kind", "count", "avg change", "up", "same", "down"])
    table("refused crafts", [[k, r, n] for (k, r), n in refused.most_common(15)], ["kind", "reason", "count"])
    table("new profiles", [[o, t, r, n] for (o, t, r), n in sorted(acquires.items())], ["origin", "tier", "rarity", "count"])
    table("encounters (outcome, status per player)", [[k, o, s, n] for k, c in sorted(settlements.items()) for (o, s), n in sorted(c.items())], ["kind", "outcome", "status", "count"])
    table("encounters by team rarity (mean party rarity, rounded)", [[k, r, sum(c.values()), dict(c)] for (k, r), c in sorted(by_team.items(), key=lambda kv: (kv[0][0], RARITIES.index(kv[0][1])))], ["kind", "team rarity", "count", "by outcome"])
    if supply:
        first, last = supply[0], supply[-1]
        table("money supply (first and last snapshot)", [[m, first["supply"].get(m, 0), last["supply"].get(m, 0), last["supply"].get(m, 0) - first["supply"].get(m, 0)]
                                                          for m in sorted(set(first["supply"]) | set(last["supply"]))], ["material", "first", "last", "change"])
    rows = []
    for (mode, tier), reasons in sorted(raid_ends.items()):
        total_r = sum(reasons.values())
        won = reasons.get("victory", 0)
        hp = sorted(raid_hp.get((mode, tier), []))
        rows.append([mode, tier, total_r, f"{100 * won / total_r:.0f}%", f"{hp[len(hp) // 2]:.2f}" if hp else "-", dict(reasons)])
    table("raid endings (win rate, median boss HP left on a defeat)", rows, ["mode", "tier", "raids", "won", "hp left", "by reason"])
    table("players leaving a raid early", [[t, r, n] for (t, r), n in sorted(raid_left.items())], ["tier", "reason", "count"])
    table("tower run endings by entry Threat", [[t, s, c, n] for (t, s, c), n in sorted(tower_ends.items())], ["threat", "state", "cause", "runs"])
    table("tower floors (count, median seconds)", [[f, r, len(v), sorted(x for x in v if x is not None)[len([x for x in v if x is not None]) // 2] if any(x is not None for x in v) else "-"]
                                                   for (f, r), v in sorted(tower_floors.items())], ["floor", "result", "count", "median s"])
    if waits:
        table("arena waits (\"being prepared\")", [["first floor" if first else "next floor", len(g), f"{sorted(x['ms'] for x in g)[len(g) // 2] / 1000:.1f}", max(x["ms"] for x in g) / 1000,
                                                  sum(1 for x in g if x.get("outcome") == "ended")]
                                                 for first, g in ((True, [w for w in waits if w.get("first")]), (False, [w for w in waits if not w.get("first")])) if g],
              ["wait", "count", "median s", "max s", "left while waiting"])
    slow = sorted(perf.items(), key=lambda kv: -kv[1]["p95"])[:20]
    table("performance (worst p95 first; ms, or characters for emit.*)", [[k, v["n"], f"{v['sum'] / v['n']:.2f}", v["p95"], v["max"]] for k, v in slow], ["metric", "n", "avg", "worst p95", "max"])
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
