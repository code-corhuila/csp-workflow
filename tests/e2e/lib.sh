#!/usr/bin/env sh
# Shared by the end-to-end scripts. DEVELOPMENT ONLY: it signs tokens with the throwaway key that
# csp-infra-postgres/scripts/dev-keys.sh wrote inside the disposable copy of the platform.

E2E_HERE=$(cd "$(dirname "$0")" && pwd)
E2E_WORK=${E2E_WORK:-${TMPDIR:-/tmp}/csp-e2e}
PLATFORM_DIR=$E2E_WORK/plat
INFRA_DIR=$PLATFORM_DIR/csp-infra-postgres
ENV_FILE=$INFRA_DIR/env/dev.env
GATEWAY=${GATEWAY:-http://localhost:8000}
TAP_NAME=csp-e2e-booking-tap

compose() {
    (cd "$INFRA_DIR" && docker compose --env-file env/dev.env "$@")
}

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }

# mint_token <sub> <aud> <minutes> [permissions-json-array]
# An RS256 token. Users carry aud csp-api; a service credential carries iss csp-auth and aud csp-internal (ADR-056).
mint_token() {
    sub=$1 aud=$2 minutes=$3 permissions=${4:-}
    now=$(date +%s)
    header=$(printf '{"alg":"RS256","typ":"JWT"}' | b64url)
    extra=
    [ -n "$permissions" ] && extra=",\"permissions\":$permissions"
    payload=$(printf '{"sub":"%s","iss":"csp-auth","aud":"%s","iat":%s,"exp":%s%s}' "$sub" "$aud" "$now" "$((now + minutes * 60))" "$extra" | b64url)
    signature=$(printf '%s.%s' "$header" "$payload" | openssl dgst -sha256 -binary -sign "$INFRA_DIR/keys/jwt-private.pem" | b64url)
    printf '%s.%s.%s\n' "$header" "$payload" "$signature"
}

# env_value <NAME>: the value of a variable of the disposable environment file.
env_value() {
    grep -E "^$1=" "$ENV_FILE" | head -1 | cut -d= -f2- | tr -d '\r'
}

# psql_admin <sql>: runs a statement as the administrator of the disposable PostgreSQL and prints the bare result.
psql_admin() {
    compose exec -T postgres psql -U "$(env_value PG_ADMIN_USER)" -d "$(env_value PG_DATABASE)" -At -c "$1"
}
