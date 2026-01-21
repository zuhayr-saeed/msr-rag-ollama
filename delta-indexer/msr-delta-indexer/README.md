# HW2 — Delta Indexer (MSR Corpus)

> **Goal:** Build a small, reproducible RAG-style “delta indexer” that:
> 1) converts PDFs → normalized text → fixed-size chunks,  
> 2) detects changes by **content hashes**,  
> 3) embeds **only missing** chunks via **Ollama**, and  
> 4) publishes a **versioned retrieval index** in PostgreSQL that a tiny CLI can query semantically.

---

## Contents

- [Architecture & Components](#architecture--components)
- [Data Model (PostgreSQL)](#data-model-postgresql)
- [Prerequisites](#prerequisites)
- [Setup (one-time)](#setup-one-time)
- [Run the Pipeline (per change cycle)](#run-the-pipeline-per-change-cycle)
- [Verifying & Inspecting the Index](#verifying--inspecting-the-index)
- [Search CLI (semantic retrieval)](#search-cli-semantic-retrieval)
- [Demonstrating “Delta” Behavior](#demonstrating-delta-behavior)
- [How the Data Is Partitioned](#how-the-data-is-partitioned)
- [Inputs & Outputs](#inputs--outputs)
- [Troubleshooting](#troubleshooting)
- [Submitting to GitHub](#submitting-to-github)

---

## Architecture & Components

**Flow (simplified)**

PDFs (RAG_INPUT_DIR)
│
▼
[Normalize] -> clean text per PDF (Apache Tika + UIMA)
│
▼
[Chunk] -> fixed-size segments w/ gentle sentence-aware cut
│ └─ per-chunk content_hash
▼
[Delta] -> only new/changed docs+chunks hit the DB
│
├──► rag.documents / rag.chunks
│
└─► [Backfill Embeddings] (Ollama: mxbai-embed-large)
└─► rag.embeddings
│
▼
[Publish Versioned Retrieval Index]
└─► rag.index_versions + rag.retrieval_index
└─► view rag.retrieval_index_current


**Main programs (SBT `runMain …`):**
- `edu.yourorg.msr.RunDeltaOnce` — normalize + chunk + **delta-write** to DB (documents & chunks).
- `edu.yourorg.msr.embed.BackfillEmbeddings` — find **missing vectors** and embed in batches via Ollama.
- `edu.yourorg.msr.index.PublishIndex` — materialize a **versioned** retrieval index (blue/green style).
- `edu.yourorg.msr.search.SearchCli` — embed a query with Ollama and rank chunks by cosine similarity.

> The package prefix `edu.yourorg` is just a placeholder-style namespace. It does **not** affect functionality.

---

## Data Model (PostgreSQL)

Created under schema `rag`:

- `rag.documents(doc_id, uri, title, language, content_hash, updated_at)`
- `rag.chunks(chunk_id, doc_id, chunk_ix, start_pos, end_pos, section_path, text, content_hash, updated_at)`
- `rag.embeddings(chunk_id, content_hash, embedder, embedder_ver, vector BYTEA, updated_at, PRIMARY KEY (chunk_id, embedder, embedder_ver))`
- `rag.index_versions(version_id, created_at, embedder, embedder_ver, notes)`
- `rag.retrieval_index(version_id, chunk_id, doc_id, chunk_ix, section_path, text, title, language, embedder, embedder_ver, vector, content_hash, created_at, PRIMARY KEY(version_id, chunk_id, embedder, embedder_ver))`
- View: `rag.retrieval_index_current` → always points to the **latest** version in `rag.index_versions`.

**Embeddings**
- Stored as **little-endian float32** bytes (`BYTEA`), easy to swap models/versions without rewriting text.

---

## Prerequisites

- **WSL2 Ubuntu** (or native Linux/macOS). On Windows, use WSL paths (e.g., `/mnt/c/Users/<you>/Downloads/MSRCorpus`).
- **Java**: JDK 17+ (tested with Java 21).
- **SBT**: 1.11.x
- **Docker**: for PostgreSQL.
- **Ollama**: running locally with `mxbai-embed-large` pulled.

---

## Setup (one-time)

> **Corpus path** (example from this project):
> ```
> C:\Users\Zuhayr\Downloads\MSRCorpus
> ```
> **WSL path** to the same folder:
> ```
> /mnt/c/Users/Zuhayr/Downloads/MSRCorpus
> ```

1) **Clone / enter the project**

```bash
git clone <your-repo-url> msr-delta-indexer
cd msr-delta-indexer

    Start PostgreSQL (Docker)

docker run --name rag-pg \
  -e POSTGRES_PASSWORD=rag -e POSTGRES_USER=rag -e POSTGRES_DB=rag \
  -p 5432:5432 -d postgres:16

    Pull the embed model (Ollama)

# Install Ollama from https://ollama.com if needed, then:
ollama pull mxbai-embed-large

    Create .env

    Keep secrets out of git; commit a .env.example instead.

cat > .env <<'ENV'
# Corpus
RAG_INPUT_DIR="/mnt/c/Users/Zuhayr/Downloads/MSRCorpus"

# Postgres (Docker)
RAG_DB_URL="jdbc:postgresql://127.0.0.1:5432/rag"
RAG_DB_USER="rag"
RAG_DB_PASS="rag"

# Embeddings (Ollama)
RAG_EMBED_URL="http://127.0.0.1:11434/api/embed"
RAG_EMBEDDER="mxbai-embed-large"
RAG_EMBEDDER_VER="1.3.0"
RAG_BATCH_SIZE=64
ENV

Load env for the current shell:

set -a; source .env; set +a

    Initialize DB schema

./scripts/01_init_db.sh

    Build once to pull dependencies

sbt clean compile

Run the Pipeline (per change cycle)

    Each change cycle is: delta → backfill embeddings → publish.

# 1) Docs → Chunks (delta-only writes)
./scripts/02_delta.sh

# 2) Embeddings for any missing chunks
./scripts/03_backfill.sh

# 3) Publish a new version of the retrieval index
./scripts/04_publish.sh

You can search any time:

./scripts/05_search.sh "mining software repositories"

Verifying & Inspecting the Index

Counts

# total rows across all versions (grows with every publish)
docker exec -e PGPASSWORD=rag -it rag-pg \
  psql -U rag -d rag -c "select count(*) as total_rows from rag.retrieval_index;"

# rows in the *current* (latest) version
docker exec -e PGPASSWORD=rag -it rag-pg \
  psql -U rag -d rag -c "select count(*) as current_rows from rag.retrieval_index_current;"

Per-version breakdown (newest first)

docker exec -e PGPASSWORD=rag -it rag-pg psql -U rag -d rag -c "
  select version_id, embedder, embedder_ver, count(*) rows
    from rag.retrieval_index
group by 1,2,3
order by min(created_at) desc;"

Sanity-check document/chunk/embedding counts

docker exec -e PGPASSWORD=rag -it rag-pg psql -U rag -d rag -c "select count(*) as docs from rag.documents;"
docker exec -e PGPASSWORD=rag -it rag-pg psql -U rag -d rag -c "select count(*) as chunks from rag.chunks;"
docker exec -e PGPASSWORD=rag -it rag-pg psql -U rag -d rag -c "select count(*) as embeddings from rag.embeddings;"

Search CLI (semantic retrieval)

What it does

    Embeds your query with the same embedder/URL from .env.

    Loads all vectors from rag.retrieval_index_current.

    Ranks by cosine similarity and prints the top-k snippets.

Run

# Ensure env is loaded in the current shell
set -a; source .env; set +a

# Example query
sbt 'runMain edu.yourorg.msr.search.SearchCli "mining software repositories"'

You’ll see top results with: score, title, chunk id, and a snippet.
Demonstrating “Delta” Behavior

Add a new file (new URI → new doc/chunks → new embeddings → higher version count):

# copy any existing PDF to a *new* filename
cp "$RAG_INPUT_DIR/1083142.1083143.pdf" "$RAG_INPUT_DIR/0000_newcopy.pdf"

./scripts/02_delta.sh
./scripts/03_backfill.sh
./scripts/04_publish.sh

Edit same URI (overwrite existing path; new content hash → new chunks; embeddings backfill only the new ones):

# overwrite one existing PDF with a different file
cp "$RAG_INPUT_DIR/1083142.1083144.pdf" "$RAG_INPUT_DIR/1083142.1083143.pdf"

./scripts/02_delta.sh
./scripts/03_backfill.sh
./scripts/04_publish.sh

No work run (no changes → delta writes nothing; backfill finds 0; publish still produces a new version with the same row count):

./scripts/02_delta.sh
./scripts/03_backfill.sh
./scripts/04_publish.sh

How the Data Is Partitioned

Corpus partition (documents):

    For demonstration speed, the example apps process a small subset of the corpus during runs (e.g., the first 10 PDFs under RAG_INPUT_DIR by path order).

    You can expand to more or all PDFs by adjusting the selection logic in the small runner apps (e.g., RunDeltaOnce.scala).

Chunk partition (within each document):

    Each normalized document is split into fixed-size chunks with a target ~1400 characters.

    At boundaries we attempt a gentle cut (prefer whitespace or sentence-ending punctuation near the window end: . ! ?) to avoid mid-word splits.

    For each chunk we store:

        chunk_ix: 0-based chunk number

        start_pos / end_pos: byte/char offsets within the normalized document text

        text: the chunk’s substring

        content_hash: chunk-level content hash (drives delta behavior)

Embedding partition:

    Each chunk gets exactly one vector per (embedder, embedder_ver). Missing vectors are backfilled in batches (RAG_BATCH_SIZE) via Ollama.

Versioned publish:

    Every publish creates a new version_id in rag.index_versions and writes a snapshot of all chunk rows (joined with their doc metadata and matching embeddings) to rag.retrieval_index.

    The view rag.retrieval_index_current always points at the latest version.

Inputs & Outputs

Inputs

    RAG_INPUT_DIR: directory of PDFs (MSR corpus path in WSL style).

    Environment variables in .env (DB connection + embedder config).

Outputs

    Database rows:

        rag.documents — 1 per document (unique by doc_id). content_hash changes when the file content changes.

        rag.chunks — multiple rows per document (fixed-size segments). content_hash follows the chunk text.

        rag.embeddings — one vector per chunk per embedder+version (stored as BYTEA little-endian float32).

        rag.retrieval_index — versioned snapshot of chunks joined with doc metadata + embeddings.

        rag.retrieval_index_current — view to the latest version.

    Console:

        RunDeltaOnce prints processed docs, skipped (same), and chunks written.

        BackfillEmbeddings prints number of missing vectors and progress.

        PublishIndex prints Inserted X rows for the new version_id.

        SearchCli prints top results with scores and snippets.

Troubleshooting

    Ollama not found / HTTP error → ensure ollama serve is running and ollama pull mxbai-embed-large completed.
    Check: curl -s http://127.0.0.1:11434/api/tags.

    DB connection errors → docker ps | grep rag-pg and verify RAG_DB_URL is jdbc:postgresql://127.0.0.1:5432/rag.

    WSL path issues → always use /mnt/c/... inside WSL (not C:\...).

    Logging warning (Log4j2 could not find a logging implementation) → mitigated by adding log4j-to-slf4j (already included).
