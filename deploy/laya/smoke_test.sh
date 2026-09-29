#!/usr/bin/env bash
# Laya 决策服务连通性自测：health → 合成 choice 判定 → Token 预算检查。
# 只发送合成数据，不包含任何真实用户内容。
# 用法：./smoke_test.sh <base_url> [api_key]
set -euo pipefail

BASE_URL="${1:?usage: $0 <base_url> [api_key]}"
BASE_URL="${BASE_URL%/}"
API_KEY="${2:-}"

auth=()
if [ -n "$API_KEY" ]; then
  auth=(-H "Authorization: Bearer ${API_KEY}")
fi

fail() { echo "FAIL: $*" >&2; exit 1; }

echo "== 1/3 health probe =="
code=$(curl -sS -o /tmp/laya_health.json -w '%{http_code}' "${auth[@]}" "${BASE_URL}/health") \
  || fail "health request could not reach ${BASE_URL}"
[ "$code" = "200" ] || fail "/health returned HTTP ${code}"
echo "health OK: $(head -c 200 /tmp/laya_health.json)"

echo "== 2/3 synthetic choice decision =="
payload='{
  "state": {"probe": "smoke_test", "note": "synthetic request, no user data"},
  "model": "typed-decisions",
  "questions": {
    "decision": {
      "type": "choice",
      "instructions": "Synthetic connectivity test. Pick the option that means the service answered.",
      "criteria": {
        "service_ok": "the endpoint answered correctly",
        "service_bad": "the endpoint failed"
      }
    }
  }
}'
code=$(curl -sS -o /tmp/laya_choice.json -w '%{http_code}' \
  "${auth[@]}" -H 'Content-Type: application/json' \
  -X POST --data "$payload" "${BASE_URL}/v1/systemone") \
  || fail "choice request could not reach ${BASE_URL}"
[ "$code" = "200" ] || fail "/v1/systemone returned HTTP ${code}: $(head -c 300 /tmp/laya_choice.json)"

python3 - <<'PY' || fail "response did not contain a usable choice answer"
import json, sys
root = json.load(open('/tmp/laya_choice.json'))
decision = root.get('answers', {}).get('decision', {})
assert decision.get('type') == 'choice', f"unexpected answer type: {decision}"
assert decision.get('choice') in ('service_ok', 'service_bad'), f"unexpected choice: {decision}"
usage = root.get('usage', {})
print(f"choice OK: choice={decision['choice']} confidence={decision.get('confidence')} usage={usage}")
PY

echo "== 3/3 token budget check =="
# max_len 超过服务端 LAYA_MAX_TOKEN_BUDGET（默认 8192）必须被 422 拒绝；
# 若你的部署调大了预算，请相应调大下面的探测值。
over_budget=1000000
budget_payload=$(python3 -c "
import json
body = json.loads('''$payload''')
body['max_len'] = $over_budget
print(json.dumps(body))")
code=$(curl -sS -o /tmp/laya_budget.json -w '%{http_code}' \
  "${auth[@]}" -H 'Content-Type: application/json' \
  -X POST --data "$budget_payload" "${BASE_URL}/v1/systemone") \
  || fail "budget probe could not reach ${BASE_URL}"
if [ "$code" = "422" ]; then
  echo "budget OK: over-budget max_len rejected with 422"
else
  fail "over-budget max_len expected HTTP 422, got ${code}: $(head -c 300 /tmp/laya_budget.json)"
fi

echo "ALL CHECKS PASSED"
