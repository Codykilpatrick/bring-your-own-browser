#!/usr/bin/env bash
# Unclass side: build the image for x86_64 and bundle everything for transfer into out/.
set -euo pipefail
cd "$(dirname "$0")"
IMAGE=localhost/chromium-gpu:latest
mkdir -p out/rpms

docker build --platform linux/amd64 -t "$IMAGE" -f Containerfile .
docker save -o out/chromium-gpu.tar "$IMAGE"

# nvidia-container-toolkit RPMs, in case highside doesn't have them.
docker run --rm --platform linux/amd64 -v "$PWD/out/rpms:/out" docker.io/rockylinux/rockylinux:9 bash -c '
  curl -fsSL https://nvidia.github.io/libnvidia-container/stable/rpm/nvidia-container-toolkit.repo \
    -o /etc/yum.repos.d/nvidia-container-toolkit.repo &&
  dnf install -y --downloadonly --downloaddir /out nvidia-container-toolkit'

cp run-chromium.sh out/
(cd out && sha256sum chromium-gpu.tar rpms/*.rpm run-chromium.sh > SHA256SUMS)
ls -lh out out/rpms
