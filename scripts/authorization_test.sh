#!/usr/bin/env bash
# Verifies that the reports of one user are not readable by another one.
#
# The report list joined the workflow executions without a user filter, so every
# user saw the reports of every other user — including the full markdown of the
# technical due diligence reports.
set -uo pipefail

BASE_URL=${BASE_URL:-http://127.0.0.1:8080}
LOG_DIR=${LOG_DIR:-/tmp/flowpilot-logs}

pass() { echo "  PASS  $1"; }
fail() { echo "  FAIL  $1"; exit 1; }

echo "== the backend is reachable =="
curl -fsS "$BASE_URL/actuator/health" >/dev/null || fail "the backend is not healthy"

register() {
  local name=$1
  local register login
  register=$(curl -fsS -X POST "$BASE_URL/api/v1/auth/register" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"$name\",\"password\":\"SmokeTest123\",\"email\":\"$name@example.com\"}")
  echo "$register" | grep -q '"code":0' || fail "the registration of $name failed: $register"
  login=$(curl -fsS -X POST "$BASE_URL/api/v1/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"$name\",\"password\":\"SmokeTest123\"}")
  echo "$login" | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["token"])'
}

OWNER="owner_$(date +%s)"
OTHER="other_$(date +%s)"
OWNER_TOKEN=$(register "$OWNER")
OTHER_TOKEN=$(register "$OTHER")

echo "== the owner runs a workflow =="
WORKFLOWS=$(curl -fsS "$BASE_URL/api/v1/workflows?page=1&size=20" -H "Authorization: Bearer $OWNER_TOKEN")
WORKFLOW_ID=$(echo "$WORKFLOWS" | python3 -c '
import json,sys
print(json.load(sys.stdin)["data"]["records"][0]["id"])')
START=$(curl -fsS -X POST "$BASE_URL/api/v1/workflows/$WORKFLOW_ID/executions" \
  -H "Authorization: Bearer $OWNER_TOKEN" -H 'Content-Type: application/json' \
  -d '{"input":{"repositoryUrl":"https://github.com/octocat/Hello-World","owner":"octocat","repo":"Hello-World"}}')
EXECUTION_ID=$(echo "$START" | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["executionId"])')
echo "  execution $EXECUTION_ID"

echo "== wait for the execution =="
for i in $(seq 1 90); do
  STATUS=$(curl -fsS "$BASE_URL/api/v1/executions/$EXECUTION_ID" -H "Authorization: Bearer $OWNER_TOKEN" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["status"])' 2>/dev/null)
  [ "$STATUS" = "WAITING_HUMAN" ] && break
  [ "$STATUS" = "SUCCEEDED" ] && break
  sleep 2
done

if [ "$STATUS" = "WAITING_HUMAN" ]; then
  echo "== approve the report =="
  APPROVALS=$(curl -fsS "$BASE_URL/api/v1/approvals?status=PENDING&page=1&size=20" -H "Authorization: Bearer $OWNER_TOKEN")
  APPROVAL_ID=$(echo "$APPROVALS" | python3 -c '
import json,sys
records = json.load(sys.stdin)["data"]["records"]
print(records[0]["approvalId"] if records else "")')
  [ -n "$APPROVAL_ID" ] || fail "no approval was created"
  curl -fsS -X POST "$BASE_URL/api/v1/approvals/$APPROVAL_ID/decision" \
    -H "Authorization: Bearer $OWNER_TOKEN" -H 'Content-Type: application/json' \
    -d '{"decision":"APPROVED","comment":"ok"}' >/dev/null
  for i in $(seq 1 60); do
    STATUS=$(curl -fsS "$BASE_URL/api/v1/executions/$EXECUTION_ID" -H "Authorization: Bearer $OWNER_TOKEN" \
      | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["status"])' 2>/dev/null)
    [ "$STATUS" = "SUCCEEDED" ] && break
    sleep 2
  done
fi
[ "$STATUS" = "SUCCEEDED" ] || fail "the execution did not succeed: $STATUS"

echo "== the owner generates a report =="
REPORT=$(curl -fsS -X POST "$BASE_URL/api/v1/executions/$EXECUTION_ID/report" -H "Authorization: Bearer $OWNER_TOKEN")
REPORT_ID=$(echo "$REPORT" | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["reportId"])')
[ -n "$REPORT_ID" ] || fail "the report was not generated: $REPORT"
echo "  report $REPORT_ID"

echo "== the owner reads its own report =="
OWN=$(curl -fsS "$BASE_URL/api/v1/reports/$REPORT_ID" -H "Authorization: Bearer $OWNER_TOKEN")
echo "$OWN" | grep -q '"code":0' || fail "the owner cannot read its own report: $OWN"
pass "the owner reads its own report"

echo "== the list of the owner only contains its own reports =="
LIST=$(curl -fsS "$BASE_URL/api/v1/reports?page=1&size=50" -H "Authorization: Bearer $OWNER_TOKEN")
echo "$LIST" | python3 -c '
import json,sys
records = json.load(sys.stdin)["data"]["records"]
assert records, "the owner must see its own report"
for record in records:
    assert "markdownContent" not in record, "the list must not contain the full report"
print(f"  {len(records)} report(s) in the list, without the markdown")
' || fail "the report list is wrong: $LIST"
pass "the list of the owner only contains its own reports"

echo "== the other user sees neither the report nor its content =="
OTHER_LIST=$(curl -fsS "$BASE_URL/api/v1/reports?page=1&size=50" -H "Authorization: Bearer $OTHER_TOKEN")
echo "$OTHER_LIST" | python3 -c '
import json,sys
records = json.load(sys.stdin)["data"]["records"]
assert not records, f"the other user must not see any report, but saw {len(records)}"
print("  the list of the other user is empty")
' || fail "the other user sees the reports of the owner: $OTHER_LIST"

OTHER_READ=$(curl -sS -o /dev/null -w "%{http_code}" "$BASE_URL/api/v1/reports/$REPORT_ID" \
  -H "Authorization: Bearer $OTHER_TOKEN")
[ "$OTHER_READ" = "403" ] || fail "the other user can read the report: http $OTHER_READ"
pass "the other user cannot read the report of the owner"

echo "== the other user cannot read the approval of the owner =="
# A second execution of the owner, so that an approval is pending while the
# other user tries to read it.
START2=$(curl -fsS -X POST "$BASE_URL/api/v1/workflows/$WORKFLOW_ID/executions" \
  -H "Authorization: Bearer $OWNER_TOKEN" -H 'Content-Type: application/json' \
  -d '{"input":{"repositoryUrl":"https://github.com/octocat/Hello-World","owner":"octocat","repo":"Hello-World"}}')
EXECUTION2=$(echo "$START2" | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["executionId"])')
APPROVAL_ID=""
for i in $(seq 1 90); do
  APPROVALS=$(curl -fsS "$BASE_URL/api/v1/approvals?status=PENDING&page=1&size=20" -H "Authorization: Bearer $OWNER_TOKEN")
  APPROVAL_ID=$(echo "$APPROVALS" | python3 -c '
import json,sys
records = json.load(sys.stdin)["data"]["records"]
print(records[0]["approvalId"] if records else "")')
  [ -n "$APPROVAL_ID" ] && break
  sleep 2
done
if [ -n "$APPROVAL_ID" ]; then
  OTHER_APPROVAL=$(curl -sS -o /dev/null -w "%{http_code}" "$BASE_URL/api/v1/approvals/$APPROVAL_ID" \
    -H "Authorization: Bearer $OTHER_TOKEN")
  [ "$OTHER_APPROVAL" = "403" ] || fail "the other user can read the approval: http $OTHER_APPROVAL"
  pass "the other user cannot read the approval of the owner"

  OTHER_DECIDE=$(curl -sS -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/api/v1/approvals/$APPROVAL_ID/decision" \
    -H "Authorization: Bearer $OTHER_TOKEN" -H 'Content-Type: application/json' \
    -d '{"decision":"APPROVED","comment":"stolen"}')
  [ "$OTHER_DECIDE" = "403" ] || fail "the other user can decide the approval: http $OTHER_DECIDE"
  pass "the other user cannot decide the approval of the owner"
else
  fail "no approval was created for the check"
fi

echo
echo "ALL AUTHORIZATION TESTS PASSED"
