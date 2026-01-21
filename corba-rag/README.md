# CS441 HW1 — CORBA RAG Index Builder (Option 2)

> **Course:** CS 441  
> **Homework:** HW1 (Alternative Textbook Group — **CORBA**)  
> **Project:** Distributed RAG Index Builder over the MSR PDF Corpus  
> **Author:** *Zuhayr Saeed* (`zsaee2@uic.edu`)

---

## What this repo does (executive summary)

This project implements a **distributed embedding pipeline** using **CORBA (omniORBpy)**. A **Worker** process exposes RPC methods that:

1. extract & normalize text from MSR PDF papers,  
2. chunk the text with overlap,  
3. compute embeddings via **Ollama** (e.g., `nomic-embed-text`, 768‑D), and  
4. write per‑document **JSONL** artifacts (one JSON record per chunk in `out/`).

A **Coordinator** process calls the Worker locally or across hosts (e.g., on **EC2**). After ingestion, an **analytics** step builds a vocabulary with token statistics and nearest neighbors and produces simple **similarity/analogy** scorecards—demonstrating that learned embeddings encode useful semantics.

This submission satisfies **HW1 Option 2** requirements:

- ✅ **CORBA distributed object** (IDL + Python worker servant)  
- ✅ **Coordinator client** performs remote method invocations  
- ✅ **Massively parallel friendly** (one‑file‑at‑a‑time; resume‑safe; cloud‑ready)  
- ✅ **Embeddings + artifacts** written to disk for the MSR corpus  
- ✅ **Statistics & probes** (vocab, neighbors, similarity/analogy)  
- ✅ **Cloud deployment** runbook (EC2 with conda + Ollama)  
- ✅ **Logging + config** driven (no secrets hardcoded)

---

## Architecture

```
+-------------------+                  +----------------------+
|   Coordinator     |    CORBA RPC     |        Worker        |
| (client, Python)  |  --------------> | (server, omniORBpy)  |
+-------------------+                  +----------------------+
         |                                       |
         |                            +----------v-----------+
         |                            | PDF Extract + Chunk  |
         |                            +----------+-----------+
         |                                       |
         |                            +----------v-----------+
         |                            |  Ollama Embeddings   |
         |                            | (e.g., nomic-embed)  |
         |                            +----------+-----------+
         |                                       |
         |                            +----------v-----------+
         |                            |  Write JSONL (out/)  |
         |                            +----------+-----------+
         |                                       |
         |                            +----------v-----------+
         |                            |     Analytics        |
         |                            +----------------------+
```

### CORBA IDL

```idl
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
```

- `ping()` — health check.
- `processFile(...)` — end‑to‑end pipeline for a single PDF; returns the path to `out/<job_id>.jsonl` on success.

### Worker (Python + omniORBpy)

- **Extraction**: defaults to `pdfminer.six`; will fall back to `pdftotext` (Poppler) when `RAG_EXTRACTOR=poppler` or if pdfminer errors.
- **Chunking**: fixed window `W = max_chars`, overlap `O`, stride `S = W − O`. Typical defaults `W=1200`, `O=200` (~17% overlap).
- **Embeddings**: POST to `OLLAMA_HOST/api/embeddings`. With `nomic-embed-text`, vectors are **768‑D**.
- **Output**: one JSONL line per chunk, fields:

```json
{
  "job_id": "MSR.2019.00061",
  "doc_id": "MSR.2019.00061.pdf",
  "chunk_id": 0,
  "text": "normalized chunk text ...",
  "vec": [float, ...],          // 768 numbers
  "model": "nomic-embed-text",
  "timestamp": 1759974650
}
```

- **Logging**: INFO/WARN/ERROR via `common/logging.yaml` → `worker.log`.

### Coordinator (Python client)

- `coordinator/ping.py` — confirms the worker is reachable.
- `coordinator/run_one.py` — process a single file by absolute path.
- `coordinator/batch_ingest.py` — resilient one‑at‑a‑time loop over a glob (safe to resume).
- `tools/run_from_config.py` — convenience: read `config.yml`, ping, then run `batch_ingest`.

### Analytics

- `analytics/build_vocab_and_neighbors.py` consumes `out/*.jsonl` to build:
  - `analytics/vocab.csv` / `vocab.yaml` — tokens + df/tf;
  - `analytics/token_vecs.npy` — `(V, D)` token vectors (D=768);
  - `analytics/nn.csv` — nearest neighbors by cosine;
  - `analytics/similarity_scorecard.md` — quick qualitative checks.

**Why fixed windows with overlap?**  
Shifting by 1 character creates huge duplication and cost for little recall benefit. A moderate overlap (10–25%) preserves context across boundaries with reasonable index size and speed.

---

## Directory layout (what graders will see)

```
cs441-corba-rag/
├── coordinator/
│   ├── batch_ingest.py
│   ├── run_one.py
│   └── ping.py
├── worker/
│   └── server.py
├── analytics/
│   └── build_vocab_and_neighbors.py
├── search/
│   └── search_cli.py
├── idl/
│   └── rag.idl
├── rag_idl.py
├── tools/
│   └── run_from_config.py
├── common/
│   └── logging.yaml
├── config.yml
├── out/                 # JSONL artifacts (created at runtime)
├── tests/               # 5 tests (unit + integration)
└── README.md
```

---

## Installation & Setup

You can run locally (Ubuntu/WSL) or on **AWS EC2**. For reproducibility we document **both**: a **virtualenv** path (local) and a **conda** path (EC2).

### A) Local (Ubuntu or WSL2, with virtualenv)

1) **Clone + venv**

```bash
git clone <your private repo url> cs441-corba-rag
cd cs441-corba-rag

python3 -m venv .venv
source .venv/bin/activate
python -m pip install --upgrade pip
pip install -r requirements.txt
```

2) **System packages (for omniORB & PDF fallback)**

```bash
sudo apt update
sudo apt -y install omniidl omniorb omniorb-nameserver poppler-utils jq tmux
```

3) **Ollama**

```bash
# Install from https://ollama.com/download or via script:
curl -fsSL https://ollama.com/install.sh | sh
# Ensure service is running; on WSL start manually: `ollama serve &`
ollama pull nomic-embed-text
export OLLAMA_HOST=${OLLAMA_HOST:-http://127.0.0.1:11434}
```

4) **Configure what to ingest** — edit **`config.yml`**. The **`pdf_glob`** must match *all PDFs*, not a single prefix.

```yaml
# Windows / WSL path example (recommended)
pdf_glob: "/mnt/c/Users/<YourName>/Downloads/MSRCorpus/*.pdf"

# Linux example
# pdf_glob: "/home/<you>/msr/*.pdf"

model: "nomic-embed-text"  # 768‑D embeddings
max_chars: 1200            # chunk window W
overlap: 200               # overlap O (stride = W - O = 1000)
out_dir: "out"
```

> ⚠️ Avoid using a prefix glob like `1083142*.pdf`; use `*.pdf` to include the whole corpus.

5) **Start CORBA NameService + Worker**

```bash
# If port 2809 is busy, free it: sudo fuser -k 2809/tcp  (or `pkill -f omniNames`)
omniNames -start -always -logdir . &

# Start the Worker from this repo (uses the active venv’s Python)
PYTHONPATH=$PWD python worker/server.py &

# Health check (expect: “Success! The worker responded.”)
python coordinator/ping.py
```

6) **Ingest files**

```bash
# Recommended: use config.yml
python tools/run_from_config.py

# Or explicitly, one file (smoke test)
python coordinator/batch_ingest.py "/abs/path/to/some.pdf" nomic-embed-text 1200 200 out
```

7) **Verify output**

```bash
ls -1 out/*.jsonl | wc -l
head -n 1 out/*.jsonl | jq .
```

8) **Analytics**

```bash
python analytics/build_vocab_and_neighbors.py \
  --input 'out/*.jsonl' --min_df 5 --topk 10 --dump_yaml

head -n 15 analytics/similarity_scorecard.md
```

---

### B) AWS EC2 (used for full corpus)

**Instance**: `c7i2.large` (CPU), Ubuntu 22.04 LTS, 30–50 GB gp3.  
**Security**: allow **only SSH** from your IP (do **not** expose Ollama).  
**Data**: copy PDFs to `~/msr` on the instance.

1) **Base OS**

```bash
ssh -i ~/.ssh/<key>.pem ubuntu@<EC2_PUBLIC_IP>
sudo apt update
sudo apt -y install git tmux jq curl omniidl omniorb omniorb-nameserver poppler-utils
```

2) **Conda env (EC2 recommended for clean omniORB)**

```bash
wget https://github.com/conda-forge/miniforge/releases/latest/download/Miniforge3-Linux-x86_64.sh -O mforge.sh
bash mforge.sh -b -p $HOME/miniforge3
eval "$($HOME/miniforge3/bin/conda shell.bash hook)"

conda create -y -n cs441 -c conda-forge python=3.10 omniorb omniorbpy numpy pyyaml requests tqdm
conda activate cs441
python -m pip install --no-cache-dir pdfminer.six==20231228
```

3) **Ollama (EC2)**

```bash
curl -fsSL https://ollama.com/install.sh | sh
sudo systemctl enable --now ollama
ollama pull nomic-embed-text
export OLLAMA_HOST=http://127.0.0.1:11434
```

4) **Repo + Data**

```bash
git clone <your private repo url> cs441-corba-rag
mkdir -p ~/msr
# From your laptop:
# scp -i ~/.ssh/<key>.pem "/path/to/MSRCorpus/*.pdf" ubuntu@<EC2_PUBLIC_IP>:~/msr/
```

5) **Start Worker + sanity**

```bash
cd ~/cs441-corba-rag
omniNames -start -always -logdir . &
PYTHONPATH=$PWD python worker/server.py &
python coordinator/ping.py
```

6) **Process**

```bash
# One file
python coordinator/batch_ingest.py "/home/ubuntu/msr/1083142.1083143.pdf" nomic-embed-text 1200 200 out

# Entire folder (resume-friendly; one-by-one)
for f in /home/ubuntu/msr/*.pdf; do
  python coordinator/batch_ingest.py "$f" nomic-embed-text 1200 200 out || echo "$f" >> ingest_failures.txt
done
```

7) **Analytics**

```bash
python analytics/build_vocab_and_neighbors.py --input 'out/*.jsonl' --min_df 5 --topk 10 --dump_yaml
```

**Cost tips:** stop the instance when idle; compress & download results; keep the repo private.

---

## Configuration & Logging

### `config.yml`

- `pdf_glob` — path to your PDFs (`*.pdf`).  
- `model` — embedding model (e.g., `nomic-embed-text`, 768‑D).  
- `max_chars` — chunk window size (e.g., 1200).  
- `overlap` — overlap (e.g., 200).  
- `out_dir` — where to write JSONL artifacts (e.g., `out`).

### `common/logging.yaml` (example)

```yaml
version: 1
formatters:
  simple:
    format: "%(asctime)s [%(levelname)s] %(name)s: %(message)s"
handlers:
  console:
    class: logging.StreamHandler
    formatter: simple
    level: INFO
  file:
    class: logging.FileHandler
    formatter: simple
    level: INFO
    filename: worker.log
root:
  level: INFO
  handlers: [console, file]
```

Set `RAG_EXTRACTOR=poppler` to force `pdftotext` when a PDF is hostile to `pdfminer`:

```bash
export RAG_EXTRACTOR=poppler
```

---

## Models & Parameters

- **Embedding model:** `nomic-embed-text` (Ollama). Pros: free, fast CPU inference, 768‑D vectors, permissive license.
- **Similarity:** cosine (vectors can be L2‑normalized downstream).
- **Chunking:** `W=1200`, `O=200` (≈17% overlap). Good balance between recall & compute.
- **Why not shift by 1?** creates tens of thousands of near‑duplicate chunks per doc with negligible recall gain; index size and runtime explode.

---

## Running the CLI

### Health check

```bash
python coordinator/ping.py
```

### Ingest one PDF

```bash
python coordinator/batch_ingest.py "/abs/path/to/file.pdf" nomic-embed-text 1200 200 out
```

### Verify output

```bash
ls -1 out/*.jsonl | wc -l
head -n 1 out/<job_id>.jsonl | jq .
```

### Analytics

```bash
python analytics/build_vocab_and_neighbors.py --input 'out/*.jsonl' --min_df 5 --topk 10 --dump_yaml
```

---

## Tests (how graders can reproduce 5/5)

There are **5 tests**. Two are “integration” and hit live services (Ollama/CORBA).

- **Local unit-only** (no services needed):  
  ```bash
  pytest -q
  ```

- **Full suite (5 passed)**: start NameService and Worker (as above), then:  
  ```bash
  export RUN_INTEGRATION=1
  export OLLAMA_HOST="http://127.0.0.1:11434"
  export EMBED_DIM=768        # matches nomic-embed-text JSONLs
  pytest -q
  ```

> If the Worker isn’t up, the CORBA test will **skip**. To get **5/5 passed**, ensure the Worker is running and reachable before running `pytest`.

---

## Example Results (from my runs)

- PDFs processed: **608**  
- JSONL artifacts: **608** (one per paper; corrupt partials salvaged & removed)  
- Total chunks: **147,507**  
- Embedding dimension: **768**  
- Analytics artifacts written to `analytics/`  
  - `vocab.csv`, `vocab.yaml` — vocab (min_df=5, cap=5000)  
  - `token_vecs.npy` — (V×768) token vectors  
  - `nn.csv` — nearest neighbors  
  - `similarity_scorecard.md` — qualitative checks

> These numbers reflect one full‑corpus EC2 run and serve as a reference. Your local counts will differ if you ingest a subset.

---

## Troubleshooting & Tips

- **`omniORB.CORBA.TRANSIENT ConnectFailed`**  
  Worker isn’t up or can’t reach NameService; (re)start both and retry:  
  ```bash
  pkill -f omniNames || true
  pkill -f 'worker/server.py' || true
  omniNames -start -always -logdir . &
  PYTHONPATH=$PWD python worker/server.py &
  python coordinator/ping.py
  ```

- **Port 2809 busy**  
  ```bash
  sudo fuser -k 2809/tcp
  ```

- **Ollama not reachable / empty vectors**  
  Confirm service + model:  
  ```bash
  systemctl is-active ollama   # or ensure `ollama serve &` on WSL
  ollama list | grep nomic-embed-text
  curl -s $OLLAMA_HOST/api/tags | jq .
  ```

- **`pdfminer` slow or stuck**  
  ```bash
  export RAG_EXTRACTOR=poppler
  ```

- **Partial JSON line (truncated write)**  
  Re‑run the single file; JSONL is line‑oriented, prior lines remain valid.

- **Wrong glob**  
  Use `*.pdf` to capture the whole corpus; avoid `1083142*.pdf` unless testing a tiny subset.

---

## Submission Checklist

- ✅ This **README.md** (instructions + design + deployment + results + limitations)  
- ✅ Source code (worker, coordinator, analytics, config, logging)  
- ✅ A **video link** (in the next section) that shows: repo → EC2 → start worker → process one PDF → inspect JSONL → run analytics → show NN scorecard. Introduce yourself on camera, show your AWS console username (top‑right).  
- ✅ Example outputs: representative `out/*.jsonl` + `analytics/*`  
- ✅ Tests passing locally (`pytest -q`; with Worker up: `RUN_INTEGRATION=1 pytest -q`)  
- ✅ `.gitignore` excludes heavy artifacts (`out/`, `analytics/*.npy`, logs, `__pycache__/`, `.venv/`)

---

## Video Link

> **YouTube:** https://youtu.be/JWiFTtGN7BE

---

## License

This coursework is intended for academic grading in CS441. Please keep this repository **private** until grading is complete.

