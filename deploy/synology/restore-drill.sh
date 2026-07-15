#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$SCRIPT_DIR"

if [ -f .env ]; then
    set -a
    # shellcheck disable=SC1091
    . ./.env
    set +a
fi

BACKUP_ROOT="${ERP_BACKUP_DIR:-$SCRIPT_DIR/backup}"
BACKUP_DIR="${1:-}"
MYSQL_IMAGE="${ERP_RESTORE_MYSQL_IMAGE:-mysql:8.0.43}"
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
CONTAINER="forklift-erp-restore-drill-$TIMESTAMP"
ROOT_PASSWORD="restore-drill-$TIMESTAMP"
TEMP_UPLOADS=$(mktemp -d "${TMPDIR:-/tmp}/forklift-erp-restore.XXXXXX")

if [ -z "$BACKUP_DIR" ]; then
    BACKUP_DIR=$(find "$BACKUP_ROOT/daily" -mindepth 1 -maxdepth 1 -type d -name '20??????-??????' \
        | sort | tail -n 1)
fi
if [ -z "$BACKUP_DIR" ] || [ ! -d "$BACKUP_DIR" ]; then
    echo "Backup directory not found. Usage: sh restore-drill.sh [backup-directory]" >&2
    exit 1
fi
for required in forklift_erp.sql.gz uploads.tar.gz SHA256SUMS.txt; do
    if [ ! -f "$BACKUP_DIR/$required" ]; then
        echo "Required backup file missing: $BACKUP_DIR/$required" >&2
        exit 1
    fi
done

cleanup() {
    docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
    rm -rf -- "$TEMP_UPLOADS"
}
trap cleanup EXIT INT TERM

echo "Verifying backup checksums"
(
    cd "$BACKUP_DIR"
    sha256sum -c SHA256SUMS.txt
)
tar -tzf "$BACKUP_DIR/uploads.tar.gz" >/dev/null
tar -xzf "$BACKUP_DIR/uploads.tar.gz" -C "$TEMP_UPLOADS"

echo "Starting isolated MySQL restore container $CONTAINER"
docker run -d --name "$CONTAINER" \
    -e MYSQL_ROOT_PASSWORD="$ROOT_PASSWORD" \
    -e MYSQL_DATABASE=forklift_erp \
    "$MYSQL_IMAGE" >/dev/null

ready=false
attempt=0
while [ "$attempt" -lt 60 ]; do
    if docker exec "$CONTAINER" mysqladmin ping \
        -h 127.0.0.1 -uroot -p"$ROOT_PASSWORD" --silent >/dev/null 2>&1; then
        ready=true
        break
    fi
    attempt=$((attempt + 1))
    sleep 2
done
if [ "$ready" != "true" ]; then
    echo "Restore-drill MySQL did not become ready." >&2
    exit 1
fi

echo "Restoring database dump"
gzip -dc "$BACKUP_DIR/forklift_erp.sql.gz" \
    | docker exec -i "$CONTAINER" mysql -uroot -p"$ROOT_PASSWORD" forklift_erp

TABLE_COUNT=$(docker exec "$CONTAINER" mysql -N -uroot -p"$ROOT_PASSWORD" \
    -e "select count(*) from information_schema.tables where table_schema='forklift_erp'")
if [ "${TABLE_COUNT:-0}" -lt 1 ]; then
    echo "Restore drill failed: no tables were restored." >&2
    exit 1
fi

UPLOAD_FILE_COUNT=$(find "$TEMP_UPLOADS" -type f | wc -l | tr -d ' ')
echo "Restore drill passed: tables=$TABLE_COUNT, upload_files=$UPLOAD_FILE_COUNT"
echo "Tested backup: $BACKUP_DIR"
