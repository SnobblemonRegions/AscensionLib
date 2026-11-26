"""Validate proposed content and calculate exact design odds; Python stdlib only."""
from collections import defaultdict
from pathlib import Path
import json
import math

ROOT = Path(__file__).resolve().parents[1]


def read(name):
    return json.loads((ROOT / "design" / name).read_text(encoding="utf-8-sig"))


def require(condition, message):
    if not condition:
        raise ValueError(message)


def core_distribution(wins, chance, guarantee, target):
    """Exact DP, capped at target cores; state is (cores, consecutive misses)."""
    states = {(0, 0): 1.0}
    for _ in range(wins):
        next_states = defaultdict(float)
        for (cores, misses), probability in states.items():
            if cores == target:
                next_states[(cores, 0)] += probability
                continue
            hit = 1.0 if misses == guarantee - 1 else chance
            next_states[(min(target, cores + 1), 0)] += probability * hit
            if hit < 1:
                next_states[(cores, misses + 1)] += probability * (1 - hit)
        states = next_states
    require(abs(sum(states.values()) - 1) < 1e-10, "Core probabilities lost mass")
    return sum(prob for (cores, _), prob in states.items() if cores == target)


def validate(balance, catalog, example, ranked):
    tiers = balance["rarities"]
    affixes = catalog["affixes"]
    require(len(tiers) == 6, "Expected six rarities")
    require(sum(t["weight"] for t in tiers) == 10_000, "Weights must sum to 10,000")
    require(all(isinstance(t["weight"], int) and t["weight"] > 0 for t in tiers), "Invalid weight")
    require(len({t["id"] for t in tiers}) == 6, "Duplicate rarity")
    require(len(affixes) == 54, "Expected fifty-four affix templates (34 launch + 20 expansion)")
    require(len({a["id"] for a in affixes}) == len(affixes), "Duplicate affix ID")
    for affix in affixes:
        require(affix["slot"] in ("prefix", "suffix"), "Invalid slot category")
        require(affix["channel"] in balance["capsPercent"], "Unknown effect channel")
        require(0 <= affix["min"] <= affix["max"] <= 100, "Invalid affix range")
        require(affix["weight"] > 0, "Invalid affix weight")
    for index, tier in enumerate(tiers):
        require(tier["prefixSlots"] + tier["suffixSlots"] == index + 1, "Invalid slot progression")
        for category in ("prefix", "suffix"):
            families = {a["family"] for a in affixes if a["slot"] == category}
            require(len(families) >= tier[category + "Slots"], "Insufficient distinct affix families")
        if index:
            previous = tiers[index - 1]
            require(all(tier[k] >= previous[k] for k in ("prefixSlots", "suffixSlots")), "Promotion loses slots")
    promotions = balance["promotions"]
    require(len(promotions) == len(tiers) - 1, "Incomplete promotion path")
    for index, promotion in enumerate(promotions):
        require(promotion["from"] == tiers[index]["id"] and promotion["to"] == tiers[index + 1]["id"], "Broken promotion chain")
        require(all(isinstance(promotion[key], int) and promotion[key] >= 0 for key in ("dust", "facets", "cores", "attunement")), "Invalid promotion cost")
        if index:
            require(promotion["attunement"] > promotions[index - 1]["attunement"], "Attunement gate not increasing")
    require(all(isinstance(cap, int) and 0 <= cap <= 100 for cap in balance["capsPercent"].values()), "Invalid channel cap")
    require(balance["capsPercent"]["incomingReduction"] <= 90, "Reduction cap would approach invulnerability")
    profile = example["cobbleascend"]
    tier = next(t for t in tiers if t["id"] == profile["rarity"])
    definitions = {a["id"]: a for a in affixes}
    families, slots = set(), set()
    for rolled in profile["ordinarySlots"]:
        require(rolled["affixId"] in definitions, "Unknown example affix")
        definition = definitions[rolled["affixId"]]
        category, slot_index = rolled["slotId"].split(":")
        require(category == definition["slot"] and 0 <= int(slot_index) < tier[category + "Slots"], "Example slot mismatch")
        require(rolled["slotId"] not in slots, "Duplicate example slot")
        require(definition["family"] not in families, "Example duplicate family")
        require(rolled["affixId"] in ranked["ranks"] and 1 <= rolled["rank"] <= 5, "Example rank missing")
        band_min, band_max = ranked["ranks"][rolled["affixId"]][rolled["rank"] - 1]
        require(band_min <= rolled["rolledValue"] <= band_max, "Example roll outside its rank band")
        families.add(definition["family"])
        slots.add(rolled["slotId"])
    require(len(slots) == tier["prefixSlots"] + tier["suffixSlots"], "Unfilled example slots")


def analyze(balance, catalog):
    tiers = balance["rarities"]
    total = sum(t["weight"] for t in tiers)
    rows = ["# Balance model results", "", "Generated from the proposed JSON files by `python tools/analyze_balance.py`.", "", "These are exact arithmetic/probability calculations, not measured gameplay or a Minecraft integration test.", "", "## Capture odds", "", "| Rarity | Chance per capture | Mean captures to first | Captures for at least 95% chance |", "|---|---:|---:|---:|"]
    for tier in tiers:
        probability = tier["weight"] / total
        percentile = math.ceil(math.log(0.05) / math.log1p(-probability))
        rows.append(f"| {tier['id'].title()} | {probability:.2%} | {1 / probability:,.2f} | {percentile:,} |")
    mythic = tiers[-1]["weight"] / total
    rows += ["", "| Captures | Chance of at least one Mythical |", "|---:|---:|"]
    for count in (100, 500, 1000, 2000, 3000):
        rows.append(f"| {count:,} | {1 - (1 - mythic) ** count:.2%} |")
    expected_slots = sum(t["weight"] * (t["prefixSlots"] + t["suffixSlots"]) for t in tiers) / total
    rows += ["", f"Expected initial affix slots per capture: **{expected_slots:.3f}**. No capture pity is assumed.", "", "## Promotion budget", ""]
    costs = {key: sum(p[key] for p in balance["promotions"]) for key in ("dust", "facets", "cores")}
    rows.append(f"Common to Mythical: **{costs['dust']} Dust, {costs['facets']} Facets, {costs['cores']} Cores**; {balance['promotions'][-1]['attunement']} lifetime attunement.")
    attunement_wins = math.ceil(balance["promotions"][-1]["attunement"] / balance["attunementPerVictory"])
    rank3 = balance["trialRewards"][-1]
    dust_wins = math.ceil(costs["dust"] / rank3["dust"])
    facet_wins = math.ceil(costs["facets"] / rank3["facets"])
    chance = rank3["coreChance"]
    guarantee = balance["coreGuaranteedByEligibleWin"]
    expected_wait = sum((1 - chance) ** n for n in range(guarantee))
    core_p95 = next(n for n in range(costs["cores"], costs["cores"] * guarantee + 1) if core_distribution(n, chance, guarantee, costs["cores"]) >= .95)
    rows += ["", f"Rank-3-only comparison: Dust needs {dust_wins} full-reward wins; Facets need {facet_wins}; attunement needs {attunement_wins} qualifying wins. Starting from zero Core pity, seven Cores require at most {costs['cores'] * guarantee} eligible top-band wins, with 95% obtained by {core_p95} wins.", "", f"Core guarantee yields a mean wait of {expected_wait:.6f} eligible wins per Core (long-run rate {1 / expected_wait:.2%}), versus the displayed base roll of {chance:.0%}.", "", f"With no research stipend, unlocks, losses or rerolls, {dust_wins} full rank-3 clears take {dust_wins * rank3['cycleMinutesAssumption'] / 60:.1f} hours at the ASSUMED {rank3['cycleMinutesAssumption']}-minute cycle. At {balance['fullRewardWinsPerDay']} full rewards/day, those full payouts span at least {math.ceil(dust_wins / balance['fullRewardWinsPerDay'])} UTC reward days. This is a reference scenario, not a minimum across overflow farming or a prediction for new players."]
    rows += ["", "## Reroll odds", "", "Refine draws uniformly including the current value. The table describes numerical values only; no useful-build probability is inferred.", "", "| Affix | Values | Mean attempts to maximum | 95% chance by attempt |", "|---|---:|---:|---:|"]
    for affix in catalog["affixes"]:
        count = affix["max"] - affix["min"] + 1
        p95 = math.ceil(math.log(.05) / math.log1p(-1 / count)) if count > 1 else 1
        rows.append(f"| {affix['name']} | {affix['min']}–{affix['max']}% | {count} | {p95} |")
    caps = balance["capsPercent"]
    out_multiplier = 1 + caps["outgoingDamage"] / 100
    reduction_multiplier = 1 - caps["incomingReduction"] / 100
    rows += ["", "## Damage envelope", "", f"A fully active +{caps['outgoingDamage']}% outgoing channel against zero reduction gives {out_multiplier:.4g}× the native direct-damage value before engine rounding. Against the capped {caps['incomingReduction']}% reduction it gives {out_multiplier * reduction_multiplier:.4g}×. Reduction alone gives approximately {1 / reduction_multiplier:.4g}× effective HP against affected direct moves. Eligible move healing is capped at +{caps['healing']}%. These bounds exclude native multipliers, conditions, fixed/residual damage and actual engine rounding. Reaching a cap requires stacking several affixes in the same channel; additive stacking is clamped per channel.", "", "## Validation scope", "", "Validated tier weights, promotion chain/costs, slot growth, sufficient distinct families, unique affix identifiers, numerical ranges, example profile slots/families, and conservation of probability in the capped Core-pity model. No simulator hooks, GUI, database, capture persistence or real combat were tested.", ""]
    return "\n".join(rows)


if __name__ == "__main__":
    balance, catalog, example = read("balance.json"), read("affixes.json"), read("profile-example.json")
    validate(balance, catalog, example, read("ranked-catalog.json"))
    output = ROOT / "docs" / "BALANCE-RESULTS.md"
    output.write_text(analyze(balance, catalog), encoding="utf-8")
    print(f"Validated proposed content and wrote {output}")
