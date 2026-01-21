
CS441 HW1 — CORBA RAG Index Builder (Option 2)

Author: Zuhayr Saeed

Email: zsaee2@uic.edu

Repo: github.com/zuhayr-saeed/cs441-corba-rag
TL;DR

Implements Option 2 (Alternative Textbook Group) using CORBA (omniORB) — not Map/Reduce. A CORBA Worker exposes methods to (1) extract text from MSR PDFs, (2) chunk with overlap, (3) compute Ollama embeddings, and (4) write JSONL artifacts. A Coordinator drives the worker locally or across hosts. Basic analytics produce a vocabulary, token vectors, nearest neighbors, and simple similarity/analogy probes to show that embeddings encode semantics. (Generator LLM is not fine-tuned; this builds retrieval artifacts for RAG.)
What the Grader Will Find

    CORBA design (Chapter 7 style): IDL interface, worker servant, and a coordinator client that invokes remote procedures (no Hadoop).
    End-to-end pipeline: PDF → chunks → embeddings → JSONL artifacts.
    Analytics: analytics/vocab.csv, analytics/token_vecs.npy, analytics/nn.csv, analytics/vocab.yaml, plus small evaluation reports.
    Docs & scripts: Exact commands to reproduce locally and a short EC2 runbook.
    Logging & config: Structured logs, config via environment/CLI (no secrets in code), and .gitignore for large artifacts.

Architecture (CORBA only)
Components
IDL

module rag {
  interface Worker {
    void   ping();
    string processFile(in string pdf,
                       in string job_id,
                       in string model,
                       in long   max_chars,
                       in long   overlap,
                       in string out_dir);
  };
};

    ping() — health check.
    processFile(...) — extracts text, chunks with overlap, embeds with Ollama, writes out/<job_id>.jsonl and returns the path.

Worker (server)

    Language: Python with omniORBpy.
    Responsibilities:
        Load PDF; normalize & chunk text (e.g., max 1200 chars, 200 overlap).
        Call Ollama /api/embeddings (e.g., nomic-embed-text, 768-dim).
        Write one JSON line per chunk: {"job_id","doc_id","chunk_id","text","vec",...}.
    Logging: INFO progress per file; WARN for PDF parsing quirks (e.g., harmless pdfminer gray color warnings).

Coordinator (client)

    coordinator/ping.py — verifies worker is reachable.
    coordinator/run_one.py — processes one PDF.
    coordinator/batch_ingest.py — iterates a PDF glob (e.g., MSRCorpus/*.pdf), assigns job_id per file, invokes processFile, and tracks results.
    tools/run_from_config.py — convenience wrapper (pings, then runs a configured batch).

Ollama

    Local runtime hosting the embedding model.
    Typical pair used: nomic-embed-text for indexing (768-dim). (A chat model like llama3.2:3b is optional and only used for the “ask” demo, not required for HW1.)

Data Flow

    Extract & Chunk
        Normalize whitespace.
        Chunk size W=1200 chars, overlap O=200 → stride S=1000.
        Keep sentence-friendly boundaries when possible.
    Embed
        POST to OLLAMA_HOST/api/embeddings with model nomic-embed-text.
        Store raw float vector (length 768).
    Write artifacts
        For each input PDF X.pdf, write: out/X.jsonl (each line has: job_id, doc_id, chunk_id, text, vec, model, timestamp).
    Analytics (post-processing)
        Build vocab from the corpus and compute an embedding per token (centroids over occurrences in chunk embeddings).
        Export:
            analytics/vocab.csv — id,token,df,tf
            analytics/token_vecs.npy — shape (V, 768)
            analytics/nn.csv — top-K nearest neighbors per token (cosine)
            analytics/vocab.yaml — same vocab in YAML
        Simple probes:
            analytics/similarity_scorecard.md — readable neighbor samples
            analytics/similarity_eval.jsonl — similarity queries
            analytics/analogy_eval.jsonl — vector arithmetic sanity checks

Repro Steps (Local)

Assumes Ubuntu / WSL, Python 3.10+, and Ollama installed.
0) Environment

# clone
git clone https://github.com/zuhayr-saeed/cs441-corba-rag
cd cs441-corba-rag

# Python venv + deps
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt

# Ollama: pull embedding model
ollama pull nomic-embed-text

# ensure OLLAMA host (default is fine)
export OLLAMA_HOST=${OLLAMA_HOST:-http://127.0.0.1:11434}

1) Start the CORBA Worker (Terminal A)

cd ~/cs441-corba-rag
source .venv/bin/activate
# stop any old process (harmless if none)
pkill -f worker/server.py || true
# start fresh
python worker/server.py

You may see benign pdfminer warnings (color space), safe to ignore.
2) Sanity ping (Terminal B)

cd ~/cs441-corba-rag
source .venv/bin/activate
python coordinator/ping.py
# expect: "Success! The worker responded."

If you see omniORB.CORBA._omni_sys_exc: CORBA.TRANSIENT(…ConnectFailed…), the worker isn’t running or the IOR can’t be found. Start/restart Worker and retry.
3) Batch ingest a subset of PDFs

# Example: 22 MSR PDFs with a common prefix
python coordinator/batch_ingest.py "/mnt/c/Users/Zuhayr/Downloads/MSRCorpus/1083142*.pdf"   nomic-embed-text 1200 200 out

Expected log pattern (sample):

    Repeats for each file: INFO coord: [N/M] 1083142.1083143 -> /path/to/1083142.1083143.pdf
    INFO coord: wrote /home/.../out/1083142.1083143.jsonl

4) Quick checks

# count JSONL files (expect the number of matched PDFs)
ls -1 out/1083142*.jsonl | wc -l

# inspect first line of one output
head -n 1 out/1083142.1083143.jsonl | jq .

# (optional) verify all vectors are the same length
python - <<'PY'
import json, glob
files = sorted(glob.glob('out/1083142*.jsonl'))
dim=None; lines=0
for f in files:
  for line in open(f):
    j=json.loads(line)
    v=len(j.get('vec',[])); dim=dim or v
    assert v==dim, f"Vector length mismatch in {f}"
    lines+=1
print(f"OK: files={len(files)}, chunks={lines}, dim={dim}")
PY

Typical result for this prefix: 22 files, dim=768, and a few thousand chunks.
5) Build vocab & neighbors (analytics)

# Build over the full prepared corpus (or switch to your out/*.jsonl if desired)
python analytics/build_vocab_and_neighbors.py   --input corpus/corpus.jsonl --min_df 5 --topk 10 --dump_yaml

Outputs:

    analytics/vocab.csv
    analytics/token_vecs.npy
    analytics/nn.csv
    analytics/vocab.yaml

6) Lightweight quality probes (optional but recommended)

    Similarity scorecard (human-readable): analytics/similarity_scorecard.md

    | agile | scrum (0.981), covered (0.978), recurrent (0.975) |
    | debugging | investigate (0.957), related (0.945), what (0.945) |

    JSONL probes:
        analytics/similarity_eval.jsonl — neighbors for a small query set.
        analytics/analogy_eval.jsonl — analogies like (bug - bugs + issue) (signal only).

File Formats
JSONL Chunks (out/<job_id>.jsonl)

{
  "job_id": "1083142.1083143",
  "doc_id": "1083142.1083143.pdf",
  "chunk_id": 0,
  "text": "…normalized chunk text…",
  "vec": [float, float, ...],        // 768-dim
  "model": "nomic-embed-text",
  "timestamp": 1759974650
}

Analytics Artifacts

analytics/vocab.csv

id,token,df,tf
0,agile,174,177
1,scrum,79,82
…

analytics/token_vecs.npy

Numpy array, shape (V, 768), L2-normalized before similarity.

analytics/nn.csv

token,neighbor,cosine
agile,scrum,0.9807
agile,covered,0.9777
…

analytics/vocab.yaml

YAML dump of vocab rows; convenient for inspection.
Configuration & Logging

    Embedding model: CLI argument to coordinator (e.g., nomic-embed-text).
    Chunking: max_chars and overlap are parameters passed to the worker.
    Ollama host: export OLLAMA_HOST=http://127.0.0.1:11434 (default).
    Logging: INFO for progress, WARN for recoverable PDF warnings, ERROR on failures.

Common Issues & Fixes

    CORBA.TRANSIENT ConnectFailed: Start/restart worker/server.py in Terminal A, then run python coordinator/ping.py.
    Ollama 404 on /api/generate (only for optional chat demo): Pull a chat model (e.g., ollama pull llama3.2:3b) and set:

    export RAG_GEN_MODEL=llama3.2:3b

    Then restart anything that depends on it.
    pdfminer warnings about gray non-stroke color: Harmless; PDF quirks. Extraction proceeds.


