"""Monte Carlo model of the proposed Tower completion reward (docs/TOWER-COMPLETION-REWARDS.md).

usage: python -I tools/tower_loot_model.py [--n 200000]

Prints the expected value per completion, how long each progression goal takes in hours of Tower play, the spread (how often a completion
pays a core, how long droughts last), and how Tower income per hour compares with trials and dungeons. All inputs are the proposed
numbers in PARAMS below; change them here and re-run. Arithmetic only: no live data.
"""
import random
import statistics
import sys

N = int(sys.argv[sys.argv.index('--n') + 1]) if '--n' in sys.argv else 200_000
RESPEC = '--respec' in sys.argv   # also model the proposed respec crown items (docs/RESPEC-DESIGN.md)
RUN_HOURS = 1.0  # one F10 completion

PARAMS = {
    # quality tier: probability among completions, amount multiplier, crown slots, crown table weights
    'tiers': {
        'bronze': dict(p=0.15, mult=0.7, slots=1, w=dict(core=30, frag=40, sigil=10, catalyst=0, armor=14, ticket=6)),
        'silver': dict(p=0.45, mult=1.0, slots=1, w=dict(core=36, frag=30, sigil=12, catalyst=2, armor=14, ticket=6)),
        'gold':   dict(p=0.30, mult=1.5, slots=2, w=dict(core=36, frag=22, sigil=14, catalyst=4, armor=16, ticket=8)),
        'mythic': dict(p=0.10, mult=2.25, slots=3, w=dict(core=36, frag=14, sigil=14, catalyst=8, armor=18, ticket=10)),
    },
    # guaranteed at Silver multiplier 1.0 (scaled by the tier multiplier)
    'dust': 800, 'facets': 6, 'fragments': 3, 'scouters': 4,
    'marks_f10': 3, 'marks_f5': 1,
    'crown_fragment_bundle': 12,
    # the F5 mini chest (about half way through the hour)
    'f5': dict(dust=200, facets=1, fragments=1, scouters=1, core_chance=0.05),
}

# Respec crown items as decided (docs/RESPEC-DESIGN.md: a Hourglass of Undoing about every 10 completions, an Unbinding Prism about every 35; no Transcendent Respec): weights per tier on the same scale as the crown table above (they are fractions of a point,
# so the real table would scale every weight by 10 or 100). Only used with --respec.
RESPEC_WEIGHTS = {
    'bronze': dict(respec_rank=0.0, respec_unique=0.0, respec_trans=0.0),
    'silver': dict(respec_rank=6.0, respec_unique=0.0, respec_trans=0.0),
    'gold':   dict(respec_rank=8.0, respec_unique=3.0, respec_trans=0.0),
    'mythic': dict(respec_rank=10.0, respec_unique=5.0, respec_trans=0.0),
}
if RESPEC:
    for tier, extra in RESPEC_WEIGHTS.items():
        PARAMS['tiers'][tier]['w'].update(extra)

# what the goals cost (domain constants and docs/ECONOMY.md, DEPTH-DESIGN.md)
GOALS = {
    'Mythic promotion (Common to Mythic)': dict(dust=770, facets=37, cores=7, attunement=90),
    'one Fusion (host Mythic + donor)': dict(dust=600, facets=30, cores=10, marks=5, attunement=150, catalyst=1),
    'one Catalyst (40 fragments)': dict(fragments=40),
    'perfect one rank V slot (typical band width 8)': dict(dust=520),
    'one Overreach attempt': dict(dust=60, facets=2, marks=1),
}
ATTUNEMENT_PER_RUN = 6  # +3 for the F5 boss and +3 for the F10 boss, to every profiled Pokemon in the party


def pick(weights, rng):
    total = sum(weights.values())
    roll = rng.uniform(0, total)
    for key, weight in weights.items():
        roll -= weight
        if roll <= 0:
            return key
    return key


def one_completion(rng):
    tier = pick({k: v['p'] for k, v in PARAMS['tiers'].items()}, rng)
    spec = PARAMS['tiers'][tier]
    mult = spec['mult']
    out = dict(tier=tier, dust=PARAMS['dust'] * mult + PARAMS['f5']['dust'], facets=round(PARAMS['facets'] * mult) + PARAMS['f5']['facets'],
               fragments=round(PARAMS['fragments'] * mult) + PARAMS['f5']['fragments'], scouters=PARAMS['scouters'] + PARAMS['f5']['scouters'],
               marks=PARAMS['marks_f10'] + PARAMS['marks_f5'] + (1 if tier in ('gold', 'mythic') else 0) + (1 if tier == 'mythic' else 0),
               cores=1 if rng.random() < PARAMS['f5']['core_chance'] else 0, sigils=0, catalysts=0, armor=0, tickets=0, notable=0, bundles=0, respec_rank=0, respec_unique=0, respec_trans=0)
    for _ in range(spec['slots']):
        kind = pick(spec['w'], rng)
        if kind == 'core':
            out['cores'] += 1
        elif kind == 'frag':
            out['fragments'] += PARAMS['crown_fragment_bundle']
            out['bundles'] += 1
        elif kind == 'sigil':
            out['sigils'] += 1
        elif kind == 'catalyst':
            out['catalysts'] += 1
        elif kind == 'armor':
            out['armor'] += 1
        elif kind in ('respec_rank', 'respec_unique', 'respec_trans'):
            out[kind] += 1
        else:
            out['tickets'] += 1
    out['notable'] = out['cores'] + out['sigils'] + out['catalysts'] + out['armor'] + out['tickets'] + out['bundles'] + out['respec_rank'] + out['respec_unique'] + out['respec_trans']
    return out


def hours_to(goal_cost, per_run, extra_runs_floor=0):
    runs = max((goal_cost.get(k, 0) / per_run[k]) if per_run.get(k) else 0 for k in goal_cost if k not in ('attunement', 'catalyst'))
    if 'attunement' in goal_cost:
        runs = max(runs, goal_cost['attunement'] / ATTUNEMENT_PER_RUN)
    return max(runs, extra_runs_floor) * RUN_HOURS


def main():
    rng = random.Random(2026)
    runs = [one_completion(rng) for _ in range(N)]
    keys = ['dust', 'facets', 'fragments', 'scouters', 'marks', 'cores', 'sigils', 'catalysts', 'armor', 'tickets']
    mean = {k: statistics.fmean(r[k] for r in runs) for k in keys}
    print(f'Per completion (mean of {N:,}): ' + ', '.join(f'{k} {mean[k]:.2f}' for k in keys))
    if RESPEC:
        print('\nRespec crown items (one completion = one hour):')
        for key, label in (('respec_rank', 'Hourglass of Undoing (Rank Respec)'), ('respec_unique', 'Unbinding Prism (Unique Respec)')):
            rate = statistics.fmean(r[key] for r in runs)
            by_tier = ', '.join(f"{t} {statistics.fmean(r[key] for r in runs if r['tier'] == t):.3f}" for t in PARAMS['tiers'])
            print(f"  {label}: {rate:.4f} per completion, one every {1 / rate:.1f} completions (hours of Tower play)  ({by_tier})")
        print(f"  cores per completion with the respec entries in the table: {mean['cores']:.2f} (0.59 without)")
    print('\nBy tier (mean):')
    for tier in PARAMS['tiers']:
        sub = [r for r in runs if r['tier'] == tier]
        print(f'  {tier:7s} ' + ', '.join(f"{k} {statistics.fmean(r[k] for r in sub):.2f}" for k in ('dust', 'facets', 'fragments', 'marks', 'cores', 'sigils')))
    print('\nSpread:')
    print(f"  completions with at least one core: {sum(1 for r in runs if r['cores'] >= 1) / N:.1%}; two or more: {sum(1 for r in runs if r['cores'] >= 2) / N:.1%}")
    print(f"  completions with at least one 'notable' (core, sigil, catalyst, armor, ticket, or a big fragment bundle): {sum(1 for r in runs if r['notable'] >= 1) / N:.1%}")
    # drought: completions in a row without a core
    drought, longest, droughts = 0, 0, []
    for r in runs:
        if r['cores'] == 0:
            drought += 1
        else:
            droughts.append(drought)
            drought = 0
    droughts.sort()
    print(f"  completions between cores: mean {statistics.fmean(droughts):.1f}, 90th percentile {droughts[int(len(droughts) * 0.9)]}, 99th {droughts[int(len(droughts) * 0.99)]}, max {droughts[-1]}")

    print('\nHours of Tower play (one completion = 1 hour) to reach a goal, at the mean payout:')
    for name, cost in GOALS.items():
        print(f'  {name}: {hours_to(cost, mean):.1f} h' + (f"  (attunement alone: {cost['attunement'] / ATTUNEMENT_PER_RUN:.0f} h)" if 'attunement' in cost else ''))
    print(f"  one Catalyst from fragments plus crown catalysts: {100 / mean['fragments']:.1f} h from fragments alone; crown catalysts add {mean['catalysts']:.2f} per hour")

    print('\nIncome per hour of play, to check the Tower stays the best place to earn (dust / facets / cores):')
    print(f"  Tower completion (mean): {mean['dust']:.0f} / {mean['facets']:.1f} / {mean['cores']:.2f}   plus {mean['marks']:.1f} Marks, {mean['fragments']:.0f} fragments")
    print('  Trial rank 3 (12 min, 16 dust + 1 facet): 80 / 5 / 0 per hour')
    # dungeon runs: 15 min each, today's bands and the proposed lower ones (two arena wins and a boss)
    for label, tier_dust, arena_dust, boss_dust in (('today', 10, 14, 30), ('proposed', 10, 10, 12)):
        for tier in (1, 3, 5, 8):
            per_run = tier_dust * tier + 2 * arena_dust * (1 + 0.1 * (tier - 1)) + boss_dust * tier
            print(f'  Dungeon tier {tier} ({label} bands, 2 arenas + boss, 15 min): {per_run * 4:.0f} dust per hour')
    print('\nThe dungeon bands as built can out-earn a Tower completion on dust per hour at deep tiers; the proposal lowers them.')


if __name__ == '__main__':
    main()
