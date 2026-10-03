#!/usr/bin/env python3
"""Regenerate astro-core/src/test/resources/sgp4-reference-vectors.json from a CelesTrak GP JSON
snapshot using the reference `sgp4` package (pip install sgp4). Picks ~12 objects per regime plus
a few well-known ones, and propagates each at nine time offsets."""
import json, math, sys
from datetime import datetime
from sgp4.api import Satrec
from sgp4 import omm

src = sys.argv[1] if len(sys.argv) > 1 else 'data/celestrak-active-2026-09-09.json'
out = sys.argv[2] if len(sys.argv) > 2 else 'astro-core/src/test/resources/sgp4-reference-vectors.json'
data = json.load(open(src))

def regime(d):
    n, e, i = d['MEAN_MOTION'], d['ECCENTRICITY'], d['INCLINATION']
    a = (398600.4418 / ((n * 2 * math.pi / 86400) ** 2)) ** (1 / 3)
    ap, pe, P = a * (1 + e) - 6378.137, a * (1 - e) - 6378.137, 1440 / n
    if abs(P - 1436.0683) / 1436.0683 < 0.005 and e < 0.1 and i < 25: return 'GEO'
    if e >= 0.25 and ap >= 2000: return 'HEO'
    if ap < 2000: return 'LEO'
    if pe >= 2000 and ap < 35586: return 'MEO'
    return 'OTHER'

buckets = {}
for d in data: buckets.setdefault(regime(d), []).append(d)
sel = []
for v in buckets.values():
    step = max(1, len(v) // 12); sel += v[::step][:12]
sel += [d for d in data if d['OBJECT_NAME'] in {'ISS (ZARYA)', 'HST', 'NOAA 19', 'GOES 16'}]
seen, uniq = set(), []
for d in sel:
    if d['NORAD_CAT_ID'] not in seen: seen.add(d['NORAD_CAT_ID']); uniq.append(d)
times = [0.0, 10.0, 90.0, 360.0, 720.0, 1440.0, 4320.0, -300.0, 10080.0]
res = []
for d in uniq:
    sat = Satrec(); omm.initialize(sat, {k: str(v) for k, v in d.items()})
    epoch = (datetime.strptime(d['EPOCH'], '%Y-%m-%dT%H:%M:%S.%f') - datetime(1949, 12, 31)).total_seconds() / 86400.0
    vecs = []
    for t in times:
        e, r, v = sat.sgp4_tsince(t)
        vecs.append({'tsince': t, 'error': e, 'r': list(r) if e == 0 else None, 'v': list(v) if e == 0 else None})
    res.append({'name': d['OBJECT_NAME'], 'noradId': d['NORAD_CAT_ID'], 'regime': regime(d), 'epochDays1950': epoch,
                'elements': d, 'deepSpace': sat.method == 'd', 'vectors': vecs})
json.dump(res, open(out, 'w'), indent=1)
print(len(res), 'objects ->', out)
