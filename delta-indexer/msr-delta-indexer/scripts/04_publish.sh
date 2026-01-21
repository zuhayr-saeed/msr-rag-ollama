#!/usr/bin/env bash
set -euo pipefail
set -a; source .env; set +a
sbt "runMain edu.yourorg.msr.index.PublishIndex"
