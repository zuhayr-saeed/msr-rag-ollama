#!/bin/bash
# =============================================================================
# GraphRAG REST API Server Runner
# =============================================================================
# This script starts the GraphRAG REST API server.
#
# Prerequisites:
#   1. Neo4j running on bolt://localhost:7687
#
# Usage:
#   ./scripts/run-api.sh [--port <port>]
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
API_HOST=${API_HOST:-"0.0.0.0"}
API_PORT=${API_PORT:-"8080"}
NEO4J_URI=${NEO4J_URI:-"bolt://localhost:7687"}
NEO4J_USER=${NEO4J_USER:-"neo4j"}
NEO4J_PASS=${NEO4J_PASS:-"test123"}

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --port)
            API_PORT="$2"
            shift 2
            ;;
        -h|--help)
            echo "Usage: $0 [--port <port>]"
            echo ""
            echo "Options:"
            echo "  --port    API server port (default: 8080)"
            echo ""
            echo "Environment variables:"
            echo "  API_HOST      Server bind address (default: 0.0.0.0)"
            echo "  API_PORT      Server port (default: 8080)"
            echo "  NEO4J_URI     Neo4j Bolt URI (default: bolt://localhost:7687)"
            echo "  NEO4J_USER    Neo4j username (default: neo4j)"
            echo "  NEO4J_PASS    Neo4j password (default: test123)"
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            exit 1
            ;;
    esac
done

echo -e "${GREEN}============================================${NC}"
echo -e "${GREEN}     GraphRAG REST API Server${NC}"
echo -e "${GREEN}============================================${NC}"
echo ""

# Display configuration
echo -e "${YELLOW}Configuration:${NC}"
echo "  API_HOST:    $API_HOST"
echo "  API_PORT:    $API_PORT"
echo "  NEO4J_URI:   $NEO4J_URI"
echo "  NEO4J_USER:  $NEO4J_USER"
echo ""

# Change to project directory
cd "$PROJECT_ROOT"

# Export environment variables
export API_HOST API_PORT NEO4J_URI NEO4J_USER NEO4J_PASS

# Run the API server
echo -e "${YELLOW}Starting GraphRAG API server on http://$API_HOST:$API_PORT${NC}"
echo ""

sbt "api/runMain edu.yourorg.msr.graphrag.api.GraphRagApi"

