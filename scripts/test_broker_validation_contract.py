import importlib.util
import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CONFIG = ROOT / "scripts/broker_validation_config.json"
SCRIPT = ROOT / "scripts/test_broker_validation.py"
EXPECTED = {"FBS_MT5", "EXNESS_MT5", "HFM_MT5", "DERIV_MT5"}
EXPECTED_HFM_HOST = "mt5-europe1.dcglobalfarm.com"

SPEC = importlib.util.spec_from_file_location("broker_validator", SCRIPT)
VALIDATOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VALIDATOR)


class BrokerValidationContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.data = json.loads(CONFIG.read_text(encoding="utf-8"))
        cls.by_id = {item["spec_id"]: item for item in cls.data["brokers"]}

    def test_exactly_four_configured_mt5_brokers(self):
        self.assertEqual(4, len(self.data["brokers"]))
        self.assertEqual(EXPECTED, set(self.by_id))

    def test_kotlin_parser_finds_the_four_actual_mt5_login_specs(self):
        self.assertEqual(EXPECTED, VALIDATOR.read_mt5_specs())

    def test_every_broker_has_demo_and_real_server_identity_labels(self):
        for broker in self.data["brokers"]:
            for mode in ("demo", "real"):
                with self.subTest(broker=broker["spec_id"], mode=mode):
                    labels = broker[mode]["server_names"]
                    self.assertTrue(labels)
                    self.assertTrue(all(isinstance(x, str) and x.strip() for x in labels))

    def test_hfm_uses_the_published_demo_and_live1_ports(self):
        demo = self.by_id["HFM_MT5"]["demo"]["network_endpoints"]
        live = self.by_id["HFM_MT5"]["real"]["network_endpoints"]
        self.assertEqual(
            [{"server_label": "HFMarketsGlobal-Demo", "host": EXPECTED_HFM_HOST, "port": 1950}],
            demo,
        )
        self.assertEqual(
            [{"server_label": "HFMarketsGlobal-Live1", "host": EXPECTED_HFM_HOST, "port": 1951}],
            live,
        )

    def test_account_assigned_brokers_are_skipped_without_failing_gate(self):
        for spec_id in ("FBS_MT5", "EXNESS_MT5", "DERIV_MT5"):
            for mode in ("demo", "real"):
                with self.subTest(broker=spec_id, mode=mode):
                    entry = self.by_id[spec_id][mode]
                    self.assertEqual([], entry["network_endpoints"])
                    result = VALIDATOR.probe_mode(entry, 1.0)
                    self.assertEqual(VALIDATOR.SKIPPED, result["result"])
                    self.assertEqual("SKIPPED", result["dns"])
                    self.assertEqual("SKIPPED", result["tcp"])
                    self.assertEqual([], result["probes"])

    def test_transport_only_and_no_order_api(self):
        source = SCRIPT.read_text(encoding="utf-8")
        self.assertIn("socket.getaddrinfo", source)
        self.assertIn("sock.connect", source)
        self.assertIn("NOT_PROVEN", source)
        self.assertNotIn("MetaTrader5", source)
        self.assertNotIn("order_send", source)
        self.assertNotIn("password", source.lower())
        self.assertNotIn("443", source)

    def test_missing_owner_servers_are_not_network_failures(self):
        for spec_id in ("FBS_MT5", "EXNESS_MT5", "DERIV_MT5"):
            for mode in ("demo", "real"):
                result = VALIDATOR.probe_mode(self.by_id[spec_id][mode], 1.0)
                self.assertNotEqual("FAIL", result["result"])

    def test_no_unconfigured_broker_brands_added(self):
        content = json.dumps(self.data).lower()
        for item in ("xm", "ic markets", "pepperstone", "octafx"):
            self.assertNotIn(item, content)


if __name__ == "__main__":
    unittest.main()
