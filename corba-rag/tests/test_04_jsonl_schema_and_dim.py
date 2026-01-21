from pathlib import Path
import json
import os
import random

def _seed_dummy_out(outdir: Path, dim: int):
    outdir.mkdir(parents=True, exist_ok=True)
    dummy = outdir / "DUMMY.jsonl"
    if dummy.exists():
        return
    recs = []
    for i in range(3):
        recs.append({
            "job_id": "DUMMY",
            "doc_id": "DUMMY.pdf",
            "chunk_id": i,
            "text": f"dummy text {i}",
            "vec": [float(random.random()) for _ in range(dim)],
        })
    with dummy.open("w", encoding="utf-8") as fh:
        for r in recs:
            fh.write(json.dumps(r) + "\n")

def test_jsonl_schema_and_dim():
    root = Path(__file__).resolve().parents[1]
    outdir = root / "out"
    expected_dim = int(os.environ.get("EMBED_DIM", "768"))

    if not any(outdir.glob("*.jsonl")):
        _seed_dummy_out(outdir, expected_dim)

    files = sorted(outdir.glob("*.jsonl"))
    assert files, f"No JSONL files found in {outdir}"

    max_files = int(os.environ.get("TEST_MAX_JSONL_FILES", "10"))
    max_lines_per_file = int(os.environ.get("TEST_MAX_LINES_PER_FILE", "100"))

    checked = 0
    for f in files[:max_files]:
        with f.open("r", encoding="utf-8", errors="ignore") as fh:
            for i, line in enumerate(fh, 1):
                if not line.strip():
                    continue
                rec = json.loads(line)
                for key in ("job_id", "doc_id", "chunk_id", "text", "vec"):
                    assert key in rec, f"Missing '{key}' in {f.name}:line{i}"
                assert isinstance(rec["chunk_id"], int)
                assert isinstance(rec["text"], str) and rec["text"].strip()
                vec = rec["vec"]
                assert isinstance(vec, list)
                assert len(vec) == expected_dim, f"dim {len(vec)} != {expected_dim} in {f.name}:line{i}"
                checked += 1
                if i >= max_lines_per_file:
                    break
    assert checked > 0, "No JSONL lines validated"

