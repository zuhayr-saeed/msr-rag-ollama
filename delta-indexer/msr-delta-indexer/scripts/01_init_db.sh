#!/usr/bin/env bash
set -euo pipefail
set -a; source .env; set +a
docker ps --format '{{.Names}}' | grep -q '^rag-pg$' || \
docker run --name rag-pg -e POSTGRES_PASSWORD=$RAG_DB_PASS -e POSTGRES_USER=$RAG_DB_USER -e POSTGRES_DB=rag -p 5432:5432 -d postgres:16

# apply both schema files
docker exec -e PGPASSWORD=$RAG_DB_PASS -i rag-pg psql -U $RAG_DB_USER -d rag -v ON_ERROR_STOP=1 -f - < schema/schema.sql
docker exec -e PGPASSWORD=$RAG_DB_PASS -i rag-pg psql -U $RAG_DB_USER -d rag -v ON_ERROR_STOP=1 -f - < schema/02_retrieval_index.sql
