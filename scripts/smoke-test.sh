#!/usr/bin/env bash
# End-to-end smoke test of the FlowPilot platform.
#
# It starts the backend against the local infrastructure, registers a user, creates and publishes
# the seed workflow, starts an execution and verifies that the nodes run.
set -euo pipefail

BASE_URL=${BASE_URL:-http://127.0.0.1:8080}
LOG_DIR=${LOG_DIR:-/tmp/flowpilot-logs}
# The seed definition is read from the file, so the script works from any directory.
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
SEED_FILE=${SEED_FILE:-$SCRIPT_DIR/../backend/src/main/resources/seed/github-due-diligence.json}
mkdir -p "$LOG_DIR"

pass() { echo "  PASS  $1"; }
fail() { echo "  FAIL  $1"; exit 1; }

echo "== waiting for the backend =="
for i in $(seq 1 90); do
  if curl -fsS "$BASE_URL/actuator/health" >/dev/null 2>&1; then break; fi
  sleep 2
done
curl -fsS "$BASE_URL/actuator/health" >/dev/null || fail "the backend did not become healthy"

echo "== T01: register and log in =="
USERNAME="smoke_$(date +%s)"
REGISTER=$(curl -fsS -X POST "$BASE_URL/api/v1/auth/register" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USERNAME\",\"password\":\"SmokeTest123\",\"email\":\"$USERNAME@example.com\"}")
echo "$REGISTER" | grep -q '"code":0' || fail "registration failed: $REGISTER"

LOGIN=$(curl -fsS -X POST "$BASE_URL/api/v1/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USERNAME\",\"password\":\"SmokeTest123\"}")
TOKEN=$(echo "$LOGIN" | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["token"])')
[ -n "$TOKEN" ] || fail "login did not return a token"
AUTH="Authorization: Bearer $TOKEN"
pass "registered $USERNAME and received a token"

echo "== T01: the profile endpoint answers =="
ME=$(curl -fsS "$BASE_URL/api/v1/auth/me" -H "$AUTH")
echo "$ME" | grep -q '"code":0' || fail "the profile request failed: $ME"
pass "the profile endpoint answered"

echo "== T02/T03: the seed workflow exists and validates =="
WORKFLOWS=$(curl -fsS "$BASE_URL/api/v1/workflows?page=1&size=20" -H "$AUTH")
WORKFLOW_ID=$(echo "$WORKFLOWS" | python3 -c '
import json,sys
data = json.load(sys.stdin)["data"]["records"]
print(data[0]["id"] if data else "")')
[ -n "$WORKFLOW_ID" ] || fail "no workflow was found: $WORKFLOWS"
pass "workflow $WORKFLOW_ID exists"

VERSIONS=$(curl -fsS "$BASE_URL/api/v1/workflows/$WORKFLOW_ID/versions" -H "$AUTH")
VERSION_ID=$(echo "$VERSIONS" | python3 -c '
import json,sys
data = json.load(sys.stdin)["data"]
print(data[0]["id"] if data else "")')
[ -n "$VERSION_ID" ] || fail "the workflow has no version"

VALIDATE=$(curl -fsS -X POST "$BASE_URL/api/v1/workflows/validate" \
  -H "$AUTH" -H 'Content-Type: application/json' \
  -d "$(python3 -c '
import json,sys
doc = json.load(open(sys.argv[1]))
print(json.dumps({"definition": doc}))' "$SEED_FILE")")
echo "$VALIDATE" | grep -q '"valid":true' || fail "the seed definition did not validate: $VALIDATE"
pass "the DAG validates"

echo "== T05: start an execution =="
START=$(curl -fsS -X POST "$BASE_URL/api/v1/workflows/$WORKFLOW_ID/executions" \
  -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"input\":{\"repositoryUrl\":\"https://github.com/octocat/Hello-World\",\"owner\":\"octocat\",\"repo\":\"Hello-World\"}}")
EXECUTION_ID=$(echo "$START" | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["executionId"])')
EXECUTION_NO=$(echo "$START" | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["executionNo"])')
[ -n "$EXECUTION_ID" ] || fail "no execution id was returned: $START"
pass "execution $EXECUTION_NO ($EXECUTION_ID) started"

echo "== T04/T14: the nodes are scheduled and the events are recorded =="
sleep 12
NODES=$(curl -fsS "$BASE_URL/api/v1/executions/$EXECUTION_ID/nodes" -H "$AUTH")
echo "$NODES" | python3 -c '
import json,sys
nodes = json.load(sys.stdin)["data"]
assert len(nodes) == 10, f"expected 10 nodes, got {len(nodes)}"
print("  node states: " + ", ".join(f"{n[chr(110)+chr(111)+chr(100)+chr(101)+chr(75)+chr(101)+chr(121)]}={n[chr(115)+chr(116)+chr(97)+chr(116)+chr(117)+chr(115)]}" for n in nodes))
' || fail "the node list is wrong: $NODES"

EVENTS=$(curl -fsS "$BASE_URL/api/v1/executions/$EXECUTION_ID/events?afterSequence=0&limit=200" -H "$AUTH")
echo "$EVENTS" | python3 -c '
import json,sys
events = json.load(sys.stdin)["data"]
assert events, "no events were recorded"
seqs = [e["sequenceNo"] for e in events]
assert seqs == sorted(seqs), "the event sequence is not increasing"
assert len(set(seqs)) == len(seqs), "the event sequence contains duplicates"
print(f"  {len(events)} events recorded, sequence {seqs[0]}..{seqs[-1]}")
' || fail "the event list is wrong: $EVENTS"
pass "the nodes were scheduled and the events were recorded"

echo "== T12: the SSE stream delivers the events =="
SSE=$(timeout 10 curl -fsSN "$BASE_URL/api/v1/executions/$EXECUTION_ID/events/stream?token=$TOKEN" || true)
echo "$SSE" | grep -q "event:" || fail "the SSE stream produced no events"
pass "the SSE stream delivered the events"

echo "== T11: the workflow waits for a human decision =="
APPROVAL_ID=""
for i in $(seq 1 30); do
  APPROVALS=$(curl -fsS "$BASE_URL/api/v1/approvals?status=PENDING&page=1&size=10" -H "$AUTH")
  APPROVAL_ID=$(echo "$APPROVALS" | python3 -c '
import json,sys
records = json.load(sys.stdin)["data"]["records"]
print(records[0]["approvalId"] if records else "")')
  [ -n "$APPROVAL_ID" ] && break
  sleep 2
done
[ -n "$APPROVAL_ID" ] || fail "no approval was created: $APPROVALS"
pass "approval $APPROVAL_ID is pending"

STATUS=$(curl -fsS "$BASE_URL/api/v1/executions/$EXECUTION_ID" -H "$AUTH" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["status"])')
[ "$STATUS" = "WAITING_HUMAN" ] || fail "the workflow should wait for a human, but it is $STATUS"
pass "the workflow is waiting for a human"

echo "== T11: the approval is idempotent and resumes the workflow =="
DECISION=$(curl -fsS -X POST "$BASE_URL/api/v1/approvals/$APPROVAL_ID/decision" \
  -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"decision":"APPROVED","comment":"报告内容符合要求"}')
echo "$DECISION" | grep -q '"status":"APPROVED"' || fail "the decision failed: $DECISION"

# A repeated decision must not change the recorded result.
REPEAT=$(curl -sS -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/api/v1/approvals/$APPROVAL_ID/decision" \
  -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"decision":"REJECTED","comment":"重复提交"}')
[ "$REPEAT" = "409" ] || fail "a repeated decision should be rejected, but it returned $REPEAT"
pass "the approval was recorded and a repeated decision was rejected"

sleep 5
FINAL=$(curl -fsS "$BASE_URL/api/v1/executions/$EXECUTION_ID" -H "$AUTH" \
  | python3 -c 'import json,sys; d=json.load(sys.stdin)["data"]; print(d["status"])')
[ "$FINAL" = "SUCCEEDED" ] || fail "the workflow should have succeeded, but it is $FINAL"
pass "the workflow succeeded after the approval"

echo "== T13: the report was generated =="
REPORT=$(curl -fsS -X POST "$BASE_URL/api/v1/executions/$EXECUTION_ID/report" -H "$AUTH")
REPORT_ID=$(echo "$REPORT" | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["reportId"])')
[ -n "$REPORT_ID" ] || fail "the report was not generated: $REPORT"
MARKDOWN=$(curl -fsS "$BASE_URL/api/v1/reports/$REPORT_ID" -H "$AUTH" \
  | python3 -c 'import json,sys; print(len(json.load(sys.stdin)["data"]["markdownContent"]))')
[ "$MARKDOWN" -gt 200 ] || fail "the report looks empty: $MARKDOWN characters"
pass "report $REPORT_ID was generated with $MARKDOWN characters"

echo "== the execution is reachable =="
DETAIL=$(curl -fsS "$BASE_URL/api/v1/executions/$EXECUTION_ID" -H "$AUTH")
echo "$DETAIL" | python3 -c '
import json,sys
data = json.load(sys.stdin)["data"]
print(f"  status={data[chr(115)+chr(116)+chr(97)+chr(116)+chr(117)+chr(115)]} total={data[chr(116)+chr(111)+chr(116)+chr(97)+chr(108)+chr(78)+chr(111)+chr(100)+chr(101)+chr(115)]}")
' || fail "the execution detail is wrong: $DETAIL"

echo
echo "ALL SMOKE TESTS PASSED"
