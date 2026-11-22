import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).with_name("public-endpoints.py")
SPEC = importlib.util.spec_from_file_location("public_endpoints", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(MODULE)


class PublicEndpointsTests(unittest.TestCase):
    def setUp(self):
        self.env = patch.dict(
            os.environ,
            {
                "CODESPACES": "true",
                "CODESPACE_NAME": "steady-lizard-abc123",
                "GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN": "preview.app.github.dev",
            },
            clear=True,
        )
        self.env.start()
        self.addCleanup(self.env.stop)

    def rows(self, visibility="public", bridge=None, management=None):
        return [
            {
                "sourcePort": 8000,
                "browseUrl": bridge or "https://steady-lizard-abc123-8000.preview.app.github.dev",
                "visibility": visibility,
            },
            {
                "sourcePort": 8010,
                "browseUrl": management or "https://steady-lizard-abc123-8010.preview.app.github.dev",
                "visibility": visibility,
            },
        ]

    def mock_gh(self, rows, returncode=0, stdout=None):
        return patch.object(
            MODULE.subprocess,
            "run",
            return_value=subprocess.CompletedProcess(
                ["gh", "codespace", "ports"], returncode,
                json.dumps(rows) if stdout is None else stdout, ""
            ),
        )

    def test_uses_environment_preview_domain_even_when_gh_reports_standard_alias(self):
        rows = [
            {"sourcePort": 8000, "browseUrl": "https://steady-lizard-abc123-8000.app.github.dev", "visibility": "public"},
            {"sourcePort": 8010, "browseUrl": "https://steady-lizard-abc123-8010.app.github.dev", "visibility": "public"},
        ]
        with self.mock_gh(rows) as run:
            manifest = MODULE.make_codespaces_manifest()
        self.assertEqual(
            manifest["bridge_url"],
            "https://steady-lizard-abc123-8000.preview.app.github.dev",
        )
        self.assertEqual(
            manifest["management_api_origin"],
            "https://steady-lizard-abc123-8010.preview.app.github.dev",
        )
        self.assertEqual(run.call_args.args[0], [
            "gh", "codespace", "ports", "--json",
            "browseUrl,sourcePort,visibility", "-c", "steady-lizard-abc123",
        ])

    def test_accepts_current_app_github_dev_forwarding_domain(self):
        os.environ["GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN"] = "app.github.dev"
        rows = [
            {"sourcePort": 8000, "browseUrl": "https://steady-lizard-abc123-8000.app.github.dev", "visibility": "public"},
            {"sourcePort": 8010, "browseUrl": "https://steady-lizard-abc123-8010.app.github.dev", "visibility": "public"},
        ]
        with self.mock_gh(rows):
            manifest = MODULE.make_codespaces_manifest()
        self.assertTrue(manifest["bridge_url"].endswith(".app.github.dev"))

    def test_rejects_private_or_missing_ports(self):
        with self.mock_gh(self.rows(visibility="private")):
            with self.assertRaisesRegex(ValueError, "not public"):
                MODULE.make_codespaces_manifest()
        with self.mock_gh(self.rows()[:1]):
            with self.assertRaisesRegex(ValueError, "missing required"):
                MODULE.make_codespaces_manifest()

    def test_rejects_wrong_codespace_or_unapproved_domain(self):
        with self.mock_gh(self.rows(bridge="https://other-space-8000.preview.app.github.dev")):
            with self.assertRaisesRegex(ValueError, "does not match"):
                MODULE.make_codespaces_manifest()
        os.environ["GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN"] = "attacker.example.org"
        with self.mock_gh(self.rows()):
            with self.assertRaisesRegex(ValueError, "unsupported"):
                MODULE.make_codespaces_manifest()

    def test_rejects_example_org_placeholder_even_if_it_is_https(self):
        with self.assertRaisesRegex(ValueError, "placeholder or reserved"):
            MODULE.public_origin("https://api.example.org", "endpoint")

    def test_rejects_cli_failure_and_invalid_json(self):
        with self.mock_gh([], returncode=1):
            with self.assertRaisesRegex(ValueError, "gh codespace ports failed"):
                MODULE.make_codespaces_manifest()
        with self.mock_gh([], stdout="not-json"):
            with self.assertRaisesRegex(ValueError, "invalid JSON"):
                MODULE.make_codespaces_manifest()

    def test_static_manifest_accepts_real_domain_origins(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "bridge.env"
            source.write_text("\n".join([
                "BRIDGE_BASE_URL=https://bridge.destinedfx.app",
                "BRIDGE_HOST=bridge.destinedfx.app",
                "EXPECTED_FORWARDED_HOST=bridge.destinedfx.app",
                "APP_DOMAIN=api.destinedfx.app",
            ]) + "\n", encoding="utf-8")
            manifest = MODULE.make_manifest(source)
        self.assertEqual(manifest["bridge_url"], "https://bridge.destinedfx.app")
        self.assertEqual(manifest["management_api_origin"], "https://api.destinedfx.app")


if __name__ == "__main__":
    unittest.main()
