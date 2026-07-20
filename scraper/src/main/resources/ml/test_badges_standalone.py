#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Standalone assert script for ml_pipeline.assign_badges() — badges-oportunidades-revamp
Phase 2 (T2.4/T2.5). The bundled Python runtime (`_tools/python`, python311._pth) has
no pytest, so this is a plain script run manually (`python test_badges_standalone.py`)
instead of a pytest suite, per pre-apply decision #442. Exits 0 on success, 1 on the
first failed assertion (with a message identifying which case failed).

Run from this directory:  python test_badges_standalone.py
"""
import sys
import importlib.util
from pathlib import Path

_here = Path(__file__).parent
_spec = importlib.util.spec_from_file_location("ml_pipeline", _here / "ml_pipeline.py")
ml_pipeline = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(ml_pipeline)

assign_badges = ml_pipeline.assign_badges

failures = []


def check(name, actual, expected):
    if actual != expected:
        failures.append(f"FAIL {name}: expected {expected!r}, got {actual!r}")
    else:
        print(f"ok   {name}")


# signature: assign_badges(hist, comp, mz, cheap, alta, trend, exp, ratio, desc_pct, es_oferta_real)

# 1. Priority order: a product qualifying for BOTH all_time_low and
#    below_market must list all_time_low first (highest priority).
check(
    "priority_order_all_time_low_beats_below_market",
    assign_badges(hist=True, comp=15, mz=-2.0, cheap=True, alta=False,
                   trend='estable', exp=False, ratio=1.0, desc_pct=0, es_oferta_real=False),
    ['all_time_low', 'below_market'],
)

# 2. Multi-badge case explicitly required by T2.5: trending + verified_deal
#    simultaneously (independent predicates, not mutually exclusive).
check(
    "multi_badge_trending_plus_verified_deal",
    assign_badges(hist=False, comp=60, mz=0.0, cheap=False, alta=True,
                   trend='estable', exp=False, ratio=1.25, desc_pct=25, es_oferta_real=True),
    ['verified_deal', 'trending'],
)

# 3. Three-way ordering: all_time_low + below_market + verified_deal must
#    come out in exactly that fixed priority order when all three qualify
#    at once (comp<=20 satisfies both hist's comp<=35 and cheap's comp<=20).
check(
    "three_way_priority_order",
    assign_badges(hist=True, comp=15, mz=-2.0, cheap=True, alta=False,
                   trend='estable', exp=False, ratio=1.25, desc_pct=25, es_oferta_real=True),
    ['all_time_low', 'below_market', 'verified_deal'],
)

# 4. No badge qualifies -> empty list, principal "" (badge = badges[0] or '').
check(
    "no_badges_when_no_condition_matches",
    assign_badges(hist=False, comp=50, mz=0.0, cheap=False, alta=False,
                   trend='estable', exp=False, ratio=1.0, desc_pct=0, es_oferta_real=False),
    [],
)

# 5. Single badge (trending alone) — sanity check for the non-multi case.
check(
    "single_badge_trending_only",
    assign_badges(hist=False, comp=40, mz=0.0, cheap=False, alta=True,
                   trend='estable', exp=False, ratio=1.0, desc_pct=0, es_oferta_real=False),
    ['trending'],
)

# 6. above_market + fake_discount coexist (a product can be both "expensive"
#    and have an irrelevant/cosmetic discount at the same time).
check(
    "above_market_plus_fake_discount",
    assign_badges(hist=False, comp=85, mz=1.6, cheap=False, alta=False,
                   trend='estable', exp=False, ratio=1.05, desc_pct=5, es_oferta_real=False),
    ['above_market', 'fake_discount'],
)

print()
if failures:
    for f in failures:
        print(f)
    print(f"\n{len(failures)} assertion(s) FAILED")
    sys.exit(1)
else:
    print(f"All {6} standalone badge-assignment checks passed.")
    sys.exit(0)
