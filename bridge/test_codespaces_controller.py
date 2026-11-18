import importlib.util
import json
import subprocess
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def test_codespaces_python_sources_compile():
    paths = (
        ROOT / "host_lifecycle.py",
        ROOT.parent / "deploy/codespaces/host_controller.py",
    )
    for path in paths:
        source = path.read_text(encoding="utf-8")
        compile(source, str(path), "exec")


def test_codespaces_shell_scripts_parse():
    paths = (
        ROOT.parent / "deploy/codespaces/start.sh",
        ROOT.parent / "deploy/codespaces/stop.sh",
        ROOT.parent / "deploy/codespaces/vnc-up.sh",
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
        (ROOT.parent / ".devcontainer/devcontainer.json").read_text(encoding="utf-8")
    )
    assert config["postStartCommand"] == (
        "python3 deploy/codespaces/host_controller.py ensure-started"
    )
    assert "start.sh" not in config["postStartCommand"]
