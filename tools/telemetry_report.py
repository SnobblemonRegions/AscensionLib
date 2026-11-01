"""Summarises AscensionLib's telemetry files for balancing; Python stdlib only.

usage: python -I tools/telemetry_report.py <world>/cobbleascend/telemetry [--days N]

Reads every events-YYYY-MM-DD.jsonl in the folder (or only the newest N days) and prints: material income by source and spending by use,
craft outcomes by kind, refusals, how new profiles roll by origin and tier, win rate by encounter kind, the money supply over time, and the
slowest performance metrics. The event list is documented in BalanceLog.java.
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
    slow = sorted(perf.items(), key=lambda kv: -kv[1]["p95"])[:20]
    table("performance (worst p95 first; ms, or characters for emit.*)", [[k, v["n"], f"{v['sum'] / v['n']:.2f}", v["p95"], v["max"]] for k, v in slow], ["metric", "n", "avg", "worst p95", "max"])
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
