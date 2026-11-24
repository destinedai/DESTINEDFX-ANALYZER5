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
    assert config["postStartCommand"] == (
        "python3 scripts/host/destinedfx_trigger_host.py start_all"
    )
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
