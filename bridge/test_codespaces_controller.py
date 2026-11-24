import ast
import json
import subprocess
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def test_codespaces_python_sources_compile():
    paths = (
        ROOT / "bridge/host_lifecycle.py",
        ROOT / "deploy/codespaces/host_controller.py",
    )
    for path in paths:
        source = path.read_text(encoding="utf-8")
        compile(source, str(path), "exec")


def test_codespaces_shell_scripts_parse():
    paths = (
        ROOT / "deploy/codespaces/start.sh",
        ROOT / "deploy/codespaces/stop.sh",
        ROOT / "deploy/codespaces/vnc-up.sh",
    )
    for path in paths:
        result = subprocess.run(
            ["bash", "-n", str(path)],
            capture_output=True,
            text=True,
            check=False,
        )
        assert result.returncode == 0, result.stderr


def test_devcontainer_start_command_is_non_blocking_python_supervisor():
    config = json.loads(
        (ROOT / ".devcontainer/devcontainer.json").read_text(encoding="utf-8")
    )
    assert config["postStartCommand"] == "bash deploy/codespaces/start-demo.sh --background"
    adapter_path = ROOT / "scripts/host/destinedfx_trigger_host.py"
    adapter = adapter_path.read_text(encoding="utf-8")
    assert "host_controller.ensure_started()" in adapter
    tree = ast.parse(adapter, filename=str(adapter_path))
    for node in ast.walk(tree):
        if isinstance(node, ast.Call):
            call_source = ast.get_source_segment(adapter, node) or ""
            assert "gui.sh" not in call_source
    assert "start.sh" not in config["postStartCommand"]
    controller = (ROOT / "deploy/codespaces/host_controller.py").read_text(encoding="utf-8")
    assert (
        "inspect_shutdown_safety(\n"
        "                        DB_PATH, heartbeat_stale_seconds=15, require_ea_stopped=True\n"
        "                    )"
    ) in controller
    assert "cancel_stop(RUNTIME, DB_PATH" in controller

def test_codespaces_demo_command_waits_for_guarded_public_bridge_and_api():
    script = (ROOT / "deploy/codespaces/start-demo.sh").read_text(encoding="utf-8")
    config = json.loads(
        (ROOT / ".devcontainer/devcontainer.json").read_text(encoding="utf-8")
    )
    assert config["forwardPorts"][:2] == [8000, 8010]
    assert config["portsAttributes"]["8000"]["visibility"] == "private"
    assert config["portsAttributes"]["8010"]["visibility"] == "private"
    assert "destinedfx_trigger_host.py" in script and "start_all" in script
    assert "public-endpoints.json" in script
    assert "last-activity" in script
    assert "http://127.0.0.1:8000/health/live" in script
    assert "http://127.0.0.1:8010/ready" in script
    assert "http://127.0.0.1:8787/health/ready" in script
    assert "Codespaces DEMO host is ready" in script
    assert "gh codespace ports visibility" not in script
