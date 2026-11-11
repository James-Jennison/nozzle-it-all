#!/usr/bin/env python3

import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts" / "upstream"))
import gthost_candidate as candidate


class GTHostCandidateContractTests(unittest.TestCase):
    def make_build(self, root: Path, cmake_home: str = str(candidate.CONTAINER_SOURCE)) -> None:
        build = root / "build"
        tests = build / "tests"
        tests.mkdir(parents=True)
        (build / "CMakeCache.txt").write_text(
            f"CMAKE_HOME_DIRECTORY:INTERNAL={cmake_home}\n"
            "CMAKE_CONFIGURATION_TYPES:STRING=Debug;Release;RelWithDebInfo\n", encoding="utf-8")
        (build / "CTestTestfile.cmake").write_text("subdirs(\"tests\")\n", encoding="utf-8")
        (tests / "CTestTestfile.cmake").write_text(
            f"add_test(example \"{candidate.CONTAINER_SOURCE}/build/tests/example\")\n", encoding="utf-8")

    def test_container_bound_metadata_is_accepted(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self.make_build(root)
            self.assertEqual(candidate.validate_build_metadata(root, "build", "Release")["cmake_home"],
                             str(candidate.CONTAINER_SOURCE))

    def test_host_bound_metadata_is_rejected_without_fallback(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self.make_build(root, str(root))
            with self.assertRaisesRegex(candidate.ContractError, "host-side CTest is forbidden"):
                candidate.validate_build_metadata(root, "build", "Release")

    def test_docker_command_pins_runner_contract(self) -> None:
        source = Path("/candidate")
        command = candidate.docker_command(source, ["--test-dir", "build", "-N"])
        self.assertIn("--userns=host", command)
        self.assertIn("1000:1000", command)
        self.assertIn(f"LANG={candidate.CONTAINER_LOCALE}", command)
        self.assertIn(f"LC_ALL={candidate.CONTAINER_LOCALE}", command)
        self.assertIn(f"{source}:{candidate.CONTAINER_SOURCE}", command)
        self.assertIn(candidate.BUILDER_IMAGE, command)
        self.assertEqual(command[-4:], ["ctest", "--test-dir", "build", "-N"])

    def test_manifest_maps_and_hashes_recorded_output_only(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            artifact = root / "build" / "package" / "bin" / "nia"
            artifact.parent.mkdir(parents=True)
            artifact.write_bytes(b"candidate")
            mapped = candidate.map_container_artifact(
                root, str(candidate.CONTAINER_SOURCE / "build" / "package" / "bin" / "nia"))
            self.assertEqual(mapped, artifact)
            self.assertEqual(candidate.sha256_file(mapped), hashlib.sha256(b"candidate").hexdigest())
            with self.assertRaisesRegex(candidate.ContractError, "outside"):
                candidate.map_container_artifact(root, "/tmp/guessed.AppImage")

    def test_package_record_schema_is_json(self) -> None:
        record = {"schema_version": 1, "artifact_kind": "appdir-executable",
                  "artifact_path": str(candidate.CONTAINER_SOURCE / "build/package/bin/nia"),
                  "artifact_sha256": "0" * 64, "configuration": "Release"}
        self.assertEqual(json.loads(json.dumps(record))["schema_version"], 1)

    def test_successful_full_test_must_match_commit_and_configuration(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            build = root / "build"
            build.mkdir()
            record = {
                "mode": "full", "returncode": 0, "source_commit": "candidate-commit",
                "configuration": "Release", "command": ["ctest", "-C", "Release"],
            }
            (build / "gthost-validation-results.jsonl").write_text(json.dumps(record) + "\n", encoding="utf-8")
            with mock.patch.object(candidate, "git_output", return_value="candidate-commit"):
                self.assertEqual(
                    candidate.successful_full_test_record(root, "build", "Release")["mode"], "full")
                with self.assertRaisesRegex(candidate.ContractError, "no successful full Debug"):
                    candidate.successful_full_test_record(root, "build", "Debug")

    def test_sanitization_attestation_hash_binds_every_input(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "build").mkdir()
            artifact = root / "artifact.AppImage"
            manifest = root / "manifest.json"
            artifact.write_bytes(b"candidate")
            manifest.write_text("{}\n", encoding="utf-8")
            path = candidate.write_sanitization_attestation(root, "build", [artifact, manifest])
            record = json.loads(path.read_text(encoding="utf-8"))
            self.assertEqual({item["path"] for item in record["files"]}, {str(artifact), str(manifest)})
            self.assertTrue(all(item["sanitized"] is True for item in record["files"]))
            self.assertTrue(all(item["contains_secrets"] is False for item in record["files"]))
            self.assertEqual(record["files"][0]["sha256"], hashlib.sha256(b"candidate").hexdigest())

    def test_archive_delegates_to_machine_lifecycle_and_verifies_status(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self.make_build(root)
            build = root / "build"
            artifact = build / "package" / "bin" / "nozzle-it-all-desktop"
            artifact.parent.mkdir(parents=True)
            artifact.write_bytes(b"candidate")
            artifact_sha = hashlib.sha256(b"candidate").hexdigest()
            commit = "2acb5ac16c4ed2c70aa173d0e086e0404dcdd1ca"
            package_record = {
                "schema_version": 1,
                "artifact_kind": "appdir-executable",
                "artifact_path": str(candidate.CONTAINER_SOURCE / "build/package/bin/nozzle-it-all-desktop"),
                "artifact_sha256": artifact_sha,
                "configuration": "Release",
            }
            (build / "package-output.json").write_text(
                json.dumps(package_record) + "\n", encoding="utf-8")
            manifest = {
                "source": {"commit": commit},
                "artifact": {"host_path": str(artifact), "sha256": artifact_sha},
            }
            (build / "candidate-manifest.json").write_text(json.dumps(manifest) + "\n", encoding="utf-8")
            full_record = {
                "mode": "full", "returncode": 0, "source_commit": commit,
                "configuration": "Release", "command": ["ctest", "-C", "Release"],
            }
            (build / "gthost-validation-results.jsonl").write_text(
                json.dumps(full_record) + "\n", encoding="utf-8")
            destination = Path("/mnt/storage-node/cold/gthost-build01/orcaslicer-candidates/artifacts/test")
            configuration = "Release-builder-26c4788d9bca"

            def run_archive(command: list[str], **kwargs: object) -> mock.Mock:
                self.assertEqual(command[0], str(candidate.ARCHIVE_TOOL))
                self.assertIn("--sanitization-attestation", command)
                self.assertEqual(command[command.index("--source-identity") + 1], commit)
                status = {
                    "project": candidate.ARCHIVE_PROJECT,
                    "source_identity": commit,
                    "configuration": configuration,
                    "artifact_sha256": artifact_sha,
                    "storage_path": str(destination),
                    "local_artifact_removed": False,
                    "local_artifact_removal_state": "retained",
                }
                (artifact.parent / f"{artifact.name}.storage-archive.json").write_text(
                    json.dumps(status) + "\n", encoding="utf-8")
                return mock.Mock(stdout=f"{destination}\n")

            with (mock.patch.object(candidate.os, "geteuid", return_value=0),
                  mock.patch.object(candidate, "git_output", return_value=commit),
                  mock.patch.object(candidate.subprocess, "run", side_effect=run_archive)):
                observed = candidate.archive_candidate(root, "build", "Release")
            self.assertEqual(observed, destination)
            wrapper_record = json.loads((build / "storage-archive-record.json").read_text(encoding="utf-8"))
            self.assertEqual(wrapper_record["archive_contract"], "gthost-archive-artifact-v3")
            self.assertFalse(wrapper_record["local_artifact_removed"])

    def test_local_removal_requires_retirement_record(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self.make_build(root)
            with (mock.patch.object(candidate.os, "geteuid", return_value=0),
                  mock.patch.object(candidate, "successful_full_test_record"),
                  mock.patch.object(candidate, "git_output", return_value="2acb5ac16c4ed2c70aa173d0e086e0404dcdd1ca")):
                with self.assertRaisesRegex(candidate.ContractError, "requires --retirement-record"):
                    candidate.archive_candidate(root, "build", "Release", remove_local_artifact=True)


if __name__ == "__main__":
    unittest.main()
