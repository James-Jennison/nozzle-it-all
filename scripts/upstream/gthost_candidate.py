#!/usr/bin/env python3
"""Validate an Orca candidate only through its pinned GTHost container contract."""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import sys
import uuid


EXPECTED_HOST = "gthost-build01"
BUILDER_IMAGE = "orcaslicer-linux-builder:ubuntu-24.04--cmake-4.3.0--4fc88e178d94"
BUILDER_IMAGE_ID = "sha256:26c4788d9bca8b29024d75df8f52e36bea0438a0007c3a527eecd13496ca6b23"
CONTAINER_SOURCE = Path("/__w/OrcaSlicer/OrcaSlicer")
CONTAINER_HOME = "/tmp"
CONTAINER_LOCALE = "C.utf8"
BUILDER_UID = 1000
BUILDER_GID = 1000
DEFAULT_BUILD_DIR = "build"
DEFAULT_CONFIG = "Release"
ARCHIVE_TOOL = Path("/usr/local/bin/gthost-archive-artifact")
ARCHIVE_PROJECT = "orcaslicer-candidates"


class ContractError(RuntimeError):
    pass


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def parse_cmake_cache(path: Path) -> dict[str, str]:
    if not path.is_file():
        raise ContractError(f"missing CMake cache: {path}")
    values: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8", errors="strict").splitlines():
        if not line or line.startswith(("#", "//")) or "=" not in line:
            continue
        key_with_type, value = line.split("=", 1)
        values[key_with_type.split(":", 1)[0]] = value
    return values


def validate_build_metadata(source: Path, build_dir: str, config: str) -> dict[str, str]:
    build = source / build_dir
    cache = parse_cmake_cache(build / "CMakeCache.txt")
    cmake_home = cache.get("CMAKE_HOME_DIRECTORY")
    if cmake_home != str(CONTAINER_SOURCE):
        raise ContractError(
            "build source mismatch: CMAKE_HOME_DIRECTORY is "
            f"{cmake_home!r}, expected {str(CONTAINER_SOURCE)!r}; host-side CTest is forbidden"
        )
    configs = cache.get("CMAKE_CONFIGURATION_TYPES", "").split(";")
    if config not in configs:
        raise ContractError(f"configuration {config!r} is not present in CMAKE_CONFIGURATION_TYPES={configs!r}")
    ctest_root = build / "CTestTestfile.cmake"
    if not ctest_root.is_file():
        raise ContractError(f"missing CTest metadata: {ctest_root}")
    metadata_files = [ctest_root, *sorted((build / "tests").glob("**/CTestTestfile.cmake"))]
    if not any(str(CONTAINER_SOURCE) in item.read_text(encoding="utf-8", errors="replace") for item in metadata_files):
        raise ContractError("CTest metadata is not bound to the canonical container source path")
    return {"cmake_home": cmake_home, "build_dir": build_dir, "configuration": config}


def map_container_artifact(source: Path, artifact_path: str) -> Path:
    artifact = Path(artifact_path)
    try:
        relative = artifact.relative_to(CONTAINER_SOURCE)
    except ValueError as exc:
        raise ContractError(f"packaging output is outside the canonical container source: {artifact}") from exc
    host_artifact = source / relative
    if not host_artifact.is_file():
        raise ContractError(f"recorded packaging output does not exist: {host_artifact}")
    return host_artifact


def docker_command(source: Path, ctest_args: list[str]) -> list[str]:
    return [
        "docker", "run", "--rm", "--userns=host", "--user", f"{BUILDER_UID}:{BUILDER_GID}",
        "-e", f"HOME={CONTAINER_HOME}", "-e", f"LANG={CONTAINER_LOCALE}",
        "-e", f"LC_ALL={CONTAINER_LOCALE}", "-v", f"{source}:{CONTAINER_SOURCE}",
        "-w", str(CONTAINER_SOURCE),
        BUILDER_IMAGE, "ctest", *ctest_args,
    ]


def git_output(source: Path, *args: str) -> str:
    return subprocess.check_output(["git", "-C", str(source), *args], text=True).strip()


def verify_host_contract(source: Path) -> str:
    host = socket.gethostname().split(".", 1)[0]
    if host != EXPECTED_HOST:
        raise ContractError(f"this entrypoint runs only on {EXPECTED_HOST}; observed {host}")
    stat = source.stat()
    if (stat.st_uid, stat.st_gid) != (BUILDER_UID, BUILDER_GID):
        raise ContractError(
            f"source ownership mismatch: observed {stat.st_uid}:{stat.st_gid}, expected {BUILDER_UID}:{BUILDER_GID}"
        )
    observed_image = subprocess.check_output(
        ["docker", "image", "inspect", "--format", "{{.Id}}", BUILDER_IMAGE], text=True
    ).strip()
    if observed_image != BUILDER_IMAGE_ID:
        raise ContractError(f"builder image mismatch: observed {observed_image!r}, expected {BUILDER_IMAGE_ID!r}")
    return observed_image


def write_test_record(source: Path, build_dir: str, mode: str, command: list[str], returncode: int, regex: str | None) -> None:
    record = {
        "schema_version": 1,
        "recorded_at_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "mode": mode,
        "configuration": next((command[index + 1] for index, value in enumerate(command[:-1]) if value == "-C"), None),
        "regex": regex,
        "returncode": returncode,
        "command": command,
        "source_commit": git_output(source, "rev-parse", "HEAD"),
        "builder_image_id": BUILDER_IMAGE_ID,
    }
    output = source / build_dir / "gthost-validation-results.jsonl"
    with output.open("a", encoding="utf-8") as stream:
        stream.write(json.dumps(record, sort_keys=True) + "\n")


def run_tests(source: Path, build_dir: str, config: str, mode: str, regex: str | None) -> int:
    ctest_args = ["--test-dir", build_dir, "-C", config]
    if mode == "list":
        ctest_args.append("-N")
    else:
        ctest_args.append("--output-on-failure")
        if mode == "focused":
            if not regex:
                raise ContractError("focused mode requires --regex")
            ctest_args.extend(["-R", regex])
        else:
            ctest_args.extend(["-j", "12"])
    command = docker_command(source, ctest_args)
    result = subprocess.run(command, check=False)
    write_test_record(source, build_dir, mode, command, result.returncode, regex)
    return result.returncode


def successful_full_test_record(source: Path, build_dir: str, config: str) -> dict[str, object]:
    results_path = source / build_dir / "gthost-validation-results.jsonl"
    if not results_path.is_file():
        raise ContractError(f"candidate has no validation record: {results_path}")
    commit = git_output(source, "rev-parse", "HEAD")
    matching: list[dict[str, object]] = []
    for line_number, line in enumerate(results_path.read_text(encoding="utf-8").splitlines(), start=1):
        if not line:
            continue
        try:
            record = json.loads(line)
        except json.JSONDecodeError as exc:
            raise ContractError(f"invalid validation record at {results_path}:{line_number}") from exc
        command = record.get("command", [])
        recorded_config = record.get("configuration")
        config_matches = recorded_config == config or (
            recorded_config is None and isinstance(command, list) and "-C" in command
            and command.index("-C") + 1 < len(command) and command[command.index("-C") + 1] == config
        )
        if (record.get("mode") == "full" and record.get("returncode") == 0
                and record.get("source_commit") == commit and config_matches):
            matching.append(record)
    if not matching:
        raise ContractError(
            f"candidate has no successful full {config} test record for source commit {commit}"
        )
    return matching[-1]


def write_json_for_source(path: Path, value: dict[str, object], source: Path) -> None:
    temporary = path.with_name(f".{path.name}.tmp-{uuid.uuid4().hex}")
    with temporary.open("x", encoding="utf-8") as stream:
        json.dump(value, stream, indent=2, sort_keys=True)
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())
    source_stat = source.stat()
    os.chown(temporary, source_stat.st_uid, source_stat.st_gid)
    os.chmod(temporary, 0o600)
    temporary.replace(path)


def write_sanitization_attestation(source: Path, build_dir: str,
                                   inputs: list[Path]) -> Path:
    attestation = source / build_dir / "archive-sanitization-attestation.json"
    value = {
        "schema_version": 1,
        "attested_by": "orcaslicer-gthost-candidate-wrapper-v1",
        "inspection_method": (
            "Protected-builder provenance: the candidate manifest requires clean tracked source; "
            "the test and package records are wrapper-generated schema-bound outputs; the builder "
            "container receives only HOME, LANG, and LC_ALL; each exact input is hash-bound here."
        ),
        "files": [
            {
                "path": str(path),
                "sha256": sha256_file(path),
                "sanitized": True,
                "contains_secrets": False,
            }
            for path in inputs
        ],
    }
    write_json_for_source(attestation, value, source)
    return attestation


def archive_candidate(source: Path, build_dir: str, config: str,
                      remove_local_artifact: bool = False,
                      retirement_record: Path | None = None) -> Path:
    if os.geteuid() != 0:
        raise ContractError("archive mode requires sudo so the root-owned CIFS mount remains access-controlled")
    if remove_local_artifact and retirement_record is None:
        raise ContractError("--remove-local-artifact requires --retirement-record")
    if not remove_local_artifact and retirement_record is not None:
        raise ContractError("--retirement-record is valid only with --remove-local-artifact")
    successful_full_test_record(source, build_dir, config)
    manifest_path = source / build_dir / "candidate-manifest.json"
    package_record_path = source / build_dir / "package-output.json"
    results_path = source / build_dir / "gthost-validation-results.jsonl"
    if not manifest_path.is_file():
        raise ContractError(f"candidate manifest is missing: {manifest_path}")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    commit = git_output(source, "rev-parse", "HEAD")
    if manifest.get("source", {}).get("commit") != commit:
        raise ContractError("candidate manifest source commit does not match the current source")
    artifact = Path(str(manifest.get("artifact", {}).get("host_path", "")))
    if not artifact.is_file():
        raise ContractError(f"candidate artifact is missing: {artifact}")
    artifact_sha = sha256_file(artifact)
    if artifact_sha != manifest.get("artifact", {}).get("sha256"):
        raise ContractError("candidate artifact no longer matches its manifest")
    provenance = [manifest_path, package_record_path, results_path]
    if not all(path.is_file() for path in provenance):
        raise ContractError("candidate provenance is incomplete")
    attestation = write_sanitization_attestation(source, build_dir, [artifact, *provenance])
    configuration = f"{config}-builder-{BUILDER_IMAGE_ID.removeprefix('sha256:')[:12]}"
    command = [
        str(ARCHIVE_TOOL),
        "--project", ARCHIVE_PROJECT,
        "--source-identity", commit,
        "--configuration", configuration,
        "--artifact", str(artifact),
    ]
    for path in provenance:
        command.extend(["--provenance", str(path)])
    command.extend(["--sanitization-attestation", str(attestation)])
    if remove_local_artifact:
        command.extend(["--remove-local-artifact", "--retirement-record", str(retirement_record)])
    result = subprocess.run(command, check=True, capture_output=True, text=True)
    output_lines = [line.strip() for line in result.stdout.splitlines() if line.strip()]
    if not output_lines:
        raise ContractError("archive tool returned no storage destination")
    destination = Path(output_lines[-1])

    generic_status_path = artifact.parent / f"{artifact.name}.storage-archive.json"
    if not generic_status_path.is_file():
        raise ContractError(f"archive tool did not write its local status: {generic_status_path}")
    generic_status = json.loads(generic_status_path.read_text(encoding="utf-8"))
    if (generic_status.get("project") != ARCHIVE_PROJECT
            or generic_status.get("source_identity") != commit
            or generic_status.get("configuration") != configuration
            or generic_status.get("artifact_sha256") != artifact_sha
            or generic_status.get("storage_path") != str(destination)):
        raise ContractError("archive tool status does not match the candidate request")
    if remove_local_artifact and (
            artifact.exists() or generic_status.get("local_artifact_removed") is not True
            or generic_status.get("local_artifact_removal_state") != "removed"):
        raise ContractError("archive tool did not complete the authorized local retirement")

    wrapper_record = {
        "schema_version": 2,
        "archive_contract": "gthost-archive-artifact-v3",
        "archive_tool": str(ARCHIVE_TOOL),
        "archive_tool_status_path": str(generic_status_path),
        "archive_tool_status_sha256": sha256_file(generic_status_path),
        "source_commit": commit,
        "configuration": configuration,
        "builder_image_id": BUILDER_IMAGE_ID,
        "artifact_sha256": artifact_sha,
        "storage_path": str(destination),
        "local_artifact_removed": not artifact.exists(),
    }
    write_json_for_source(source / build_dir / "storage-archive-record.json", wrapper_record, source)
    print(destination)
    return destination


def write_manifest(source: Path, build_dir: str, config: str, source_archive_sha256: str,
                   observed_image_id: str, output: Path | None) -> Path:
    if not re.fullmatch(r"[0-9a-f]{64}", source_archive_sha256):
        raise ContractError("--source-archive-sha256 must be a lowercase SHA-256 digest")
    if git_output(source, "status", "--porcelain=v1", "--untracked-files=no"):
        raise ContractError("candidate source has tracked dirty state; manifest creation is refused")
    package_record_path = source / build_dir / "package-output.json"
    if not package_record_path.is_file():
        raise ContractError(f"packaging output record is missing: {package_record_path}")
    package_record = json.loads(package_record_path.read_text(encoding="utf-8"))
    if package_record.get("configuration") != config:
        raise ContractError("packaging output configuration does not match the requested candidate configuration")
    artifact = map_container_artifact(source, str(package_record.get("artifact_path", "")))
    actual_sha = sha256_file(artifact)
    if actual_sha != package_record.get("artifact_sha256"):
        raise ContractError("packaging output hash does not match the recorded artifact")
    results_path = source / build_dir / "gthost-validation-results.jsonl"
    manifest = {
        "schema_version": 1,
        "created_at_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "source": {"host_path": str(source), "container_path": str(CONTAINER_SOURCE),
                   "commit": git_output(source, "rev-parse", "HEAD"),
                   "archive_sha256": source_archive_sha256, "tracked_dirty": False},
        "runner": {"host": EXPECTED_HOST, "builder_image": BUILDER_IMAGE,
                   "builder_image_id": observed_image_id, "userns": "host",
                   "uid": BUILDER_UID, "gid": BUILDER_GID, "home": CONTAINER_HOME,
                   "locale": CONTAINER_LOCALE, "workdir": str(CONTAINER_SOURCE)},
        "build": {"directory": build_dir, "configuration": config,
                  "cmake_home_directory": str(CONTAINER_SOURCE)},
        "artifact": {"kind": package_record.get("artifact_kind"),
                     "container_path": package_record.get("artifact_path"), "host_path": str(artifact),
                     "sha256": actual_sha, "size_bytes": artifact.stat().st_size},
        "test_results_jsonl": str(results_path) if results_path.is_file() else None,
    }
    destination = output or source / build_dir / "candidate-manifest.json"
    destination.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(destination)
    return destination


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("list", "focused", "full", "manifest", "archive"))
    parser.add_argument("--source-dir", type=Path, default=Path.cwd())
    parser.add_argument("--build-dir", default=DEFAULT_BUILD_DIR)
    parser.add_argument("--config", default=DEFAULT_CONFIG)
    parser.add_argument("--regex")
    parser.add_argument("--source-archive-sha256")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--remove-local-artifact", action="store_true")
    parser.add_argument("--retirement-record", type=Path)
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    try:
        source = args.source_dir.resolve(strict=True)
        observed_image = verify_host_contract(source)
        validate_build_metadata(source, args.build_dir, args.config)
        if args.mode == "manifest":
            if not args.source_archive_sha256:
                raise ContractError("manifest mode requires --source-archive-sha256")
            write_manifest(source, args.build_dir, args.config, args.source_archive_sha256, observed_image, args.output)
            return 0
        if args.mode == "archive":
            archive_candidate(source, args.build_dir, args.config,
                              args.remove_local_artifact, args.retirement_record)
            return 0
        return run_tests(source, args.build_dir, args.config, args.mode, args.regex)
    except (ContractError, OSError, subprocess.CalledProcessError, json.JSONDecodeError) as exc:
        print(f"gthost-candidate: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
