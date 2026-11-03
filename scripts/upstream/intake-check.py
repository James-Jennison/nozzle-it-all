#!/usr/bin/env python3
"""Produce a provenance-bearing, read-only NIA upstream intake report."""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import re
import subprocess
import sys
from pathlib import Path
from typing import Any


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_REGISTRY = REPOSITORY_ROOT / "docs/upstream/sources.json"
REQUIRED_CORE = {
    "orca-slicer",
    "snapmaker-orca",
    "prusa-slicer",
    "bambu-studio",
}
REQUIRED_SUMMARY = {"elegoo-slicer", "creality-print"}
VALID_TIERS = {"core", "summary", "targeted"}
COMMIT_RE = re.compile(r"^[0-9a-f]{40}$")


class IntakeError(RuntimeError):
    """The intake input or remote result violated a required invariant."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise IntakeError(message)


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_path(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def run_git(*arguments: str, timeout: int = 30) -> subprocess.CompletedProcess[bytes]:
    return subprocess.run(
        ["git", *arguments],
        cwd=REPOSITORY_ROOT,
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=timeout,
        env={**os.environ, "GIT_TERMINAL_PROMPT": "0"},
    )


def repository_fingerprint() -> dict[str, str]:
    head = run_git("rev-parse", "HEAD")
    require(head.returncode == 0, "cannot resolve repository HEAD")
    tree = run_git("rev-parse", "HEAD^{tree}")
    require(tree.returncode == 0, "cannot resolve repository HEAD tree")
    index = run_git("ls-files", "-s", "-z")
    require(index.returncode == 0, "cannot fingerprint repository index")
    diff = run_git("diff", "--binary", "HEAD", "--", ".")
    require(diff.returncode == 0, "cannot fingerprint repository tracked state")
    head_value = head.stdout.decode().strip()
    tree_value = tree.stdout.decode().strip()
    index_hash = sha256_bytes(index.stdout)
    diff_hash = sha256_bytes(diff.stdout)
    return {
        "head": head_value,
        "head_tree": tree_value,
        "tracked_index_sha256": index_hash,
        "tracked_diff_sha256": diff_hash,
        "tracked_state_sha256": sha256_bytes(
            f"{head_value}\n{tree_value}\n{index_hash}\n{diff_hash}\n".encode()
        ),
    }


def load_registry(path: Path) -> dict[str, Any]:
    try:
        with path.open("r", encoding="utf-8") as stream:
            registry = json.load(stream)
    except (OSError, json.JSONDecodeError) as exc:
        raise IntakeError(f"cannot load registry {path}: {exc}") from exc

    require(registry.get("schema_version") == 1, "unsupported registry schema_version")
    sources = registry.get("sources")
    require(isinstance(sources, list) and sources, "registry sources must be a non-empty list")

    ids: set[str] = set()
    for index, source in enumerate(sources):
        require(isinstance(source, dict), f"source {index} is not an object")
        source_id = source.get("id")
        require(isinstance(source_id, str) and source_id, f"source {index} has no id")
        require(source_id not in ids, f"duplicate source id: {source_id}")
        ids.add(source_id)
        require(source.get("tier") in VALID_TIERS, f"invalid tier for {source_id}")
        for field in ("repository", "fetch_url", "tracked_ref", "license", "specialization"):
            require(isinstance(source.get(field), str) and source[field], f"{source_id}.{field} is missing")
        require(source["fetch_url"].startswith("https://"), f"{source_id}.fetch_url must use HTTPS")
        require(source["tracked_ref"].startswith("refs/heads/"), f"{source_id}.tracked_ref must be a branch ref")
        for field in ("last_assessed_commit", "last_accepted_commit", "current_fetched_commit"):
            value = source.get(field)
            require(value is None or (isinstance(value, str) and COMMIT_RE.fullmatch(value)), f"invalid {source_id}.{field}")
        if source.get("current_fetched_commit") is not None:
            report = source.get("current_fetched_report")
            require(isinstance(report, str) and report, f"{source_id} needs current_fetched_report")
            require((REPOSITORY_ROOT / report).is_file(), f"current fetched report is missing for {source_id}: {report}")
        require(isinstance(source.get("known_ancestry"), list), f"{source_id}.known_ancestry must be a list")
        require(isinstance(source.get("capabilities"), list), f"{source_id}.capabilities must be a list")
        if source["tier"] == "targeted":
            require(bool(source["capabilities"]), f"targeted source {source_id} needs capabilities")
        else:
            require(not source["capabilities"], f"non-targeted source {source_id} cannot declare targeted capabilities")
        if source.get("last_assessed_commit") is not None:
            record = source.get("assessment_record")
            require(isinstance(record, str) and record, f"{source_id} needs assessment_record for last_assessed_commit")
            require((REPOSITORY_ROOT / record).is_file(), f"assessment record is missing for {source_id}: {record}")

    require({s["id"] for s in sources if s["tier"] == "core"} == REQUIRED_CORE, "core source set does not match policy")
    require({s["id"] for s in sources if s["tier"] == "summary"} == REQUIRED_SUMMARY, "summary source set does not match policy")
    for source in sources:
        unknown = set(source["known_ancestry"]) - ids
        require(not unknown, f"{source['id']} has unknown ancestry ids: {sorted(unknown)}")
    report_cache: dict[str, dict[str, Any]] = {}
    for source in sources:
        if source.get("current_fetched_commit") is None:
            continue
        record = source["current_fetched_report"]
        if record not in report_cache:
            try:
                with (REPOSITORY_ROOT / record).open("r", encoding="utf-8") as stream:
                    report_cache[record] = json.load(stream)
            except (OSError, json.JSONDecodeError) as exc:
                raise IntakeError(f"cannot load current fetched report {record}: {exc}") from exc
        matches = [item for item in report_cache[record].get("sources", []) if item.get("id") == source["id"]]
        require(len(matches) == 1, f"current fetched report has no unique entry for {source['id']}")
        require(
            matches[0].get("commit") == source["current_fetched_commit"],
            f"current fetched commit does not match report for {source['id']}",
        )
    return registry


def select_sources(registry: dict[str, Any], capability: str | None) -> list[dict[str, Any]]:
    sources = registry["sources"]
    selected = [source for source in sources if source["tier"] in {"core", "summary"}]
    if capability is not None:
        targeted = [source for source in sources if capability in source["capabilities"]]
        available = sorted({item for source in sources for item in source["capabilities"]})
        require(bool(targeted), f"unknown capability {capability!r}; available: {', '.join(available)}")
        selected.extend(targeted)
    return selected


def resolve_offline(source: dict[str, Any], fixture_dir: Path) -> str:
    fixture_path = fixture_dir / f"{source['id']}.json"
    try:
        with fixture_path.open("r", encoding="utf-8") as stream:
            fixture = json.load(stream)
    except (OSError, json.JSONDecodeError) as exc:
        raise IntakeError(f"cannot load fixture for {source['id']}: {exc}") from exc
    require(fixture.get("ref") == source["tracked_ref"], f"fixture ref mismatch for {source['id']}")
    commit = fixture.get("commit")
    require(isinstance(commit, str) and COMMIT_RE.fullmatch(commit), f"invalid fixture commit for {source['id']}")
    return commit


def resolve_live(source: dict[str, Any], timeout: int) -> str:
    try:
        result = run_git(
            "ls-remote",
            "--exit-code",
            source["fetch_url"],
            source["tracked_ref"],
            timeout=timeout,
        )
    except subprocess.TimeoutExpired as exc:
        raise IntakeError(f"live query timed out for {source['id']}") from exc
    if result.returncode != 0:
        detail = result.stderr.decode(errors="replace").strip()
        raise IntakeError(f"live query failed for {source['id']}: {detail or 'no matching ref'}")
    matches: list[str] = []
    for line in result.stdout.decode().splitlines():
        fields = line.split()
        if len(fields) == 2 and fields[1] == source["tracked_ref"]:
            matches.append(fields[0].lower())
    require(len(matches) == 1 and COMMIT_RE.fullmatch(matches[0]), f"unexpected live result for {source['id']}")
    return matches[0]


def local_relationship(last_accepted: str | None, current: str) -> str:
    if last_accepted is None:
        return "NO_ACCEPTED_BASE"
    if last_accepted == current:
        return "SAME"
    for commit in (last_accepted, current):
        present = run_git("cat-file", "-e", f"{commit}^{{commit}}")
        if present.returncode != 0:
            return "UNKNOWN_OBJECTS"
    descendant = run_git("merge-base", "--is-ancestor", last_accepted, current)
    if descendant.returncode == 0:
        return "DESCENDANT"
    reverse = run_git("merge-base", "--is-ancestor", current, last_accepted)
    if reverse.returncode == 0:
        return "BEHIND_ACCEPTED"
    return "DIVERGED"


def build_report(
    registry_path: Path,
    registry: dict[str, Any],
    mode: str,
    fixture_dir: Path | None,
    capability: str | None,
    timeout: int,
    generated_at: str | None,
) -> dict[str, Any]:
    before = repository_fingerprint()
    resolved: list[dict[str, Any]] = []
    for source in select_sources(registry, capability):
        commit = resolve_offline(source, fixture_dir) if fixture_dir is not None else resolve_live(source, timeout)
        resolved.append(
            {
                "id": source["id"],
                "tier": source["tier"],
                "repository": source["repository"],
                "fetch_url": source["fetch_url"],
                "tracked_ref": source["tracked_ref"],
                "commit": commit,
                "previous_current_fetched_commit": source["current_fetched_commit"],
                "last_assessed_commit": source["last_assessed_commit"],
                "last_accepted_commit": source["last_accepted_commit"],
                "relationship_to_last_accepted": local_relationship(source["last_accepted_commit"], commit),
                "needs_assessment": source["last_assessed_commit"] != commit,
                "license": source["license"],
                "specialization": source["specialization"],
                "known_ancestry": source["known_ancestry"],
            }
        )
    after = repository_fingerprint()
    unchanged = before == after
    require(unchanged, "repository HEAD or tracked state changed during intake")
    timestamp = generated_at or dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds")
    return {
        "schema_version": 1,
        "mode": mode,
        "generated_at": timestamp,
        "registry_path": str(registry_path),
        "registry_sha256": sha256_path(registry_path),
        "requested_capability": capability,
        "selection_policy": "core+summary always; targeted only for a registered capability",
        "selected_source_ids": [source["id"] for source in resolved],
        "product_state_before": before,
        "product_state_after": after,
        "product_source_unchanged": unchanged,
        "last_assessed_advanced": False,
        "sources": resolved,
    }


def write_new_report(path: Path, report: dict[str, Any]) -> None:
    require(not path.exists(), f"refusing to overwrite existing report: {path}")
    path.parent.mkdir(parents=True, exist_ok=True)
    try:
        with path.open("x", encoding="utf-8") as stream:
            json.dump(report, stream, indent=2, sort_keys=True)
            stream.write("\n")
    except OSError as exc:
        raise IntakeError(f"cannot write report {path}: {exc}") from exc


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--registry", type=Path, default=DEFAULT_REGISTRY)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--live", action="store_true", help="query public tracked refs with git ls-remote")
    mode.add_argument("--offline-fixtures", type=Path, help="read deterministic per-source fixture JSON")
    parser.add_argument("--capability", help="include targeted sources registered for this capability")
    parser.add_argument("--output", type=Path, help="write a new JSON report instead of stdout")
    parser.add_argument("--timeout", type=int, default=45, help="per-source live query timeout in seconds")
    parser.add_argument("--generated-at", help=argparse.SUPPRESS)
    return parser.parse_args()


def main() -> int:
    arguments = parse_arguments()
    try:
        registry_path = arguments.registry.resolve()
        registry = load_registry(registry_path)
        fixture_dir = arguments.offline_fixtures.resolve() if arguments.offline_fixtures else None
        report = build_report(
            registry_path,
            registry,
            "live" if arguments.live else "offline",
            fixture_dir,
            arguments.capability,
            arguments.timeout,
            arguments.generated_at,
        )
        if arguments.output:
            write_new_report(arguments.output.resolve(), report)
        else:
            json.dump(report, sys.stdout, indent=2, sort_keys=True)
            sys.stdout.write("\n")
    except IntakeError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
