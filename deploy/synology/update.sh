#!/bin/sh
set -eu

VERSION="${1:-}"
IMAGE_ARCHIVE="${2:-}"

if [ -z "$VERSION" ]; then
    echo "Usage: sh update.sh <version> [image-tar]" >&2
    exit 2
fi

case "$VERSION" in
    *[!0-9A-Za-z._-]*)
        echo "Invalid version: $VERSION" >&2
        exit 2
        ;;
esac

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$SCRIPT_DIR"

if [ ! -f .env ]; then
    echo ".env does not exist. Configure it from .env.example first." >&2
    exit 1
fi

if [ -n "$IMAGE_ARCHIVE" ] && [ ! -f "$IMAGE_ARCHIVE" ]; then
    echo "Image archive not found: $IMAGE_ARCHIVE" >&2
    exit 1
fi

if ! docker compose ps --status running --services | grep -qx 'mysql'; then
    echo "The MySQL service is not running; refusing to upgrade without a database backup." >&2
    exit 1
fi

if [ ! -f backup.sh ]; then
    echo "backup.sh is missing from the release directory." >&2
    exit 1
fi
sh backup.sh --pre-release

TIMESTAMP=$(date +%Y%m%d-%H%M%S)

ENV_TMP=".env.$TIMESTAMP.tmp"
awk -v version="$VERSION" '
    BEGIN { updated = 0 }
    /^ERP_VERSION=/ { print "ERP_VERSION=" version; updated = 1; next }
    { print }
    END { if (!updated) print "ERP_VERSION=" version }
' .env > "$ENV_TMP"
mv "$ENV_TMP" .env

if [ -n "$IMAGE_ARCHIVE" ]; then
    echo "Loading $IMAGE_ARCHIVE"
    docker load --input "$IMAGE_ARCHIVE"
else
    echo "Pulling the configured ERP image tag $VERSION"
    docker compose pull app
fi

echo "Recreating the ERP application container"
docker compose up -d --no-deps app
docker compose ps app

set -a
# shellcheck disable=SC1091
. ./.env
set +a
BASE_URL="http://127.0.0.1:${ERP_HTTP_PORT:-8080}"
ready=false
attempt=0
while [ "$attempt" -lt 60 ]; do
    if HEALTH_JSON=$(curl -fsS "$BASE_URL/actuator/health" 2>/dev/null) \
        && echo "$HEALTH_JSON" | grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"'; then
        ready=true
        break
    fi
    attempt=$((attempt + 1))
    sleep 2
done
if [ "$ready" != "true" ]; then
    echo "Application health check failed after 120 seconds: $BASE_URL/actuator/health" >&2
    docker compose logs --tail=200 app >&2
    exit 1
fi

INFO_JSON=$(curl -fsS "$BASE_URL/actuator/info")
if ! echo "$INFO_JSON" | grep -Eq "\"version\"[[:space:]]*:[[:space:]]*\"$VERSION\""; then
    echo "Application is healthy but build version does not match $VERSION." >&2
    echo "$INFO_JSON" >&2
    exit 1
fi

echo "Upgrade completed: health=UP, version=$VERSION"
echo "Run login, attachment, and core business smoke tests before closing the release."
