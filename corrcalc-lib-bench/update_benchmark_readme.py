#!/usr/bin/env python3
"""Rewrite a README benchmark section from JMH JSON results.

Run locally on the reference machine — official numbers never come from CI.

Usage:
    corrcalc-lib-bench/update_benchmark_readme.py corrcalc-lib-bench/results/<date>-<version>.json README.md \
        --section release|snapshot --version 1.0.2 --commit abc1234 \
        --runner "Intel Core i7-6820HQ (4 cores, WSL2)"

Replaces everything between the <!-- benchmark-<section>:start --> and
<!-- benchmark-<section>:end --> markers; fails if the markers are missing
so a silently broken README never gets committed.
"""

import argparse
import datetime
import json
import re
import sys

CAPTIONS = {"release": "Release", "snapshot": "Development snapshot"}


def find(entries, method, size):
    for entry in entries:
        if entry["benchmark"].endswith(method) and entry["params"]["size"] == size:
            return entry
    return None


def time_cell(entry):
    if entry is None:
        return "—"
    metric = entry["primaryMetric"]
    score = f'{metric["score"]:,.2f}'
    error = metric.get("scoreError")
    # JMH writes the literal string "NaN" when iterations are too few
    if isinstance(error, (int, float)) and error == error:
        score += f" ± {error:,.2f}"
    return score


def alloc_cell(entry):
    if entry is None:
        return "—"
    metric = entry.get("secondaryMetrics", {}).get("gc.alloc.rate.norm")
    if metric is None:
        return "—"
    return f'{metric["score"] / 1e6:,.2f}'


def render(entries, section, version, commit, runner):
    sizes = sorted(
        {entry["params"]["size"] for entry in entries},
        key=lambda s: int(s.split("x")[0]) * int(s.split("x")[1]) ** 2,
    )
    units = {entry["primaryMetric"]["scoreUnit"] for entry in entries}
    if len(units) != 1:
        sys.exit(f"expected one score unit, got {sorted(units)}")
    unit = units.pop()
    jdk = entries[0].get("jdkVersion", "unknown JDK")

    lines = [
        f"{CAPTIONS[section]} **v{version}** (`{commit}`), measured on "
        f"{datetime.date.today().isoformat()} with JDK {jdk} on {runner}. "
        f"JMH average time per correlation matrix in **{unit}** "
        "(± 99.9% confidence interval) and heap allocated per calculation "
        "in **MB/op** (`gc.alloc.rate.norm`); lower is better.",
        "",
        "| rows × cols | double | float | double alloc | float alloc |",
        "|---|---|---|---|---|",
    ]
    for size in sizes:
        rows, cols = size.split("x")
        d = find(entries, ".pearsonDouble", size)
        f = find(entries, ".pearsonFloat", size)
        lines.append(
            f"| {int(rows):,} × {int(cols):,} "
            f"| {time_cell(d)} | {time_cell(f)} "
            f"| {alloc_cell(d)} | {alloc_cell(f)} |"
        )
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("results")
    parser.add_argument("readme")
    parser.add_argument("--section", required=True, choices=sorted(CAPTIONS))
    parser.add_argument("--version", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--runner", required=True)
    args = parser.parse_args()

    with open(args.results) as f:
        entries = json.load(f)
    if not entries:
        sys.exit("no benchmark entries in results file")

    start = f"<!-- benchmark-{args.section}:start -->"
    end = f"<!-- benchmark-{args.section}:end -->"
    with open(args.readme) as f:
        readme = f.read()
    pattern = re.compile(re.escape(start) + ".*?" + re.escape(end), re.DOTALL)
    if not pattern.search(readme):
        sys.exit(f"markers {start} ... {end} not found in {args.readme}")

    body = render(entries, args.section, args.version, args.commit, args.runner)
    section = f"{start}\n{body}\n{end}"
    with open(args.readme, "w") as f:
        f.write(pattern.sub(lambda _: section, readme))
    print(f"updated benchmark {args.section} section in {args.readme}")


if __name__ == "__main__":
    main()
