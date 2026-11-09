#!/usr/bin/env bash
# End-to-end flow over real HTTP against a running server. Start the server with a short CRITICAL SLA
# so escalation is visible quickly, e.g.:
#   java -jar n1-server/target/incidentops-0.0.1-SNAPSHOT.jar --incidentops.ack-sla.CRITICAL=8s --incidentops.sla.scan-interval-ms=1000
set -euo pipefail
API=${API:-http://localhost:8080/api}
j() { python3 -c "import sys,json; d=json.load(sys.stdin); print($1)"; }
timeline() { python3 -c 'import sys,json
for e in json.load(sys.stdin): print("  %-13s %-10s %s" % (e["type"], e["actor"], e["message"]))'; }
step() { printf '\n== %s\n' "$*"; }

step "subscribe to SSE stream in background"
curl -sN "$API/stream" > /tmp/incidentops-sse.log 2>/dev/null & SSE=$!
trap 'kill $SSE 2>/dev/null || true' EXIT
sleep 1

step "create HIGH incident"
R=$(curl -s -X POST "$API/incidents" -H 'Content-Type: application/json' \
  -d '{"serviceName":"Checkout API","severity":"HIGH","summary":"5xx spike after deploy","reporter":"demo"}')
echo "$R" | j 'd["status"], d["assignee"], "ackDueAt="+d["ackDueAt"], "v"+str(d["version"])'
ID=$(echo "$R" | j 'd["id"]')

step "list (page 0, size 5) and search q=checkout"
curl -s "$API/incidents?page=0&size=5" | j '"totalElements", d["totalElements"]'
curl -s "$API/incidents?q=checkout" | j '"search hits", d["totalElements"]'

step "illegal transition OPEN -> RESOLVED (expect 409 problem+json)"
curl -s -o /tmp/r.json -w '%{http_code} %{content_type}\n' -X PATCH "$API/incidents/$ID/status" -H 'Content-Type: application/json' \
  -d '{"status":"RESOLVED","postmortem":{"rootCause":"a","impact":"b","followUp":"c"}}'
cat /tmp/r.json; echo

step "ack with expectedVersion=0 (expect 200)"
curl -s -X PATCH "$API/incidents/$ID/status" -H 'Content-Type: application/json' \
  -d '{"status":"ACKNOWLEDGED","expectedVersion":0,"actor":"alice-tab"}' | j 'd["status"], "v"+str(d["version"])'

step "second person, stale expectedVersion=0, tries to reassign (expect 409 version-conflict)"
curl -s -o /tmp/r.json -w '%{http_code}\n' -X PATCH "$API/incidents/$ID/assignee" -H 'Content-Type: application/json' \
  -d '{"assignee":"responder-c","expectedVersion":0,"actor":"bob-tab"}'
cat /tmp/r.json; echo

step "comment"
curl -s -X POST "$API/incidents/$ID/comments" -H 'Content-Type: application/json' \
  -d '{"author":"alice-tab","message":"rolling back deploy"}' | j 'd["type"], d["message"]'

step "resolve without postmortem (expect 422), then with postmortem (expect 200)"
curl -s -o /dev/null -w '%{http_code}\n' -X PATCH "$API/incidents/$ID/status" -H 'Content-Type: application/json' -d '{"status":"RESOLVED"}'
curl -s -X PATCH "$API/incidents/$ID/status" -H 'Content-Type: application/json' \
  -d '{"status":"RESOLVED","actor":"alice-tab","postmortem":{"rootCause":"bad config in deploy","impact":"checkout 5xx","followUp":"config validation in CI"}}' \
  | j 'd["status"], d["postmortem"]["followUp"]'

step "timeline"
curl -s "$API/incidents/$ID/events" | timeline

step "CRITICAL incident left unacknowledged -> SLA job escalates it"
C=$(curl -s -X POST "$API/incidents" -H 'Content-Type: application/json' -d '{"serviceName":"Payments","severity":"CRITICAL","summary":"card auth timeouts"}')
CID=$(echo "$C" | j 'd["id"]'); echo "$C" | j '"assignee", d["assignee"], "ackDueAt", d["ackDueAt"]'
sleep "${ESCALATION_WAIT:-11}"
curl -s "$API/incidents/$CID" | j '"escalationLevel", d["escalationLevel"], "assignee now", d["assignee"]'
curl -s "$API/incidents/$CID/events" | timeline

step "stats + legacy metrics"
curl -s "$API/stats"; echo
curl -s "$API/metrics"; echo

step "SSE events received by the background subscriber"
sleep 1; kill $SSE 2>/dev/null || true
grep '^event:' /tmp/incidentops-sse.log | sort | uniq -c
