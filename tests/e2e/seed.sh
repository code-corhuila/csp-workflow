#!/usr/bin/env sh
# Seeds the real Catalog of the disposable platform with one movie, one room and one showtime, through the gateway and
# with the administration routes of csp-catalog-api. The administrator is a synthetic user whose token carries the three
# permissions the service checks. Prints SHOWTIME_ID=<uuid> and writes it to $E2E_WORK/showtime.id.
set -eu
. "$(dirname "$0")/lib.sh"

ADMIN_TOKEN=$(mint_token 00000000-0000-4000-8000-0000000000ad csp-api 30 '["catalog:write","room:write","showtime:write"]')
BASE=$GATEWAY/api/v1/catalog
run=$(openssl rand -hex 4)

# post <path> <json>: prints the id of the created resource, stops on anything but 201.
post() {
    out=$(curl -s -w '\n%{http_code}' -X POST "$BASE/$1" \
        -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
        -H "Idempotency-Key: e2e-seed-$(date +%s)-$$-$1" -d "$2")
    code=$(printf '%s\n' "$out" | tail -1)
    body=$(printf '%s\n' "$out" | sed '$d')
    [ "$code" = 201 ] || { echo "seed $1 failed: HTTP $code $body" >&2; exit 1; }
    printf '%s' "$body" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p'
}

movie=$(post movies "{\"title\":\"E2E Synthetic Movie $run\",\"synopsis\":\"Synthetic.\",\"durationMinutes\":90,\"classification\":\"PG\",\"genres\":[\"Drama\"],\"status\":\"PUBLISHED\"}")

seats=
for row in A B C D; do
    for n in 1 2 3 4 5 6; do
        seats="$seats{\"label\":\"$row$n\",\"row\":\"$row\",\"number\":$n,\"type\":\"STANDARD\"},"
    done
done
room=$(post rooms "{\"name\":\"E2E Room $run\",\"seats\":[${seats%,}]}")

year=$(( $(date +%Y) + 1 ))
showtime=$(post showtimes "{\"movieId\":\"$movie\",\"roomId\":\"$room\",\"startsAt\":\"$year-06-01T18:00:00Z\",\"endsAt\":\"$year-06-01T19:30:00Z\",\"priceCents\":1500000}")

echo "$showtime" > "$E2E_WORK/showtime.id"
echo "SHOWTIME_ID=$showtime"
