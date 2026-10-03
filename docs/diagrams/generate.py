"""Generates docs/diagrams/orbitwatch-system-design.drawio (5 pages, draw.io / diagrams.net).

The diagram is generated rather than hand-drawn so it can be regenerated when the system changes,
and so layout is checked mechanically: every box is tested for overlap and every edge endpoint must
exist. Run:  python3 docs/diagrams/generate.py
"""
from xml.sax.saxutils import escape
import xml.etree.ElementTree as ET

# ---- styles -------------------------------------------------------------------------------------
SVC   = "rounded=1;whiteSpace=wrap;html=1;fillColor=#dae8fc;strokeColor=#6c8ebf;align=center;verticalAlign=top;spacingTop=4;fontSize=12;"
EXT   = "rounded=0;whiteSpace=wrap;html=1;fillColor=#f5f5f5;strokeColor=#999999;dashed=1;fontSize=12;"
TOPIC = "rounded=1;whiteSpace=wrap;html=1;fillColor=#d5e8d4;strokeColor=#82b366;fontSize=11;"
STORE = "shape=cylinder3;boundedLbl=1;backgroundOutline=1;whiteSpace=wrap;html=1;fillColor=#ffe6cc;strokeColor=#d79b00;fontSize=11;"
BOX   = "rounded=1;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#666666;fontSize=11;"
CLIENT= "rounded=1;whiteSpace=wrap;html=1;fillColor=#e1d5e7;strokeColor=#9673a6;fontSize=11;"
BAD   = "rounded=1;whiteSpace=wrap;html=1;fillColor=#f8cecc;strokeColor=#b85450;fontSize=11;"
NOTE  = "shape=note;whiteSpace=wrap;html=1;size=14;fillColor=#fff2cc;strokeColor=#d6b656;align=left;verticalAlign=top;spacing=6;fontSize=11;"
BAND  = "rounded=0;whiteSpace=wrap;html=1;fillColor=#f9f9f9;strokeColor=#cccccc;dashed=1;align=left;verticalAlign=top;spacing=8;fontSize=12;fontStyle=1;fontColor=#666666;"
HDR   = "rounded=0;whiteSpace=wrap;html=1;fillColor=#e6e6e6;strokeColor=#999999;fontStyle=1;fontSize=11;"
CELL  = "rounded=0;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#999999;align=left;spacingLeft=6;fontSize=11;"

E      = "edgeStyle=orthogonalEdgeStyle;rounded=1;html=1;fontSize=10;endArrow=blockThin;endFill=1;strokeColor=#4d4d4d;"
E_DASH = E + "dashed=1;strokeColor=#999999;"
E_SYNC = E + "dashed=1;strokeColor=#9673a6;"

class Page:
    def __init__(self, name):
        self.name, self.cells, self.boxes = name, [], []
    def node(self, i, label, x, y, w, h, style=BOX, check=True):
        if check:
            for (bx, by, bw, bh, bid) in self.boxes:
                if x < bx + bw and bx < x + w and y < by + bh and by < y + h:
                    raise SystemExit(f"[{self.name}] '{i}' overlaps '{bid}'")
            self.boxes.append((x, y, w, h, i))
        self.cells.append(f'<mxCell id="{i}" value="{escape(label).replace(chr(10), "&lt;br&gt;")}" style="{style}" vertex="1" parent="1">'
                          f'<mxGeometry x="{x}" y="{y}" width="{w}" height="{h}" as="geometry"/></mxCell>')
        return i
    def band(self, i, label, x, y, w, h):
        return self.node(i, label, x, y, w, h, BAND, check=False)
    def edge(self, i, src, dst, label="", style=E, exit_=None, entry=None):
        s = style + (f"exitX={exit_[0]};exitY={exit_[1]};exitDx=0;exitDy=0;" if exit_ else "") \
                  + (f"entryX={entry[0]};entryY={entry[1]};entryDx=0;entryDy=0;" if entry else "")
        self.cells.append(f'<mxCell id="{i}" value="{escape(label).replace(chr(10), "&lt;br&gt;")}" style="{s}" edge="1" parent="1" '
                          f'source="{src}" target="{dst}"><mxGeometry relative="1" as="geometry"/></mxCell>')
    def xml(self):
        return (f'  <diagram name="{escape(self.name)}" id="{self.name.replace(" ", "-").lower()}">\n'
                f'    <mxGraphModel dx="1400" dy="900" grid="1" gridSize="10" guides="1" tooltips="1" connect="1" '
                f'arrows="1" fold="1" page="1" pageScale="1" pageWidth="1600" pageHeight="1200" math="0" shadow="0">\n'
                f'      <root>\n        <mxCell id="0"/>\n        <mxCell id="1" parent="0"/>\n        '
                + "\n        ".join(self.cells) + "\n      </root>\n    </mxGraphModel>\n  </diagram>")

pages = []

# =================================================================================================
# 1. System overview
# =================================================================================================
p = Page("1 System overview")
p.band("b_ext", "External", 30, 60, 250, 330)
p.band("b_kafka", "Kafka (KRaft, single broker locally / OCI Streaming in cloud) — the system of record", 300, 330, 1240, 190)
p.band("b_obs", "Observability", 300, 560, 620, 180)
p.band("b_cli", "Clients", 1280, 560, 260, 300)

p.node("celestrak", "CelesTrak\nGP JSON (active) + SATCAT CSV\n~7 MB per pull", 50, 100, 210, 100, EXT)
p.node("snapshot", "data/  committed snapshot\ncelestrak-active-*.json\nsatcat-*.csv", 50, 240, 210, 100, EXT)

p.node("ingest", "ingest-service  :8081\nfetch · cache · parse · fingerprint\ndiff · publish NEW/CHANGED only\nreadiness = last run succeeded",
       320, 100, 230, 120, SVC)
p.node("prop", "propagation-service  :8082\nmaterialises catalogue in memory\nSGP4 for every object per tick\naligned wall-clock ticks",
       640, 100, 230, 120, SVC)
p.node("analytics", "analytics-service  :8083\nKafka Streams topology\nstats · conjunctions · decay\nstale · manoeuvres · dedupe",
       960, 100, 230, 120, SVC)
p.node("gateway", "gateway-service  :8080\nbounded read models\nREST · GraphQL · SSE\nserves the dashboard",
       1280, 100, 230, 120, SVC)

topics = [
    ("t_gp",    "ow.catalog.gp-elements.v1\nkey: NORAD id · 6p · compacted", 320),
    ("t_obj",   "ow.catalog.objects.v1\nkey: NORAD id · 6p · compacted", 520),
    ("t_sv",    "ow.telemetry.state-vectors.v1\nkey: NORAD id · 6p · 1 h retention", 720),
    ("t_stats", "ow.analytics.tick-stats.v1\nkey: tick · 1p · 24 h", 920),
    ("t_alerts","ow.analytics.alerts.v1\nkey: alert id · 6p · 7 d", 1120),
    ("t_runs",  "ow.ops.ingest-runs.v1\nkey: run id · 1p · 7 d", 1320),
]
for i, (tid, label, x) in enumerate(topics):
    p.node(tid, label, x, 390, 180, 80, TOPIC)

p.node("prom", "Prometheus\nscrapes /actuator/prometheus", 320, 610, 180, 70, BOX)
p.node("graf", "Grafana\nprovisioned dashboard", 520, 610, 180, 70, BOX)
p.node("kui",  "Kafka UI\ntopics · lag · payloads", 720, 610, 180, 70, BOX)

p.node("dash", "Dashboard (React + TypeScript)\nmap · KPIs · alerts · object detail", 1300, 600, 220, 70, CLIENT)
p.node("rest", "REST / OpenAPI\ncurl, other services", 1300, 690, 220, 60, CLIENT)
p.node("gql",  "GraphQL + WebSocket\nqueries & subscriptions", 1300, 770, 220, 60, CLIENT)

p.node("note1", "Contracts live in one module (topic names, keys,\npartitions, policy, record types). Additive fields never\nbump the version; a breaking change is a new .v2 topic.\nNo database: every service rebuilds its state from Kafka.",
       30, 560, 250, 140, NOTE, check=False)

p.edge("e1", "celestrak", "ingest", "HTTPS pull, never inside the\nprovider's 2 h minimum")
p.edge("e2", "snapshot", "ingest", "fallback: provider unreachable\nand cache cold (ADR-0009)", E_DASH)
p.edge("e3", "ingest", "t_gp", "changed element sets", exit_=(0.5,1), entry=(0.5,0))
p.edge("e4", "ingest", "t_obj", "SATCAT rows", exit_=(0.5,1), entry=(0.5,0))
p.edge("e5", "ingest", "t_runs", "run summaries", exit_=(0.5,1), entry=(0.5,0))
p.edge("e6", "t_gp", "prop", "table", exit_=(0.5,0), entry=(0.5,1))
p.edge("e7", "t_obj", "prop", "table", exit_=(0.5,0), entry=(0.5,1))
p.edge("e8", "prop", "t_sv", "16,506 vectors\nevery 10 s", exit_=(0.5,1), entry=(0.5,0))
p.edge("e9", "t_sv", "analytics", "stream", exit_=(0.5,0), entry=(0.5,1))
p.edge("e10", "t_sv", "gateway", "latest per object", exit_=(0.5,0), entry=(0.5,1))
p.edge("e11", "t_gp", "analytics", "decay watch,\nmanoeuvre detection", exit_=(0.5,0), entry=(0.5,1))
p.edge("e12", "analytics", "t_stats", "per-tick stats", exit_=(0.5,1), entry=(0.5,0))
p.edge("e13", "analytics", "t_alerts", "deduped alerts", exit_=(0.5,1), entry=(0.5,0))
p.edge("e14", "t_stats", "gateway", exit_=(0.5,0), entry=(0.5,1))
p.edge("e15", "t_alerts", "gateway", exit_=(0.5,0), entry=(0.5,1))
p.edge("e16", "t_runs", "gateway", exit_=(0.5,0), entry=(0.5,1))
p.edge("e17", "gateway", "dash", "SSE + REST")
p.edge("e18", "gateway", "rest")
p.edge("e19", "gateway", "gql")
p.edge("e20", "gateway", "prop", "ground track on demand —\nthe one synchronous call", E_SYNC)
p.edge("e21", "prom", "ingest", "scrapes /actuator/prometheus\non all four services", E_DASH)
p.edge("e22", "graf", "prom", "", E_DASH)
p.edge("e23", "kui", "t_sv", "", E_DASH)
pages.append(p)

# =================================================================================================
# 2. Topic contracts
# =================================================================================================
p = Page("2 Topic contracts")
cols = [("Topic", 340), ("Key", 130), ("Semantics", 330), ("Partitions / policy", 180), ("Producer → consumers", 330)]
rows = [
    ("ow.catalog.gp-elements.v1", "NORAD id", "Latest element set per object — a TABLE", "6 / compacted", "ingest → propagation, analytics"),
    ("ow.catalog.objects.v1", "NORAD id", "SATCAT metadata (owner, type, launch) — a table", "6 / compacted", "ingest → propagation"),
    ("ow.telemetry.state-vectors.v1", "NORAD id", "One position/velocity per object per tick — a STREAM", "6 / 1 h retention", "propagation → analytics, gateway"),
    ("ow.analytics.tick-stats.v1", "tick", "Fleet statistics per tick", "1 / 24 h", "analytics → gateway"),
    ("ow.analytics.alerts.v1", "alert id", "Operator alerts", "6 / 7 d", "analytics, ingest, propagation → gateway"),
    ("ow.ops.ingest-runs.v1", "run id", "Ingest observability", "1 / 7 d", "ingest → gateway"),
]
x0, y0, rh = 40, 90, 60
p.node("t_title", "Topics are the API contract — names, keys, partitions, retention and record types in the `contracts` module",
       40, 30, 1310, 40, "text;html=1;fontSize=14;fontStyle=1;align=left;verticalAlign=middle;", check=False)
x = x0
for ci, (name, w) in enumerate(cols):
    p.node(f"h{ci}", name, x, y0, w, 40, HDR)
    x += w
for ri, row in enumerate(rows):
    x = x0
    y = y0 + 40 + ri * rh
    for ci, val in enumerate(row):
        p.node(f"c{ri}_{ci}", val, x, y, cols[ci][1], rh, CELL)
        x += cols[ci][1]
p.node("note2", "Why keys are NORAD ids: every event for one object lands on one partition, so ordering per object\nis guaranteed and consumers shard by partition.\n\nWhy compaction: a compacted topic IS the table. A restarted service replays it to rebuild state -\nno database, and an ingest restart republishes nothing.\n\nVersioning: additive optional fields never bump the version (unknown properties are ignored, so old\nconsumers tolerate new producers). A breaking change is a new .v2 topic with a bridge - never an\nin-place mutation.\n\nJSON, not Avro: readable in Kafka UI during a demo, no schema registry to operate; the contract\nmodule plus round-trip tests supply the compatibility discipline. The trade-off flips with external\nconsumers or 10x the volume.",
       40, 520, 1310, 250, NOTE, check=False)
pages.append(p)

# =================================================================================================
# 3. Analytics topology
# =================================================================================================
p = Page("3 Analytics topology")
p.node("a_title", "analytics-service — Kafka Streams topology (event time = the tick, so windows align regardless of publish latency)",
       40, 30, 1300, 40, "text;html=1;fontSize=14;fontStyle=1;align=left;verticalAlign=middle;", check=False)
p.node("in_sv", "ow.telemetry.state-vectors.v1", 40, 120, 210, 60, TOPIC)
p.node("in_gp", "ow.catalog.gp-elements.v1", 40, 560, 210, 60, TOPIC)

p.node("acc", "TickAccumulator\nper-partition processor, in-memory fold,\none partial per tick on punctuation", 310, 100, 260, 90, BOX)
p.node("rep", "repartition → 1 partition", 630, 110, 200, 70, BOX)
p.node("merge", "merge partials\ndrops stragglers already published", 890, 100, 230, 90, BOX)
p.node("out_stats", "ow.analytics.tick-stats.v1", 1180, 110, 210, 70, TOPIC)

p.node("ghost", "flatMap to cell keys\n50 km cells + ghost copies for\nneighbours within 10 km", 310, 230, 260, 90, BOX)
p.node("win", "window(tick) per cell\n+ grace 5 s + suppress", 630, 235, 200, 80, BOX)
p.node("screen", "screen bucket locally\npair reported once, in the own\ncell of the lower NORAD id", 890, 230, 230, 90, BOX)

p.node("stale", "filter: element epoch > 30 days", 310, 380, 260, 60, BOX)
p.node("decay", "DecayWatch\nperigee < 250 km, or B* > 5e-3\nwith perigee < 300 km", 310, 480, 260, 90, BOX)
p.node("man", "ManeuverDetector\ncompares consecutive element sets\nagainst the set's own drag term", 310, 610, 260, 90, BOX)
p.node("man_store", "store:\nlast element set\nper object", 630, 610, 170, 90, STORE)

p.node("dedupe", "AlertDeduper\nsame dedupeKey not re-emitted\nwithin 30 min", 890, 420, 230, 90, BOX)
p.node("dedupe_store", "store:\nlast emit per\ndedupeKey", 890, 550, 230, 80, STORE)
p.node("out_alerts", "ow.analytics.alerts.v1", 1180, 430, 210, 70, TOPIC)

p.node("note3", "Two-stage aggregation is the point of the top row: the naive groupBy(tick) funnels 16.5k records per\ntick through one key, one partition and one thread and fell 100k records behind. Per-partition partials\nplus a single-partition merge fixed it.\n\nConjunction screening is O(N.k) instead of O(N^2) (136 M pairs per tick): space is the partition key.\nValidated against brute force in ConjunctionScreenerTest.\n\nStores marked in orange are Kafka-backed (RocksDB + changelog), so suppression and manoeuvre history\nsurvive restarts and rebalances. The accumulators are deliberately in-memory: derived, cheap to recompute.",
       40, 760, 1350, 190, NOTE, check=False)

p.edge("a1", "in_sv", "acc")
p.edge("a2", "acc", "rep", "one partial\nper tick")
p.edge("a3", "rep", "merge")
p.edge("a4", "merge", "out_stats")
p.edge("a5", "in_sv", "ghost")
p.edge("a6", "ghost", "win")
p.edge("a7", "win", "screen", "window closes")
p.edge("a8", "screen", "dedupe", "CONJUNCTION")
p.edge("a9", "in_sv", "stale")
p.edge("a10", "stale", "dedupe", "STALE_ELEMENTS")
p.edge("a11", "in_gp", "decay")
p.edge("a12", "decay", "dedupe", "DECAY_WATCH")
p.edge("a13", "in_gp", "man")
p.edge("a14", "man", "man_store", "", E_DASH)
p.edge("a15", "man", "dedupe", "MANEUVER_CANDIDATE")
p.edge("a16", "dedupe", "dedupe_store", "", E_DASH)
p.edge("a17", "dedupe", "out_alerts")
pages.append(p)

# =================================================================================================
# 4. Resilience
# =================================================================================================
p = Page("4 Resilience")
p.node("r_title", "Failure is designed for: the fetch ladder on the left, and the rule that readiness means producing on the right",
       40, 30, 1300, 40, "text;html=1;fontSize=14;fontStyle=1;align=left;verticalAlign=middle;", check=False)
p.band("r_b1", "ingest fetch ladder — first one that works wins, and the choice is visible", 40, 90, 520, 620)
p.node("f1", "1. live fetch from CelesTrak\nHTTP/1.1, 3 attempts, backoff", 70, 140, 300, 70, SVC)
p.node("f2", "2. cache, if inside the 2 h\nprovider minimum interval", 70, 240, 300, 70, BOX)
p.node("f3", "3. stale cache\nWARN + fetch_failures metric", 70, 340, 300, 70, BOX)
p.node("f4", "4. committed snapshot in data/\nWARN + seed_fallbacks metric\n+ health detail: degraded", 70, 440, 300, 80, TOPIC)
p.node("f5", "5. fail the run\nreadiness DOWN, error on the\nops topic, container unhealthy", 70, 560, 300, 90, BAD)
p.edge("r1", "f1", "f2", "unreachable")
p.edge("r2", "f2", "f3", "expired")
p.edge("r3", "f3", "f4", "no cache at all")
p.edge("r4", "f4", "f5", "no snapshot either")

p.band("r_b2", "readiness means producing (ADR-0008) — a green health check must mean work is happening", 600, 90, 760, 300)
p.node("h1", "ingest readiness DOWN\nwhen the last GP or SATCAT run failed\n→ container (unhealthy), error in health details", 630, 140, 340, 90, BOX)
p.node("h2", "analytics readiness DOWN\nwhen the Streams client is not RUNNING", 630, 260, 340, 70, BOX)
p.node("h3", "analytics exits with code 70\non Streams ERROR → the orchestrator restarts it,\nso a permanent fault is a visible restart loop", 1000, 140, 330, 90, BAD)
p.node("h4", "JVM: -XX:+ExitOnOutOfMemoryError\ncontainer mem_limit, heap 65% of it", 1000, 260, 330, 70, BAD)

p.band("r_b3", "other guarantees, each with a unit test and a line on the runbook alarm checklist", 600, 420, 760, 290)
p.node("g1", "cache write failure falls back to memory\nan optimisation can never fail the main path", 630, 470, 340, 70, BOX)
p.node("g2", "tick deferred (<= 3) while element sets\nare still arriving - no partial snapshot", 630, 560, 340, 70, BOX)
p.node("g3", "AlertDeduper survives restarts\nno alert storm after a rebalance", 1000, 470, 330, 70, BOX)
p.node("g4", "every service rebuilds state from Kafka\nrestart is not a recovery procedure", 1000, 560, 330, 70, BOX)

p.node("note4", "Each of these exists because it was observed, not imagined. 2026-09-10: a root-owned cache volume turned a\nsuccessful fetch into a failed run; the same ownership bug would have crash-looped analytics; behind that,\nRocksDB's native library could not load on Alpine, so Streams shut down while the web server kept passing health\nchecks; behind that, JVMs without container memory limits were being OOM-killed. Every container was green.\nThe next day CelesTrak was unreachable from the operator's network entirely - plain HTTP failed too - which is\nwhy the ladder on the left ends in a committed snapshot rather than an empty catalogue.",
       40, 740, 1320, 170, NOTE, check=False)
pages.append(p)

# =================================================================================================
# 5. Build and deployment
# =================================================================================================
p = Page("5 Build and deployment")
p.node("d_title", "One Dockerfile, three deployment targets — broker configuration stays in environment variables, never in code",
       40, 30, 1300, 40, "text;html=1;fontSize=14;fontStyle=1;align=left;verticalAlign=middle;", check=False)
p.band("d_b1", "docker build --build-arg SERVICE=...", 40, 90, 500, 290)
p.node("s1", "stage 1: node:22-alpine\nbuilds the React/TS dashboard into\nthe gateway's static resources", 70, 140, 300, 70, BOX)
p.node("s2", "stage 2: maven:3.9-temurin-21\nbuilds the whole reactor", 70, 220, 300, 60, BOX)
p.node("s3", "stage 3: eclipse-temurin:21-jre-alpine\nnon-root user, HEALTHCHECK, libstdc++\nfor RocksDB's native library", 70, 290, 300, 70, BOX)
p.edge("d1", "s1", "s2")
p.edge("d2", "s2", "s3")

p.band("d_b2", "local — docker compose", 580, 90, 380, 290)
p.node("l1", "start.cmd / start.sh\npicks free host ports, writes .env,\nwaits for every container healthy", 610, 140, 320, 80, SVC)
p.node("l2", "5 containers + kafka-ui\n(+ Prometheus and Grafana under\nthe observability profile)", 610, 240, 320, 80, BOX)
p.edge("d3", "l1", "l2")

p.band("d_b3", "OCI — single VM (Terraform)", 1000, 90, 360, 290)
p.node("v1", "VCN, public subnet, security list", 1030, 140, 300, 50, BOX)
p.node("v2", "Ampere A1 instance (Always-Free shape)\ncloud-init installs Docker, clones the\nrepo, runs compose + observability", 1030, 210, 300, 80, BOX)
p.edge("d4", "v1", "v2")

p.band("d_b4", "OCI — OKE (kustomize)", 40, 420, 660, 300)
p.node("k1", "base: 4 Deployments with liveness and\nreadiness probes, Prometheus scrape\nannotations, single-node Kafka StatefulSet,\nOCI flexible Load Balancer for the gateway", 70, 470, 300, 110, BOX)
p.node("k2", "overlay oke-oci-streaming:\ndeletes the in-cluster broker and points\nevery service at OCI Streaming\n(Kafka-compatible, SASL_SSL)", 400, 470, 270, 110, TOPIC)
p.node("k3", "The only change is environment variables:\nKAFKA_BOOTSTRAP_SERVERS, KAFKA_SECURITY_PROTOCOL,\nKAFKA_SASL_* — which is the point of keeping broker\nconfiguration out of the code.", 70, 600, 600, 90, NOTE)
p.edge("d5", "k1", "k2", "swap the broker")

p.band("d_b5", "CI — GitHub Actions", 740, 420, 620, 300)
p.node("c1", "build the dashboard", 770, 470, 260, 50, BOX)
p.node("c2", "mvn verify — 138 tests, no broker", 770, 540, 260, 50, BOX)
p.node("c3", "build the four service images", 770, 610, 260, 50, BOX)
p.node("c4", "smoke test: start Kafka, run the services\noffline, assert that tick statistics and\nconjunction alerts actually flow\nend to end", 1060, 470, 280, 190, TOPIC)
p.edge("d6", "c1", "c2")
p.edge("d7", "c2", "c3")
p.edge("d8", "c3", "c4")
pages.append(p)

out = ('<mxfile host="app.diagrams.net" agent="OrbitWatch" type="device" version="24.7.17">\n'
       + "\n".join(pg.xml() for pg in pages) + "\n</mxfile>\n")
import os
path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "orbitwatch-system-design.drawio")
open(path, "w").write(out)
ET.parse(path)
print("ok:", path, len(out), "bytes,", len(pages), "pages")
