#!/usr/bin/env python3
"""Small latency + race check against a running IncidentOps server. Python stdlib only.

Usage: python3 scripts/loadcheck.py [--base http://localhost:8080/api] [--requests 2000] [--concurrency 8]

What it measures (client-side wall time per request, localhost, keep-alive connection per thread):
  POST /incidents, PATCH /incidents/{id}/status (ack), GET /incidents (page of 20),
  GET /incidents?q=... (search), GET /stats.
Then a race check: R rounds of C simultaneous acks on the same fresh incident; counts how many got 200.
"""
import argparse, http.client, json, statistics, threading, time, urllib.parse
from concurrent.futures import ThreadPoolExecutor

_local = threading.local()


def conn(host, port):
    c = getattr(_local, "c", None)
    if c is None:
        c = _local.c = http.client.HTTPConnection(host, port, timeout=10)
    return c


def call(base, method, path, body=None):
    u = urllib.parse.urlparse(base)
    data = json.dumps(body).encode() if body is not None else None
    headers = {"Content-Type": "application/json"} if data else {}
    for attempt in range(2):
        c = conn(u.hostname, u.port)
        t0 = time.perf_counter()
        try:
            c.request(method, u.path + path, body=data, headers=headers)
            r = c.getresponse()
            payload = r.read()
            return r.status, (time.perf_counter() - t0) * 1000, payload
        except (http.client.HTTPException, ConnectionError, OSError):
            c.close(); _local.c = None
            if attempt: raise


def pct(sorted_ms, p):
    k = max(0, min(len(sorted_ms) - 1, round(p / 100 * len(sorted_ms) + 0.5) - 1))  # nearest rank
    return sorted_ms[k]


def run(base, label, jobs, concurrency, expect):
    """jobs: list of (method, path, body). Returns stats dict."""
    t0 = time.perf_counter()
    with ThreadPoolExecutor(concurrency) as ex:
        results = list(ex.map(lambda j: call(base, *j), jobs))
    wall = time.perf_counter() - t0
    ms = sorted(r[1] for r in results)
    errors = sum(1 for r in results if r[0] != expect)
    out = {"endpoint": label, "requests": len(ms), "concurrency": concurrency, "errors": errors,
           "p50_ms": round(pct(ms, 50), 2), "p95_ms": round(pct(ms, 95), 2), "p99_ms": round(pct(ms, 99), 2),
           "max_ms": round(ms[-1], 2), "mean_ms": round(statistics.fmean(ms), 2), "throughput_rps": round(len(ms) / wall, 1)}
    print(f"{label:34s} n={out['requests']:5d} err={errors:3d} p50={out['p50_ms']:7.2f}ms "
          f"p95={out['p95_ms']:7.2f}ms p99={out['p99_ms']:7.2f}ms  {out['throughput_rps']:8.1f} req/s")
    return out, results


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8080/api")
    ap.add_argument("--requests", type=int, default=2000)
    ap.add_argument("--concurrency", type=int, default=8)
    ap.add_argument("--race-rounds", type=int, default=50)
    ap.add_argument("--out")
    a = ap.parse_args()
    n, c = a.requests, a.concurrency
    sev = ["LOW", "MEDIUM", "HIGH", "CRITICAL"]
    create = lambda i: ("POST", "/incidents", {"serviceName": f"svc-{i % 40} api", "severity": sev[i % 4],
                                               "summary": f"load test incident {i}", "reporter": "loadcheck"})

    print("warm-up (JIT, connection pools)...")
    run(a.base, "warmup POST", [create(i) for i in range(300)], c, 201)
    run(a.base, "warmup GET", [("GET", "/incidents?page=0&size=20", None)] * 300 + [("GET", "/stats", None)] * 100, c, 200)

    report = []
    s, created = run(a.base, "POST /incidents", [create(i) for i in range(n)], c, 201); report.append(s)
    ids = [json.loads(r[2])["id"] for r in created if r[0] == 201]
    s, _ = run(a.base, "PATCH /incidents/{id}/status (ack)",
               [("PATCH", f"/incidents/{i}/status", {"status": "ACKNOWLEDGED", "actor": "loadcheck"}) for i in ids], c, 200)
    report.append(s)
    s, _ = run(a.base, "GET /incidents?page=0&size=20", [("GET", "/incidents?page=0&size=20", None)] * n, c, 200); report.append(s)
    s, _ = run(a.base, "GET /incidents?q=svc-7&size=20", [("GET", "/incidents?q=svc-7&size=20", None)] * n, c, 200); report.append(s)
    s, _ = run(a.base, "GET /stats", [("GET", "/stats", None)] * n, c, 200); report.append(s)
    total = json.loads(call(a.base, "GET", "/incidents?size=1")[2])["totalElements"]

    # Race: C people hit "Acknowledge" on the same incident at the same instant.
    winners_per_round = []
    for r in range(a.race_rounds):
        _, _, body = call(a.base, "POST", "/incidents", {"serviceName": "race", "severity": "HIGH", "summary": f"race {r}"})
        iid = json.loads(body)["id"]
        barrier = threading.Barrier(c)
        def ack(k):
            barrier.wait()
            return call(a.base, "PATCH", f"/incidents/{iid}/status", {"status": "ACKNOWLEDGED", "actor": f"p{k}"})[0]
        with ThreadPoolExecutor(c) as ex:
            codes = list(ex.map(ack, range(c)))
        winners_per_round.append(sum(1 for x in codes if x == 200))
        assert all(x in (200, 409) for x in codes), codes
    race = {"rounds": a.race_rounds, "concurrent_acks_per_round": c,
            "rounds_with_exactly_one_winner": sum(1 for w in winners_per_round if w == 1),
            "max_winners_in_a_round": max(winners_per_round)}
    print(f"race: {race}")

    result = {"rows_in_db_after_load": total, "latency": report, "race": race}
    if a.out:
        with open(a.out, "w") as f: json.dump(result, f, indent=2)
    print(json.dumps({"rows_in_db_after_load": total}))


if __name__ == "__main__":
    main()
