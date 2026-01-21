# CS 441 Homework 3: GraphRAG Pipeline

**Author:** Zuhayr Saeed  
**Email:** zsaee2@uic.edu  
**Course:** CS 441 - Engineering Distributed Objects for Cloud Computing  
**University:** University of Illinois at Chicago

---

## Table of Contents

1. [Overview](#overview)
2. [Architecture](#architecture)
3. [Project Structure](#project-structure)
4. [Data Model](#data-model)
5. [Prerequisites](#prerequisites)
6. [Quick Start](#quick-start)
7. [Running the Pipeline](#running-the-pipeline)
8. [REST API Endpoints](#rest-api-endpoints)
9. [Configuration](#configuration)
10. [Testing](#testing)
11. [AWS EKS Deployment](#aws-eks-deployment)
12. [Design Rationale](#design-rationale)
13. [Limitations](#limitations)
14. [Video Demo](#video-demo)

---

## Overview

This project implements a **scalable GraphRAG (Graph Retrieval Augmented Generation) pipeline** that:

1. **Streams indexed document chunks** through Apache Flink DataStream
2. **Extracts concepts** using heuristic methods (NER, keyphrases, patterns)
3. **Generates relation candidates** from co-occurrence windows and cue patterns
4. **Scores relations with Ollama LLM** using structured JSON prompts
5. **Constructs a knowledge graph** with versioned concept and chunk nodes
6. **Atomically upserts** the graph into Neo4j with idempotent MERGE semantics
7. **Exposes RESTful APIs** for querying dependencies, evidence, and graph exploration

The system processes the MSRCorpus dataset (Mining Software Repositories conference papers) and builds a queryable knowledge graph where:
- **Nodes** represent concepts (techniques, datasets, methods) and text chunks
- **Edges** represent semantic relations (`is_a`, `causes`, `part_of`, `synonym_of`, `related_to`) and co-occurrences

### Connection to HW1 and HW2

This homework is the natural capstone after Homeworks 1 and 2:
- **HW1** proved you can build a robust batch indexer using CORBA
- **HW2** proved you can keep that index fresh using PostgreSQL with delta updates
- **HW3** lifts the abstraction from `documents → index` to `(documents+index) → knowledge graph`

We reuse the same disciplined ingestion, versioning, and atomic publish patterns, but extend them with relation extraction (rules + Ollama), fusion, and idempotent upserts into Neo4j, all orchestrated by Flink.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          GraphRAG Pipeline                                   │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  [S3/Index Store] --> [Flink Source]                                        │
│                            |                                                 │
│                            v                                                 │
│                      [Chunk Stream]──────────────────────.                   │
│                            |                              |                  │
│                            v                              |                  │
│                   [Concept Extraction]                    |                  │
│                     (Heuristic + Pattern)                 |                  │
│                            |                              |                  │
│                            v                              |                  │
│                 [Relation Candidates] <──────────────────'                   │
│                   (Co-occurrence + Cue patterns)                             │
│                            |                                                 │
│                            v                                                 │
│                 [LLM Scoring via Ollama]                                     │
│                   (Predicate + Confidence)                                   │
│                            |                                                 │
│                            v                                                 │
│                    [Graph Projection]                                        │
│                 (UpsertNode, UpsertEdge)                                     │
│                            |                                                 │
│                            v                                                 │
│                      [Neo4j Sink]                                            │
│                   (Idempotent MERGE)                                         │
│                            |                                                 │
│                            v                                                 │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │                         REST API Server                               │   │
│  │  POST /v1/query          - Semantic query against knowledge graph     │   │
│  │  GET  /v1/evidence/:id   - Retrieve evidence snippet                  │   │
│  │  GET  /v1/graph/concept/:id/neighbors - Explore graph neighborhood    │   │
│  │  GET  /v1/graph/stats    - Graph statistics                           │   │
│  │  GET  /health            - Health check                               │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Project Structure

```
graphrag/
├── build.sbt                  # SBT multi-module build configuration
├── README.md                  # This documentation
├── data/
│   └── chunks.tsv             # Sample chunk data for testing
├── scripts/
│   ├── run-pipeline.sh        # Run the Flink ingestion pipeline
│   ├── run-api.sh             # Run the REST API server
│   └── setup-dependencies.sh  # Setup Neo4j and Ollama
├── deploy/                    # Kubernetes/EKS deployment configs
│   ├── namespace.yaml
│   ├── neo4j-deployment.yaml
│   ├── ollama-daemonset.yaml
│   ├── job-graph-rag.yaml
│   ├── graphrag-api-deployment.yaml
│   ├── flink-values.yaml
│   └── application.conf
├── modules/
│   ├── core/                  # Core domain model (no external deps)
│   │   └── src/main/scala/edu/yourorg/msr/graphrag/core/
│   │       ├── model.scala    # Chunk, Concept, GraphWrite types
│   │       └── Logging.scala  # Logging trait
│   ├── ingestion/             # Flink streaming pipeline
│   │   └── src/main/scala/edu/yourorg/msr/graphrag/ingestion/
│   │       ├── FlinkGraphRagJob.scala      # Main Flink job
│   │       ├── ConceptExtractor.scala      # Heuristic + pattern extraction
│   │       ├── RelationStage.scala         # Co-occurrence + candidate gen
│   │       └── MSRCorpusProcessor.scala    # Chunk TSV generator
│   ├── neo4j/                 # Neo4j graph operations
│   │   └── src/main/scala/edu/yourorg/msr/graphrag/neo4j/
│   │       ├── GraphNeo4jClient.scala      # Driver wrapper
│   │       └── GraphUpsert.scala           # Cypher generation
│   ├── llm/                   # Ollama LLM integration
│   │   └── src/main/scala/edu/yourorg/msr/graphrag/llm/
│   │       ├── OllamaClient.scala          # HTTP client for Ollama API
│   │       └── OllamaRelationScorer.scala  # Prompt building + parsing
│   └── api/                   # REST API microservices
│       └── src/main/scala/edu/yourorg/msr/graphrag/api/
│           ├── GraphRagApi.scala           # Akka HTTP server
│           ├── QueryService.scala          # Semantic query handling
│           ├── EvidenceService.scala       # Evidence retrieval
│           └── ExploreService.scala        # Graph neighborhood exploration
└── docs/
```

---

## Data Model

### Node Types

| Label | Primary Key | Description |
|-------|-------------|-------------|
| `Chunk` | `chunkId` | Text segment from a document with provenance |
| `Concept` | `conceptId` | Semantic entity (technique, dataset, method) |

### Edge Types

| Type | From → To | Description |
|------|-----------|-------------|
| `MENTIONS` | Chunk → Concept | Chunk mentions a concept at specific span |
| `CO_OCCURS` | Concept → Concept | Concepts co-occur in same chunk (with frequency) |
| `RELATES_TO` | Concept → Concept | Semantic relation with predicate and confidence |

### Predicates for RELATES_TO

- `is_a` - Taxonomic relationship (X is a type of Y)
- `part_of` - Compositional relationship
- `causes` - Causal relationship
- `synonym_of` - Equivalent terms
- `related_to` - General association

### Identity and Deduplication

- `chunkId = sha256(docId:start:end:contentHash)` - Content-addressed
- `conceptId = concept:<normalized_lemma>` - Normalized lemma with optional disambiguator

This schema ensures:
- Stable IDs for idempotent upserts
- Safe replay without duplication
- Separation of text containers from semantic units
- Provenance tracking

---

## Prerequisites

### Local Development

- **JDK 17+** (tested with OpenJDK 17)
- **SBT 1.10+**
- **Docker** (for Neo4j)
- **Ollama** with `llama3.2` model

### Install Ollama

```bash
# Install Ollama (Linux/WSL)
curl -fsSL https://ollama.com/install.sh | sh

# Pull the model
ollama pull llama3.2:3b

# Start Ollama server
ollama serve
```

### Start Neo4j (Docker)

```bash
docker run -d \
  --name neo4j \
  -p 7474:7474 -p 7687:7687 \
  -e NEO4J_AUTH=neo4j/test123 \
  neo4j:5.25.0-community
```

---

## Quick Start

### 1. Clone and Build

```bash
git clone <repo-url> cs441-hw3-graphrag
cd cs441-hw3-graphrag

# Build the project
sbt clean compile

# Run all tests (70 tests)
sbt test
```

### 2. Set Environment Variables

```bash
export NEO4J_URI="bolt://localhost:7687"
export NEO4J_USER="neo4j"
export NEO4J_PASS="test123"
export OLLAMA_ENDPOINT="http://localhost:11434"
export OLLAMA_MODEL="llama3.2:latest"
```

### 3. Run the Pipeline

```bash
# Basic run (uses sample data, no LLM)
./scripts/run-pipeline.sh

# With LLM relation scoring (requires Ollama)
./scripts/run-pipeline.sh --with-llm

# With custom chunk file
./scripts/run-pipeline.sh --chunk-file data/chunks.tsv
```

### 4. Run the API Server

```bash
./scripts/run-api.sh
```

### 5. Test the API

```bash
# Health check
curl http://localhost:8080/health

# Query the knowledge graph
curl -X POST http://localhost:8080/v1/query \
  -H "Content-Type: application/json" \
  -d '{"query": "defect prediction techniques"}'

# Get graph statistics
curl http://localhost:8080/v1/graph/stats

# Explore concept neighbors
curl "http://localhost:8080/v1/graph/concept/concept:defect/neighbors?depth=1"
```

---

## Running the Pipeline

### Pipeline Stages

1. **Chunk Ingestion** - Reads chunks from TSV file or uses sample data
2. **Normalization** - Cleans whitespace, ensures language tags
3. **Concept Extraction** - Heuristic (technical terms, n-grams) + Pattern-based (cue patterns)
4. **Co-occurrence Building** - Pairs concepts appearing in same chunk
5. **Relation Scoring (Optional)** - LLM classifies relations with predicates
6. **Graph Projection** - Translates to UpsertNode/UpsertEdge operations
7. **Neo4j Sink** - Atomic MERGE with idempotent semantics

### Input Format

Tab-separated file with 10 columns:

```
chunkId  docId  chunkIx  startPos  endPos  sectionPath  text  sourceUri  contentHash  language
```

Example:
```
chunk:msr2020-001-0	doc:msr2020-001	0	0	450	abstract	Just-in-time defect prediction...	file:///msr2020/commit2vec.pdf	hash:abc123	en
```

### Processing MSRCorpus PDFs

To generate chunks from MSRCorpus PDFs:

```bash
# First extract text from PDFs (using HW1/HW2 tools or pdftotext)
# Then run the MSRCorpusProcessor
sbt "ingestion/runMain edu.yourorg.msr.graphrag.ingestion.MSRCorpusProcessor \
  --input /path/to/msr/texts \
  --output data/msr_chunks.tsv"
```

---

## REST API Endpoints

### QueryService

**POST /v1/query** - Submit semantic query against the knowledge graph

Request:
```json
{
  "query": "Since 2018, which techniques improved defect prediction on JITGIT?",
  "timeRange": {"from": 2018, "to": 2025},
  "constraints": {
    "datasets": ["JITGIT", "SEOSS-JIT"],
    "baselines": ["Random Forest"]
  },
  "output": {
    "groupBy": ["techFamily"],
    "topKPerGroup": 5,
    "includeCitations": true
  }
}
```

Response:
```json
{
  "mode": "sync",
  "summary": "Found 3 concepts matching your query...",
  "results": [{
    "conceptId": "concept:defect_prediction",
    "lemma": "defect prediction",
    "relatedConcepts": [
      {"conceptId": "concept:machine_learning", "predicate": "uses", "confidence": 0.85}
    ],
    "chunks": [{"chunkId": "chunk:...", "text": "...", "sourceUri": "..."}]
  }],
  "evidenceAvailable": true,
  "traceId": "trace-123"
}
```

### EvidenceService

**GET /v1/evidence/{evidenceId}** - Retrieve evidence snippet for a claim

Response:
```json
{
  "evidenceId": "evid:chunk-abc",
  "paperId": "doc:msr2020-001",
  "chunkId": "chunk-abc",
  "text": "Our model achieves AUC 0.81 on JITGIT...",
  "docRef": {
    "title": "CodeGraph-JIT",
    "year": 2022,
    "url": "file:///papers/msr2022-056.pdf"
  }
}
```

### ExploreService

**GET /v1/graph/concept/{conceptId}/neighbors** - Explore graph neighborhood

Query parameters:
- `direction` - "in", "out", or "both" (default: "both")
- `depth` - 1 to 3 (default: 1)
- `limit` - max nodes (default: 50)
- `edgeTypes` - comma-separated filter (e.g., "RELATES_TO,CO_OCCURS")

**GET /v1/graph/stats** - Get graph statistics

Response:
```json
{
  "totalConcepts": 1500,
  "totalChunks": 5000,
  "totalRelations": 2500,
  "totalMentions": 8000,
  "totalCoOccurs": 12000
}
```

### Health

**GET /health** - Health check endpoint

---

## Configuration

Configuration is loaded from `application.conf` with environment variable overrides:

```hocon
# Ollama LLM Configuration
ollama {
  endpoint = "http://localhost:11434"
  endpoint = ${?OLLAMA_ENDPOINT}
  model = "llama3.2:latest"
  model = ${?OLLAMA_MODEL}
  temperature = 0.0
  timeoutMs = 30000
}

# Neo4j Database Configuration
neo4j {
  uri = "bolt://localhost:7687"
  uri = ${?NEO4J_URI}
  user = "neo4j"
  user = ${?NEO4J_USER}
  passEnv = "NEO4J_PASS"
}

# Relation Extraction Configuration
relation {
  cooccur {
    window = 3
    minPmi = 0.2
  }
  llm {
    predicateSet = ["is_a", "part_of", "causes", "synonym_of", "related_to"]
    minConfidence = 0.65
  }
}

# GraphRAG API Configuration
graphrag {
  api {
    host = "0.0.0.0"
    port = 8080
  }
  flink {
    parallelism = 2
  }
}
```

---

## Testing

The project includes **70 unit tests** across all modules:

```bash
# Run all tests
sbt test

# Run specific module tests
sbt "core/test"
sbt "ingestion/test"
sbt "neo4j/test"
sbt "llm/test"
sbt "api/test"
```

### Test Coverage

| Module | Tests | Description |
|--------|-------|-------------|
| core | 10 | Domain model validation (Chunk, Concept, GraphWrite) |
| ingestion | 17 | Concept extraction, relation stages, co-occurrence |
| neo4j | 9 | Cypher generation, idempotent upserts |
| llm | 8 | LLM verdict parsing, prompt structure |
| api | 26 | Query/Evidence/Explore service JSON serialization |

### Sample Test Run

```
[info] ModelSpec:
[info] - Chunk should be created with all required fields
[info] - Chunk should handle optional fields as None
[info] - Concept should be created with proper structure
...
[info] Run completed in 3 seconds, 363 milliseconds.
[info] Total number of tests run: 70
[info] Tests: succeeded 70, failed 0, canceled 0
[info] All tests passed.
```

---

## AWS EKS Deployment

### 1. Create Namespace

```bash
kubectl apply -f deploy/namespace.yaml
```

### 2. Deploy Neo4j

```bash
kubectl apply -f deploy/neo4j-deployment.yaml
```

### 3. Deploy Ollama DaemonSet

```bash
kubectl apply -f deploy/ollama-daemonset.yaml
```

### 4. Install Flink Operator

```bash
helm repo add flink https://downloads.apache.org/flink/flink-kubernetes-operator-helm
helm install flink-operator flink/flink-kubernetes-operator \
  -n graphrag \
  -f deploy/flink-values.yaml
```

### 5. Deploy Flink Job

```bash
kubectl apply -f deploy/job-graph-rag.yaml
```

### 6. Deploy REST API

```bash
kubectl apply -f deploy/graphrag-api-deployment.yaml
```

---

## Design Rationale

### Why Flink?

- **Streaming**: Incremental processing as documents arrive
- **Backpressure**: Handles LLM latency spikes gracefully
- **Checkpointing**: Exactly-once semantics for reliability
- **Scalability**: Horizontal scaling on Kubernetes

### Why Neo4j?

- **Property Graph**: Native support for typed nodes and edges
- **Cypher**: Expressive query language for graph traversal
- **ACID**: Transactional guarantees for consistent writes
- **MERGE**: Idempotent upserts for safe replays

### Why Ollama?

- **Local Inference**: Low latency, no external API costs
- **Model Flexibility**: Switch models without code changes
- **Privacy**: Data stays within the cluster
- **JSON Mode**: Structured output for reliable parsing

### Idempotency

All writes use MERGE with content-addressed IDs:
- `chunkId = sha256(docId:start:end:contentHash)`
- `conceptId = concept:<normalized_lemma>`

This allows safe replays and incremental updates without duplication.

### Concept Extraction Strategy

We combine multiple strategies for high recall:
1. **Heuristic extraction** - Technical term detection, n-gram phrases (fast, deterministic)
2. **Pattern-based extraction** - Cue patterns like "X is a Y", "X causes Y" (precise)
3. **LLM refinement** - Semantic grouping and disambiguation (generalizes)

### Relation Scoring Prompt

The LLM receives a structured prompt and returns JSON:

```json
{
  "predicate": "causes",
  "confidence": 0.85,
  "evidence": "short supporting snippet",
  "ref": "docId:chunkId:span"
}
```

Temperature is set to 0 for deterministic classification.

---

## Limitations

1. **PDF Extraction**: The pipeline expects pre-extracted text in TSV format. Use HW1/HW2 tools or `pdftotext` to extract text from PDFs.

2. **Single-node Neo4j**: Current local deployment uses standalone Neo4j. For production, use Neo4j Cluster or Aura.

3. **LLM Throughput**: Ollama throughput depends on hardware. Consider GPU nodes for production.

4. **Memory**: Large chunk texts may exceed memory. Configure Flink taskmanager memory appropriately.

5. **No Incremental Updates**: Currently processes all chunks on each run. Could be enhanced with change detection using content hashes.

---

## Video Demo

> **YouTube:** [Link to be added]

The video demonstrates:
1. Building and testing the project
2. Starting Neo4j and Ollama
3. Running the Flink pipeline
4. Querying the REST API
5. Exploring the knowledge graph

---

## References

- [Apache Flink Documentation](https://flink.apache.org/docs/)
- [Neo4j Documentation](https://neo4j.com/docs/)
- [Ollama Documentation](https://ollama.ai/docs)
- *Build a Large Language Model (From Scratch)* - Sebastian Raschka
- *A Simple Guide to Retrieval Augmented Generation* - Abhinav Kimothi
- *Essential GraphRAG* - Tomaž Bratanič and Oskar Hane

---

## License

This project is submitted as coursework for CS 441 at UIC.
# cs441-graphrag-ingestion
# cs441-graphrag-ingestion
