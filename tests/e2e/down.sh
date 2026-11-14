#!/usr/bin/env sh
# Removes everything tests/e2e/up.sh created: the containers, images and volumes of the Compose project csp-e2e, the
# recording proxy and the disposable copy. The shared network `platform` is left alone.
set -eu
. "$(dirname "$0")/lib.sh"

docker rm -f "$TAP_NAME" >/dev/null 2>&1 || true
if [ -f "$ENV_FILE" ]; then
    compose --profile tooling down --volumes --rmi local --remove-orphans
fi
# Only a folder that up.sh made (it leaves versions.txt in it) is deleted, never an arbitrary E2E_WORK.
if [ -f "$E2E_WORK/versions.txt" ] && [ -d "$PLATFORM_DIR" ]; then rm -rf "${E2E_WORK:?}"; fi
echo "removed"
