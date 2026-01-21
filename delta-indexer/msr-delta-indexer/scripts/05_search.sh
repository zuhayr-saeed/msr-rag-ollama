#!/usr/bin/env bash
set -euo pipefail
set -a; source .env; set +a
if [ $# -lt 1 ]; then
  echo "Usage: scripts/05_search.sh \"your query\""; exit 1
fi
sbt "runMain edu.yourorg.msr.search.SearchCli $*"
