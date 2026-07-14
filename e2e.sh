#!/usr/bin/env bash

set -Eeuo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
API_URL="${BASE_URL}/api"
TODAY="${TEST_DATE:-$(date +%Y-%m-%d)}"
RUN_NEGATIVE_TESTS="${RUN_NEGATIVE_TESTS:-true}"

# These paths assume both IntelliJ/bootRun and this script use the project root
# as their working directory. Override them if your IntelliJ working directory differs.
MARKET_LOG="${MARKET_LOG:-log/market_orders.log}"
STEERING_LOG="${STEERING_LOG:-log/steering_signals.log}"

RESPONSE=""
HTTP_CODE=""
TEST_COUNT=0

pass() {
  TEST_COUNT=$((TEST_COUNT + 1))
  printf '  PASS: %s\n' "$1"
}

fail() {
  printf '\nFAILED: %s\n' "$1" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "Required command '$1' is not installed."
}

pretty_json() {
  if jq -e . >/dev/null 2>&1 <<<"$1"; then
    jq . <<<"$1"
  else
    printf '%s\n' "$1"
  fi
}

request() {
  local method="$1"
  local url="$2"
  local expected_http="$3"
  local payload="${4:-}"
  local response_file

  response_file="$(mktemp)"

  if [[ -n "$payload" ]]; then
    HTTP_CODE="$(curl -sS -o "$response_file" -w '%{http_code}' \
      -X "$method" "$url" \
      -H 'Content-Type: application/json' \
      --data "$payload")"
  else
    HTTP_CODE="$(curl -sS -o "$response_file" -w '%{http_code}' \
      -X "$method" "$url")"
  fi

  RESPONSE="$(cat "$response_file")"
  rm -f "$response_file"

  if [[ "$HTTP_CODE" != "$expected_http" ]]; then
    printf '\nRequest failed: %s %s\n' "$method" "$url" >&2
    printf 'Expected HTTP %s, received HTTP %s\n' "$expected_http" "$HTTP_CODE" >&2
    pretty_json "$RESPONSE" >&2
    exit 1
  fi
}

assert_json() {
  local description="$1"
  local filter="$2"
  local json="$3"

  if ! jq -e "$filter" >/dev/null 2>&1 <<<"$json"; then
    printf '\nAssertion failed: %s\n' "$description" >&2
    printf 'jq assertion: %s\n' "$filter" >&2
    pretty_json "$json" >&2
    exit 1
  fi
  pass "$description"
}

post_order() {
  local description="$1"
  local expected_status="$2"
  local order_time="$3"
  local delivery_start="$4"
  local delivery_end="$5"
  local side="$6"
  local quantity="$7"
  local price="$8"
  local payload

  payload="$(jq -n \
    --arg orderTime "$order_time" \
    --arg deliveryStart "$delivery_start" \
    --arg deliveryEnd "$delivery_end" \
    --arg side "$side" \
    --argjson quantity "$quantity" \
    --argjson price "$price" \
    '{
      order_time: $orderTime,
      delivery_start_time: $deliveryStart,
      delivery_end_time: $deliveryEnd,
      order_side: $side,
      quantity: $quantity,
      price: $price
    }')"

  printf '\n%s\n' "$description"
  request POST "$API_URL/orderupdate" 200 "$payload"
  pretty_json "$RESPONSE"

  assert_json "$description returns $expected_status" \
    ".status == \"$expected_status\"" "$RESPONSE"
  assert_json "$description returns an order_id" \
    '(.order_id | type == "string") and (.order_id | length > 0)' "$RESPONSE"
  assert_json "$description returns a timestamp" \
    '(.timestamp | type == "string") and (.timestamp | length > 0)' "$RESPONSE"
}

nonblank_line_count() {
  local file="$1"
  if [[ ! -f "$file" ]]; then
    printf '0\n'
    return
  fi
  awk 'NF { count++ } END { print count + 0 }' "$file"
}

wait_for_app() {
  local attempt
  for attempt in $(seq 1 30); do
    if curl -sS --max-time 1 "$BASE_URL/" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  fail "Application is not reachable at $BASE_URL. Start it before running this script."
}

require_command curl
require_command jq

printf '=== Smart Asset API end-to-end verification ===\n'
printf 'Base URL: %s\n' "$BASE_URL"
printf 'Test date: %s\n' "$TODAY"

wait_for_app

printf '\n1. Health endpoint\n'
request GET "$BASE_URL/" 200
pretty_json "$RESPONSE"
assert_json 'GET / returns the expected health message' \
  '.message == "smart-asset-test-Frank is running"' "$RESPONSE"

# The application has no reset endpoint. A fresh process is required for
# deterministic assertions because its order book and purchase tracker are in memory.
printf '\n2. Verify clean application state\n'
request GET "$API_URL/market/overview" 200
assert_json 'Market overview is initially empty' '.quarters | length == 0' "$RESPONSE"

request GET "$API_URL/client/orders/average" 200
assert_json 'Client purchase summary initially has zero MWh' \
  '(.total_mwh | tonumber) == 0' "$RESPONSE"
assert_json 'Client purchase summary initially has zero cost' \
  '(.total_cost | tonumber) == 0' "$RESPONSE"
assert_json 'Client purchase summary initially has zero average price' \
  '(.average_price_per_mwh | tonumber) == 0' "$RESPONSE"

# Log files are append-only and survive application restarts, unlike the in-memory state.
# Clear only the local test logs so log assertions below remain deterministic.
mkdir -p "$(dirname "$MARKET_LOG")" "$(dirname "$STEERING_LOG")"
: > "$MARKET_LOG"
: > "$STEERING_LOG"
pass 'Local market and steering logs reset'

if [[ "$RUN_NEGATIVE_TESTS" == "true" ]]; then
  printf '\n3. Request validation\n'
  MISSING_ORDER_TIME_PAYLOAD="$(jq -n \
    --arg start "${TODAY}T11:00:00" \
    --arg end "${TODAY}T11:15:00" \
    '{
      delivery_start_time: $start,
      delivery_end_time: $end,
      order_side: "BUY",
      quantity: 1,
      price: 50
    }')"
  request POST "$API_URL/orderupdate" 400 "$MISSING_ORDER_TIME_PAYLOAD"
  pass 'POST /api/orderupdate rejects a request without order_time'

  INVALID_SIDE_PAYLOAD="$(jq -n \
    --arg orderTime "${TODAY}T10:55:00" \
    --arg start "${TODAY}T11:00:00" \
    --arg end "${TODAY}T11:15:00" \
    '{
      order_time: $orderTime,
      delivery_start_time: $start,
      delivery_end_time: $end,
      order_side: "HOLD",
      quantity: 1,
      price: 50
    }')"
  request POST "$API_URL/orderupdate" 400 "$INVALID_SIDE_PAYLOAD"
  pass 'POST /api/orderupdate rejects an invalid order_side'
else
  printf '\n3. Request validation (skipped)\n'
  printf '  Negative request tests skipped; Spring WARN lines will not be generated.\n'
fi

# 11:30-12:15 is outside every configured charging-group window. This isolates
# order-book matching from the charging optimizer's automatic client purchases.
PERIOD_1_START="${TODAY}T11:30:00"
PERIOD_1_END="${TODAY}T11:45:00"
PERIOD_2_START="${TODAY}T12:00:00"
PERIOD_2_END="${TODAY}T12:15:00"

printf '\n4. Order status and matching scenarios\n'
post_order 'Unmatched SELL order' 'ACCEPTED' \
  "${TODAY}T11:20:00" "$PERIOD_1_START" "$PERIOD_1_END" SELL 10 55

post_order 'Non-crossing BUY order' 'ACCEPTED' \
  "${TODAY}T11:21:00" "$PERIOD_1_START" "$PERIOD_1_END" BUY 4 50

post_order 'Fully matched BUY order' 'FILLED' \
  "${TODAY}T11:22:00" "$PERIOD_1_START" "$PERIOD_1_END" BUY 6 55

post_order 'Partially matched BUY order' 'PARTIALLY_FILLED' \
  "${TODAY}T11:23:00" "$PERIOD_1_START" "$PERIOD_1_END" BUY 10 55

post_order 'SELL order clearing the best bid' 'FILLED' \
  "${TODAY}T11:24:00" "$PERIOD_1_START" "$PERIOD_1_END" SELL 6 55

printf '\n5. Multi-price-level matching\n'
post_order 'Lower-priced SELL level' 'ACCEPTED' \
  "${TODAY}T11:50:00" "$PERIOD_2_START" "$PERIOD_2_END" SELL 2 40

post_order 'Higher-priced SELL level' 'ACCEPTED' \
  "${TODAY}T11:51:00" "$PERIOD_2_START" "$PERIOD_2_END" SELL 3 45

post_order 'BUY consuming multiple ask levels' 'FILLED' \
  "${TODAY}T11:52:00" "$PERIOD_2_START" "$PERIOD_2_END" BUY 4 50

printf '\n6. Market overview endpoint\n'
request GET "$API_URL/market/overview" 200
pretty_json "$RESPONSE"
assert_json 'GET /api/market/overview returns two tested quarters' \
  '.quarters | length == 2' "$RESPONSE"
# Use jq --arg through a separate invocation because assert_json accepts a plain filter.
if ! jq -e --arg start "$PERIOD_1_START" \
  'any(.quarters[]; .delivery_start_time == $start and
      (.highest_buy_price | tonumber) == 50 and
      .lowest_sell_price == null)' >/dev/null <<<"$RESPONSE"; then
  fail 'First quarter overview does not contain highest bid 50 and null lowest ask.'
fi
pass 'First quarter overview contains highest bid 50 and no ask'

if ! jq -e --arg start "$PERIOD_2_START" \
  'any(.quarters[]; .delivery_start_time == $start and
      .highest_buy_price == null and
      (.lowest_sell_price | tonumber) == 45)' >/dev/null <<<"$RESPONSE"; then
  fail 'Second quarter overview does not contain lowest ask 45 and null highest bid.'
fi
pass 'Second quarter overview contains lowest ask 45 and no bid'

printf '\n7. Charging optimizer and client-purchase endpoint\n'
# 07:00 is inside charging groups A and B. One accepted SELL of 2 MWh lets:
# - Group A buy 0.50 MWh (2 MW * 0.25 h)
# - Group B buy 0.75 MWh (3 MW * 0.25 h)
# Total client purchase = 1.25 MWh at 40 = 50 total cost.
OPT_PERIOD_START="${TODAY}T07:00:00"
OPT_PERIOD_END="${TODAY}T07:15:00"
OPT_ORDER_TIME="${TODAY}T06:55:00"
post_order 'SELL order inside charging windows A and B' 'ACCEPTED' \
  "$OPT_ORDER_TIME" "$OPT_PERIOD_START" "$OPT_PERIOD_END" SELL 2 40

request GET "$API_URL/client/orders/average" 200
pretty_json "$RESPONSE"
assert_json 'Client endpoint reports 1.25 MWh purchased' \
  '(.total_mwh | tonumber) == 1.25' "$RESPONSE"
assert_json 'Client endpoint reports total cost 50' \
  '(.total_cost | tonumber) == 50' "$RESPONSE"
assert_json 'Client endpoint reports average price 40' \
  '(.average_price_per_mwh | tonumber) == 40' "$RESPONSE"

printf '\n8. File-output verification\n'
MARKET_LINES="$(nonblank_line_count "$MARKET_LOG")"
STEERING_LINES="$(nonblank_line_count "$STEERING_LOG")"

# 8 explicit valid market requests + 1 optimizer-triggering SELL + 2 generated
# client BUY orders = 11 market-order log lines.
[[ "$MARKET_LINES" == '11' ]] || \
  fail "Expected 11 lines in $MARKET_LOG, found $MARKET_LINES."
pass 'Market-order log contains 11 expected records'

[[ "$STEERING_LINES" == '2' ]] || \
  fail "Expected 2 lines in $STEERING_LOG, found $STEERING_LINES."
pass 'Steering-signal log contains signals for groups A and B'

if ! awk -F'|' 'NF != 9 { exit 1 }' "$MARKET_LOG"; then
  fail 'A market-order log record does not contain the expected 9 fields.'
fi
pass 'Every market-order log record has the expected 9 fields'

# Verify the optimizer records by their fields rather than assuming they are
# the final three lines. LocalDateTime.toString() may omit zero seconds, so
# 2026-07-14T06:55 and 2026-07-14T06:55:00 are treated as equal.
if ! awk -F'|' \
  -v expected_time="$OPT_ORDER_TIME" \
  -v expected_start="$OPT_PERIOD_START" \
  -v expected_end="$OPT_PERIOD_END" '
  function canonical_time(value) {
    sub(/\r$/, "", value)
    sub(/\.[0-9]+$/, "", value)
    if (value ~ /T[0-9][0-9]:[0-9][0-9]:00$/) sub(/:00$/, "", value)
    return value
  }
  function time_matches(actual) {
    return canonical_time(actual) == canonical_time(expected_time)
  }
  $3 == "market" && canonical_time($4) == canonical_time(expected_start) && canonical_time($5) == canonical_time(expected_end) &&
      $6 == "SELL" && ($7 + 0) == 2 && ($8 + 0) == 40 && $9 == "ACCEPTED" {
    sell_found++
    if (time_matches($1)) sell_time_ok++
    else print "Timestamp mismatch for optimizer SELL: expected=" expected_time ", actual=" $1 > "/dev/stderr"
  }
  $3 == "A" && canonical_time($4) == canonical_time(expected_start) && canonical_time($5) == canonical_time(expected_end) &&
      $6 == "BUY" && ($7 + 0) == 0.5 && ($8 + 0) == 40 && $9 == "FILLED" {
    a_found++
    if (time_matches($1)) a_time_ok++
    else print "Timestamp mismatch for generated A BUY: expected=" expected_time ", actual=" $1 > "/dev/stderr"
  }
  $3 == "B" && canonical_time($4) == canonical_time(expected_start) && canonical_time($5) == canonical_time(expected_end) &&
      $6 == "BUY" && ($7 + 0) == 0.75 && ($8 + 0) == 40 && $9 == "FILLED" {
    b_found++
    if (time_matches($1)) b_time_ok++
    else print "Timestamp mismatch for generated B BUY: expected=" expected_time ", actual=" $1 > "/dev/stderr"
  }
  END {
    ok = (sell_found == 1 && a_found == 1 && b_found == 1 &&
          sell_time_ok == 1 && a_time_ok == 1 && b_time_ok == 1)
    if (!ok) {
      print "Optimizer market-log matches: SELL=" sell_found ", A=" a_found ", B=" b_found > "/dev/stderr"
      print "Optimizer timestamp matches: SELL=" sell_time_ok ", A=" a_time_ok ", B=" b_time_ok > "/dev/stderr"
      exit 1
    }
  }
' "$MARKET_LOG"; then
  printf '\nRelevant market-order records:\n' >&2
  grep -E '\|(?:market|A|B)\|.*\|(SELL|BUY)\|' "$MARKET_LOG" >&2 || true
  fail 'Optimizer-triggering SELL and generated client BUY orders were not logged with the expected order_time.'
fi
pass 'order_time is propagated to the optimizer SELL and generated A/B client orders'
pass 'Generated client BUY orders exist for groups A and B'

if ! awk -F'|' \
  -v expected_time="$OPT_ORDER_TIME" \
  -v expected_start="$OPT_PERIOD_START" \
  -v expected_end="$OPT_PERIOD_END" '
  function canonical_time(value) {
    sub(/\r$/, "", value)
    sub(/\.[0-9]+$/, "", value)
    if (value ~ /T[0-9][0-9]:[0-9][0-9]:00$/) sub(/:00$/, "", value)
    return value
  }
  function time_matches(actual) {
    return canonical_time(actual) == canonical_time(expected_time)
  }
  $2 == "A" && canonical_time($3) == canonical_time(expected_start) && canonical_time($4) == canonical_time(expected_end) && ($5 + 0) == 2 {
    a_found++
    if (time_matches($1)) a_time_ok++
    else print "Timestamp mismatch for A steering signal: expected=" expected_time ", actual=" $1 > "/dev/stderr"
  }
  $2 == "B" && canonical_time($3) == canonical_time(expected_start) && canonical_time($4) == canonical_time(expected_end) && ($5 + 0) == 3 {
    b_found++
    if (time_matches($1)) b_time_ok++
    else print "Timestamp mismatch for B steering signal: expected=" expected_time ", actual=" $1 > "/dev/stderr"
  }
  END {
    ok = (a_found == 1 && b_found == 1 && a_time_ok == 1 && b_time_ok == 1)
    if (!ok) {
      print "Steering matches: A=" a_found ", B=" b_found > "/dev/stderr"
      print "Steering timestamp matches: A=" a_time_ok ", B=" b_time_ok > "/dev/stderr"
      exit 1
    }
  }
' "$STEERING_LOG"; then
  printf '\nRelevant steering-signal records:\n' >&2
  cat "$STEERING_LOG" >&2
  fail 'Expected A/B steering signals with the incoming request order_time were not found.'
fi
pass 'Steering signals use the incoming request order_time'
pass 'Steering signal powers are correct for groups A and B'

printf '\n=== All %d checks passed ===\n' "$TEST_COUNT"
printf 'Verified endpoints:\n'
printf '  GET  /\n'
printf '  POST /api/orderupdate\n'
printf '  GET  /api/market/overview\n'
printf '  GET  /api/client/orders/average\n'
