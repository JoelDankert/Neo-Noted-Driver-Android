#!/usr/bin/env python3
"""Generate the Android runtime table from the pinned Noted AHK distribution.

The source directory is a developer input only. The application never reads it.
"""
import argparse
import hashlib
import json
import re
from pathlib import Path

EXPECTED_ROWS = "efd492b8b410da3083bdaf1a5632e32f4964301e64c113bbb60c317e51b31cd2"
EXPECTED_COMPOSE = "aeb6104f0d1f518748166f59c0fee0b8a00b7b8a8ae8e2e078f4244f81c5674c"


def parse_rows(text):
    result = {}
    bodies = re.findall(r'^\s*data\["([^"]+)"\] := \[(.*?)\]$', text, re.M)
    canonical = "\n".join(f"{key}={body}" for key, body in bodies)
    if hashlib.sha256(canonical.encode()).hexdigest() != EXPECTED_ROWS:
        raise SystemExit("resolved-row source hash does not match the pinned distribution")
    for key, body in bodies:
        fields = [s.replace("``", "`").replace('`"', '"')
                  for s in re.findall(r'"((?:``|`"|[^"])*)"', body)]
        result[key] = {"letter": body.lstrip().startswith("1,"), "slots": fields}
    if len(result) != 91 or any(len(row["slots"]) != 8 for row in result.values()):
        raise SystemExit("unexpected resolved-row shape")
    return result


def parse_compose(text):
    packed = text.split("( Join`n", 1)[1].split('\n)"', 1)[0].strip("\n")
    if hashlib.sha256(packed.encode()).hexdigest() != EXPECTED_COMPOSE:
        raise SystemExit("compose source hash does not match the pinned distribution")
    result = dict(line.split("`t", 1) for line in packed.splitlines())
    if len(result) != 7622:
        raise SystemExit("unexpected compose table size")
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    text = (args.source / "data.ahk").read_text(encoding="utf-8-sig")
    payload = {
        "format": 1,
        "source": "dariogoetz/noted-layout@20f43c1a27736a5f894f6fe32620d41e629a5aab via noted-layout-windows-ahk",
        "rowsSha256": EXPECTED_ROWS,
        "composeSha256": EXPECTED_COMPOSE,
        "rows": parse_rows(text),
        "compose": parse_compose(text),
    }
    args.output.write_text(json.dumps(payload, ensure_ascii=False, separators=(",", ":")) + "\n",
                           encoding="utf-8")


if __name__ == "__main__":
    main()
