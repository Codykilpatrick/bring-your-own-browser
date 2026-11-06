#!/bin/sh
# Attach mode (C): start the app untouched, wait, then load the agent into the running JVM.
# Usage: run-attach.sh java [args...]
delay=${ATTACH_DELAY:-15}
"$@" &
app=$!
sleep "$delay"
echo "[run-attach] attaching ui-agent to pid $app after ${delay}s"
java -jar /agent/ui-agent.jar attach "$app" "${UI_AGENT_ARGS:-}" || echo "[run-attach] attach failed"
wait $app
