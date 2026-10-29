#!/usr/bin/env sh
# Development: backend + frontend on http://localhost:${PORT:-8080} with a file-based H2 database.
# Usage: ./run.sh            (PORT=8081 ./run.sh to use another port)
set -e
cd "$(dirname "$0")/backend"
exec mvn -q -B spring-boot:run
