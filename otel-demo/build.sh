#!/usr/bin/env bash
# Connected side: build the browser script and proxy image, pull the pinned images, and bundle
# them with the chart source for transfer into out/.
set -euo pipefail
cd "$(dirname "$0")"
PLATFORM=linux/amd64

rm -rf out && mkdir -p out
(cd bundle && npm ci --no-audit --no-fund && npm run build)

docker build --platform "$PLATFORM" -t localhost/otel-poc/proxy:0.1.1 -f proxy/Containerfile .
docker pull --platform "$PLATFORM" public.ecr.aws/zinclabs/openobserve:v1.0.4
docker pull --platform "$PLATFORM" docker.io/otel/opentelemetry-collector-contrib:0.161.0
docker tag public.ecr.aws/zinclabs/openobserve:v1.0.4 localhost/otel-poc/openobserve:v1.0.4
docker tag docker.io/otel/opentelemetry-collector-contrib:0.161.0 localhost/otel-poc/otelcol-contrib:0.161.0
docker save --platform "$PLATFORM" -o out/otel-poc-images.tar \
  localhost/otel-poc/proxy:0.1.1 localhost/otel-poc/openobserve:v1.0.4 localhost/otel-poc/otelcol-contrib:0.161.0

cp -R chart out/chart
cp README.md out/

# Java desktop agent: one jar for Java 6+, plus its install card.
(cd ui-agent && ./build.sh)
mkdir -p out/ui-agent
cp ui-agent/dist/ui-agent.jar ui-agent/README.md out/ui-agent/

(cd out && find otel-poc-images.tar chart README.md ui-agent -type f -exec sha256sum {} + > SHA256SUMS)
ls -lh out
