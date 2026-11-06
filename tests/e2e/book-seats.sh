#!/usr/bin/env sh
# End-to-end test of the saga book-seats against the real csp-booking-api and csp-catalog-api, through the gateway on
# localhost:8000, in the disposable platform that tests/e2e/up.sh started. It is the last box of "Cómo se verifica" of
# Annex E. It prints one PASS/FAIL line per check and, at the end, the totals; the exit status is not zero if any check failed.
. "$(dirname "$0")/lib.sh"

W=$E2E_WORK/run
mkdir -p "$W"
TAP=http://localhost:18083
BOOKING=csp-e2e-booking-api-1
WORKFLOW=csp-e2e-workflow-1
PASSED=0
FAILED=0

pass() { PASSED=$((PASSED + 1)); echo "PASS  $1"; }
fail() { FAILED=$((FAILED + 1)); echo "FAIL  $1  [$2]"; }
section() { printf '\n== %s\n' "$1"; }
# expect <description> <actual> <expected>
expect() { if [ "$2" = "$3" ]; then pass "$1"; else fail "$1" "expected '$3', got '$2'"; fi; }
# expect_match <description> <actual> <grep-regex>
expect_match() { if printf '%s' "$2" | grep -Eq -- "$3"; then pass "$1"; else fail "$1" "'$2' does not match '$3'"; fi; }
# expect_absent <description> <text> <forbidden>
expect_absent() { case "$2" in *"$3"*) fail "$1" "found '$3'";; *) pass "$1";; esac; }

new_uuid() { openssl rand -hex 15 | sed 's/\(........\)\(....\)\(...\)\(...\)\(............\)/\1-\2-4\3-8\4-\5/'; }
sha256() { printf '%s' "$1" | openssl dgst -sha256 | awk '{print $NF}'; }
jget() { printf '%s' "$BODY" | sed -n "s/.*\"$1\":\(\"[^\"]*\"\|[^,}]*\).*/\1/p" | tr -d '"'; }
header() { grep -i "^$1:" "$W/res.h" | head -1 | cut -d: -f2- | tr -d '\r' | sed 's/^ *//'; }

# req <method> <path> <token> <idempotency-key> <correlation-id> [body]
req() {
    { [ -n "$3" ] && printf 'Authorization: Bearer %s\n' "$3"
      [ -n "$4" ] && printf 'Idempotency-Key: %s\n' "$4"
      [ -n "$5" ] && printf 'X-Correlation-Id: %s\n' "$5"
      printf 'Content-Type: application/json\n'; } > "$W/req.h"
    if [ -n "${6:-}" ]; then
        CODE=$(curl -s -m 90 -D "$W/res.h" -o "$W/res.b" -w '%{http_code}' -X "$1" "$GATEWAY$2" -H @"$W/req.h" -d "$6")
    else
        CODE=$(curl -s -m 90 -D "$W/res.h" -o "$W/res.b" -w '%{http_code}' -X "$1" "$GATEWAY$2" -H @"$W/req.h")
    fi
    BODY=$(cat "$W/res.b")
}
book() { # <token> <key> <corr> <seats-json>
    req POST /api/v1/sagas/book-seats "$1" "$2" "$3" "{\"showtimeId\":\"$SHOWTIME\",\"seatLabels\":$4}"
}
# booking_direct <path> <key> <corr> [token]: an operator's call to the internal route, inside the platform network.
booking_direct() {
    out=$(compose exec -T booking-api curl -s -w '\n%{http_code}' -X POST \
        -H "Authorization: Bearer ${4:-$WF_TOKEN}" -H "Idempotency-Key: $2" -H "X-Correlation-Id: $3" -H 'Content-Type: application/json' \
        ${5:+-d "$5"} "http://localhost:8083/api/v1/booking$1")
    DIRECT_CODE=$(printf '%s\n' "$out" | tail -1)
    DIRECT_BODY=$(printf '%s\n' "$out" | sed '$d')
}
reservation_status() { psql_admin "select status from booking.reservation where id='$1'"; }
tap_lines() { curl -s "$TAP/__tap/calls" | grep -- "$1"; }
count() { printf '%s\n' "$1" | grep -c . ; }
# tamper <jwt>: changes the first character of the signature (the last one may carry only padding bits).
tamper() {
    signature=${1##*.}
    first=$(printf '%s' "$signature" | cut -c1)
    swap=Z; [ "$first" != Z ] || swap=Y
    printf '%s.%s%s\n' "${1%.*}" "$swap" "$(printf '%s' "$signature" | cut -c2-)"
}
saga_id_of_key() { psql_admin "select id from workflow.saga where idempotency_key='$1'"; }

# ---------------------------------------------------------------------------------------------------------------
section "Preconditions"
WF_TOKEN=$(env_value WORKFLOW_SERVICE_TOKEN)
WF_SHA=$(sha256 "$WF_TOKEN")
req GET /api/v1/sagas/00000000-0000-4000-8000-000000000000 "" "" ""
expect "the gateway answers and the workflow is behind it (401 without token)" "$CODE" 401
curl -s -XPOST "$TAP/__tap/reset" >/dev/null && curl -s -XPOST "$TAP/__tap/config" -d '{"confirm_delay_ms":0,"fill_snapshot":false}' >/dev/null
sh "$E2E_HERE/seed.sh" >/dev/null && SHOWTIME=$(cat "$E2E_WORK/showtime.id")
expect_match "a fresh showtime was seeded in the real Catalog" "$SHOWTIME" '^[0-9a-f-]{36}$'
{ cat "$E2E_WORK/versions.txt"; } | sed 's/^/INFO  version: /'

# Booking develop validates movieTitle and roomName as required although booking-service.yaml says Catalog supplies them.
booking_direct /internal/workflow/holds "e2e-probe-$(new_uuid)" "$(new_uuid)" "" \
    "{\"userId\":\"$(new_uuid)\",\"showtimeId\":\"$SHOWTIME\",\"seatLabels\":[\"D6\"]}"
DEFECT=0
if [ "$DIRECT_CODE" = 400 ]; then
    DEFECT=1
    echo "INFO  KNOWN BOOKING DEFECT: the internal hold without movieTitle/roomName answers 400; the recording proxy adds them (patched=1)"
    curl -s -XPOST "$TAP/__tap/config" -d '{"fill_snapshot":true}' >/dev/null
else
    echo "INFO  Booking accepts the hold without movieTitle/roomName (HTTP $DIRECT_CODE): no patch needed"
    [ "$DIRECT_CODE" = 201 ] && booking_direct "/internal/workflow/reservations/$(printf '%s' "$DIRECT_BODY" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')/release" probe-release-000000001 "$(new_uuid)"
fi

# ---------------------------------------------------------------------------------------------------------------
section "1. Happy path"
U1=$(new_uuid); T1=$(mint_token "$U1" csp-api 30); K1=e2e-happy-$(new_uuid); C1=$(new_uuid)
book "$T1" "$K1" "$C1" '["A1","A2"]'
expect "POST book-seats answers 201" "$CODE" 201
SAGA1=$(jget id); RES1=$(jget reservationId)
expect "Location points at the saga" "$(header Location)" "/api/v1/sagas/$SAGA1"
expect "the saga is COMPLETED" "$(jget status)" COMPLETED
expect "both steps are completed" "$(printf '%s' "$BODY" | grep -o '"completedSteps":\[[^]]*\]')" '"completedSteps":["hold-seats","confirm-reservation"]'
expect "failedStep is null" "$(jget failedStep)" null
expect_match "reservationId is set" "$RES1" '^[0-9a-f-]{36}$'
expect "Booking: the reservation is CONFIRMED" "$(reservation_status "$RES1")" CONFIRMED
expect "Booking: the reservation belongs to the user of the token" "$(psql_admin "select user_id from booking.reservation where id='$RES1'")" "$U1"
if [ "$DEFECT" = 0 ]; then
    expect_match "Booking: the snapshot of the title and the room comes from Catalog, not from the request" \
        "$(psql_admin "select movie_title_snapshot||'/'||room_name_snapshot from booking.seat_hold where id='$RES1'")" '^E2E Synthetic Movie [0-9a-f]+/E2E Room [0-9a-f]+$'
    expect "Booking: the total is the price of the showtime times two seats" "$(psql_admin "select total_amount from booking.reservation where id='$RES1'")" 3000000
fi
expect "Booking: both seats are CONFIRMED" "$(psql_admin "select count(*) from booking.seat_hold_item where hold_id='$RES1' and status='CONFIRMED'")" 2
req GET "/api/v1/sagas/$SAGA1" "$T1" "" ""
expect "GET the saga answers 200" "$CODE" 200
expect "GET shows COMPLETED" "$(jget status)" COMPLETED

# ---------------------------------------------------------------------------------------------------------------
section "4. Service token, idempotency key and correlation id reach Booking"
CALLS=$(tap_lines "$SAGA1")
expect "exactly two participant calls for the saga" "$(count "$CALLS")" 2
expect "hold carries Idempotency-Key <sagaId>:hold-seats and the request's X-Correlation-Id" \
    "$(printf '%s\n' "$CALLS" | grep -c "holds status=201 idem=$SAGA1:hold-seats corr=$C1 ")" 1
expect "confirm carries Idempotency-Key <sagaId>:confirm-reservation and the same X-Correlation-Id" \
    "$(printf '%s\n' "$CALLS" | grep -c "$RES1/confirm status=200 idem=$SAGA1:confirm-reservation corr=$C1 ")" 1
expect "both calls carry the service token of the workflow (SHA-256 of the bearer)" "$(printf '%s\n' "$CALLS" | grep -c "tokensha=$WF_SHA ")" 2
expect_absent "the token of the person is never sent to Booking" "$CALLS" "$(sha256 "$T1")"
docker logs "$BOOKING" > "$W/booking.log" 2>&1
expect "Booking audit log: hold by sub csp-workflow, workflow token digest, trace id = correlation id" \
    "$(grep "\"traceId\":\"$C1\"" "$W/booking.log" | grep -c "internal call sub=csp-workflow route=/internal/workflow/holds status=201 authenticated=true tokenSha256=$WF_SHA")" 1
expect "Booking audit log: confirm by sub csp-workflow with the same trace id" \
    "$(grep "\"traceId\":\"$C1\"" "$W/booking.log" | grep -c "internal call sub=csp-workflow route=/internal/workflow/reservations/$RES1/confirm status=200 authenticated=true tokenSha256=$WF_SHA")" 1
expect "Booking never logged the token of the person" "$(grep -c "$(sha256 "$T1")" "$W/booking.log")" 0
expect "Booking table idempotency_key holds <sagaId>:hold-seats for the reservation" \
    "$(psql_admin "select hold_id from booking.idempotency_key where key='$SAGA1:hold-seats'")" "$RES1"
expect "Booking outbox: ReservationHeld and BookingConfirmed carry the correlation id" \
    "$(psql_admin "select count(*) from booking.outbox_event where aggregate_id='$RES1' and payload->'metadata'->>'correlationId'='$C1'")" 2
docker logs "$WORKFLOW" > "$W/workflow.log" 2>&1
expect_match "workflow log: the access line of the request carries the correlation id" \
    "$(grep "\"correlationId\":\"$C1\"" "$W/workflow.log" | grep 'request method=POST')" 'status=201'
expect "workflow log: no token appears in it" "$(grep -c -e "$WF_TOKEN" -e "$T1" "$W/workflow.log")" 0

# ---------------------------------------------------------------------------------------------------------------
section "3. Idempotency"
BEFORE=$(curl -s "$TAP/__tap/calls" | grep -c .)
book "$T1" "$K1" "$(new_uuid)" '["A1","A2"]'
expect "the same user, key and payload answer 200" "$CODE" 200
expect "and the same saga" "$(jget id)" "$SAGA1"
expect "no step reached Booking again" "$(curl -s "$TAP/__tap/calls" | grep -c .)" "$BEFORE"
expect "Booking still holds exactly one reservation for the user" "$(psql_admin "select count(*) from booking.reservation where user_id='$U1'")" 1
book "$T1" "$K1" "$(new_uuid)" '["B6"]'
expect "the same key with another payload answers 409" "$CODE" 409
expect "and still no step reached Booking" "$(curl -s "$TAP/__tap/calls" | grep -c .)" "$BEFORE"
expect "the 409 uses the error envelope" "$(jget error)" CONFLICT

# ---------------------------------------------------------------------------------------------------------------
section "Contract of Booking that the workflow relies on: confirm and release repeat"
booking_direct "/internal/workflow/reservations/$RES1/confirm" "$SAGA1:confirm-reservation" "$(new_uuid)"
expect "confirm of a CONFIRMED reservation answers 200" "$DIRECT_CODE" 200
expect "and writes no second BookingConfirmed" "$(psql_admin "select count(*) from booking.outbox_event where aggregate_id='$RES1' and event_type='BookingConfirmed'")" 1
booking_direct "/internal/workflow/reservations/$RES1/release" "$SAGA1:release-seats" "$(new_uuid)"
expect "release of a CONFIRMED reservation answers 422" "$DIRECT_CODE" 422
expect "and leaves it CONFIRMED" "$(reservation_status "$RES1")" CONFIRMED

# ---------------------------------------------------------------------------------------------------------------
section "2. Confirmation fails after the hold: the hold is released"
# The hold of the saga is released in Booking (as the expiration sweep of csp-worker would do) while the confirm is held back
# by the proxy. The real Booking then answers 422 INVALID_STATUS_TRANSITION to the confirm.
U2=$(new_uuid); T2=$(mint_token "$U2" csp-api 30); K2=e2e-expired-$(new_uuid); C2=$(new_uuid)
curl -s -XPOST "$TAP/__tap/config" -d '{"confirm_delay_ms":6000}' >/dev/null
curl -s -m 90 -D "$W/bg.h" -o "$W/bg.b" -w '%{http_code}' -X POST "$GATEWAY/api/v1/sagas/book-seats" \
    -H "Authorization: Bearer $T2" -H "Idempotency-Key: $K2" -H "X-Correlation-Id: $C2" -H 'Content-Type: application/json' \
    -d "{\"showtimeId\":\"$SHOWTIME\",\"seatLabels\":[\"B1\",\"B2\"]}" > "$W/bg.code" &
BG=$!
tries=0
until [ "$(tap_lines "corr=$C2" | grep -c 'holds status=201')" = 1 ] || [ $tries -ge 100 ]; do sleep 0.2; tries=$((tries + 1)); done
RES2=$(psql_admin "select id from booking.reservation where user_id='$U2'")
booking_direct "/internal/workflow/reservations/$RES2/release" "e2e-operator-release-$(new_uuid)" "$(new_uuid)"
expect "the hold expires in Booking while the saga waits (operator release answers 200)" "$DIRECT_CODE" 200
wait $BG
curl -s -XPOST "$TAP/__tap/config" -d '{"confirm_delay_ms":0}' >/dev/null
CODE=$(cat "$W/bg.code"); BODY=$(cat "$W/bg.b")
expect "POST answers 201" "$CODE" 201
SAGA2=$(jget id)
expect "the saga is COMPENSATED" "$(jget status)" COMPENSATED
expect "failedStep is confirm-reservation" "$(jget failedStep)" confirm-reservation
expect "Booking: the reservation is EXPIRED" "$(reservation_status "$RES2")" EXPIRED
expect "Booking: the seats are RELEASED" "$(psql_admin "select count(*) from booking.seat_hold_item where hold_id='$RES2' and status='RELEASED'")" 2
CALLS2=$(tap_lines "$SAGA2")
expect "Booking answered 422 to the confirm" "$(printf '%s\n' "$CALLS2" | grep -c "$RES2/confirm status=422 idem=$SAGA2:confirm-reservation corr=$C2 ")" 1
expect "the compensation released the hold with Idempotency-Key <sagaId>:release-seats and answered 200 (already EXPIRED)" \
    "$(printf '%s\n' "$CALLS2" | grep -c "$RES2/release status=200 idem=$SAGA2:release-seats corr=$C2 ")" 1
expect "the release did not write a second ReservationExpired" "$(psql_admin "select count(*) from booking.outbox_event where aggregate_id='$RES2' and event_type='ReservationExpired'")" 1
U3=$(new_uuid); T3=$(mint_token "$U3" csp-api 30)
book "$T3" "e2e-again-$(new_uuid)" "$(new_uuid)" '["B1","B2"]'
expect "the same seats are available again: another user books them (COMPLETED)" "$(jget status)" COMPLETED

# ---------------------------------------------------------------------------------------------------------------
section "2b. The hold itself fails (seats already taken)"
U4=$(new_uuid); T4=$(mint_token "$U4" csp-api 30); K4=e2e-taken-$(new_uuid)
book "$T4" "$K4" "$(new_uuid)" '["A1"]'
expect "POST answers 201" "$CODE" 201
expect "the saga is COMPENSATED" "$(jget status)" COMPENSATED
expect "failedStep is hold-seats" "$(jget failedStep)" hold-seats
expect "no reservationId" "$(jget reservationId)" null
expect "no step is reported as completed" "$(printf '%s' "$BODY" | grep -o '"completedSteps":\[[^]]*\]')" '"completedSteps":[]'
SAGA4=$(jget id)

# ---------------------------------------------------------------------------------------------------------------
section "2c. The confirmation and the compensation both fail: the saga is FAILED (the real Booking answers 500)"
# While the proxy holds the confirm back, a trigger of the disposable database makes every update of a reservation raise an
# error, so the real Booking answers 500 to the confirm and then to the release of the compensation. Booking is not edited.
U6=$(new_uuid); T6=$(mint_token "$U6" csp-api 30); K6=e2e-failed-$(new_uuid); C6=$(new_uuid)
curl -s -XPOST "$TAP/__tap/config" -d '{"confirm_delay_ms":6000}' >/dev/null
curl -s -m 90 -o "$W/bg6.b" -w '%{http_code}' -X POST "$GATEWAY/api/v1/sagas/book-seats" \
    -H "Authorization: Bearer $T6" -H "Idempotency-Key: $K6" -H "X-Correlation-Id: $C6" -H 'Content-Type: application/json' \
    -d "{\"showtimeId\":\"$SHOWTIME\",\"seatLabels\":[\"D1\",\"D2\"]}" > "$W/bg6.code" &
BG6=$!
tries=0
until [ "$(tap_lines "corr=$C6" | grep -c 'holds status=201')" = 1 ] || [ $tries -ge 100 ]; do sleep 0.2; tries=$((tries + 1)); done
psql_admin "CREATE FUNCTION public.e2e_block_reservation_update() RETURNS trigger LANGUAGE plpgsql AS \$\$ BEGIN RAISE EXCEPTION 'e2e fault injection'; END \$\$; CREATE TRIGGER e2e_block_reservation_update BEFORE UPDATE ON booking.reservation FOR EACH ROW EXECUTE FUNCTION public.e2e_block_reservation_update()" > /dev/null
curl -s -XPOST "$TAP/__tap/config" -d '{"confirm_delay_ms":0}' >/dev/null
wait $BG6
psql_admin "DROP TRIGGER e2e_block_reservation_update ON booking.reservation; DROP FUNCTION public.e2e_block_reservation_update()" > /dev/null
CODE=$(cat "$W/bg6.code"); BODY=$(cat "$W/bg6.b")
SAGA6=$(jget id); RES6=$(jget reservationId)
expect "POST answers 201" "$CODE" 201
expect "the saga is FAILED" "$(jget status)" FAILED
expect "failedStep is confirm-reservation" "$(jget failedStep)" confirm-reservation
expect_match "the hold is still reported as completed" "$(printf '%s' "$BODY" | grep -o '"completedSteps":\[[^]]*\]')" 'hold-seats'
CALLS6=$(tap_lines "$SAGA6")
expect "Booking answered 500 to the three attempts of the confirm, all with the same key" \
    "$(printf '%s\n' "$CALLS6" | grep -c "$RES6/confirm status=500 idem=$SAGA6:confirm-reservation corr=$C6 ")" 3
expect "Booking answered 500 to the three attempts of the release, all with the same key" \
    "$(printf '%s\n' "$CALLS6" | grep -c "$RES6/release status=500 idem=$SAGA6:release-seats corr=$C6 ")" 3
expect "Booking: the reservation stays HELD (the expiration sweep of the worker will take it)" "$(reservation_status "$RES6")" HELD
DETAIL6=$(psql_admin "select failure_detail from workflow.saga where id='$SAGA6'")
expect_match "workflow.saga.failure_detail keeps the detail of the failure" "$DETAIL6" '500'
expect_absent "the answer does not carry the detail" "$BODY" "$DETAIL6"
expect_absent "the answer does not carry the error code of Booking" "$BODY" INTERNAL_ERROR

# ---------------------------------------------------------------------------------------------------------------
section "5. The answer never carries the internal detail of a failure"
DETAIL2=$(psql_admin "select failure_detail from workflow.saga where id='$SAGA2'")
DETAIL4=$(psql_admin "select failure_detail from workflow.saga where id='$SAGA4'")
expect_match "workflow.saga.failure_detail keeps the detail of the failed confirm" "$DETAIL2" '422'
expect_match "workflow.saga.failure_detail keeps the detail of the failed hold" "$DETAIL4" '422'
for pair in "$SAGA2:$T2" "$SAGA4:$T4"; do
    id=${pair%%:*}; token=${pair#*:}
    req GET "/api/v1/sagas/$id" "$token" "" ""
    detail=$(psql_admin "select failure_detail from workflow.saga where id='$id'")
    expect_absent "GET of saga ${id%%-*} does not contain its failure detail" "$BODY" "$detail"
    expect_absent "GET of saga ${id%%-*} does not mention the Booking status or error code" "$BODY" "BUSINESS_RULE_VIOLATION"
    expect_absent "GET of saga ${id%%-*} does not mention INVALID_STATUS_TRANSITION" "$BODY" "INVALID_STATUS_TRANSITION"
    expect "GET of saga ${id%%-*} has only the documented keys" \
        "$(printf '%s' "$BODY" | grep -o '"[a-zA-Z]*":' | tr -d '\n')" '"id":"sagaName":"status":"completedSteps":"failedStep":"reservationId":"createdAt":"updatedAt":'
done

# ---------------------------------------------------------------------------------------------------------------
section "6. Input"
OK_BODY="{\"showtimeId\":\"$SHOWTIME\",\"seatLabels\":[\"C6\"]}"
req POST /api/v1/sagas/book-seats "" "e2e-in-$(new_uuid)" "" "$OK_BODY"
expect "no token: 401" "$CODE" 401
expect "no token: error envelope" "$(jget error)" UNAUTHORIZED
req POST /api/v1/sagas/book-seats "$(tamper "$T1")" "e2e-in-$(new_uuid)" "" "$OK_BODY"
expect "tampered signature: 401" "$CODE" 401
req POST /api/v1/sagas/book-seats "$(mint_token "$U1" csp-api -5)" "e2e-in-$(new_uuid)" "" "$OK_BODY"
expect "expired token: 401" "$CODE" 401
req POST /api/v1/sagas/book-seats "$WF_TOKEN" "e2e-in-$(new_uuid)" "" "$OK_BODY"
expect "service credential: 403" "$CODE" 403
req POST /api/v1/sagas/book-seats "$T1" "e2e-in-$(new_uuid)" "" '{"showtimeId":"not-a-uuid","seatLabels":[]}'
expect "invalid body: 400" "$CODE" 400
expect "invalid body: VALIDATION_ERROR" "$(jget error)" VALIDATION_ERROR
expect_match "invalid body: a detail for showtimeId" "$BODY" '"field":"showtimeId"'
expect_match "invalid body: a detail for seatLabels" "$BODY" '"field":"seatLabels"'
req POST /api/v1/sagas/book-seats "$T1" "e2e-in-$(new_uuid)" "" "{\"showtimeId\":\"$SHOWTIME\",\"seatLabels\":[\"C6\"],\"userId\":\"$(new_uuid)\"}"
expect "a userId in the body is rejected: 400" "$CODE" 400
req POST /api/v1/sagas/book-seats "$T1" "" "" "$OK_BODY"
expect "no Idempotency-Key: 400" "$CODE" 400
req GET "/api/v1/sagas/$(new_uuid)" "$T1" "" ""
expect "unknown saga: 404" "$CODE" 404
expect "unknown saga: error envelope" "$(jget error)" NOT_FOUND
req GET "/api/v1/sagas/$SAGA2" "$T1" "" ""
expect "a saga of another user: 404" "$CODE" 404
req GET "/api/v1/sagas/$SAGA1" "" "" ""
expect "GET without token: 401" "$CODE" 401
req GET "/api/v1/sagas/$SAGA1" "$WF_TOKEN" "" ""
expect "GET with a service credential: 403" "$CODE" 403
expect "every answer carries X-Correlation-Id" "$(header X-Correlation-Id | grep -Ec '.+')" 1
RESERVED=$(psql_admin "select count(*) from booking.reservation where user_id='$U1'")
expect "none of the rejected requests held a seat" "$RESERVED" 1

# ---------------------------------------------------------------------------------------------------------------
section "7. Recovery: the workflow is cut in the middle of a saga"
U5=$(new_uuid); T5=$(mint_token "$U5" csp-api 30); K5=e2e-recover-$(new_uuid); C5=$(new_uuid)
curl -s -XPOST "$TAP/__tap/config" -d '{"confirm_delay_ms":30000}' >/dev/null
curl -s -m 60 -o /dev/null -w '%{http_code}' -X POST "$GATEWAY/api/v1/sagas/book-seats" \
    -H "Authorization: Bearer $T5" -H "Idempotency-Key: $K5" -H "X-Correlation-Id: $C5" -H 'Content-Type: application/json' \
    -d "{\"showtimeId\":\"$SHOWTIME\",\"seatLabels\":[\"C1\",\"C2\"]}" > "$W/bg5.code" &
BG5=$!
tries=0
until [ "$(tap_lines "corr=$C5" | grep -c 'holds status=201')" = 1 ] || [ $tries -ge 100 ]; do sleep 0.2; tries=$((tries + 1)); done
sleep 1
docker kill "$WORKFLOW" >/dev/null
wait $BG5
SAGA5=$(saga_id_of_key "$K5")
expect "the workflow was cut while the saga was RUNNING" "$(psql_admin "select status from workflow.saga where id='$SAGA5'")" RUNNING
RES5=$(psql_admin "select id from booking.reservation where user_id='$U5'")
expect "Booking holds the reservation (HELD or already confirmed by the call in flight)" "$(reservation_status "$RES5" | grep -Ec '^(HELD|CONFIRMED)$')" 1
curl -s -XPOST "$TAP/__tap/config" -d '{"confirm_delay_ms":0}' >/dev/null
docker start "$WORKFLOW" >/dev/null
tries=0
until [ "$(psql_admin "select status from workflow.saga where id='$SAGA5'")" != RUNNING ] || [ $tries -ge 60 ]; do sleep 1; tries=$((tries + 1)); done
expect "after the restart the saga is COMPLETED" "$(psql_admin "select status from workflow.saga where id='$SAGA5'")" COMPLETED
expect "Booking: the reservation is CONFIRMED" "$(reservation_status "$RES5")" CONFIRMED
req GET "/api/v1/sagas/$SAGA5" "$T5" "" ""
expect "GET answers 200 with COMPLETED" "$CODE$(jget status)" 200COMPLETED
CALLS5=$(tap_lines "$SAGA5")
expect "the hold was not repeated: one hold call and one reservation" "$(printf '%s\n' "$CALLS5" | grep -c 'holds status=')$(psql_admin "select count(*) from booking.reservation where user_id='$U5'")" 11
expect "every call of the saga used the key <sagaId>:<step>" \
    "$(printf '%s\n' "$CALLS5" | grep -vc "idem=$SAGA5:\(hold-seats\|confirm-reservation\) ")" 0
expect "no release was sent" "$(printf '%s\n' "$CALLS5" | grep -c '/release')" 0
expect "Booking wrote one BookingConfirmed for it" "$(psql_admin "select count(*) from booking.outbox_event where aggregate_id='$RES5' and event_type='BookingConfirmed'")" 1
expect "the restart left the FAILED saga of section 2c alone" "$(psql_admin "select status from workflow.saga where id='$SAGA6'")" FAILED
docker logs "$WORKFLOW" 2>&1 | grep -q 'saga recovery resumed=1' && pass "the workflow logged saga recovery resumed=1" || fail "the workflow logged saga recovery resumed=1" "line not found"

# ---------------------------------------------------------------------------------------------------------------
printf '\nTOTAL passed=%s failed=%s\n' "$PASSED" "$FAILED"
[ "$FAILED" = 0 ]
