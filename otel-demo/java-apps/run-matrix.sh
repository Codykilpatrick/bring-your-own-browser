#!/usr/bin/env bash
# Runs the attach matrix end to end: starts every compose service, drives each app through its
# scenario (all in parallel, each in its own X display), then runs verify.sh against OpenObserve.
#
#   ./run-matrix.sh             # the five plain runs
#   ./run-matrix.sh --with-otel # plus fire-control with the official OTel javaagent loaded too
#
# Needs the collector reachable at UI_AGENT_ENDPOINT (default http://host.docker.internal:4318,
# i.e. a kubectl port-forward of the proxy's 4318 on this machine) and OpenObserve on :5080.
set -euo pipefail
cd "$(dirname "$0")"
OTEL_AGENT_VERSION=2.33.1
services=(sonar-jdk7-a sonar-jdk6-b sonar-jdk8-c fc-jdk8fx-b fc-jdk17fx-c)
envs=(jdk7-a jdk6-b jdk8-c jdk8fx-b jdk17fx-c)
profile=()
if [ "${1:-}" = "--with-otel" ]; then
  if [ ! -f ../ui-agent/dist/opentelemetry-javaagent.jar ]; then
    echo "downloading opentelemetry-javaagent $OTEL_AGENT_VERSION"
    curl -sSL -o ../ui-agent/dist/opentelemetry-javaagent.jar \
      "https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v$OTEL_AGENT_VERSION/opentelemetry-javaagent.jar"
  fi
  services+=(fc-jdk8fx-otel); envs+=(jdk8fx-otel); profile=(--profile otel)
fi

export SINCE=$(date +%s)
docker compose "${profile[@]}" down --remove-orphans >/dev/null 2>&1 || true
docker compose "${profile[@]}" up -d "${services[@]}"

# Mode C attaches 15 s after launch; give every app time to show its window and the agent to hook it.
echo "waiting 30 s for the apps to come up"; sleep 30

mkdir -p /tmp/java-apps-matrix
pids=()
for s in "${services[@]}"; do
  case $s in sonar-*) sc=sonar-console;; *) sc=fire-control;; esac
  docker compose "${profile[@]}" exec -T "$s" sh "/scenarios/$sc.sh" >"/tmp/java-apps-matrix/$s.log" 2>&1 &
  pids+=($!)
done
rc=0
for i in "${!pids[@]}"; do
  if wait "${pids[$i]}"; then echo "scenario ${services[$i]}: done"; else echo "scenario ${services[$i]}: FAILED (see /tmp/java-apps-matrix/${services[$i]}.log)"; rc=1; fi
done

# Let the agents' health tick (10 s) and the collector batch (up to 10 s) flush.
sleep 25
for s in "${services[@]}"; do
  docker compose "${profile[@]}" logs --no-log-prefix "$s" 2>/dev/null | grep -a '^\[ui-agent\]' >"/tmp/java-apps-matrix/$s.agent.log" || true
  printf '%-14s agent lines: %4s  failed posts: %s\n' "$s" "$(wc -l <"/tmp/java-apps-matrix/$s.agent.log" | tr -d ' ')" \
    "$(grep -c 'failed' "/tmp/java-apps-matrix/$s.agent.log" || true)"
done
./verify.sh "${envs[@]}" || rc=1
exit $rc
