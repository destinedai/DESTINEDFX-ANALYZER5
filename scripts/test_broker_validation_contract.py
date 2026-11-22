import importlib.util,json,unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
CONFIG=ROOT/"scripts/broker_validation_config.json"
SCRIPT=ROOT/"scripts/test_broker_validation.py"
EXPECTED={"FBS_MT5","EXNESS_MT5","HFM_MT5","DERIV_MT5"}
SPEC=importlib.util.spec_from_file_location("broker_validator",SCRIPT)
VALIDATOR=importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VALIDATOR)
class BrokerValidationContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls): cls.data=json.loads(CONFIG.read_text(encoding="utf-8"))
    def test_exactly_four_configured_mt5_brokers(self):
        self.assertEqual(4,len(self.data["brokers"]))
        self.assertEqual(EXPECTED,{x["spec_id"] for x in self.data["brokers"]})
    def test_kotlin_source_parser_finds_the_four_actual_mt5_login_specs(self):
        self.assertEqual(EXPECTED,VALIDATOR.read_mt5_specs())
    def test_demo_and_real_server_labels_exist(self):
        for b in self.data["brokers"]:
            for mode in ("demo","real"):
                with self.subTest(broker=b["spec_id"],mode=mode):
                    self.assertTrue(b[mode]["server_names"])
                    self.assertTrue(all(isinstance(x,str) and x.strip() for x in b[mode]["server_names"]))
    def test_only_hfm_provider_cluster_host_is_probed(self):
        for b in self.data["brokers"]:
            for mode in ("demo","real"):
                hosts=b[mode].get("dns_hosts",[])
                self.assertEqual(["mt5-europe1.dcglobalfarm.com"] if b["spec_id"]=="HFM_MT5" else [],hosts)
    def test_transport_only_and_no_trading_or_login(self):
        s=SCRIPT.read_text(encoding="utf-8")
        self.assertIn("socket.getaddrinfo",s); self.assertIn("sock.connect",s)
        self.assertIn("NOT PROVEN",s); self.assertNotIn("MetaTrader5",s)
        self.assertNotIn("order_send",s); self.assertNotIn("password",s.lower())
    def test_no_unconfigured_brokers(self):
        text=json.dumps(self.data).lower()
        for item in ("xm","ic markets","pepperstone","octafx"): self.assertNotIn(item,text)
if __name__=="__main__": unittest.main()
