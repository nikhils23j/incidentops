"""Make the README charts from the saved results.

    python3 scripts/make_figures.py

Reads ONLY results.json at the repo root and writes PNGs to docs/img/.
It does not start the server or re-run the load check, and no plotted
number is typed in by hand. Needs matplotlib.
(docs/img/dashboard.png is a browser screenshot, not made by this script.)
"""
import json
import re
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
from matplotlib.lines import Line2D  # noqa: E402
from matplotlib.patches import Patch  # noqa: E402
from matplotlib.ticker import FuncFormatter, LogLocator  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "docs" / "img"
RESULTS = ROOT / "results.json"

# Palette: categorical slots 1-2, an ordinal blue ramp for percentiles, neutral chrome.
BLUE = "#2a78d6"
ORANGE = "#eb6834"
PCT = {"p50_ms": "#86b6ef", "p95_ms": "#2a78d6", "p99_ms": "#104281"}
INK = "#0b0b0b"
INK2 = "#52514e"
MUTED = "#898781"
GRID = "#e1e0d9"
AXIS = "#c3c2b7"
SURFACE = "#ffffff"

plt.rcParams.update({
    "font.family": "sans-serif",
    "font.sans-serif": ["Helvetica Neue", "Helvetica", "Arial", "DejaVu Sans"],
    "font.size": 11,
    "axes.edgecolor": AXIS,
    "axes.labelcolor": INK2,
    "axes.titlecolor": INK,
    "axes.titlesize": 13,
    "axes.titlelocation": "left",
    "xtick.color": INK2,
    "ytick.color": INK2,
    "figure.facecolor": SURFACE,
    "axes.facecolor": SURFACE,
    "savefig.facecolor": SURFACE,
})


def clean_axes(ax):
    for side in ("top", "right", "left"):
        ax.spines[side].set_visible(False)
    ax.grid(axis="x", color=GRID, linewidth=0.8, which="both")
    ax.set_axisbelow(True)
    ax.tick_params(length=0)


def footnote(fig, text, y=-0.04):
    fig.text(0.0, y, text, color=MUTED, fontsize=9.5, ha="left")


def save(fig, name):
    OUT.mkdir(parents=True, exist_ok=True)
    path = OUT / name
    fig.savefig(path, dpi=150, bbox_inches="tight", pad_inches=0.25)
    plt.close(fig)
    print(f"wrote {path.relative_to(ROOT)} ({path.stat().st_size / 1024:.0f} KB)")


def load_note(d):
    nums = re.findall(r"load average was ([\d.]+) before .*? and ([\d.]+) after", d["environment"]["machine_load_note"])
    return f"1-min load average {nums[0][0]} before, {nums[0][1]} after, on 8 cores" if nums else "machine heavily loaded"


def fig_latency(d):
    """p50 / p95 / p99 per endpoint on a log scale, one row per endpoint."""
    rows = d["load_check"]["latency"]
    fig, ax = plt.subplots(figsize=(10, 4.6))
    for i, r in enumerate(rows):
        ax.plot([r["p50_ms"], r["p99_ms"]], [i, i], color=AXIS, linewidth=2, zorder=1)
        for key, color in PCT.items():
            ax.scatter(r[key], i, s=80, color=color, edgecolor=SURFACE, linewidth=1.5, zorder=3)
        ax.text(r["p99_ms"] * 1.12, i, f"p50 {r['p50_ms']:g} / p99 {r['p99_ms']:g} ms",
                va="center", ha="left", color=INK2, fontsize=9.5)
    ax.set_yticks(range(len(rows)))
    ax.set_yticklabels([r["endpoint"] for r in rows], color=INK)
    ax.set_ylim(len(rows) + 0.1, -0.6)
    ax.set_xscale("log")
    lo = min(r["p50_ms"] for r in rows)
    hi = max(r["p99_ms"] for r in rows)
    ax.set_xlim(lo / 1.6, hi * 8)
    ax.xaxis.set_major_locator(LogLocator(base=10, subs=(1, 2, 5)))
    ax.xaxis.set_major_formatter(FuncFormatter(lambda v, _: f"{v:g}"))
    ax.xaxis.set_minor_locator(plt.NullLocator())
    ax.set_xlabel("client-side latency, ms (log scale)")
    clean_axes(ax)
    ax.legend(handles=[Line2D([], [], marker="o", linestyle="", markersize=8, color=c,
                              label=k.replace("_ms", "")) for k, c in PCT.items()],
              loc="lower right", frameon=False, labelcolor=INK, ncol=3)
    lc = d["load_check"]
    n = rows[0]["requests"]
    conc = rows[0]["concurrency"]
    ax.set_title(f"Latency per endpoint ({n:,} requests each, {conc} concurrent clients, localhost)")
    footnote(fig, f"Busy laptop: {load_note(d)}. Tails are pessimistic. "
             f"~{lc['rows_in_db_after_load']:,} rows in H2 at the end. Source: results.json")
    save(fig, "latency.png")


def fig_throughput(d):
    rows = d["load_check"]["latency"]
    fig, ax = plt.subplots(figsize=(10, 3.8))
    for i, r in enumerate(rows):
        ax.barh(i, r["throughput_rps"], height=0.6, color=BLUE)
        ax.text(r["throughput_rps"] + 8, i, f"{r['throughput_rps']:g} req/s  ({r['errors']} errors)",
                va="center", ha="left", color=INK2, fontsize=10)
    ax.set_yticks(range(len(rows)))
    ax.set_yticklabels([r["endpoint"] for r in rows], color=INK)
    ax.set_ylim(len(rows) - 0.5, -0.5)
    ax.set_xlim(0, max(r["throughput_rps"] for r in rows) * 1.35)
    ax.set_xlabel("requests per second")
    clean_axes(ax)
    conc = rows[0]["concurrency"]
    ax.set_title(f"Throughput per endpoint ({conc} concurrent clients, same machine as server)")
    footnote(fig, "/stats loads every incident to compute medians; search is an unindexed LIKE scan. "
             "Source: results.json", y=-0.06)
    save(fig, "throughput.png")


def fig_race(d):
    """How many of N simultaneous acks 'won', with and without @Version."""
    race = d["load_check"]["race"]
    m = re.search(r"(\d+) of (\d+) requests returned 200", d["backend_tests"]["mutation_check"])
    without_wins, without_n = int(m.group(1)), int(m.group(2))
    per_round = race["concurrent_acks_per_round"]
    rows = [
        ("without @Version\n(test run, mutation check)", without_wins, without_n, ORANGE,
         f"{without_wins} of {without_n} got 200 OK"),
        (f"with @Version\n(load check, {race['rounds']} rounds)", race["max_winners_in_a_round"], per_round, BLUE,
         f"max {race['max_winners_in_a_round']} winner per round; "
         f"{race['rounds_with_exactly_one_winner']}/{race['rounds']} rounds had exactly one"),
    ]
    fig, ax = plt.subplots(figsize=(10, 3.4))
    for i, (label, wins, n, color, note) in enumerate(rows):
        ax.barh(i, n, height=0.55, color="#f0efec")
        ax.barh(i, wins, height=0.55, color=color)
        ax.text(n + 0.15, i, note, va="center", ha="left", color=INK2, fontsize=10)
    ax.axvline(1, color=INK, linewidth=1, linestyle=(0, (3, 3)))
    ax.text(1.05, -0.48, "correct: exactly 1", color=INK, fontsize=9.5, va="bottom")
    ax.set_yticks(range(len(rows)))
    ax.set_yticklabels([r[0] for r in rows], color=INK)
    ax.set_ylim(len(rows) - 0.5, -0.65)
    ax.set_xlim(0, per_round * 2.3)
    ax.set_xticks(range(0, per_round + 1, 2))
    ax.set_xlabel(f"simultaneous acks on the same incident that returned 200 (out of {per_round})")
    clean_axes(ax)
    ax.legend(handles=[Patch(color="#f0efec", label="acks sent")], loc="lower right",
              frameon=False, labelcolor=INK)
    ax.set_title(f"{per_round} people click Acknowledge at once: how many 'win'?")
    footnote(fig, "Without @Version: @Version temporarily commented out, ConcurrencyAndStreamTests race. "
             "Source: results.json", y=-0.08)
    save(fig, "ack_race.png")


def main():
    d = json.loads(RESULTS.read_text())
    fig_latency(d)
    fig_throughput(d)
    fig_race(d)


if __name__ == "__main__":
    main()
