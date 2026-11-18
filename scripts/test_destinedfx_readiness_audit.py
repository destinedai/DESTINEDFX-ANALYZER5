#!/usr/bin/env python3
from __future__ import annotations

from io import BytesIO
import json
from pathlib import Path
import sys
import unittest
from urllib.error import HTTPError
from urllib.parse import urlsplit

sys.path.insert(0, str(Path(__file__).resolve().parent))
import destinedfx_readiness_audit as audit


class FakeResponse:
    def __init__(self, status: int, value: dict):
        self.status = status
        self._body = json.dumps(value).encode("utf-8")

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False

    def read(self, _limit: int = -1) -> bytes:
        return self._body

    def getcode(self) -> int:
        return self.status


class ReadinessAuditTests(unittest.TestCase):
    def test_validates_only_bare_https_origins(self):
        self.assertEqual(
            "https://bridge.example.org",
            audit.validate_bridge_origin(" https://bridge.example.org/ "),
        )
        for invalid in (
            "http://bridge.example.org",
            "https://user:password@bridge.example.org",
            "https://bridge.example.org/health",
            "https://bridge.example.org?token=secret",
            "https://localhost",
        ):
            with self.subTest(value=invalid):
                with self.assertRaises(ValueError):
                    audit.validate_bridge_origin(invalid)

    def test_static_source_audit_checks_real_repository_contracts(self):
        report = audit.audit_repository(audit.ROOT)
        self.assertEqual("PASS", report["status"], report["failed_checks"])
        names = {item["name"] for item in report["checks"]}
        self.assertIn("MT5 EA order and management path", names)
        self.assertIn("fail-closed persistent-host Live policy", names)
        self.assertIn("Analyzer provider diagnostics", names)
        self.assertIn("automatic Codespaces Bridge discovery", names)
        self.assertIn("Linux Bridge configuration reflects root slot attestation", names)
        self.assertIn("four broker-specific MT5 DEMO paths", names)
        self.assertIn("isolated MT5 host memory floor", names)
        self.assertIn("persistent Azure host is not falsely marked ready", names)

    def test_bridge_probe_distinguishes_ephemeral_liveness_from_readiness(self):
        requests = []

        def fake_open(request, timeout):
            path = urlsplit(request.full_url).path
            requests.append((path, request.get_header("Authorization")))
            if path == "/health":
                return FakeResponse(200, {
                    "status": "ok", "host_profile": "EPHEMERAL",
                    "public_exposure": True,
                })
            if path == "/health/config":
                return FakeResponse(200, {
                    "status": "configured", "database": "ok",
                    "managed_execution": "configured",
                    "per_user_terminal_isolation": "ready",
                })
            if path == "/health/ready":
                payload = json.dumps({
                    "status": "not_ready",
                    "detail": {"code": "EPHEMERAL_HOST"},
                }).encode("utf-8")
                raise HTTPError(
                    request.full_url, 503, "not ready", {}, BytesIO(payload)
                )
            raise AssertionError("Unexpected route: " + path)

        result = audit.probe_bridge(
            "https://codespace-8000.app.github.dev",
            opener=fake_open,
        )
        self.assertFalse(result["ready"])
        self.assertEqual("EPHEMERAL", result["health"]["host_profile"])
        self.assertEqual(503, result["readiness"]["http_status"])
        self.assertEqual("EPHEMERAL_HOST", result["readiness"]["error_code"])
        self.assertTrue(all(auth is None for _, auth in requests))

    def test_app_token_is_sent_only_to_authenticated_state_route(self):
        requests = []

        def fake_open(request, timeout):
            path = urlsplit(request.full_url).path
            auth = request.get_header("Authorization")
            requests.append((path, auth))
            if path == "/health":
                return FakeResponse(200, {"status": "ok", "host_profile": "PERSISTENT"})
            if path == "/health/config":
                return FakeResponse(200, {"status": "configured", "database": "ok"})
            if path == "/health/ready":
                return FakeResponse(200, {"status": "ready"})
            if path == "/v1/app/state":
                return FakeResponse(200, {
                    "terminal_online": True,
                    "heartbeat_fresh": True,
                    "broker_identity_verified": True,
                    "managed_execution": {"state": "READY", "worker_status": "READY"},
                    "account": {"account_mode": "DEMO", "login": "12345678"},
                })
            raise AssertionError("Unexpected route: " + path)

        result = audit.probe_bridge(
            "https://bridge.example.org",
            app_token="never-print-this-token",
            opener=fake_open,
        )
        self.assertTrue(result["ready"])
        self.assertEqual("READY", result["authenticated_state"]["managed_state"])
        self.assertEqual("DEMO", result["authenticated_state"]["account_mode"])
        self.assertNotIn("12345678", json.dumps(result))
        for path, authorization in requests:
            if path == "/v1/app/state":
                self.assertEqual("Bearer never-print-this-token", authorization)
            else:
                self.assertIsNone(authorization)


if __name__ == "__main__":
    unittest.main(verbosity=2)
