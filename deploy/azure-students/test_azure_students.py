from __future__ import annotations

import json
import re
import subprocess
from pathlib import Path
from urllib.parse import unquote

ROOT = Path(__file__).parent
TEMPLATE = json.loads((ROOT / "azuredeploy.json").read_text())
BICEP = (ROOT / "main.bicep").read_text()
README = (ROOT / "README.md").read_text()


def test_subscription_template_has_only_student_vm_sizes_and_conservative_disk_guard() -> None:
    params = TEMPLATE["parameters"]
    assert params["vmSize"]["defaultValue"] == "Standard_B1s"
    assert params["vmSize"]["allowedValues"] == ["Standard_B1s", "Standard_B2ats_v2", "Standard_B2pts_v2"]
    assert params["osDiskSizeGb"]["allowedValues"] == [30]
    assert "Standard_D" not in BICEP


def test_template_has_no_public_ip_or_inbound_security_rules() -> None:
    body = json.dumps(TEMPLATE)
    assert "Microsoft.Network/publicIPAddresses" not in body
    assert '"securityRules": []' in body
    assert "publicIp" not in body


def test_cloud_init_is_embedded_and_names_missing_d1_fix() -> None:
    body = json.dumps(TEMPLATE)
    assert "D1 host-agent.py is absent" in body
    assert "destinedfx-azure-first-boot.service" in body
    assert "tailscale up --ssh --accept-dns=false" in body


def test_deploy_button_decodes_to_pushed_compiled_template_path() -> None:
    match = re.search(r"https://portal\.azure\.com/#create/Microsoft\.Template/uri/([^\s)]+)", README)
    assert match
    raw = unquote(match.group(1))
    assert raw.endswith("/deploy/azure-students/azuredeploy.json")
    assert "/free/phone-only-v1/" in raw


def test_deploy_script_rejects_unlisted_size_before_azure_login() -> None:
    proc = subprocess.run(
        [
            str(ROOT / "deploy.sh"),
            "--resource-group", "rg",
            "--location", "eastus",
            "--ssh-public-key-file", "/does/not/exist",
            "--budget-email", "owner@example.com",
            "--vm-size", "Standard_D2s_v5",
        ],
        text=True,
        capture_output=True,
        check=False,
    )
    assert proc.returncode == 20
    assert "outside the Azure for Students allow-list" in proc.stderr
