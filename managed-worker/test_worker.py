import os
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import worker


class WorkerTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.install = self.root / "install"
        (self.install / "MQL5").mkdir(parents=True)
        standard_include = self.install / "MQL5" / "Include" / "Trade" / "Trade.mqh"
        standard_include.parent.mkdir(parents=True)
        standard_include.write_text("// standard library fixture\n")
        self.term = self.install / "terminal64.exe"
        self.term.write_text("#!/usr/bin/env python3\nimport pathlib,sys,time\n"
                             "args=sys.argv[1:]\npathlib.Path(" + repr(str(self.root / "terminal-args.txt") ) + ").write_text('\\n'.join(args))\n"
                             "time.sleep(0.25)\n"
                             "cfg=next((a[len('/config:'):] for a in args if a.startswith('/config:')), None)\n"
                             "if cfg: pathlib.Path(" + repr(str(self.root / "terminal-ini-read.txt") ) + ").write_text(pathlib.Path(cfg).read_text())\n")
        self.term.chmod(0o755)
        self.source = self.root / "DestinedFXTraderEA.mq5"
        self.source.write_text("// fake source\n")
        self.meta = self.install / "metaeditor64.exe"
        self.meta.write_text("#!/usr/bin/env python3\nimport pathlib,sys\nargs=sys.argv[1:]\n"
                             "p=pathlib.Path(args[0].split(':',1)[1])\n"
                             "expected=f'/include:{p.parent.parent}'\n"
                             "if expected not in args: sys.exit(9)\n"
                             "p.with_suffix('.ex5').write_bytes(b'fake compiled')\n")
        self.meta.chmod(0o755)
        self.job = {"job_id": "job_123", "pairing_id": "pair_456", "pairing_code": "abcdefgh1234",
                    "bridge_url": "https://bridge.destinedfx.internal", "account": {"login": "123456", "server": "Demo-Server",
                    "password": "secret-not-logged", "access_type": "INVESTOR", "account_mode": "DEMO", "broker_brand": "FBS"}}
        self.settings = {"data_root": str(self.root / "accounts"), "mt5_install": str(self.install),
                         "terminal_exe": str(self.term), "metaeditor_exe": str(self.meta), "ea_source": str(self.source),
                         "single_broker_brand": "FBS", "max_terminals": 1}

    def tearDown(self):
        self.tmp.cleanup()

    def test_request_json_adds_optional_cloudflare_headers_without_logging_them(self):
        import io
        import logging
        import contextlib
        captured = {}
        logs = io.StringIO()
        handler = logging.StreamHandler(logs)
        worker.LOG.addHandler(handler)
        try:
            def open_request(req, timeout):
                captured.update(req.header_items())
                return io.BytesIO(b"{}")
            with patch.dict(os.environ, {"CF_ACCESS_CLIENT_ID": "worker-id-secret-test", "CF_ACCESS_CLIENT_SECRET": "worker-secret-test"}), \
                 patch.object(worker.urllib.request, "urlopen", side_effect=open_request):
                self.assertEqual(worker.request_json("https://bridge.invalid/health/ready", "worker-token", "GET"), {})
        finally:
            worker.LOG.removeHandler(handler)
        headers = {key.lower(): value for key, value in captured.items()}
        self.assertEqual(headers["cf-access-client-id"], "worker-id-secret-test")
        self.assertEqual(headers["cf-access-client-secret"], "worker-secret-test")
        self.assertNotIn("worker-secret-test", logs.getvalue())

    def test_request_json_omits_partial_cloudflare_credentials(self):
        import io
        captured = {}
        def open_request(req, timeout):
            captured.update(req.header_items())
            return io.BytesIO(b"{}")
        with patch.dict(os.environ, {"CF_ACCESS_CLIENT_ID": "only-id", "CF_ACCESS_CLIENT_SECRET": ""}), \
             patch.object(worker.urllib.request, "urlopen", side_effect=open_request):
            worker.request_json("https://bridge.invalid/health/ready", "worker-token", "GET")
        self.assertFalse(any(key.lower().startswith("cf-access-client-") for key, _ in captured.items()))

    def test_supported_broker_brands_are_normalized_without_legacy_broker_property(self):
        for brand in ("FBS", "EXNESS", "HFM", "DERIV"):
            for mode in ("DEMO", "LIVE"):
                account = {k: v for k, v in self.job["account"].items() if k != "broker"}
                job = {**self.job, "account": {**account, "broker_brand": brand.lower(), "account_mode": mode}}
                self.assertEqual(worker.validate_job(job)["account"]["broker_brand"], brand)
        for brand in ("UNKNOWN", "OTHER", None, "FBS/EXNESS"):
            job = {**self.job, "account": {**self.job["account"], "broker_brand": brand}}
            with self.assertRaises(worker.ProvisionError):
                worker.validate_job(job)
        for legacy in ("EXNESS", "UNKNOWN", None):
            job = {**self.job, "account": {**self.job["account"], "broker": legacy}}
            with self.assertRaises(worker.ProvisionError) as error:
                worker.validate_job(job)
            self.assertEqual(error.exception.code, "CONFLICTING_BROKER_IDENTITY")
        matching_alias = {**self.job, "account": {**self.job["account"], "broker": " fbs "}}
        self.assertEqual(worker.validate_job(matching_alias)["account"]["broker_brand"], "FBS")

    def test_malformed_account_and_missing_required_claim_fields_fail_closed(self):
        for job in ({**self.job, "account": None}, {**self.job, "account": []},
                    {key: value for key, value in self.job.items() if key != "job_id"}):
            with self.assertRaises(worker.ProvisionError):
                worker.validate_job(job)
        for field in ("login", "server", "password", "access_type", "account_mode", "broker_brand"):
            account = {**self.job["account"]}
            account.pop(field)
            with self.assertRaises(worker.ProvisionError, msg=f"missing account.{field}"):
                worker.validate_job({**self.job, "account": account})

    def test_broker_specific_terminal_mapping_is_explicit(self):
        mapped = {
            **self.settings,
            "broker_terminals": {
                "FBS": {"mt5_install": "C:/FBS", "terminal_exe": "C:/FBS/terminal64.exe", "metaeditor_exe": "C:/FBS/metaeditor64.exe"},
                "EXNESS": {"mt5_install": "C:/Exness", "terminal_exe": "C:/Exness/terminal64.exe", "metaeditor_exe": "C:/Exness/metaeditor64.exe"},
            },
        }
        self.assertEqual(worker.settings_for_broker(mapped, "EXNESS")["mt5_install"], "C:/Exness")
        with self.assertRaises(worker.ProvisionError):
            worker.settings_for_broker(mapped, "HFM")

    def test_mapping_only_configuration_loads_all_four_brokers_without_global_terminal(self):
        config = self.root / "mapping-only-worker.toml"
        lines = [
            'control_plane_url = "https://bridge.realcop.com"',
            f'data_root = "{self.root / "accounts"}"',
            f'ea_source = "{self.source}"',
            'max_terminals = 4',
            '[broker_terminals.FBS]',
            f'mt5_install = "{self.install}"',
            f'terminal_exe = "{self.term}"',
            f'metaeditor_exe = "{self.meta}"',
            '[broker_terminals.EXNESS]',
            f'mt5_install = "{self.install}"',
            f'terminal_exe = "{self.term}"',
            f'metaeditor_exe = "{self.meta}"',
            '[broker_terminals.HFM]',
            f'mt5_install = "{self.install}"',
            f'terminal_exe = "{self.term}"',
            f'metaeditor_exe = "{self.meta}"',
            '[broker_terminals.DERIV]',
            f'mt5_install = "{self.install}"',
            f'terminal_exe = "{self.term}"',
            f'metaeditor_exe = "{self.meta}"',
        ]
        config.write_text("\n".join(lines) + "\n", encoding="utf-8")
        loaded = worker.load_settings(config)
        self.assertNotIn("terminal_exe", loaded)
        self.assertEqual(set(loaded["broker_terminals"]), {"FBS", "EXNESS", "HFM", "DERIV"})
        for brand in worker.SUPPORTED_BROKER_BRANDS:
            self.assertEqual(worker.settings_for_broker(loaded, brand)["selected_broker_brand"], brand)

    def test_mapping_only_provisions_two_brokers_and_supervises_recorded_executables(self):
        import shutil
        installs = {}
        for brand in ("FBS", "EXNESS"):
            install = self.root / brand.lower()
            shutil.copytree(self.install, install)
            installs[brand] = install
        compiled = self.root / "trusted.ex5"
        compiled.write_bytes(b"trusted compiled EA")
        mapped = {
            "data_root": str(self.root / "accounts"), "ea_source": str(self.source),
            "max_terminals": 4, "broker_terminals": {
                brand: {"mt5_install": str(path), "terminal_exe": str(path / "terminal64.exe"),
                        "metaeditor_exe": str(path / "metaeditor64.exe"), "compiled_ea": str(compiled)}
                for brand, path in installs.items()
            },
        }
        jobs = []
        for brand, job_id in (("FBS", "fbs_job"), ("EXNESS", "exness_job")):
            jobs.append({**self.job, "job_id": job_id, "pairing_id": f"{brand.lower()}_pair",
                         "account": {**self.job["account"], "broker_brand": brand}})
        processes = [type("Process", (), {"pid": 1001})(), type("Process", (), {"pid": 1002})()]
        with patch.object(worker.subprocess, "Popen", side_effect=processes):
            results = [worker.provision(job, mapped) for job in jobs]
        executable_paths = []
        for result in results:
            marker = result / "terminal" / ".destinedfx-worker.json"
            record = worker.json.loads(marker.read_text())
            executable = Path(record["executable"])
            executable_paths.append(executable)
            self.assertTrue(executable.is_absolute())
            self.assertEqual(executable.parent, marker.parent.resolve())
        self.assertNotEqual(executable_paths[0], executable_paths[1])
        with patch.object(worker, "process_is_owned", return_value=worker.ProcessState.OWNED_RUNNING):
            outcome = worker.supervise_terminals(mapped)
        self.assertEqual({item["job_id"] for item in outcome}, {"fbs_job", "exness_job"})
        self.assertTrue(all(item["status"] == "RUNNING" for item in outcome))

    def test_active_terminal_capacity_counts_inconclusive_processes(self):
        marker = Path(self.settings["data_root"]) / "job_123" / "terminal" / ".destinedfx-worker.json"
        marker.parent.mkdir(parents=True)
        marker.write_text('{"job_id":"job_123","pairing_id":"pair_456","pid":44,"executable":"terminal.exe","status":"RUNNING"}', encoding="utf-8")
        with patch.object(worker, "process_is_owned", return_value=worker.ProcessState.UNKNOWN):
            self.assertEqual(worker.active_terminal_count(self.settings), 1)

    def test_load_settings_requires_brand_binding_and_bounded_capacity(self):
        config = self.root / "worker.toml"
        lines = [
            'control_plane_url = "https://bridge.realcop.com"',
            f'data_root = "{self.root / "accounts"}"',
            f'mt5_install = "{self.install}"',
            f'terminal_exe = "{self.term}"',
            f'metaeditor_exe = "{self.meta}"',
            f'ea_source = "{self.source}"',
            'single_broker_brand = "fbs"',
            'max_terminals = 1',
        ]
        config.write_text("\n".join(lines) + "\n", encoding="utf-8")
        loaded = worker.load_settings(config)
        self.assertEqual(loaded["single_broker_brand"], "FBS")
        config.write_text(
            config.read_text(encoding="utf-8").replace(
                'single_broker_brand = "fbs"', 'single_broker_brand = "UNKNOWN"'
            ),
            encoding="utf-8"
        )
        with self.assertRaises(worker.ProvisionError):
            worker.load_settings(config)

    def test_investor_ini_disables_expert_trading(self):
        text = worker.ini_text(self.job, "INVESTOR", "EURUSD")
        self.assertIn("AllowLiveTrading=0", text)
        self.assertIn("Login=123456", text)
        self.assertIn("Expert=DestinedFXTraderEA", text)
        self.assertIn("ExpertParameters=DestinedFXTraderEA.set", text)

    def test_set_contains_pairing_and_bridge(self):
        text = worker.make_set_file("https://bridge.destinedfx.internal", "abcdefgh1234", self.job["account"])
        self.assertIn("InpBridgeBaseURL=https://bridge.destinedfx.internal", text)
        self.assertIn("InpPairingCode=abcdefgh1234", text)
        self.assertIn("InpExpectedLogin=123456", text)
        self.assertIn("InpExpectedServer=Demo-Server", text)
        self.assertIn("InpExpectedAccountMode=DEMO", text)

    def test_configured_cost_model_requires_positive_value_and_evidence(self):
        account = {**self.job["account"], "cost_model": "CONFIGURED_ROUND_TURN",
                   "commission_per_lot": 7.5, "cost_evidence": "broker tariff 2026-10"}
        text = worker.make_set_file("https://bridge.destinedfx.internal", "abcdefgh1234", account)
        self.assertIn("InpCostModel=CONFIGURED_ROUND_TURN", text)
        self.assertIn("InpCommissionPerLot=7.50000000", text)
        with self.assertRaises(worker.ProvisionError) as error:
            worker.make_set_file("https://bridge.destinedfx.internal", "abcdefgh1234",
                                 {**account, "commission_per_lot": 0})
        self.assertEqual(error.exception.code, "INVALID_CONFIGURED_COST_EVIDENCE")

    def test_zero_commission_spread_only_requires_reference_and_writes_zero(self):
        account = {**self.job["account"], "cost_model": "ZERO_COMMISSION_SPREAD_ONLY",
                   "commission_per_lot": 0.0, "cost_evidence": "Official broker FX zero-commission schedule"}
        text = worker.make_set_file("https://bridge.destinedfx.internal", "abcdefgh1234", account)
        self.assertIn("InpCostModel=ZERO_COMMISSION_SPREAD_ONLY", text)
        self.assertIn("InpCommissionPerLot=0.00000000", text)
        with self.assertRaises(worker.ProvisionError) as error:
            worker.make_set_file("https://bridge.destinedfx.internal", "abcdefgh1234",
                                 {**account, "cost_evidence": "bad\nreference"})
        self.assertEqual(error.exception.code, "INVALID_COST_EVIDENCE")

    def test_job_requires_explicit_valid_account_mode(self):
        missing = {**self.job, "account": {k: v for k, v in self.job["account"].items() if k != "account_mode"}}
        with self.assertRaises(worker.ProvisionError):
            worker.validate_job(missing)
        unknown = {**self.job, "account": {**self.job["account"], "account_mode": "UNKNOWN"}}
        with self.assertRaises(worker.ProvisionError) as e:
            worker.validate_job(unknown)
        self.assertEqual(e.exception.code, "INVALID_ACCOUNT_MODE")

    def test_invalid_path_and_bridge_rejected(self):
        bad = dict(self.job, job_id="../escape")
        with self.assertRaises(worker.ProvisionError):
            worker.validate_job(bad)
        bad = dict(self.job, bridge_url="http://insecure.example")
        with self.assertRaises(worker.ProvisionError):
            worker.validate_job(bad)
        bad = dict(self.job, bridge_url="https://your-bridge.example")
        with self.assertRaises(worker.ProvisionError):
            worker.validate_job(bad)
        for origin in (
            "https://bridge.destinedfx.internal:0",
            "https://bridge.destinedfx.internal:99999",
            "https://10.0.0.4",
            "https://100.64.0.1",
            "https://192.0.2.1",
            "https://198.18.0.1",
            "https://203.0.113.1",
            "https://[::1]",
            "https://[::ffff:10.0.0.1]",
            "https://[fe80::1%25eth0]",
            "https://[2001:db8::1]",
            "https://user:pass@bridge.destinedfx.internal",
            "https://bridge.destinedfx.internal/path",
            "https://bridge.destinedfx.internal/?x=1",
        ):
            self.assertIsNone(worker.validate_origin(origin), origin)

    def test_valid_origin_is_normalized(self):
        self.assertEqual(worker.validate_origin(" HTTPS://Bridge.Realcorp.com:443/ "), "https://bridge.realcorp.com:443")

    def test_launch_preserves_ini_until_trusted_ready_ack(self):
        processes = []
        real_popen = subprocess.Popen
        def tracked_popen(*args, **kwargs):
            proc = real_popen(*args, **kwargs)
            processes.append(proc)
            return proc
        with patch.object(worker.subprocess, "Popen", side_effect=tracked_popen):
            result = worker.provision(self.job, self.settings)
        self.assertTrue(processes)
        args_file = self.root / "terminal-args.txt"
        for _ in range(100):
            if args_file.exists():
                break
            import time
            time.sleep(0.01)
        self.assertTrue(args_file.exists())
        self.assertIn("/portable", args_file.read_text())
        startup_inis = list(result.glob("startup-*.ini"))
        self.assertEqual(len(startup_inis), 1)
        self.assertIn(self.job["account"]["password"], startup_inis[0].read_text())
        self.assertTrue(all(self.job["account"]["password"] not in str(arg) for proc in processes for arg in proc.args))
        self.assertFalse((self.root / "terminal-ini-read.txt").exists())
        self.assertTrue(startup_inis[0].exists(), "INI remains available while delayed terminal initializes")
        processes[0].wait(timeout=3)
        for _ in range(100):
            if (self.root / "terminal-ini-read.txt").exists():
                break
            import time
            time.sleep(0.01)
        self.assertIn(self.job["account"]["password"], (self.root / "terminal-ini-read.txt").read_text())
        worker.remove_terminal_configs(result)
        self.assertFalse(list(result.glob("startup-*.ini")))
        self.assertTrue((result / "terminal/MQL5/Experts/DestinedFXTraderEA.ex5").is_file())
        self.assertTrue((result / "terminal/MQL5/Include/Trade/Trade.mqh").is_file())
        self.assertTrue((result / "terminal/MQL5/Presets/DestinedFXTraderEA.set").is_file())

    def test_standard_library_uses_matching_terminal_data_origin(self):
        installed_trade = self.install / "MQL5" / "Include" / "Trade" / "Trade.mqh"
        installed_trade.unlink()
        appdata = self.root / "appdata"
        data_dir = appdata / "MetaQuotes" / "Terminal" / "terminal-instance"
        (data_dir / "origin.txt").parent.mkdir(parents=True)
        (data_dir / "origin.txt").write_text(str(self.install), encoding="utf-8")
        trade = data_dir / "MQL5" / "Include" / "Trade" / "Trade.mqh"
        trade.parent.mkdir(parents=True)
        trade.write_text("// genuine terminal data library fixture\n")
        clone = self.root / "clone"
        with patch.dict(os.environ, {"APPDATA": str(appdata)}):
            worker._copy_terminal_standard_includes(self.install, clone)
        staged = clone / "MQL5" / "Include" / "Trade" / "Trade.mqh"
        self.assertTrue(staged.is_file())
        self.assertEqual(staged.read_text(), "// genuine terminal data library fixture\n")

    def test_terminal_that_never_authenticates_times_out_without_ready_promotion(self):
        with patch.object(worker, "request_json", return_value={"status": "AUTHENTICATING"}), patch.object(worker.time, "sleep"):
            self.assertEqual(worker.wait_for_terminal("https://bridge.realcop.com", "x" * 32, "job_123", "worker-01", timeout=1), "TIMEOUT")

    def test_no_process_if_ea_source_missing(self):
        self.source.unlink()
        with patch.object(worker.subprocess, "Popen") as popen:
            with self.assertRaises(worker.ProvisionError) as e:
                worker.provision(self.job, self.settings)
        self.assertEqual(e.exception.code, "EA_SOURCE_MISSING")
        popen.assert_not_called()
        self.assertFalse((Path(self.settings["data_root"]) / self.job["job_id"]).exists())

    def test_revoke_removes_only_matching_worker_owned_tree(self):
        result = worker.provision(self.job, self.settings, launch=False)
        marker = result / "terminal" / ".destinedfx-worker.json"
        marker.write_text('{"pairing_id":"pair_456","pid":123,"executable":"' + str(result / "terminal" / "terminal64.exe") + '"}', encoding="utf-8")
        # PID 123 may be a live unrelated process on the host; pin the ownership result so the
        # test does not depend on host PIDs. Fail-closed behaviour is covered by other tests.
        with patch.object(worker, "process_is_owned", return_value=worker.ProcessState.DEFINITELY_DEAD):
            worker.revoke_pairing(self.settings, "pair_456")
        self.assertFalse(result.exists())

    def test_revoke_fails_if_nonempty_tree_has_no_matching_ownership_marker(self):
        account = Path(self.settings["data_root"]) / "unknown-job" / "terminal"
        account.mkdir(parents=True)
        with self.assertRaises(worker.ProvisionError) as e:
            worker.revoke_pairing(self.settings, "pair_456")
        self.assertEqual(e.exception.code, "REVOKE_MARKER_NOT_FOUND")
        self.assertTrue(account.exists())

    def test_no_process_if_compiler_and_precompiled_ea_missing(self):
        self.meta.unlink()
        with patch.object(worker.subprocess, "Popen") as popen:
            with self.assertRaises(worker.ProvisionError) as e:
                worker.provision(self.job, self.settings)
        self.assertEqual(e.exception.code, "EA_COMPILER_MISSING")
        popen.assert_not_called()
        self.assertFalse((Path(self.settings["data_root"]) / self.job["job_id"]).exists())

    def test_duplicate_pairing_is_rejected_before_second_launch(self):
        result = worker.provision(self.job, self.settings, launch=False)
        marker = result / "terminal" / ".destinedfx-worker.json"
        marker.write_text('{"job_id":"job_123","pairing_id":"pair_456","pid":123,"executable":"' + str(result / "terminal" / "terminal64.exe") + '","status":"AUTHENTICATING"}', encoding="utf-8")
        with self.assertRaises(worker.ProvisionError) as error:
            worker.provision({**self.job, "job_id": "job_789"}, self.settings, launch=False)
        self.assertEqual(error.exception.code, "DUPLICATE_TERMINAL")

    def test_supervision_restarts_owned_terminal_with_bounded_retries(self):
        result = worker.provision(self.job, self.settings, launch=False)
        marker = result / "terminal" / ".destinedfx-worker.json"
        marker.write_text('{"job_id":"job_123","pairing_id":"pair_456","pid":99,"restart_count":0,"executable":"' + str(result / "terminal" / "terminal64.exe") + '","status":"RUNNING","expected_login":"123456","expected_server":"Demo-Server","account_mode":"DEMO","access_type":"MASTER","bridge_url":"https://bridge.realcop.com","broker_brand":"FBS"}', encoding="utf-8")
        fake = type("Process", (), {"pid": 100})()
        with patch.object(worker, "process_is_owned", return_value=worker.ProcessState.DEFINITELY_DEAD), patch.object(worker.subprocess, "Popen", return_value=fake) as popen:
            outcome = worker.supervise_terminals({**self.settings, "max_restart_retries": 1, "restart_backoff_seconds": 0})
        self.assertEqual(outcome[0]["status"], "RESTARTED")
        args = popen.call_args.args[0]
        self.assertTrue(any(str(value).startswith("/config:") for value in args))
        self.assertIn("/portable", args)
        restart_ini = Path(next(value.split(":", 1)[1] for value in args if str(value).startswith("/config:")))
        self.assertTrue(restart_ini.is_file(), "restart INI must survive delayed MT5 initialization")
        self.assertNotIn("Password=", restart_ini.read_text())
        self.assertEqual(worker.json.loads(marker.read_text())["restart_count"], 1)
        with patch.object(worker, "process_is_owned", return_value=worker.ProcessState.DEFINITELY_DEAD):
            outcome = worker.supervise_terminals({**self.settings, "max_restart_retries": 1, "restart_backoff_seconds": 0})
        self.assertEqual(outcome[0]["status"], "RESTART_LIMIT")

    def test_restart_ready_cleanup_requires_authenticating_acknowledgement(self):
        result = worker.provision(self.job, self.settings, launch=False)
        marker = result / "terminal" / ".destinedfx-worker.json"
        record = {"job_id":"job_123", "pairing_id":"pair_456", "pid":77, "restart_count":1,
                  "status":"AUTHENTICATING", "restart_requires_reauthentication":True,
                  "restart_auth_acknowledged":False, "last_restart":__import__("time").time(),
                  "executable":str(result / "terminal" / "terminal64.exe"),
                  "expected_login":"123456", "expected_server":"Demo-Server", "account_mode":"DEMO",
                  "access_type":"MASTER", "bridge_url":"https://bridge.realcop.com", "broker_brand":"FBS"}
        worker.write_marker_atomic(marker, record)
        ini = result / "restart-one.ini"
        ini.write_text("non-secret")
        with patch.object(worker, "process_is_owned", return_value=worker.ProcessState.OWNED_RUNNING), \
             patch.object(worker, "request_json", return_value={"status":"READY"}):
            outcome = worker.supervise_terminals(self.settings, "https://bridge.realcop.com", "x"*32, "worker-01")
        self.assertEqual(outcome[0]["status"], "AUTHENTICATING")
        self.assertTrue(ini.exists())
        with patch.object(worker, "process_is_owned", return_value=worker.ProcessState.OWNED_RUNNING), \
             patch.object(worker, "request_json", return_value={"status":"AUTHENTICATING"}):
            worker.supervise_terminals(self.settings, "https://bridge.realcop.com", "x"*32, "worker-01")
        with patch.object(worker, "process_is_owned", return_value=worker.ProcessState.OWNED_RUNNING), \
             patch.object(worker, "request_json", return_value={"status":"READY"}):
            outcome = worker.supervise_terminals(self.settings, "https://bridge.realcop.com", "x"*32, "worker-01")
        self.assertEqual(outcome[0]["status"], "READY")
        self.assertFalse(ini.exists())

    def test_restart_auth_timeout_terminates_owned_process_and_unknown_preserves_tree(self):
        for ownership, expected in ((worker.ProcessState.DEFINITELY_DEAD, "FAILED"),
                                    (worker.ProcessState.UNKNOWN, "OPERATOR_REVIEW")):
            result = worker.provision(self.job, self.settings, launch=False)
            marker = result / "terminal" / ".destinedfx-worker.json"
            worker.write_marker_atomic(marker, {"job_id":"job_123", "pairing_id":"pair_456", "pid":88,
                "restart_count":1, "status":"AUTHENTICATING", "restart_requires_reauthentication":True,
                "restart_auth_acknowledged":True, "last_restart":__import__("time").time()-10, "executable":str(result / "terminal" / "terminal64.exe")})
            ini = result / "restart-secret.ini"
            ini.write_text("secret")
            with patch.object(worker, "process_is_owned", return_value=worker.ProcessState.OWNED_RUNNING), \
                 patch.object(worker, "terminate_owned_process", return_value=ownership) as terminate, \
                 patch.object(worker, "request_json", return_value={"status":"AUTHENTICATING"}), \
                 patch.object(worker, "post_state") as post:
                outcome = worker.supervise_terminals({**self.settings, "auth_timeout_seconds":1}, "https://bridge.realcop.com", "x"*32, "worker-01")
            self.assertEqual(outcome[0]["status"], expected)
            terminate.assert_called_once()
            if expected == "FAILED":
                self.assertTrue(result.exists())
                self.assertFalse(list(result.glob("*.ini")))
                self.assertEqual(worker.json.loads(marker.read_text())["status"], "FAILED")
                post.assert_called_once()
            else:
                self.assertTrue(result.exists())
                self.assertTrue(ini.exists())
            import shutil
            shutil.rmtree(result)

    def test_restart_timeout_applies_without_fresh_auth_ack_for_missing_or_stale_ready(self):
        for backend_status in (None, "READY"):
            for termination in (worker.ProcessState.DEFINITELY_DEAD, worker.ProcessState.UNKNOWN):
                result = worker.provision(self.job, self.settings, launch=False)
                marker = result / "terminal" / ".destinedfx-worker.json"
                worker.write_marker_atomic(marker, {"job_id":"job_123", "pairing_id":"pair_456", "pid":88,
                    "restart_count":1, "status":"AUTHENTICATING", "restart_requires_reauthentication":True,
                    "restart_auth_acknowledged":False, "last_restart":__import__("time").time()-10,
                    "executable":str(result / "terminal" / "terminal64.exe")})
                ini = result / "restart-secret.ini"
                ini.write_text("secret")
                backend = {} if backend_status is None else {"status": backend_status}
                with patch.object(worker, "process_is_owned", return_value=worker.ProcessState.OWNED_RUNNING), \
                     patch.object(worker, "terminate_owned_process", return_value=termination) as terminate, \
                     patch.object(worker, "request_json", return_value=backend):
                    outcome = worker.supervise_terminals({**self.settings, "auth_timeout_seconds":1}, "https://bridge.realcop.com", "x"*32, "worker-01")
                terminate.assert_called_once()
                if termination == worker.ProcessState.DEFINITELY_DEAD:
                    self.assertEqual(outcome[0]["status"], "FAILED")
                    self.assertFalse(ini.exists())
                else:
                    self.assertEqual(outcome[0]["status"], "OPERATOR_REVIEW")
                    self.assertTrue(ini.exists(), "configuration must survive when termination is unverified")
                import shutil
                shutil.rmtree(result)

    def test_restart_ini_is_read_by_delayed_process_and_retained_without_ready(self):
        import time
        result = worker.provision(self.job, self.settings, launch=False)
        marker = result / "terminal" / ".destinedfx-worker.json"
        worker.write_marker_atomic(marker, {"job_id":"job_123", "pairing_id":"pair_456", "pid":88,
            "restart_count":0, "status":"RUNNING", "expected_login":"123456", "expected_server":"Demo-Server",
            "account_mode":"DEMO", "access_type":"MASTER", "bridge_url":"https://bridge.realcop.com", "broker_brand":"FBS",
            "executable":str(result / "terminal" / "terminal64.exe")})
        processes = []
        real_popen = subprocess.Popen
        def tracked_popen(*args, **kwargs):
            proc = real_popen(*args, **kwargs)
            processes.append(proc)
            return proc
        with patch.object(worker, "subprocess") as subprocess_mock:
            subprocess_mock.Popen.side_effect = tracked_popen
            subprocess_mock.DEVNULL = subprocess.DEVNULL
            with patch.object(worker, "process_is_owned", return_value=worker.ProcessState.DEFINITELY_DEAD):
                outcome = worker.supervise_terminals({**self.settings, "restart_backoff_seconds":0})
        self.assertEqual(outcome[0]["status"], "RESTARTED")
        restart_ini = next(result.glob("restart-*.ini"))
        readback = self.root / "terminal-ini-read.txt"
        for _ in range(200):
            if readback.exists():
                break
            time.sleep(0.01)
        self.assertTrue(readback.exists(), "delayed subprocess must actually read its /config INI")
        self.assertIn("Login=123456", readback.read_text())
        processes[0].wait(timeout=3)
        self.assertTrue(restart_ini.exists(), "restart INI remains available until and after delayed read")
        with patch.object(worker, "process_is_owned", return_value=worker.ProcessState.OWNED_RUNNING), \
             patch.object(worker, "request_json", side_effect=worker.ProvisionError("CONTROL_PLANE_UNAVAILABLE")):
            outcome = worker.supervise_terminals(self.settings, "https://bridge.realcop.com", "x"*32, "worker-01")
        self.assertEqual(outcome[0]["status"], "AUTHENTICATING")
        self.assertTrue(restart_ini.exists(), "missing READY must not remove restart configuration")

    def test_windows_private_acl_includes_validated_current_identity_sid(self):
        fake = type("Result", (), {"returncode": 0, "stdout": '"DOMAIN\\taskuser","S-1-5-21-123-456-789-1001"'})()
        with patch.object(worker.subprocess, "run", return_value=fake):
            self.assertEqual(worker.current_process_user_sid(), "S-1-5-21-123-456-789-1001")
        path = self.root / "private.ini"
        calls = []
        def run(args, **kwargs):
            calls.append(args)
            return type("Result", (), {"returncode": 0})()
        with patch.object(worker.os, "name", "nt"), \
             patch.object(worker, "current_process_user_sid", return_value="S-1-5-21-123-456-789-1001"), \
             patch.object(worker.subprocess, "run", side_effect=run):
            worker.write_private(path, "secret")
        self.assertIn("*S-1-5-18:F", calls[0])
        self.assertIn("*S-1-5-32-544:F", calls[0])
        self.assertIn("*S-1-5-21-123-456-789-1001:F", calls[0])

    def test_windows_private_acl_fails_closed_for_invalid_identity_sid(self):
        fake = type("Result", (), {"returncode": 0, "stdout": '"DOMAIN\\taskuser","not-a-sid"'})()
        with patch.object(worker.subprocess, "run", return_value=fake):
            with self.assertRaises(OSError):
                worker.current_process_user_sid()

    def test_ready_ack_removes_startup_and_restart_inis(self):
        account_dir = self.root / "accounts" / "job_123"
        account_dir.mkdir(parents=True)
        (account_dir / "startup-one.ini").write_text("secret")
        (account_dir / "restart-one.ini").write_text("non-secret")
        worker.remove_terminal_configs(account_dir)
        self.assertFalse(list(account_dir.glob("*.ini")))

    def test_auth_timeout_removes_account_only_after_owned_process_confirmed_dead(self):
        result = worker.provision(self.job, self.settings, launch=False)
        marker = result / "terminal" / ".destinedfx-worker.json"
        worker.write_marker_atomic(marker, {"job_id": "job_123", "pairing_id": "pair_456", "pid": 44,
                                            "executable": str(result / "terminal" / "terminal64.exe"),
                                            "status": "AUTHENTICATING"})
        (result / "startup-secret.ini").write_text("secret")
        with patch.object(worker, "process_is_owned", return_value=worker.ProcessState.OWNED_RUNNING), \
             patch.object(worker, "terminate_owned_process", return_value=worker.ProcessState.DEFINITELY_DEAD):
            self.assertEqual(worker.stop_terminal_for_timeout(self.settings, "job_123"), "TERMINATED")
        self.assertFalse(result.exists())

    def test_unknown_timeout_ownership_preserves_config_and_account_tree(self):
        result = worker.provision(self.job, self.settings, launch=False)
        marker = result / "terminal" / ".destinedfx-worker.json"
        worker.write_marker_atomic(marker, {"job_id": "job_123", "pairing_id": "pair_456", "pid": 44,
                                            "executable": str(result / "terminal" / "terminal64.exe"),
                                            "status": "AUTHENTICATING"})
        ini = result / "startup-secret.ini"
        ini.write_text("secret")
        with patch.object(worker, "process_is_owned", return_value=worker.ProcessState.UNKNOWN), \
             patch.object(worker, "terminate_owned_process") as terminate:
            self.assertEqual(worker.stop_terminal_for_timeout(self.settings, "job_123"), "OPERATOR_REVIEW")
        terminate.assert_not_called()
        self.assertTrue(result.exists())
        self.assertTrue(ini.exists())

    def test_password_is_absent_from_worker_logs_and_process_arguments(self):
        import io
        import logging
        logs = io.StringIO()
        handler = logging.StreamHandler(logs)
        worker.LOG.addHandler(handler)
        captured = {}
        fake = type("Process", (), {"pid": 451})()
        compiled = self.root / "trusted.ex5"
        compiled.write_bytes(b"compiled")
        try:
            with patch.object(worker.subprocess, "Popen", side_effect=lambda args, **kwargs: captured.update(args=args) or fake), \
                 patch.object(worker, "process_is_owned", return_value=worker.ProcessState.OWNED_RUNNING), \
                 patch.object(worker, "update_marker", side_effect=OSError("marker failed")), \
                 patch.object(worker, "terminate_owned_process", return_value=worker.ProcessState.UNKNOWN):
                with self.assertRaises(worker.ProvisionError):
                    worker.provision(self.job, {**self.settings, "compiled_ea": str(compiled)})
        finally:
            worker.LOG.removeHandler(handler)
        password = self.job["account"]["password"]
        self.assertNotIn(password, repr(captured.get("args", [])))
        self.assertNotIn(password, logs.getvalue())

    def test_marker_update_failure_never_deletes_tree_while_spawned_process_is_unverified(self):
        fake = type("Process", (), {"pid": 4321})()
        compiled = self.root / "trusted.ex5"
        compiled.write_bytes(b"compiled")
        with patch.object(worker.subprocess, "Popen", return_value=fake), \
             patch.object(worker, "update_marker", side_effect=OSError("marker storage failed")), \
             patch.object(worker, "process_is_owned", return_value=worker.ProcessState.OWNED_RUNNING), \
             patch.object(worker, "terminate_owned_process", return_value=worker.ProcessState.UNKNOWN):
            with self.assertRaises(worker.ProvisionError) as error:
                worker.provision(self.job, {**self.settings, "compiled_ea": str(compiled)})
        self.assertEqual(error.exception.code, "TERMINAL_OWNERSHIP_UNVERIFIED")
        account = Path(self.settings["data_root"]) / self.job["job_id"]
        self.assertTrue(account.exists())
        self.assertTrue((account / "terminal" / ".destinedfx-worker.json").exists())

    def test_process_states_do_not_restart_unknown_or_mismatched_processes(self):
        result = worker.provision(self.job, self.settings, launch=False)
        marker = result / "terminal" / ".destinedfx-worker.json"
        marker.write_text('{"job_id":"job_123","pairing_id":"pair_456","pid":99,"restart_count":0,"status":"RUNNING"}', encoding="utf-8")
        for state in (worker.ProcessState.UNKNOWN, worker.ProcessState.IDENTITY_MISMATCH):
            with patch.object(worker, "process_is_owned", return_value=state), patch.object(worker.subprocess, "Popen") as popen:
                outcome = worker.supervise_terminals(self.settings)
            self.assertEqual(outcome[0]["status"], "OPERATOR_REVIEW")
            popen.assert_not_called()

    def test_config_has_no_secret(self):
        cfg = (Path(__file__).parent / "worker.example.toml").read_text()
        self.assertNotIn("password", cfg.lower())
        self.assertNotIn("worker_token", cfg.lower())

    def test_worker_identity_is_required_and_stable_across_restart(self):
        with self.assertRaises(worker.ProvisionError) as error:
            worker.load_worker_id({})
        self.assertEqual(error.exception.code, "WORKER_ID_MISSING_STABLE_ID_REQUIRED")
        first = worker.load_worker_id({"DESTINEDFX_WORKER_ID": "host-worker-01"})
        restarted = worker.load_worker_id({"DESTINEDFX_WORKER_ID": "host-worker-01"})
        self.assertEqual(first, restarted)

    def test_restart_configuration_has_expected_identity_and_no_password(self):
        text = worker.restart_ini_contents({"expected_login": "123456", "expected_server": "Demo-Server", "account_mode": "DEMO", "access_type": "MASTER", "bridge_url": "https://bridge.realcop.com", "broker_brand":"FBS"})
        self.assertIn("Login=123456", text)
        self.assertIn("Server=Demo-Server", text)
        self.assertIn("AllowLiveTrading=1", text)
        self.assertIn("Expert=DestinedFXTraderEA", text)
        self.assertNotIn("Password=", text)

    def test_restart_configuration_rejects_missing_or_invalid_identity_and_investor_is_read_only(self):
        for record in ({"expected_login": "", "expected_server": "Demo-Server", "account_mode": "DEMO", "access_type": "MASTER", "bridge_url": "https://bridge.realcop.com", "broker_brand":"FBS"},
                       {"expected_login": "123456", "expected_server": "Demo-Server", "account_mode": "DEMO", "access_type": "BROKER", "bridge_url": "https://bridge.realcop.com", "broker_brand":"FBS"},
                       {"expected_login": "123456", "expected_server": "Demo-Server", "account_mode": "DEMO", "access_type": "MASTER", "bridge_url": "https://bridge.invalid", "broker_brand":"FBS"}):
            with self.assertRaises(worker.ProvisionError):
                worker.restart_ini_contents(record)
        investor = worker.restart_ini_contents({"expected_login": "123456", "expected_server": "Demo-Server", "account_mode": "DEMO", "access_type": "INVESTOR", "bridge_url": "https://bridge.realcop.com", "broker_brand":"FBS"})
        self.assertIn("AllowLiveTrading=0", investor)


if __name__ == "__main__":
    unittest.main()
