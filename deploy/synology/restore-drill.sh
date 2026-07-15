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
APP_IMAGE="${ERP_IMAGE:-forklift-erp}:${ERP_VERSION:-latest}"
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
NETWORK="forklift-erp-restore-drill-$TIMESTAMP"
MYSQL_CONTAINER="forklift-erp-restore-mysql-$TIMESTAMP"
APP_CONTAINER="forklift-erp-restore-app-$TIMESTAMP"
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
for required in forklift_erp.sql.gz uploads.tar.gz backup.properties SHA256SUMS.txt; do
    if [ ! -f "$BACKUP_DIR/$required" ]; then
        echo "Required backup file missing: $BACKUP_DIR/$required" >&2
        exit 1
    fi
done
if ! docker image inspect "$APP_IMAGE" >/dev/null 2>&1; then
    echo "ERP image is not available for restored application verification: $APP_IMAGE" >&2
    exit 1
fi

cleanup() {
    docker rm -f "$APP_CONTAINER" >/dev/null 2>&1 || true
    docker rm -f "$MYSQL_CONTAINER" >/dev/null 2>&1 || true
    docker network rm "$NETWORK" >/dev/null 2>&1 || true
    rm -rf -- "$TEMP_UPLOADS"
}
trap cleanup EXIT INT TERM

echo "Verifying backup checksums and uploads archive"
(
    cd "$BACKUP_DIR"
    sha256sum -c SHA256SUMS.txt
)
tar -tzf "$BACKUP_DIR/uploads.tar.gz" >/dev/null
tar -xzf "$BACKUP_DIR/uploads.tar.gz" -C "$TEMP_UPLOADS"
if [ ! -d "$TEMP_UPLOADS/uploads" ]; then
    echo "Restore drill failed: uploads directory is missing from the archive." >&2
    exit 1
fi

echo "Starting isolated MySQL restore container $MYSQL_CONTAINER"
docker network create "$NETWORK" >/dev/null
docker run -d --name "$MYSQL_CONTAINER" \
    --network "$NETWORK" \
    --network-alias mysql \
    -e MYSQL_ROOT_PASSWORD="$ROOT_PASSWORD" \
    -e MYSQL_DATABASE=forklift_erp \
    "$MYSQL_IMAGE" >/dev/null

ready=false
attempt=0
while [ "$attempt" -lt 60 ]; do
    if docker exec "$MYSQL_CONTAINER" mysqladmin ping \
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
    | docker exec -i "$MYSQL_CONTAINER" mysql -uroot -p"$ROOT_PASSWORD" forklift_erp

TABLE_COUNT=$(docker exec "$MYSQL_CONTAINER" mysql -N -uroot -p"$ROOT_PASSWORD" \
    -e "select count(*) from information_schema.tables where table_schema='forklift_erp'")
if [ "${TABLE_COUNT:-0}" -lt 1 ]; then
    echo "Restore drill failed: no tables were restored." >&2
    exit 1
fi

ATTACHMENT_LIST="$TEMP_UPLOADS/attachment-files.txt"
docker exec "$MYSQL_CONTAINER" mysql -N -uroot -p"$ROOT_PASSWORD" forklift_erp \
    -e "select stored_file_name from resource_attachment where deleted=0 order by id" \
    > "$ATTACHMENT_LIST"
MISSING_ATTACHMENTS=0
while IFS= read -r stored_name; do
    [ -n "$stored_name" ] || continue
    if ! find "$TEMP_UPLOADS/uploads" -type f -name "$stored_name" -print -quit | grep -q .; then
        echo "Missing restored attachment file: $stored_name" >&2
        MISSING_ATTACHMENTS=$((MISSING_ATTACHMENTS + 1))
    fi
done < "$ATTACHMENT_LIST"
if [ "$MISSING_ATTACHMENTS" -ne 0 ]; then
    echo "Restore drill failed: $MISSING_ATTACHMENTS attachment files are missing." >&2
    exit 1
fi
# mktemp normally creates a 0700 directory. The restored application runs as
# UID/GID 10001, so grant read/traverse access before mounting uploads read-only.
chmod -R a+rX "$TEMP_UPLOADS/uploads"

if [ -z "${ERP_JWT_SECRET:-}" ] || [ -z "${ERP_ADMIN_PASSWORD:-}" ]; then
    echo "ERP_JWT_SECRET and ERP_ADMIN_PASSWORD are required for restored application verification." >&2
    exit 1
fi

echo "Starting restored ERP application $APP_IMAGE"
docker run -d --name "$APP_CONTAINER" \
    --network "$NETWORK" \
    -p 127.0.0.1::8080 \
    -v "$TEMP_UPLOADS/uploads:/data/uploads:ro" \
    -e SPRING_PROFILES_ACTIVE=prod \
    -e FORKLIFT_ERP_SERVER_PORT=8080 \
    -e FORKLIFT_ERP_SSL_ENABLED=false \
    -e "FORKLIFT_ERP_DB_URL=jdbc:mysql://mysql:3306/forklift_erp?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true" \
    -e FORKLIFT_ERP_DB_USERNAME=root \
    -e FORKLIFT_ERP_DB_PASSWORD="$ROOT_PASSWORD" \
    -e FORKLIFT_ERP_JWT_SECRET="$ERP_JWT_SECRET" \
    -e FORKLIFT_ERP_JWT_EXPIRATION="${ERP_JWT_EXPIRATION:-86400000}" \
    -e FORKLIFT_ERP_ADMIN_USERNAME="${ERP_RESTORE_LOGIN_USERNAME:-${ERP_ADMIN_USERNAME:-admin}}" \
    -e FORKLIFT_ERP_ADMIN_PASSWORD="${ERP_RESTORE_LOGIN_PASSWORD:-$ERP_ADMIN_PASSWORD}" \
    -e FORKLIFT_ERP_SEED_DEMO_DATA=false \
    -e FORKLIFT_ERP_BUSINESS_DATA_RESET_ENABLED=false \
    -e FORKLIFT_ERP_DATA_RESTORE_ENABLED=false \
    -e FORKLIFT_ERP_ATTACHMENT_STORAGE_DIR=/data/uploads/attachments \
    -e FORKLIFT_ERP_INVOICE_STORAGE_DIR=/data/uploads/invoices \
    -e FORKLIFT_ERP_CONTRACT_STORAGE_DIR=/data/uploads/contracts \
    -e FORKLIFT_ERP_IMPORT_STORAGE_DIR=/data/uploads/imports \
    -e LOGGING_FILE_NAME=/tmp/application.log \
    "$APP_IMAGE" >/dev/null

APP_PORT=$(docker port "$APP_CONTAINER" 8080/tcp | awk -F: 'NR == 1 { print $NF }')
if [ -z "$APP_PORT" ]; then
    echo "Restore drill failed: temporary application port was not allocated." >&2
    exit 1
fi
BASE_URL="http://127.0.0.1:$APP_PORT"
ready=false
attempt=0
while [ "$attempt" -lt 75 ]; do
    if HEALTH_JSON=$(curl -fsS "$BASE_URL/actuator/health" 2>/dev/null) \
        && echo "$HEALTH_JSON" | grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"'; then
        ready=true
        break
    fi
    attempt=$((attempt + 1))
    sleep 2
done
if [ "$ready" != "true" ]; then
    echo "Restored ERP application did not become healthy." >&2
    docker logs --tail=200 "$APP_CONTAINER" >&2
    exit 1
fi

INFO_JSON=$(curl -fsS "$BASE_URL/actuator/info")
INFO_VERSION=$(printf '%s' "$INFO_JSON" \
    | sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')
if [ "$INFO_VERSION" != "${ERP_VERSION:-latest}" ]; then
    echo "Restored ERP version does not match ${ERP_VERSION:-latest}." >&2
    echo "$INFO_JSON" >&2
    exit 1
fi

json_escape() {
    printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'
}
LOGIN_USERNAME="${ERP_RESTORE_LOGIN_USERNAME:-${ERP_ADMIN_USERNAME:-admin}}"
LOGIN_PASSWORD="${ERP_RESTORE_LOGIN_PASSWORD:-$ERP_ADMIN_PASSWORD}"
LOGIN_JSON=$(curl -fsS \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"$(json_escape "$LOGIN_USERNAME")\",\"password\":\"$(json_escape "$LOGIN_PASSWORD")\"}" \
    "$BASE_URL/api/auth/login")
TOKEN=$(printf '%s' "$LOGIN_JSON" \
    | sed -n 's/.*"token"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')
if [ -z "$TOKEN" ]; then
    echo "Restore drill failed: restored application login did not return a token." >&2
    exit 1
fi
if ! curl -fsS -H "Authorization: Bearer $TOKEN" \
    "$BASE_URL/api/inventory?page=0&size=1" \
    | grep -Eq '"code"[[:space:]]*:[[:space:]]*200'; then
    echo "Restore drill failed: restored inventory API is unavailable." >&2
    exit 1
fi

SAMPLE_ATTACHMENT_ID=$(docker exec "$MYSQL_CONTAINER" mysql -N -uroot -p"$ROOT_PASSWORD" forklift_erp \
    -e "select id from resource_attachment where deleted=0 order by id limit 1")
if [ -n "$SAMPLE_ATTACHMENT_ID" ]; then
    if ! curl -fsS -H "Authorization: Bearer $TOKEN" \
        "$BASE_URL/api/attachments/$SAMPLE_ATTACHMENT_ID/download" >/dev/null; then
        echo "Restore drill failed: sample attachment $SAMPLE_ATTACHMENT_ID cannot be downloaded." >&2
        docker logs --tail=100 "$APP_CONTAINER" >&2
        exit 1
    fi
fi

LATEST_FLYWAY=$(docker exec "$MYSQL_CONTAINER" mysql -N -uroot -p"$ROOT_PASSWORD" forklift_erp \
    -e "select version from flyway_schema_history where success=1 order by installed_rank desc limit 1")
CRITICAL_TABLE_COUNT=$(docker exec "$MYSQL_CONTAINER" mysql -N -uroot -p"$ROOT_PASSWORD" \
    -e "select count(*) from information_schema.tables where table_schema='forklift_erp' and table_name in ('machine_inventory','part_inventory','stock_movement','stock_lot','financial_event','payment_record','resource_attachment','request_idempotency')")
if [ "$CRITICAL_TABLE_COUNT" -ne 8 ]; then
    echo "Restore drill failed: critical tables are missing after application startup." >&2
    exit 1
fi

UPLOAD_FILE_COUNT=$(find "$TEMP_UPLOADS/uploads" -type f | wc -l | tr -d ' ')
VEHICLE_COUNT=$(docker exec "$MYSQL_CONTAINER" mysql -N -uroot -p"$ROOT_PASSWORD" forklift_erp \
    -e "select count(*) from machine_inventory")
CUSTOMER_COUNT=$(docker exec "$MYSQL_CONTAINER" mysql -N -uroot -p"$ROOT_PASSWORD" forklift_erp \
    -e "select count(*) from customer_profile")
OUTBOUND_ORDER_COUNT=$(docker exec "$MYSQL_CONTAINER" mysql -N -uroot -p"$ROOT_PASSWORD" forklift_erp \
    -e "select count(*) from outbound_order")
PART_COUNT=$(docker exec "$MYSQL_CONTAINER" mysql -N -uroot -p"$ROOT_PASSWORD" forklift_erp \
    -e "select count(*) from part_inventory")
ATTACHMENT_COUNT=$(docker exec "$MYSQL_CONTAINER" mysql -N -uroot -p"$ROOT_PASSWORD" forklift_erp \
    -e "select count(*) from resource_attachment where deleted=0")

echo "Restore drill passed: tables=$TABLE_COUNT, flyway=$LATEST_FLYWAY, vehicles=$VEHICLE_COUNT, customers=$CUSTOMER_COUNT, outbound_orders=$OUTBOUND_ORDER_COUNT, parts=$PART_COUNT, attachments=$ATTACHMENT_COUNT, upload_files=$UPLOAD_FILE_COUNT"
echo "Restored application: health=UP, version=${ERP_VERSION:-latest}, login=OK"
echo "Tested backup: $BACKUP_DIR"
