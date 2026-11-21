from __future__ import annotations

from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import mt5_remote_controller_preflight as preflight


ROOT = Path(__file__).resolve().parents[1]


class RemoteControllerPreflightTests(unittest.TestCase):
    def test_repository_source_contract_passes_without_live_accounts_or_credentials(self):
        checks = preflight.source_checks(ROOT)
        self.assertTrue(checks, "source checks must not be empty")
        self.assertEqual([], [item for item in checks if item["status"] != "PASS"])
        report = preflight.build_report(ROOT)
        self.assertEqual("SOURCE_READY_RUNTIME_NOT_CHECKED", report["overall_status"])
        self.assertTrue(any("No broker passwords" in item for item in report["non_actions"]))

    def test_service_origins_require_bare_https(self):
        self.assertEqual(
            "https://bridge.owner.ts.net",
            preflight.validate_origin(" https://bridge.owner.ts.net/ ", "BRIDGE"),
        )
        for value in (
            "http://bridge.owner.ts.net",
            "https://user:password@bridge.owner.ts.net",
            "https://bridge.owner.ts.net/health",
            "https://bridge.owner.ts.net?token=secret",
            "https://localhost",
            "https://bridge.example",
        ):
            with self.subTest(value=value):
                with self.assertRaises(ValueError):
                    preflight.validate_origin(value, "BRIDGE")

    def test_runtime_preflight_requires_both_service_origins(self):
        report = preflight.build_report(ROOT, management_url="https://management.owner.ts.net")
        self.assertEqual("BLOCKED", report["overall_status"])
        check = next(item for item in report["checks"] if item["name"] == "service_origins")
        self.assertEqual("BLOCKED", check["status"])

    def test_no_remote_probe_is_claimed_when_origins_are_not_supplied(self):
        report = preflight.build_report(ROOT)
        self.assertEqual("SOURCE_READY_RUNTIME_NOT_CHECKED", report["overall_status"])
        self.assertFalse(any(item["name"] == "management_backend" for item in report["checks"]))


if __name__ == "__main__":
    unittest.main()
