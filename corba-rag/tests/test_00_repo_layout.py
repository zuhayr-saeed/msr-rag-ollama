from pathlib import Path

def _exists_any(root: Path, candidates: list[str]) -> bool:
    return any((root / c).exists() for c in candidates)

def test_repo_layout_flexible():
    root = Path(__file__).resolve().parents[1]

    # Allow alternative locations (root vs coordinator/worker)
    assert _exists_any(root, ["server.py", "worker/server.py"]), "Missing server.py (root or worker/)"
    assert _exists_any(root, ["batch_ingest.py", "coordinator/batch_ingest.py"]), "Missing batch_ingest.py (root or coordinator/)"
    assert _exists_any(root, ["run_one.py", "coordinator/run_one.py"]), "Missing run_one.py (root or coordinator/)"
    assert _exists_any(root, ["ping.py", "coordinator/ping.py"]), "Missing ping.py (root or coordinator/)"
    assert _exists_any(root, ["rag_idl.py"]), "Missing rag_idl.py at repo root"
    assert _exists_any(root, ["config.yml", "config.yaml"]), "Missing config.yml or config.yaml at repo root"

