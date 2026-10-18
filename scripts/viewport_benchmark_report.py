#!/usr/bin/env python3
"""Compare successful opt-in viewport JUnit reports; no dependencies or estimated FPS."""
import argparse
import json
import xml.etree.ElementTree as ET


def read(path):
    root = ET.parse(path).getroot()
    if any(int(root.get(name, "0")) for name in ("failures", "errors", "skipped")):
        raise ValueError(f"Benchmark report is incomplete or failed: {path}")
    records = []
    for line in root.findtext("system-out", "").splitlines():
        if line.startswith("NIA_VIEWPORT_BENCHMARK "):
            records.append(json.loads(line.split(" ", 1)[1]))
    if not records:
        raise ValueError(f"No viewport measurements: {path}")
    return records


def key(record):
    return tuple(record.get(field) for field in ("case", "renderer", "width", "height", "objects", "triangles_per_object", "triangles"))


def compare(baseline, candidate):
    old = {key(row): row for row in baseline}
    results = []
    for row in candidate:
        prior = old.get(key(row))
        result = {"candidate": row, "baseline": prior}
        if prior and "median_ms" in prior and "median_ms" in row:
            result["median_speedup"] = prior["median_ms"] / row["median_ms"]
            result["median_ms_saved"] = prior["median_ms"] - row["median_ms"]
            result["thread_allocated_bytes_saved"] = prior["median_thread_allocated_bytes"] - row["median_thread_allocated_bytes"]
        results.append(result)
    if any(key(row) not in {key(item) for item in candidate} for row in baseline):
        raise ValueError("Candidate is missing a baseline scenario")
    return {"measurement": "Production renderer CPU/offscreen-Skia pass, not compositor/display FPS", "comparisons": results}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline")
    parser.add_argument("candidate")
    args = parser.parse_args()
    print(json.dumps(compare(read(args.baseline), read(args.candidate)), indent=2))
