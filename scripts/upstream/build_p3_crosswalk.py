#!/usr/bin/env python3
"""Build an exact P2-candidate to P3-route crosswalk."""

from __future__ import annotations

import argparse
import json
from pathlib import Path


ROUTES = {
    "ADD_NEXT": ("engine-correctness-next", "post-first-release"),
    "ADAPT": ("filament-device-adaptation", "post-first-release"),
    "ALREADY_PRESENT": ("none", "no intake"),
    "DEFER": ("named-capability-future-assessment", "deferred"),
    "DO_NOT_ADD": ("none", "excluded"),
}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--assessment", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        raise SystemExit(f"refusing to overwrite {args.output}")
    assessment = json.loads(args.assessment.read_text())
    rows = []
    for source in assessment["sources"]:
        for candidate in source["candidates"]:
            batch, timing = ROUTES[candidate["disposition"]]
            rows.append({
                "source": source["source"],
                "candidate": candidate["commit"],
                "p2_disposition": candidate["disposition"],
                "p2_recommended_batch": candidate["recommended_batch"],
                "p3_route": batch,
                "timing": timing,
                "trigger": "A named capability request or new defect evidence plus candidate-specific objective tests and review; no automatic promotion.",
            })
    identities = {(row["source"], row["candidate"]) for row in rows}
    assessment_identities = {
        (source["source"], candidate["commit"])
        for source in assessment["sources"]
        for candidate in source["candidates"]
    }
    required_route_fields = ("p3_route", "timing", "trigger")
    complete_routes = all(all(row.get(field) for field in required_route_fields) for row in rows)
    if len(rows) != 82 or len(identities) != 82 or identities != assessment_identities or not complete_routes:
        raise ValueError(f"expected 82 unique candidate identities, got {len(rows)} rows and {len(identities)} identities")
    output = {
        "schema_version": 1,
        "source_assessment": str(args.assessment),
        "candidate_count": len(rows),
        "unique_candidate_count": len(identities),
        "identity_set_matches_p2": identities == assessment_identities,
        "all_routes_have_route_timing_trigger": complete_routes,
        "rows": rows,
    }
    args.output.write_text(json.dumps(output, indent=2) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
