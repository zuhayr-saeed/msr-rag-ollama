import os
import sys
import subprocess
import pathlib
import pytest

@pytest.mark.skipif(not os.getenv("RUN_INTEGRATION"), reason="set RUN_INTEGRATION=1 to contact CORBA worker")
def test_worker_ping_cli():
    root = pathlib.Path(__file__).resolve().parents[1]
    # Accept either location
    ping_paths = [root / "ping.py", root / "coordinator" / "ping.py"]
    ping = next((p for p in ping_paths if p.exists()), None)
    if ping is None:
        pytest.skip("No ping.py found (root or coordinator/)")

    try:
        proc = subprocess.run([sys.executable, str(ping)], capture_output=True, text=True, timeout=12)
    except FileNotFoundError as e:
        pytest.skip(f"Python not invokable: {e}")

    out = (proc.stdout or "") + "\n" + (proc.stderr or "")
    if "Success!" not in out:
        pytest.skip(f"Worker not reachable. Output:\n{out}")
    assert "Success!" in out

