#!/usr/bin/env sh
# Builds a disposable copy of the platform and starts, under the Compose project csp-e2e, what the end-to-end test needs:
# PostgreSQL, MongoDB, the real csp-catalog-api, csp-booking-api, csp-workflow and the gateway on localhost:8000, plus the
# recording proxy between the workflow and Booking. Then it seeds Catalog with a movie, a room and a showtime.
#
#   REPOS_DIR  folder that holds the sibling repositories (required)
#   E2E_WORK   where the copy lives (default: $TMPDIR/csp-e2e)
#   BOOKING_API_REF  git ref of csp-booking-api to test (default: origin/develop), for a fix that is not merged yet
#
# The sibling repositories are read with `git archive origin/develop` and never modified; csp-workflow is the current
# commit of this checkout, so the test runs the code under review. Nothing is written inside any repository.
set -eu
. "$(dirname "$0")/lib.sh"

: "${REPOS_DIR:?set REPOS_DIR to the folder that holds the csp-* repositories}"
REPO_ROOT=$(cd "$E2E_HERE/../.." && pwd)

docker info >/dev/null 2>&1 || { echo "Docker is not running" >&2; exit 1; }
docker network inspect platform >/dev/null 2>&1 || docker network create platform >/dev/null
if curl -s -m 2 "$GATEWAY/" >/dev/null 2>&1; then echo "something already answers on $GATEWAY: stop it first" >&2; exit 1; fi
[ ! -e "$E2E_WORK" ] || { echo "$E2E_WORK exists: run tests/e2e/down.sh first" >&2; exit 1; }

mkdir -p "$PLATFORM_DIR"
for repo in csp-infra-postgres csp-booking-db csp-booking-api csp-auth-db csp-auth-api csp-worker csp-infra-mongo \
    csp-catalog-db csp-catalog-api csp-infra-redis csp-infra-rabbitmq csp-api-gateway; do
    git -C "$REPOS_DIR/$repo" fetch -q origin
    mkdir -p "$PLATFORM_DIR/$repo"
    ref=origin/develop
    [ "$repo" != csp-booking-api ] || ref=${BOOKING_API_REF:-origin/develop}
    git -C "$REPOS_DIR/$repo" archive "$ref" | tar -x -C "$PLATFORM_DIR/$repo"
    echo "$repo $(git -C "$REPOS_DIR/$repo" rev-parse --short "$ref") ($ref)" >> "$E2E_WORK/versions.txt"
done
mkdir -p "$PLATFORM_DIR/csp-workflow"
git -C "$REPO_ROOT" archive HEAD | tar -x -C "$PLATFORM_DIR/csp-workflow"
echo "csp-workflow $(git -C "$REPO_ROOT" rev-parse --short HEAD)" >> "$E2E_WORK/versions.txt"

# Environment: random passwords, the compose project of this test, a throwaway key pair and the tokens signed with it.
cp "$INFRA_DIR/env/dev.env.example" "$ENV_FILE"
for name in $(grep -E '^[A-Z_]*PASSWORD=\r?$' "$ENV_FILE" | tr -d '\r' | cut -d= -f1); do
    sed -i "s|^$name=\r\?\$|$name=$(openssl rand -hex 24)|" "$ENV_FILE"
done
sed -i "s|^MONGO_KEYFILE=\r\?\$|MONGO_KEYFILE=$(openssl rand -base64 48 | tr -d '\n')|" "$ENV_FILE"
sed -i 's|^COMPOSE_PROJECT_NAME=.*|COMPOSE_PROJECT_NAME=csp-e2e|' "$ENV_FILE"
(cd "$INFRA_DIR" && sh scripts/dev-keys.sh >/dev/null)
# The workflow talks to Booking through the recording proxy.
printf 'BOOKING_API_URL=http://booking-tap:8083/api/v1/booking\n' >> "$ENV_FILE"

compose up -d postgres
until [ "$(compose logs postgres 2>&1 | grep -c 'ready to accept connections')" -ge 2 ]; do sleep 2; done
for executor in booking-db-migrate workflow-db-migrate; do compose --profile tooling run --rm "$executor"; done
compose up -d mongodb
until [ "$(compose ps mongodb --format '{{.Health}}')" = healthy ]; do sleep 2; done
compose --profile tooling run --rm catalog-db-migrate

MSYS_NO_PATHCONV=1 docker run -d --name "$TAP_NAME" --network platform --network-alias booking-tap -p 127.0.0.1:18083:8083 \
    -v "$(cd "$E2E_HERE" && { pwd -W 2>/dev/null || pwd; })/tap.py:/tap.py:ro" python:3.12-alpine python -u /tap.py >/dev/null
compose up -d --build catalog-api booking-api workflow api-gateway
for service in catalog-api booking-api; do
    until [ "$(compose ps "$service" --format '{{.Health}}')" = healthy ]; do sleep 3; done
done
until [ "$(curl -s -o "$E2E_WORK/probe" -w '%{http_code}' "$GATEWAY/api/v1/sagas/00000000-0000-4000-8000-000000000000")" = 401 ]; do sleep 2; done

sh "$E2E_HERE/seed.sh"
echo "platform copy ready in $E2E_WORK (versions in versions.txt)"
