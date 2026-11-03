#!/usr/bin/env python3

import copy
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("intake-check.py")
SPEC = importlib.util.spec_from_file_location("intake_check", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
intake_check = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(intake_check)

REGISTRY_PATH = intake_check.DEFAULT_REGISTRY
FIXTURE_DIR = Path(__file__).with_name("fixtures") / "intake"
GENERATED_AT = "2026-11-03T20:00:00+00:00"


class IntakeCheckTests(unittest.TestCase):
    def setUp(self) -> None:
        self.registry = intake_check.load_registry(REGISTRY_PATH)

    def report(self, capability: str | None = None) -> dict:
        return intake_check.build_report(
            REGISTRY_PATH,
            self.registry,
            "offline",
            FIXTURE_DIR,
            capability,
            1,
            GENERATED_AT,
        )

    def test_default_selects_every_core_and_summary_source_only(self) -> None:
        report = self.report()
        self.assertEqual(
            report["selected_source_ids"],
            [
                "orca-slicer",
                "snapmaker-orca",
                "prusa-slicer",
                "bambu-studio",
                "elegoo-slicer",
                "creality-print",
            ],
        )
        self.assertNotIn("super-slicer", report["selected_source_ids"])
        self.assertNotIn("cura-engine", report["selected_source_ids"])

    def test_named_capability_adds_only_matching_targeted_source(self) -> None:
        report = self.report("support-generation")
        self.assertEqual(report["selected_source_ids"][-1], "cura-engine")
        self.assertNotIn("super-slicer", report["selected_source_ids"])

    def test_unknown_capability_fails_closed(self) -> None:
        with self.assertRaisesRegex(intake_check.IntakeError, "unknown capability"):
            self.report("not-registered")

    def test_offline_report_does_not_advance_assessment_or_change_source(self) -> None:
        registry_hash = intake_check.sha256_path(REGISTRY_PATH)
        report = self.report()
        self.assertFalse(report["last_assessed_advanced"])
        self.assertTrue(report["product_source_unchanged"])
        self.assertRegex(report["product_state_before"]["head_tree"], r"^[0-9a-f]{40}$")
        self.assertRegex(report["product_state_before"]["tracked_index_sha256"], r"^[0-9a-f]{64}$")
        self.assertRegex(report["product_state_before"]["tracked_state_sha256"], r"^[0-9a-f]{64}$")
        self.assertEqual(intake_check.sha256_path(REGISTRY_PATH), registry_hash)
        self.assertTrue(all(source["needs_assessment"] for source in report["sources"]))

    def test_fixture_ref_mismatch_is_rejected(self) -> None:
        source = copy.deepcopy(self.registry["sources"][0])
        source["tracked_ref"] = "refs/heads/not-main"
        with self.assertRaisesRegex(intake_check.IntakeError, "fixture ref mismatch"):
            intake_check.resolve_offline(source, FIXTURE_DIR)

    def test_last_assessed_requires_a_durable_record(self) -> None:
        registry = copy.deepcopy(self.registry)
        registry["sources"][0]["last_assessed_commit"] = "a" * 40
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "sources.json"
            path.write_text(json.dumps(registry), encoding="utf-8")
            with self.assertRaisesRegex(intake_check.IntakeError, "assessment_record"):
                intake_check.load_registry(path)

    def test_current_fetched_requires_a_durable_report(self) -> None:
        registry = copy.deepcopy(self.registry)
        registry["sources"][0].pop("current_fetched_report")
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "sources.json"
            path.write_text(json.dumps(registry), encoding="utf-8")
            with self.assertRaisesRegex(intake_check.IntakeError, "current_fetched_report"):
                intake_check.load_registry(path)

    def test_report_writer_refuses_to_overwrite(self) -> None:
        report = self.report()
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "report.json"
            intake_check.write_new_report(path, report)
            self.assertEqual(json.loads(path.read_text(encoding="utf-8")), report)
            with self.assertRaisesRegex(intake_check.IntakeError, "refusing to overwrite"):
                intake_check.write_new_report(path, report)


if __name__ == "__main__":
    unittest.main()
