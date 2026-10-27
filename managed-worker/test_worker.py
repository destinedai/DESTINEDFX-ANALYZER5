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
                    "bridge_url": "https://bridge.example", "account": {"login": "123456", "server": "Demo-Server",
                    "password": "secret-not-logged", "access_type": "INVESTOR"}}
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
        text = worker.make_set_file("https://bridge.example", "abcdefgh1234")
        self.assertIn("InpBridgeBaseURL=https://bridge.example", text)
        self.assertIn("InpPairingCode=abcdefgh1234", text)

    def test_invalid_path_and_bridge_rejected(self):
        bad = dict(self.job, job_id="../escape")
        with self.assertRaises(worker.ProvisionError):
            worker.validate_job(bad)
        bad = dict(self.job, bridge_url="http://insecure.example")
        with self.assertRaises(worker.ProvisionError):
            worker.validate_job(bad)

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

    def test_no_process_if_ea_source_missing(self):
        self.source.unlink()
        with patch.object(worker.subprocess, "Popen") as popen:
            with self.assertRaises(worker.ProvisionError) as e:
                worker.provision(self.job, self.settings)
        self.assertEqual(e.exception.code, "EA_SOURCE_MISSING")
        popen.assert_not_called()

    def test_revoke_removes_only_matching_worker_owned_tree(self):
        result = worker.provision(self.job, self.settings, launch=False)
        marker = result / "terminal" / ".destinedfx-worker.json"
        marker.write_text('{"pairing_id":"pair_456","pid":123}', encoding="utf-8")
        worker.revoke_pairing(self.settings, "pair_456")
        self.assertFalse(result.exists())

    def test_no_process_if_compiler_and_precompiled_ea_missing(self):
        self.meta.unlink()
        with patch.object(worker.subprocess, "Popen") as popen:
            with self.assertRaises(worker.ProvisionError) as e:
                worker.provision(self.job, self.settings)
        self.assertEqual(e.exception.code, "EA_COMPILER_MISSING")
        popen.assert_not_called()

    def test_config_has_no_secret(self):
        cfg = (Path(__file__).parent / "worker.example.toml").read_text()
        self.assertNotIn("password", cfg.lower())
        self.assertNotIn("worker_token", cfg.lower())


if __name__ == "__main__":
    unittest.main()
