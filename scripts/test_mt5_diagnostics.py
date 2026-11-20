import importlib.util
import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from unittest.mock import patch

MODULE_PATH = Path(__file__).parent / "linux-host" / "mt5_diagnostics.py"
SPEC = importlib.util.spec_from_file_location("mt5_diagnostics", MODULE_PATH)
diagnostics = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(diagnostics)


class Mt5DiagnosticsTests(unittest.TestCase):
    def test_terminal_path_precedence_matches_codespaces_single_path_scheme(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            mt5 = home / ".mt5/drive_c/Program Files/MetaTrader 5/terminal64.exe"
            wine = home / ".wine/drive_c/Program Files/MetaTrader 5/terminal64.exe"
            mt5.parent.mkdir(parents=True)
            wine.parent.mkdir(parents=True)
            mt5.touch()
            wine.touch()
            self.assertEqual(diagnostics.resolve_terminal_path(home), mt5)
            self.assertEqual(diagnostics.resolve_terminal_path(home, str(wine)), wine)
            self.assertEqual(
                diagnostics._display_path(mt5, home),
                "~/.mt5/drive_c/Program Files/MetaTrader 5/terminal64.exe",
            )

    def test_prefix_architecture_and_configured_windows_version_are_reported(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            prefix = home / ".mt5"
            prefix.mkdir()
            (prefix / "system.reg").write_text("WINE REGISTRY Version 2\n#arch=win64\n")
            state = home / "state"
            state.mkdir()
            (state / "mt5-prefix.env").write_text(
                f"prefix={prefix}\nprefix_arch=win64\nwindows_version=win10\n"
            )
            self.assertEqual(
                diagnostics._prefix_info(prefix, state),
                {"architecture": "win64", "configured_windows_version": "win10"},
            )

    def test_provenance_rejects_non_hash_values_and_ignores_unknown_fields(self):
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory)
            (state / "mt5-installer-provenance.env").write_text(
                "wrapper_sha256=" + "a" * 64 + "\nsetup_sha256=not-a-hash\npassword=secret\n"
            )
            result = diagnostics._installer_info(state)
            self.assertEqual(result["wrapper_sha256"], "a" * 64)
            self.assertEqual(result["setup_sha256"], "invalid-or-unavailable")
            self.assertNotIn("password", json.dumps(result))
            self.assertNotIn("secret", json.dumps(result))

    def test_existing_install_marker_reports_expected_pins_not_unobserved_hashes(self):
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory)
            (state / "installer-verified").write_text(
                "verified_at=2026-11-10T00:00:00Z\nmethod=authenticode\nsigner=MetaQuotes Ltd\n"
            )
            result = diagnostics._installer_info(state)
            self.assertEqual(result["status"], "configured-pins-and-marker; downloaded artifact hashes unavailable")
            self.assertTrue(result["wrapper_expected_sha256"].startswith("f9418ed3"))
            self.assertEqual(result["authenticode_status"], "verified-marker-present")
            self.assertNotIn("wrapper_sha256", result)

    def test_wine_package_reporting_includes_version_and_repository_origin(self):
        def fake_run(argv, **_kwargs):
            if argv[0] == "dpkg-query":
                return "wine 9.0-package\nwine64 9.0-package"
            return (
                "Installed: 9.0-package\nCandidate: 10.0-package\nVersion table:\n"
                " *** 9.0-package 500\n  500 http://archive.ubuntu.com/ubuntu noble/universe amd64 Packages\n"
                "     100 /var/lib/dpkg/status\n 10.0-package 500\n"
                "  500 https://example.invalid/ubuntu noble/universe amd64 Packages"
            )

        result = diagnostics._package_info(fake_run)
        self.assertIn("wine 9.0-package", result["packages"])
        self.assertEqual(result["origin"], "archive.ubuntu.com noble/universe amd64 Packages")

    def test_windows_version_and_mt5_build_are_reported_when_observable(self):
        def fake_run(argv, **_kwargs):
            if argv[1:3] == ["reg", "query"]:
                return "ProductName    REG_SZ    Microsoft Windows 10"
            return ""

        self.assertEqual(
            diagnostics._windows_product_version("wine", Path("/prefix"), fake_run),
            "Microsoft Windows 10",
        )
        with tempfile.TemporaryDirectory() as directory:
            terminal = Path(directory) / "terminal64.exe"
            terminal.touch()
            logs = terminal.parent / "Logs"
            logs.mkdir()
            (logs / "latest.log").write_text("MetaTrader 5 x64 build 5430 started\n")
            self.assertEqual(diagnostics._mt5_build(Path(directory), terminal), "5430")

    def test_debugger_diagnostic_reports_process_names_without_arguments(self):
        result = diagnostics._debugger_process_names(lambda _argv, **_kwargs: "gdb\npython\nwinedbg\n")
        self.assertEqual(result, ["gdb", "winedbg"])

    def test_report_combines_observed_metadata_and_keeps_readiness_unverified(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            prefix = home / ".mt5"
            prefix.mkdir()
            (prefix / "system.reg").write_text("WINE REGISTRY Version 2\n#arch=win64\n")
            state = home / ".local/share/destinedfx-codespaces"
            state.mkdir(parents=True)
            (state / "mt5-prefix.env").write_text(f"prefix={prefix}\nwindows_version=win10\n")
            (state / "mt5-installer-provenance.env").write_text(
                "wrapper_host=download.terminal.free\nwrapper_sha256=" + "a" * 64 + "\n"
            )
            runtime = state / "runtime"
            runtime.mkdir()
            terminal = prefix / "drive_c/Program Files/MetaTrader 5/terminal64.exe"
            terminal.parent.mkdir(parents=True)
            terminal.touch()
            logs = terminal.parent / "Logs"
            logs.mkdir()
            (logs / "terminal.log").write_text("MetaTrader 5 x64 build 5430 started\n")

            def fake_run(argv, **_kwargs):
                if argv[-1:] == ["--version"]:
                    return "wine-9.0"
                if argv[1:3] == ["reg", "query"]:
                    return "ProductName    REG_SZ    Microsoft Windows 10"
                if argv[0] == "dpkg-query":
                    return "wine 9.0-package\nwine64 9.0-package"
                if argv[0] == "apt-cache":
                    return (
                        "Installed: 9.0-package\nCandidate: 10.0-package\nVersion table:\n"
                        " *** 9.0-package 500\n  500 http://archive.ubuntu.com/ubuntu noble/universe amd64 Packages\n"
                        " 10.0-package 500\n  500 https://example.invalid/ubuntu noble/universe amd64 Packages"
                    )
                if argv[0] == "ps":
                    return "python\nwinedbg\n"
                return ""

            with patch.object(diagnostics.shutil, "which", return_value="/usr/bin/wine"):
                report = diagnostics.collect_report(home, state, runtime, prefix, run_command=fake_run)
            self.assertEqual(report["wine_version"], "wine-9.0")
            self.assertEqual(report["wine_package"]["origin"], "archive.ubuntu.com noble/universe amd64 Packages")
            self.assertEqual(report["prefix"]["architecture"], "win64")
            self.assertEqual(report["prefix"]["configured_windows_version"], "Microsoft Windows 10")
            self.assertEqual(report["terminal"]["build"], "5430")
            self.assertEqual(report["terminal"]["ready"], "NOT_VERIFIED")
            self.assertEqual(report["debugger_process_names"], ["winedbg"])

    def test_log_redaction_hides_urls_secrets_and_hashes(self):
        line = "ERROR token=private https://example.test/path?secret=1 " + "b" * 64
        safe = diagnostics._redact_log_line(line, Path("/home/owner"))
        self.assertEqual(safe, "[sensitive startup log line omitted]")
        self.assertNotIn("private", safe)
        self.assertNotIn("secret=1", safe)
        self.assertEqual(
            diagnostics._redact_log_line("broker company Fixture Demo login 123456", Path("/home/owner")),
            "[sensitive startup log line omitted]",
        )
        safe_email = diagnostics._redact_log_line("wine: warning for owner@example.test", Path("/home/owner"))
        self.assertNotIn("owner@example.test", safe_email)
        self.assertIn("<redacted-email>", safe_email)

    def test_stale_wine_compatibility_pass_is_not_reported_as_startup_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            runtime = root / "runtime"
            runtime.mkdir()
            (runtime / "terminal.log").write_text(
                "PASS: MT5-compatible Wine detected: wine-9.0\n"
                "wine: terminal startup error\n"
            )
            output = diagnostics._startup_logs(root / ".mt5", root / "terminal64.exe", runtime, root)
            self.assertNotIn("PASS: MT5-compatible Wine detected", "\n".join(output))
            self.assertTrue(any("terminal startup error" in line for line in output))

    def test_process_and_exit_status_are_not_called_ready(self):
        with tempfile.TemporaryDirectory() as directory:
            runtime = Path(directory)
            (runtime / "terminal.pid").write_text("99999999\n")
            (runtime / "terminal.exit-code").write_text("23\n")
            with patch.object(diagnostics.os, "kill", side_effect=ProcessLookupError):
                state, exit_code = diagnostics._process_state(runtime, lambda _argv, **_kwargs: "")
            self.assertEqual((state, exit_code), ("EXITED", "23"))
            (runtime / "terminal.exit-code").unlink()
            with patch.object(diagnostics.os, "kill", side_effect=ProcessLookupError):
                state, exit_code = diagnostics._process_state(runtime, lambda _argv, **_kwargs: "")
            self.assertEqual((state, exit_code), ("NOT_RUNNING", "unavailable"))
            with patch.object(diagnostics.os, "kill", side_effect=ProcessLookupError):
                state, exit_code = diagnostics._process_state(runtime, lambda _argv, **_kwargs: "terminal64.exe\n")
            self.assertEqual((state, exit_code), ("RUNNING_UNTRACKED_NOT_VERIFIED", "pending"))

    def test_launcher_reports_start_failure_without_touching_other_processes(self):
        launcher = MODULE_PATH.with_name("launch-mt5.sh")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            result = subprocess.run(
                ["bash", str(launcher)],
                env={
                    **__import__("os").environ,
                    "WINE_BIN": str(root / "missing-wine"),
                    "MT5_TERMINAL_EXE": str(root / "missing-terminal64.exe"),
                },
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(result.returncode, 2)
            self.assertIn("BLOCKED: Wine or the configured MT5 terminal executable is missing.", result.stderr)

    def test_launcher_sanitizes_wine_environment_and_records_exit_code(self):
        launcher = MODULE_PATH.with_name("launch-mt5.sh")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fake_wine = root / "wine"
            capture = root / "wine-env.json"
            terminal = root / "terminal64.exe"
            terminal.touch()
            fake_wine.write_text(
                "#!/usr/bin/env python3\n"
                "import json, os, sys\n"
                "if sys.argv[1:] == ['--version']:\n"
                " print('wine-9.0')\n"
                " sys.exit(0)\n"
                "with open(os.environ['CAPTURE'], 'w') as stream:\n"
                " json.dump({key: os.environ.get(key) for key in ('WINEDEBUG', 'WINEDLLOVERRIDES', 'WINEPREFIX', 'PROTON_DEBUG')}, stream)\n"
                "sys.exit(37)\n",
                encoding="utf-8",
            )
            fake_wine.chmod(0o700)
            exit_file = root / "runtime/terminal.exit-code"
            result = subprocess.run(
                ["bash", str(launcher)],
                env={
                    **__import__("os").environ,
                    "WINE_BIN": str(fake_wine),
                    "WINEPREFIX": str(root / "private-prefix"),
                    "WINEDEBUG": "warn+all",
                    "WINEDLLOVERRIDES": "debugger=n",
                    "PROTON_DEBUG": "1",
                    "CAPTURE": str(capture),
                    "MT5_TERMINAL_EXE": str(terminal),
                    "DESTINEDFX_TERMINAL_EXIT_CODE_FILE": str(exit_file),
                },
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(result.returncode, 37)
            self.assertIn("MT5_TERMINAL_EXIT_CODE=37", result.stdout)
            self.assertEqual(exit_file.read_text().strip(), "37")
            launched_env = json.loads(capture.read_text())
            self.assertEqual(launched_env["WINEDEBUG"], "-all")
            self.assertEqual(launched_env["WINEDLLOVERRIDES"], "")
            self.assertEqual(launched_env["WINEPREFIX"], str(root / "private-prefix"))
            self.assertIsNone(launched_env["PROTON_DEBUG"])


if __name__ == "__main__":
    unittest.main()