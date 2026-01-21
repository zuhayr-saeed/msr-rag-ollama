#!/bin/bash
# =============================================================================
# GraphRAG Flink Pipeline Runner
# =============================================================================
# This script runs the Flink GraphRAG ingestion pipeline.
#
# Prerequisites:
#   1. Neo4j running on bolt://localhost:7687
#   2. Ollama running on http://localhost:11434 with llama3.2 model
#   3. Environment variables set (or use defaults)
#
# Usage:
#   ./scripts/run-pipeline.sh [--with-llm] [--chunk-file <path>]
#
# =============================================================================

set -e

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Script directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

# Default values
ENABLE_LLM=${ENABLE_LLM:-"false"}
MSR_CHUNK_FILE=${MSR_CHUNK_FILE:-""}
NEO4J_URI=${NEO4J_URI:-"bolt://localhost:7687"}
NEO4J_USER=${NEO4J_USER:-"neo4j"}
NEO4J_PASS=${NEO4J_PASS:-"test123"}
OLLAMA_ENDPOINT=${OLLAMA_ENDPOINT:-"http://localhost:11434"}
OLLAMA_MODEL=${OLLAMA_MODEL:-"llama3.2:latest"}

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --with-llm)
            ENABLE_LLM="true"
            shift
            ;;
        --chunk-file)
            MSR_CHUNK_FILE="$2"
            shift 2
            ;;
        -h|--help)
            echo "Usage: $0 [--with-llm] [--chunk-file <path>]"
            echo ""
            echo "Options:"
            echo "  --with-llm        Enable LLM relation scoring (requires Ollama)"
            echo "  --chunk-file      Path to custom chunk TSV file"
            echo ""
            echo "Environment variables:"
            echo "  NEO4J_URI         Neo4j Bolt URI (default: bolt://localhost:7687)"
            echo "  NEO4J_USER        Neo4j username (default: neo4j)"
            echo "  NEO4J_PASS        Neo4j password (default: test123)"
            echo "  OLLAMA_ENDPOINT   Ollama API endpoint (default: http://localhost:11434)"
            echo "  OLLAMA_MODEL      Ollama model name (default: llama3.2:latest)"
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            exit 1
            ;;
    esac
done

echo -e "${GREEN}============================================${NC}"
echo -e "${GREEN}     GraphRAG Flink Pipeline Runner${NC}"
echo -e "${GREEN}============================================${NC}"
echo ""

# Check Neo4j connectivity
echo -e "${YELLOW}Checking Neo4j connectivity...${NC}"
if command -v nc &> /dev/null; then
    if nc -z localhost 7687 2>/dev/null; then
        echo -e "${GREEN}✓ Neo4j is reachable at $NEO4J_URI${NC}"
    else
        echo -e "${RED}✗ Neo4j is not reachable at $NEO4J_URI${NC}"
        echo "Please start Neo4j: docker run -d --name neo4j -p 7474:7474 -p 7687:7687 -e NEO4J_AUTH=neo4j/$NEO4J_PASS neo4j:5.25.0-community"
        exit 1
    fi
else
    echo -e "${YELLOW}Warning: 'nc' not found, skipping connectivity check${NC}"
fi

# Check Ollama if LLM is enabled
if [ "$ENABLE_LLM" = "true" ]; then
    echo -e "${YELLOW}Checking Ollama connectivity...${NC}"
    if curl -s "$OLLAMA_ENDPOINT/api/tags" > /dev/null 2>&1; then
        echo -e "${GREEN}✓ Ollama is reachable at $OLLAMA_ENDPOINT${NC}"
    else
        echo -e "${RED}✗ Ollama is not reachable at $OLLAMA_ENDPOINT${NC}"
        echo "Please start Ollama: ollama serve"
        exit 1
    fi
fi

# Display configuration
echo ""
echo -e "${YELLOW}Configuration:${NC}"
echo "  NEO4J_URI:       $NEO4J_URI"
echo "  NEO4J_USER:      $NEO4J_USER"
echo "  ENABLE_LLM:      $ENABLE_LLM"
echo "  OLLAMA_ENDPOINT: $OLLAMA_ENDPOINT"
echo "  OLLAMA_MODEL:    $OLLAMA_MODEL"
echo "  MSR_CHUNK_FILE:  ${MSR_CHUNK_FILE:-"(using sample data)"}"
echo ""

# Change to project directory
cd "$PROJECT_ROOT"

# Export environment variables
export NEO4J_URI NEO4J_USER NEO4J_PASS OLLAMA_ENDPOINT OLLAMA_MODEL ENABLE_LLM MSR_CHUNK_FILE

# Run the Flink pipeline
echo -e "${YELLOW}Starting Flink GraphRAG pipeline...${NC}"
echo ""

sbt "ingestion/runMain edu.yourorg.msr.graphrag.ingestion.FlinkGraphRagJob"

echo ""
echo -e "${GREEN}✓ Pipeline completed successfully!${NC}"

