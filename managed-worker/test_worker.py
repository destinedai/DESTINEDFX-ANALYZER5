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
        self.term = self.install / "terminal64.exe"
        self.term.write_text("#!/bin/sh\nprintf '%s\\n' \"$@\" > '" + str(self.root / "terminal-args.txt") + "'\nexit 0\n")
        self.term.chmod(0o755)
        self.source = self.root / "DestinedFXTraderEA.mq5"
        self.source.write_text("// fake source\n")
        self.meta = self.install / "metaeditor64.exe"
        self.meta.write_text("#!/usr/bin/env python3\nimport pathlib,sys\np=pathlib.Path(sys.argv[1].split(':',1)[1])\np.with_suffix('.ex5').write_bytes(b'fake compiled')\n")
        self.meta.chmod(0o755)
        self.job = {"job_id": "job_123", "pairing_id": "pair_456", "pairing_code": "abcdefgh1234",
                    "bridge_url": "https://bridge.destinedfx.internal", "account": {"login": "123456", "server": "Demo-Server",
                    "password": "secret-not-logged", "access_type": "INVESTOR", "account_mode": "DEMO"}}
        self.settings = {"data_root": str(self.root / "accounts"), "mt5_install": str(self.install),
                         "terminal_exe": str(self.term), "metaeditor_exe": str(self.meta), "ea_source": str(self.source)}

    def tearDown(self):
        self.tmp.cleanup()

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

    def test_launch_uses_argument_list_and_deletes_ini(self):
        processes = []
        real_popen = subprocess.Popen
        def tracked_popen(*args, **kwargs):
            proc = real_popen(*args, **kwargs)
            processes.append(proc)
            return proc
        with patch.object(worker.subprocess, "Popen", side_effect=tracked_popen):
            result = worker.provision(self.job, self.settings)
        for proc in processes:
            proc.wait(timeout=3)
        args_file = self.root / "terminal-args.txt"
        for _ in range(100):
            if args_file.exists():
                break
            import time
            time.sleep(0.01)
        self.assertTrue(args_file.exists())
        self.assertIn("/portable", args_file.read_text())
        self.assertFalse(list(result.glob("startup-*.ini")))
        self.assertTrue((result / "terminal/MQL5/Experts/DestinedFXTraderEA.ex5").is_file())
        self.assertTrue((result / "terminal/MQL5/Presets/DestinedFXTraderEA.set").is_file())

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
        marker.write_text('{"pairing_id":"pair_456","pid":123}', encoding="utf-8")
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
        marker.write_text('{"job_id":"job_123","pairing_id":"pair_456","pid":123,"status":"AUTHENTICATING"}', encoding="utf-8")
        with self.assertRaises(worker.ProvisionError) as error:
            worker.provision({**self.job, "job_id": "job_789"}, self.settings, launch=False)
        self.assertEqual(error.exception.code, "DUPLICATE_TERMINAL")

    def test_supervision_restarts_owned_terminal_with_bounded_retries(self):
        result = worker.provision(self.job, self.settings, launch=False)
        marker = result / "terminal" / ".destinedfx-worker.json"
        marker.write_text('{"job_id":"job_123","pairing_id":"pair_456","pid":99,"restart_count":0,"status":"RUNNING","expected_login":"123456","expected_server":"Demo-Server","account_mode":"DEMO","access_type":"MASTER","bridge_url":"https://bridge.realcop.com"}', encoding="utf-8")
        fake = type("Process", (), {"pid": 100})()
        with patch.object(worker, "process_is_owned", return_value=worker.ProcessState.DEFINITELY_DEAD), patch.object(worker.subprocess, "Popen", return_value=fake) as popen:
            outcome = worker.supervise_terminals({**self.settings, "max_restart_retries": 1, "restart_backoff_seconds": 0})
        self.assertEqual(outcome[0]["status"], "RESTARTED")
        args = popen.call_args.args[0]
        self.assertTrue(any(str(value).startswith("/config:") for value in args))
        self.assertIn("/portable", args)
        self.assertEqual(worker.json.loads(marker.read_text())["restart_count"], 1)
        with patch.object(worker, "process_is_owned", return_value=worker.ProcessState.DEFINITELY_DEAD):
            outcome = worker.supervise_terminals({**self.settings, "max_restart_retries": 1, "restart_backoff_seconds": 0})
        self.assertEqual(outcome[0]["status"], "RESTART_LIMIT")

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
        text = worker.restart_ini_contents({"expected_login": "123456", "expected_server": "Demo-Server", "account_mode": "DEMO", "access_type": "MASTER", "bridge_url": "https://bridge.realcop.com"})
        self.assertIn("Login=123456", text)
        self.assertIn("Server=Demo-Server", text)
        self.assertIn("AllowLiveTrading=1", text)
        self.assertIn("Expert=DestinedFXTraderEA", text)
        self.assertNotIn("Password=", text)

    def test_restart_configuration_rejects_missing_or_invalid_identity_and_investor_is_read_only(self):
        for record in ({"expected_login": "", "expected_server": "Demo-Server", "account_mode": "DEMO", "access_type": "MASTER", "bridge_url": "https://bridge.realcop.com"},
                       {"expected_login": "123456", "expected_server": "Demo-Server", "account_mode": "DEMO", "access_type": "BROKER", "bridge_url": "https://bridge.realcop.com"},
                       {"expected_login": "123456", "expected_server": "Demo-Server", "account_mode": "DEMO", "access_type": "MASTER", "bridge_url": "https://bridge.invalid"}):
            with self.assertRaises(worker.ProvisionError):
                worker.restart_ini_contents(record)
        investor = worker.restart_ini_contents({"expected_login": "123456", "expected_server": "Demo-Server", "account_mode": "DEMO", "access_type": "INVESTOR", "bridge_url": "https://bridge.realcop.com"})
        self.assertIn("AllowLiveTrading=0", investor)


if __name__ == "__main__":
    unittest.main()
