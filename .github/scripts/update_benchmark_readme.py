#!/usr/bin/env python3
"""Rewrite the README benchmark section from JMH JSON results.

Usage:
    update_benchmark_readme.py <jmh-results.json> <README.md> \
        --version 1.0.2 --commit abc1234 --runner "GitHub Actions ubuntu-latest"

Replaces everything between the <!-- benchmark-results:start --> and
<!-- benchmark-results:end --> markers; fails if the markers are missing
so a silently broken README never gets committed.
"""

import argparse
import datetime
import json
import re
import sys

START = "<!-- benchmark-results:start -->"
END = "<!-- benchmark-results:end -->"


def cell(entries, method, size):
    for entry in entries:
        if entry["benchmark"].endswith(method) and entry["params"]["size"] == size:
            metric = entry["primaryMetric"]
            score = f'{metric["score"]:,.2f}'
            error = metric.get("scoreError")
            # JMH writes the literal string "NaN" when iterations are too few
            if isinstance(error, (int, float)) and error == error:
                score += f" ± {error:,.2f}"
            return score
    return "—"


def render(entries, version, commit, runner):
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
        f"Release **v{version}** (`{commit}`), measured on "
        f"{datetime.date.today().isoformat()} with JDK {jdk} on {runner}. "
        f"JMH average time per correlation matrix in **{unit}** "
        "(± 99.9% confidence interval), lower is better.",
        "",
        "| rows × cols | double | float |",
        "|---|---|---|",
    ]
    for size in sizes:
        rows, cols = size.split("x")
        lines.append(
            f"| {int(rows):,} × {int(cols):,} "
            f"| {cell(entries, '.pearsonDouble', size)} "
            f"| {cell(entries, '.pearsonFloat', size)} |"
        )
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("results")
    parser.add_argument("readme")
    parser.add_argument("--version", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--runner", required=True)
    args = parser.parse_args()

    with open(args.results) as f:
        entries = json.load(f)
    if not entries:
        sys.exit("no benchmark entries in results file")

    with open(args.readme) as f:
        readme = f.read()
    pattern = re.compile(re.escape(START) + ".*?" + re.escape(END), re.DOTALL)
    if not pattern.search(readme):
        sys.exit(f"markers {START} ... {END} not found in {args.readme}")

    section = f"{START}\n{render(entries, args.version, args.commit, args.runner)}\n{END}"
    with open(args.readme, "w") as f:
        f.write(pattern.sub(lambda _: section, readme))
    print(f"updated benchmark section in {args.readme}")


if __name__ == "__main__":
    main()
