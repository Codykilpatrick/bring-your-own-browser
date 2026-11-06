#!/usr/bin/env bash
# Asks OpenObserve what each run of the attach matrix produced and prints one ok/FAIL line per
# check. Runs are told apart by deployment_environment_name (UI_AGENT_ENV in compose.yaml).
#
#   ./verify.sh                 every run in compose.yaml
#   ./verify.sh jdk7-a jdk6-b   just those
#   O2_URL=http://localhost:5080 O2_AUTH=admin@poc.local:ChangeMe-PoC-1 HOURS=2 ./verify.sh
#   SINCE=<epoch seconds> ./verify.sh   # only rows after that moment (run-matrix.sh uses this)
set -uo pipefail
cd "$(dirname "$0")"
O2_URL=${O2_URL:-http://localhost:5080}
O2_AUTH=${O2_AUTH:-admin@poc.local:ChangeMe-PoC-1}
HOURS=${HOURS:-3}
SINCE=${SINCE:-$(( $(date +%s) - HOURS * 3600 ))}
runs=("$@")
[ ${#runs[@]} -gt 0 ] || runs=(jdk7-a jdk6-b jdk8-c jdk8fx-b jdk17fx-c)
fails=0

# search <stream_type> <sql>: prints the hits as JSON lines
search() {
  local now=$(( $(date +%s) * 1000000 ))
  python3 - "$O2_URL" "$O2_AUTH" "$1" "$2" "$now" "$SINCE" <<'EOF'
import sys, json, base64, urllib.request
url, auth, stype, sql, now, since = sys.argv[1:7]
body = {"query": {"sql": sql, "start_time": int(since) * 10**6, "end_time": int(now), "from": 0, "size": 500}}
req = urllib.request.Request(f"{url}/api/default/_search?type={stype}", json.dumps(body).encode(),
    {"Content-Type": "application/json", "Authorization": "Basic " + base64.b64encode(auth.encode()).decode()})
try:
    for h in json.load(urllib.request.urlopen(req))["hits"]: print(json.dumps(h))
except Exception as e:
    print(json.dumps({"error": str(e)}))
EOF
}

count() { search "$1" "$2" | python3 -c 'import sys,json; rows=[json.loads(l) for l in sys.stdin]; print(rows[0].get("n", 0) if rows and "n" in rows[0] else 0)'; }

check() {   # check <name> <actual> <min>
  if [ "$2" -ge "$3" ] 2>/dev/null; then echo "ok    $1 ($2)"; else echo "FAIL  $1 ($2, wanted >= $3)"; fails=$((fails + 1)); fi
}

for run in "${runs[@]}"; do
  echo "## $run"
  env="deployment_environment_name = '$run'"; tenv="service_deployment_environment_name = '$run'"
  for ev in click change submit; do
    check "$run spans event_type=$ev" "$(count traces "SELECT count(*) AS n FROM \"default\" WHERE $tenv AND event_type = '$ev'")" 1
  done
  check "$run spans with page_path and target_text" "$(count traces "SELECT count(*) AS n FROM \"default\" WHERE $tenv AND page_path <> '' AND target_text <> ''")" 5
  check "$run ui_views type=view" "$(count logs "SELECT count(*) AS n FROM \"ui_views\" WHERE $env AND type = 'view'")" 3
  check "$run ui_views type=error (uncaught exception)" "$(count logs "SELECT count(*) AS n FROM \"ui_views\" WHERE $env AND type = 'error'")" 1
  check "$run java_logs kind=app_log" "$(count logs "SELECT count(*) AS n FROM \"java_logs\" WHERE $env AND kind = 'app_log'")" 1
  check "$run java_logs kind=stdout" "$(count logs "SELECT count(*) AS n FROM \"java_logs\" WHERE $env AND kind = 'stdout'")" 1
  check "$run java_logs kind=freeze" "$(count logs "SELECT count(*) AS n FROM \"java_logs\" WHERE $env AND kind = 'freeze'")" 1
  check "$run jvm_memory_used samples" "$(count metrics "SELECT count(*) AS n FROM \"jvm_memory_used\" WHERE $env")" 2
  check "$run ui_event_thread_freezes reported" "$(count metrics "SELECT count(*) AS n FROM \"ui_event_thread_freezes\" WHERE $env AND value >= 1")" 1
done

echo
echo "## pages and controls seen (all runs)"
search logs "SELECT service, view_url AS page, count(DISTINCT session_id) AS sessions FROM \"ui_views\" WHERE type = 'view' GROUP BY service, page ORDER BY service, page" \
  | python3 -c 'import sys,json; [print("  %-14s %-40s %s" % (r.get("service"), r.get("page"), r.get("sessions"))) for r in map(json.loads, sys.stdin)]'
search traces "SELECT service_name AS app, page_path, target_text, event_type, count(*) AS n FROM \"default\" WHERE event_type IN ('click','change','submit') GROUP BY app, page_path, target_text, event_type ORDER BY app, page_path, target_text" \
  | python3 -c 'import sys,json; [print("  %-14s %-32s %-22s %-7s %s" % (r.get("app"), r.get("page_path"), r.get("target_text"), r.get("event_type"), r.get("n"))) for r in map(json.loads, sys.stdin)]'

echo
if [ "$fails" -eq 0 ]; then echo "ok"; else echo "FAIL: $fails checks"; exit 1; fi
