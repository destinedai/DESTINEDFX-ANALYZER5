from __future__ import annotations

from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import mt5_remote_controller_preflight as preflight


ROOT = Path(__file__).resolve().parents[1]


class RemoteControllerPreflightTests(unittest.TestCase):
    def test_repository_source_contract_passes_without_live_accounts_or_credentials(self):
        checks = preflight.source_checks(ROOT)
        self.assertTrue(checks, "source checks must not be empty")
        self.assertEqual([], [item for item in checks if item["status"] != "PASS"])
        self.assertIn("phone_position_stop_tightening_path", {item["name"] for item in checks})
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


    def test_remote_preflight_requires_root_attested_persistent_linux_slots_and_real_pairing_evidence(self):
        management = "https://management.owner.ts.net"
        bridge = "https://bridge.owner.ts.net"
        payloads = {
            management + "/ready": (200, {"status": "ready"}),
            management + "/api/v1/auth/bridge-verification-key": (200, {
                "algorithm": "EdDSA", "public_key_pem": "test-public-key",
                "issuer": "destinedfx-management", "audience": "destinedfx-bridge",
            }),
            bridge + "/health/config": (200, {
                "status": "configured", "host_profile": "PERSISTENT", "linux_host_mode": True,
                "per_user_terminal_isolation": "VERIFIED", "multi_user_terminal_isolation": "VERIFIED",
                "isolated_slots": 2, "max_isolated_slots": 2, "news_provider_configured": True,
                "management_step_up_key_fingerprint_sha256": "fixture-fingerprint",
                "management_step_up_issuer": "destinedfx-management",
                "management_step_up_audience": "destinedfx-bridge",
            }),
            bridge + "/health/ready": (200, {
                "status": "ready", "per_user_terminal_isolation": "VERIFIED",
                "isolated_slots": 2, "max_isolated_slots": 2,
            }),
        }
        with patch.object(preflight, "get_json", side_effect=lambda url, timeout=8.0: payloads[url]), \
             patch.object(preflight, "public_key_fingerprint", return_value="fixture-fingerprint"):
            checks = preflight.remote_checks(management, bridge)
        by_name = {item["name"]: item for item in checks}
        self.assertEqual("PASS", by_name["persistent_linux_execution_host"]["status"])
        self.assertEqual("PASS", by_name["per_user_terminal_isolation"]["status"])
        self.assertEqual("PASS", by_name["live_terminal_slot_capacity"]["status"])
        self.assertEqual("PASS", by_name["bridge_host_runtime"]["status"])
        self.assertEqual("PENDING", by_name["verified_mt5_terminal"]["status"])
        self.assertIn("does not prove", by_name["bridge_host_runtime"]["detail"].lower())

    def test_remote_preflight_rejects_windows_identity_only_host_for_multi_user_live(self):
        management = "https://management.owner.ts.net"
        bridge = "https://bridge.owner.ts.net"
        payloads = {
            management + "/ready": (200, {"status": "ready"}),
            management + "/api/v1/auth/bridge-verification-key": (200, {
                "algorithm": "EdDSA", "public_key_pem": "test-public-key",
                "issuer": "destinedfx-management", "audience": "destinedfx-bridge",
            }),
            bridge + "/health/config": (200, {
                "status": "configured", "host_profile": "PERSISTENT", "linux_host_mode": False,
                "per_user_terminal_isolation": "WORKER_ATTESTATION_REQUIRED",
                "multi_user_terminal_isolation": "NOT_VERIFIED", "isolated_slots": 0,
                "max_isolated_slots": 0, "news_provider_configured": True,
                "management_step_up_key_fingerprint_sha256": "fixture-fingerprint",
                "management_step_up_issuer": "destinedfx-management",
                "management_step_up_audience": "destinedfx-bridge",
            }),
            bridge + "/health/ready": (503, {"detail": {"code": "NO_VERIFIED_TERMINAL_PAIRING"}}),
        }
        with patch.object(preflight, "get_json", side_effect=lambda url, timeout=8.0: payloads[url]), \
             patch.object(preflight, "public_key_fingerprint", return_value="fixture-fingerprint"):
            checks = preflight.remote_checks(management, bridge)
        by_name = {item["name"]: item for item in checks}
        self.assertEqual("BLOCKED", by_name["persistent_linux_execution_host"]["status"])
        self.assertEqual("BLOCKED", by_name["per_user_terminal_isolation"]["status"])
        self.assertEqual("BLOCKED", by_name["live_terminal_slot_capacity"]["status"])
        self.assertEqual("PASS", by_name["bridge_host_runtime"]["status"])
        self.assertEqual("PENDING", by_name["verified_mt5_terminal"]["status"])

if __name__ == "__main__":
    unittest.main()
