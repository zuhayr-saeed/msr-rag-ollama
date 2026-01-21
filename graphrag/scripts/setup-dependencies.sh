#!/bin/bash
# =============================================================================
# GraphRAG Dependencies Setup
# =============================================================================
# This script helps set up Neo4j and Ollama for local development.
#
# Usage:
#   ./scripts/setup-dependencies.sh
#
# =============================================================================

set -e

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${GREEN}============================================${NC}"
echo -e "${GREEN}     GraphRAG Dependencies Setup${NC}"
echo -e "${GREEN}============================================${NC}"
echo ""

# Check Docker
echo -e "${YELLOW}Checking Docker...${NC}"
if command -v docker &> /dev/null; then
    echo -e "${GREEN}✓ Docker is installed${NC}"
    DOCKER_AVAILABLE=true
else
    echo -e "${RED}✗ Docker not found${NC}"
    echo "  Please install Docker Desktop and enable WSL2 integration"
    echo "  https://docs.docker.com/docker-for-windows/wsl/"
    DOCKER_AVAILABLE=false
fi

# Check Ollama
echo ""
echo -e "${YELLOW}Checking Ollama...${NC}"
if command -v ollama &> /dev/null; then
    echo -e "${GREEN}✓ Ollama is installed${NC}"
    OLLAMA_AVAILABLE=true
    
    # Check if model is available
    if ollama list 2>/dev/null | grep -q "llama3.2"; then
        echo -e "${GREEN}✓ llama3.2 model is available${NC}"
    else
        echo -e "${YELLOW}! llama3.2 model not found, pulling...${NC}"
        ollama pull llama3.2:3b
    fi
else
    echo -e "${RED}✗ Ollama not found${NC}"
    echo "  Install from: https://ollama.com/download"
    OLLAMA_AVAILABLE=false
fi

echo ""
echo -e "${BLUE}============================================${NC}"
echo -e "${BLUE}     Setup Instructions${NC}"
echo -e "${BLUE}============================================${NC}"
echo ""

# Neo4j setup instructions
echo -e "${YELLOW}1. Start Neo4j${NC}"
if [ "$DOCKER_AVAILABLE" = true ]; then
    echo "   Run this command to start Neo4j:"
    echo ""
    echo -e "${GREEN}   docker run -d \\\\
     --name neo4j \\\\
     -p 7474:7474 -p 7687:7687 \\\\
     -e NEO4J_AUTH=neo4j/test123 \\\\
     neo4j:5.25.0-community${NC}"
    echo ""
else
    echo "   Option A: Install Docker Desktop with WSL2 integration"
    echo "   Option B: Download Neo4j Community from https://neo4j.com/download/"
    echo ""
fi

# Ollama setup instructions
echo -e "${YELLOW}2. Start Ollama${NC}"
if [ "$OLLAMA_AVAILABLE" = true ]; then
    echo "   Ollama is already installed. Ensure it's running:"
    echo ""
    echo -e "${GREEN}   ollama serve${NC}"
    echo ""
else
    echo "   Install Ollama from: https://ollama.com/download"
    echo "   Then pull the model:"
    echo ""
    echo -e "${GREEN}   ollama pull llama3.2:3b${NC}"
    echo ""
fi

# Environment setup
echo -e "${YELLOW}3. Set Environment Variables${NC}"
echo "   Create a .env file or export these variables:"
echo ""
echo -e "${GREEN}   export NEO4J_URI=\"bolt://localhost:7687\"
   export NEO4J_USER=\"neo4j\"
   export NEO4J_PASS=\"test123\"
   export OLLAMA_ENDPOINT=\"http://localhost:11434\"
   export OLLAMA_MODEL=\"llama3.2:latest\"${NC}"
echo ""

# Run instructions
echo -e "${YELLOW}4. Run the Pipeline${NC}"
echo "   Basic run (uses sample data, no LLM):"
echo ""
echo -e "${GREEN}   ./scripts/run-pipeline.sh${NC}"
echo ""
echo "   With LLM relation scoring:"
echo ""
echo -e "${GREEN}   ./scripts/run-pipeline.sh --with-llm${NC}"
echo ""
echo "   With custom chunk file:"
echo ""
echo -e "${GREEN}   ./scripts/run-pipeline.sh --chunk-file data/msr_chunks.tsv${NC}"
echo ""

echo -e "${YELLOW}5. Run the API Server${NC}"
echo ""
echo -e "${GREEN}   ./scripts/run-api.sh${NC}"
echo ""

echo -e "${YELLOW}6. Test the API${NC}"
echo ""
echo -e "${GREEN}   # Health check
   curl http://localhost:8080/health

   # Query the graph
   curl -X POST http://localhost:8080/v1/query \\\\
     -H \"Content-Type: application/json\" \\\\
     -d '{\"query\": \"defect prediction techniques\"}'

   # Get graph stats
   curl http://localhost:8080/v1/graph/stats${NC}"
echo ""

echo -e "${GREEN}============================================${NC}"
echo -e "${GREEN}     Setup instructions complete!${NC}"
echo -e "${GREEN}============================================${NC}"

