#!/usr/bin/env bash
# Starts the FlowPilot agent service against the local infrastructure.
set -u

AGENT_DIR=${AGENT_DIR:-/workspace/agent-service}
LOG=${LOG:-/tmp/flowpilot-logs/agent.log}
VENV="$AGENT_DIR/.venv"

mkdir -p "$(dirname "$LOG")"

PIDS=$(ps -eo pid,args | grep '[a]pp.main:app' | awk '{print $1}')
if [ -n "$PIDS" ]; then
  echo "stopping the previous agent service: $PIDS"
  kill -9 $PIDS 2>/dev/null
  sleep 2
fi

if [ ! -x "$VENV/bin/python" ]; then
  echo "the virtual environment is missing, create it first"
  exit 1
fi

cd "$AGENT_DIR" || exit 1
# The broker and the database are addressed by their url, so the script can be
# pointed at another infrastructure, for example at the containers of the
# compose file.
# The token of the GitHub API is inherited from the environment: without it the
# public rate limit applies and the analysis fails with a rate limit error.
setsid nohup env \
  RABBITMQ_URL="${RABBITMQ_URL:-amqp://flowpilot:flowpilot@127.0.0.1:5672/}" \
  POSTGRES_DSN="${POSTGRES_DSN:-postgresql://flowpilot:flowpilot@127.0.0.1:5432/flowpilot_agent}" \
  REDIS_URL="${REDIS_URL:-redis://127.0.0.1:6379/0}" \
  GITHUB_TOKEN="${GITHUB_TOKEN:-${GH_TOKEN:-}}" \
  HTTP_PROXY="${HTTP_PROXY:-}" \
  HTTPS_PROXY="${HTTPS_PROXY:-}" \
  INTERNAL_API_TOKEN="${INTERNAL_API_TOKEN:-}" \
  "$VENV/bin/python" -m uvicorn app.main:app --host 127.0.0.1 --port 8000 \
  > "$LOG" 2>&1 < /dev/null &

echo "the agent service is starting, the log is at $LOG"
for i in $(seq 1 45); do
  if curl -fsS http://127.0.0.1:8000/health >/dev/null 2>&1; then
    echo "the agent service is healthy"
    exit 0
  fi
  sleep 2
done
echo "the agent service did not become healthy, the last log lines:"
tail -25 "$LOG"
exit 1
