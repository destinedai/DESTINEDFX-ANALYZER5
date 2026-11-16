from pathlib import Path


ROOT = Path(__file__).resolve().parent


def test_windows_owner_stack_behavioral_suite_is_checked_in():
    suite = ROOT / "windows" / "OwnerStack.Tests.ps1"
    assert suite.is_file()
    text = suite.read_text(encoding="utf-8")
    assert "Describe 'DestinedFX owner stack'" in text
    assert "behavioral tests" in text
    assert "health-monitor.py" in text
    assert "protect-backup.ps1" in text
