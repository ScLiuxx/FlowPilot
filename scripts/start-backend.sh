#!/usr/bin/env bash
# Starts the FlowPilot backend against the local infrastructure.
set -u

BACKEND_DIR=${BACKEND_DIR:-/workspace/backend}
JAR="$BACKEND_DIR/target/flowpilot-backend.jar"
LOG=${LOG:-/tmp/flowpilot-logs/backend.log}
PROXY_HOST=${PROXY_HOST:-127.0.0.1}
PROXY_PORT=${PROXY_PORT:-18080}

mkdir -p "$(dirname "$LOG")"

# Stop a previous instance. The pattern avoids matching this script itself.
PIDS=$(ps -eo pid,args | grep '[f]lowpilot-backend.jar' | awk '{print $1}')
if [ -n "$PIDS" ]; then
  echo "stopping the previous backend: $PIDS"
  kill -9 $PIDS 2>/dev/null
  sleep 3
fi

if [ ! -f "$JAR" ]; then
  echo "the jar is missing, build it first: (cd $BACKEND_DIR && mvn -B -q package -DskipTests)"
  exit 1
fi

cd "$BACKEND_DIR" || exit 1
# The token of the GitHub API raises the rate limit. Without it the public
# limit applies and the collection nodes fail with a rate limit error.
setsid nohup env \
  GITHUB_TOKEN="${GITHUB_TOKEN:-${GH_TOKEN:-}}" \
  java \
  -Dhttp.proxyHost="$PROXY_HOST" \
  -Dhttp.proxyPort="$PROXY_PORT" \
  -jar "$JAR" "$@" > "$LOG" 2>&1 < /dev/null &

echo "the backend is starting, the log is at $LOG"
for i in $(seq 1 60); do
  if curl -fsS http://127.0.0.1:8080/actuator/health >/dev/null 2>&1; then
    echo "the backend is healthy"
    exit 0
  fi
  sleep 2
done
echo "the backend did not become healthy, the last log lines:"
tail -20 "$LOG"
exit 1
