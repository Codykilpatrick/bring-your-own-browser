#!/usr/bin/env bash
# Builds dist/ui-agent.jar in a container (no Java needed on this machine).
set -euo pipefail
cd "$(dirname "$0")"
rm -rf dist
docker build --platform linux/amd64 -f Containerfile --output type=local,dest=dist .
ls -l dist/ui-agent.jar dist/tools.jar dist/libattach.so
