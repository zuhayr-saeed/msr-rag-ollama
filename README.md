# msr-rag-ollama

A monorepo for building and applying **Retrieval Augmented Generation (RAG)** with **Ollama** LLMs using **hundreds of MSR (Mining Software Repositories) conference PDF papers**.

## Repository layout

- **corba-rag/**  
  RAG implementation and experiments related to the CORBA-focused project.

- **delta-indexer/**  
  Indexing + preprocessing pipeline (PDF ingestion, chunking, embeddings, vector store, etc.).

- **graphrag/**  
  GraphRAG-style retrieval and related experiments.

Each subproject contains its own `README.md` with setup and usage details.
