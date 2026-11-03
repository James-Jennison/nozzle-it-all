#!/usr/bin/env python3
"""Build the candidate-bound first P2 upstream assessment ledger.

The disposition table in this file is deliberate review data, not a generic
keyword classifier. The command refuses an unexpected tip or range so a later
upstream state cannot inherit these conclusions.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
from pathlib import Path


EXPECTED = {
    "orca-slicer": ("orca.git", "59f0cc3806ab6424d081978fde331088c55d9f0f", "26a39377713c774d52ca7d7ff7242188df4de63e"),
    "snapmaker-orca": ("snapmaker.git", "4aacbb22c739ed9bebc5c30537991a59228bcc06", "99e66ae1718844f39ca6decd7d9959380554aae7"),
    "prusa-slicer": ("prusa.git", None, "ff3d6677cfccfc7fbad72acc63f01c9abec6f5d9"),
    "bambu-studio": ("bambu.git", None, "56a57d00a591360cf41bf21383915ee5bf357be5"),
    "elegoo-slicer": ("elegoo.git", None, "2d507e39a96ab9562b35d06b23ef5df8fe613297"),
    "creality-print": ("creality.git", None, "254250c015a5f8c5b9bf3742e89066c8a7095565"),
}

ALREADY_PRESENT = {
    "1093ff0714e26ca8d81f3d7fe15d782cc047346f",
    "5cc5fb7f9c40c42ca47fc13969756c4e53119046",
    "913c622574d313ac1d4164a5226c031ae16488e3",
    "7ac46c9a6186458633a8c52203179c684c23fadc",
    "eda4062d68abe3b68155bb06270906dbbfb70ae2",
    "efe735853a1bc14e6665c2bddefebe5aa2cb4c80",
    "3610d8bb35a6129baed6f110efcc2a7aaa5fe900",
}

ADD_NOW: set[str] = set()

DO_NOT_ADD = {
    "b5517b693e898261a6e28f940b110cd62683fede",
    "19a78830d70032133afb88d470061f6b6e0df0ff",
    "2117d28713502a7e5480f12775d133dcf33c91ae",
}

ADAPT = {
    "a5210459ee7149cadad2f8df6aa95a94ead31017",
    "bb4c092ae41d54f00be41faac89bbc74fce99606",
    "c77020e6549e43288d2e020fada3b3d2fadb6a18",
    "27cafaa06c5856c1502025b39acb9d5c9c344250",
    "7b6c12cf74b6530713b4c9dd75634127274d0c1a",
    "8e37d7d4b23d5f11ee12bacc549ca85792ee6c93",
    "aa66e41e64958d02b92e8ba7cd0f9954740ee048",
    "9b4f84a9239ac6733d04a55858e99da862cbbcad",
    "fcd96ad91cb6dad3c27b8eb7c24f3daa592a72ac",
    "304924a69a68d1014a48f9fc7a72003938783042",
    "98b539c3e6b44e87bbde950e26d1b23c66691a0a",
    "db35f3d0980a622bc158d1f13855abc913cad8ca",
    "abe28edbe7ca99b267ed17a8a9669ae212964489",
    "23fe91393658b7908ea42db1d0a4f2a65d643eef",
    "9992b585b0c5ae798533e7661791e89db2da1ca9",
    "61124299802a4081900c3db703deac7573e4db13",
    "88806ac63e1b8a28f5ed6b46c876ff6952ffd6f0",
    "6f20243514e581626d66d8afd8e072804fdfc43f",
    "2ca7bdf23c51b58f1b60968b7853dac5953a1bae",
}

DEFER = {
    "26a39377713c774d52ca7d7ff7242188df4de63e",
    "195fbe5ee9edc999dc7ac0c21df70ef6c4a83884",
    "e8ebe3451a316d6d5e13dc01496603e8a9c257f3",
    "b044fbacfa7d42fac51529ce5cd481621a0acac7",
    "49770913a5b8680aeff18b32686fe95e11745339",
    "95a27321456e73cf6c6270d37dccfe819a96b130",
    "d1d8204dc10ede60cbc25adcc7cdf3ec87a6f6ff",
    "4a4128176110b73e1bbc87f66606d4834b50e160",
    "4045a87b70fc4745d586bda69d844b2e7b09f74e",
    "4ab172fd3320fd5f2a037824229c7f73afe65df8",
    "4274ea6bb374fe3a27163c54703f5e86b1679730",
    "3ca96a08919a305cab32f54ef3d9031c9a48d970",
    "efd65b7334c6f3ea05de5857b9aa45a799bc3515",
    "ffdb5da100662754b799c85eda41edd9570ce0ad",
    "eac3ba4416be7589354f827fa2fe5818c61d2076",
    "52c50e626e93242182c8bc0c57d1ad99fe14490a",
    "3bc827066df2f5ca7e4dcc6ad25dcc6f36092da6",
    "f894d0888113109c133055fc2013054490b206c5",
    "6372a37d71175ffadecef02a02ac551312ff3741",
    "3911c9a56e494114b0e59adc79af83cf248e12f6",
}

RATIONALE = {
    "ADD_NOW": ("pre-branding-stability", "Crash-boundary fix with direct first-release relevance; isolate and validate before branding."),
    "ADD_NEXT": ("engine-correctness-next", "Behavior or correctness change worth a focused later batch, but not required before the isolated crash fix."),
    "ADAPT": ("filament-device-adaptation", "Overlaps Hybrid printer, toolhead, filament or preset behavior and must be adapted rather than replayed."),
    "DEFER": ("post-first-release-upstream", "Profiles, localization, packaging, cosmetic UI or unrelated maintenance is outside the bounded first-release intake."),
    "ALREADY_PRESENT": ("none", "Equivalent source-bound behavior is already present in the accepted Hybrid baseline through its recorded selective upstream intake."),
    "DO_NOT_ADD": ("none", "Repository-maintenance automation or release-process behavior does not belong in NIA product source."),
}

ALREADY_EVIDENCE = {
    "1093ff0714e26ca8d81f3d7fe15d782cc047346f": "Hybrid commit 4666a155ad: Let Slice and Print retry after a post-processing script or plugin fails",
    "5cc5fb7f9c40c42ca47fc13969756c4e53119046": "Hybrid commit acc09845d2: Fix a crash when closing OrcaSlicer with a Bambu LAN printer connected",
    "913c622574d313ac1d4164a5226c031ae16488e3": "Hybrid commit 80cd092301: Remember the last printer and keep AMS-synced filaments across restarts",
    "7ac46c9a6186458633a8c52203179c684c23fadc": "Hybrid commit d18311425c: Fix lightning infill hang when an overhang sample lands past the infill",
    "eda4062d68abe3b68155bb06270906dbbfb70ae2": "Hybrid commit 568c7e3077: Fix crash opening a project with missing or empty settings",
    "efe735853a1bc14e6665c2bddefebe5aa2cb4c80": "Hybrid commit 0a17b12fc6: Drop a Scheduled Print Host Upload When the Slice Ends Before It Is Queued",
    "3610d8bb35a6129baed6f110efcc2a7aaa5fe900": "Hybrid commit edc17b8728: Do not prebuild pages while a modal dialog is open",
}

DETAILS = {
    "ADD_NOW": {
        "nia_relevance": "Direct first-release stability or data-integrity relevance.",
        "integration_method": "Isolate the smallest behavior after proving it is absent; port or reimplement with preserved attribution, never replay the surrounding range.",
        "compatibility_concerns": ["Hybrid slicing output", "3MF/profile compatibility", "Snapmaker U1 and non-U1 behavior", "cross-platform behavior"],
        "required_tests": ["upstream focused regression", "Hybrid-focused regression", "representative normalized golden G-code", "3MF/profile round trip where affected"],
    },
    "ADD_NEXT": {
        "nia_relevance": "Useful correctness or workflow improvement outside the pre-branding stability minimum.",
        "integration_method": "Reassess equivalent current code, then stage one bounded behavior in the engine-correctness batch.",
        "compatibility_concerns": ["slicing defaults", "golden G-code", "project/profile compatibility", "multi-printer behavior"],
        "required_tests": ["upstream focused regression", "affected Hybrid unit or GUI test", "representative golden slice when slicing can change"],
    },
    "ADAPT": {
        "nia_relevance": "Overlaps NIA printer, filament, toolhead, preset or device behavior.",
        "integration_method": "Compare behavior against the accepted Hybrid implementation and adapt only the missing invariant.",
        "compatibility_concerns": ["U1 toolhead and filament mapping", "user presets", "vendor-specific device behavior", "existing settings and projects"],
        "required_tests": ["U1 and ordinary-printer focused tests", "preset/settings persistence", "filament/tool mapping regression", "candidate-specific GUI or protocol test"],
    },
    "DEFER": {
        "nia_relevance": "Not required for the bounded first-release outcome or lacks a safe comparison base.",
        "integration_method": "Do not integrate; retain the exact source identity for a later named-capability assessment.",
        "compatibility_concerns": ["scope expansion", "vendor/profile drift", "unbounded platform or packaging changes"],
        "required_tests": ["none until a named capability reopens the candidate; then define candidate-specific tests before source change"],
    },
    "ALREADY_PRESENT": {
        "nia_relevance": "Behavior is already carried by the accepted Hybrid baseline.",
        "integration_method": "Do not replay; preserve the existing implementation and provenance mapping.",
        "compatibility_concerns": ["duplicate patching", "divergent duplicate implementations", "invalidating accepted baseline evidence"],
        "required_tests": ["no new test required in P2; existing source-bound baseline evidence remains authoritative until a material change"],
    },
    "DO_NOT_ADD": {
        "nia_relevance": "No product-runtime relevance; this belongs to the upstream repository's own maintenance or release process.",
        "integration_method": "Do not integrate into NIA product source.",
        "compatibility_concerns": ["unrelated CI or release-process coupling", "maintenance burden without user-visible value"],
        "required_tests": ["none; disposition is exclusion from product-source intake"],
    },
}


def git(git_dir: Path, *args: str) -> str:
    env = dict(os.environ, GIT_NO_LAZY_FETCH="1")
    return subprocess.check_output(
        ["git", f"--git-dir={git_dir}", *args], text=True, env=env
    ).rstrip("\n")


def disposition(commit: str) -> str:
    memberships = [
        name
        for name, commits in (
            ("ALREADY_PRESENT", ALREADY_PRESENT),
            ("ADD_NOW", ADD_NOW),
            ("ADAPT", ADAPT),
            ("DEFER", DEFER),
            ("DO_NOT_ADD", DO_NOT_ADD),
        )
        if commit in commits
    ]
    if len(memberships) > 1:
        raise ValueError(f"overlapping dispositions for {commit}: {memberships}")
    return memberships[0] if memberships else "ADD_NEXT"


def commit_metadata(git_dir: Path, commit: str) -> tuple[str, str, str, str]:
    raw = git(git_dir, "cat-file", "commit", commit)
    headers, _, message = raw.partition("\n\n")
    author_line = next(line for line in headers.splitlines() if line.startswith("author "))
    match = re.match(r"author (.*) <([^>]*)> [0-9]+ [+-][0-9]{4}$", author_line)
    if not match:
        raise ValueError(f"cannot parse author for {commit}")
    subject = message.splitlines()[0] if message else ""
    return commit, match.group(1), match.group(2), subject


def commit_record(git_dir: Path, commit: str) -> dict:
    commit_id, author, email, subject = commit_metadata(git_dir, commit)
    files = git(git_dir, "diff-tree", "--no-commit-id", "--name-only", "-r", commit)
    file_lines = files.splitlines() if files else ["<merge or metadata-only commit; inspect parent range before implementation>"]
    disp = disposition(commit_id)
    batch, reason = RATIONALE[disp]
    details = DETAILS[disp]
    top_level = sorted({path.split("/", 1)[0] for path in file_lines if path})
    return {
        "commit": commit_id,
        "original_author": {"name": author, "email": email},
        "behavioral_purpose": subject,
        "files": file_lines,
        "subsystems": top_level,
        "dependencies": ["current accepted Hybrid implementation", "upstream behavior and tests named by the commit"],
        "downstream_adaptations": "Reconcile with Snapmaker and NIA changes after the shared lineage point; do not assume clean cherry-pick applicability.",
        "disposition": disp,
        "recommended_batch": batch,
        "reason": reason,
        "presence_evidence": ALREADY_EVIDENCE.get(commit_id, "not applicable to this disposition"),
        **details,
    }


def build(cache_root: Path, registry_path: Path) -> dict:
    registry = json.loads(registry_path.read_text())
    sources = {item["id"]: item for item in registry["sources"]}
    assessments = []
    for source_id, (directory, base, tip) in EXPECTED.items():
        source = sources[source_id]
        if source["current_fetched_commit"] != tip:
            raise ValueError(f"registry tip drift for {source_id}")
        git_dir = cache_root / directory
        actual_tip = git(git_dir, "rev-parse", "HEAD")
        if actual_tip != tip:
            raise ValueError(f"cache tip drift for {source_id}: {actual_tip}")

        if source_id == "orca-slicer":
            commits = git(git_dir, "rev-list", "--reverse", f"{base}..{tip}").splitlines()
            records = [commit_record(git_dir, commit) for commit in commits]
            if len(records) != 77:
                raise ValueError(f"expected 77 Orca candidates, got {len(records)}")
            assessment = {
                "source": source_id,
                "origin_repository": source["repository"],
                "license": source["license"],
                "range": {"exclusive_base": base, "inclusive_tip": tip},
                "assessment_kind": "exact_delta",
                "advances_last_assessed": True,
                "candidates": records,
            }
        elif source_id == "snapmaker-orca":
            records = git(git_dir, "rev-list", "--reverse", f"{base}..{tip}").splitlines()
            if records != [tip]:
                raise ValueError(f"unexpected Snapmaker range: {records}")
            rec = commit_record(git_dir, tip)
            rec.update(
                disposition="ADAPT",
                recommended_batch="snapmaker-u1-delta-review",
                reason="The single squashed vendor sync changes broad engine and GUI surfaces; compare U1-specific behavior against the Orca range and accepted Hybrid before staging any subset.",
                nia_relevance="High vendor relevance, but the squashed change is not a safe integration unit.",
                integration_method="Diff and test named U1 capabilities individually; never stage the squashed vendor sync as one patch.",
                compatibility_concerns=["squashed provenance", "broad GUI and engine change", "U1 behavior", "overlap with Orca and Hybrid"],
                required_tests=["candidate-specific U1 protocol or GUI test", "multi-printer regression", "normalized golden slices", "3MF/profile compatibility"],
            )
            assessment = {
                "source": source_id,
                "origin_repository": source["repository"],
                "license": source["license"],
                "range": {"exclusive_base": base, "inclusive_tip": tip},
                "assessment_kind": "exact_delta",
                "advances_last_assessed": True,
                "candidates": [rec],
            }
        else:
            _, author_name, author_email, subject = commit_metadata(git_dir, tip)
            assessment = {
                "source": source_id,
                "origin_repository": source["repository"],
                "license": source["license"],
                "range": {"exclusive_base": None, "inclusive_tip": tip},
                "assessment_kind": "base_registration_only",
                "advances_last_assessed": False,
                "candidates": [{
                    "commit": tip,
                    "original_author": {"name": author_name, "email": author_email},
                    "behavioral_purpose": f"Initial registered tip snapshot: {subject}",
                    "files": ["<repository-wide exact tip snapshot; no prior accepted source base>"],
                    "subsystems": ["repository-wide initial snapshot"],
                    "dependencies": ["named future capability", "an explicit comparison base selected before implementation"],
                    "downstream_adaptations": "Conceptual fork ancestry is routing context only; commit and behavior equivalence must be established for the named capability.",
                    "disposition": "DEFER",
                    "recommended_batch": "targeted-capability-assessment",
                    "reason": "No previously accepted source commit is registered. Preserve the exact tip and assess a named capability rather than treating the repository history as a merge queue.",
                    "presence_evidence": "not applicable to this disposition",
                    **DETAILS["DEFER"],
                }],
            }
        assessments.append(assessment)

    return {
        "schema_version": 2,
        "phase": "P2",
        "assessment_date": "2026-11-03",
        "accepted_hybrid_baseline": "3d0a917697e036a90a4aca75268c186bec3e0f2f",
        "product_source_changed": False,
        "lineage": {
            "snapmaker_orca_shared_commit": "737948be1f5b108b191420d4c670b511f8118604",
            "orca_bambu_shared_commit": "c460504e3e45a987a76ba18e50d0a267dfe77912",
            "bambu_prusa_shared_commit": None,
            "bambu_prusa_note": "No identical commit was present in the two selected branch graphs; conceptual fork ancestry is not treated as commit identity.",
            "elegoo_orca_shared_commit": "3ffb9585d2a9b97da53fdf6b305cbc5f4eb22b3b",
            "creality_orca_shared_commit": None,
        },
        "sources": assessments,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--cache-root", type=Path, required=True)
    parser.add_argument("--registry", type=Path, default=Path("docs/upstream/sources.json"))
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        raise SystemExit(f"refusing to overwrite {args.output}")
    result = build(args.cache_root, args.registry)
    args.output.write_text(json.dumps(result, indent=2) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
