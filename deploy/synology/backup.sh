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

MODE="${1:-daily}"
case "$MODE" in
    daily|--weekly|--pre-release) ;;
    *)
        echo "Usage: sh backup.sh [daily|--weekly|--pre-release]" >&2
        exit 2
        ;;
esac

BACKUP_ROOT="${ERP_BACKUP_DIR:-$SCRIPT_DIR/backup}"
REMOTE_ROOT="${ERP_BACKUP_REMOTE_DIR:-}"
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
DAILY_PARENT="$BACKUP_ROOT/daily"
DAILY_DIR="$DAILY_PARENT/$TIMESTAMP"
STAGING_DIR="$DAILY_PARENT/.$TIMESTAMP.$$"
WEEKLY_DIR=""
WEEKLY_STAGING=""
LOCK_DIR="${ERP_BACKUP_LOCK_DIR:-$BACKUP_ROOT/.backup.lock}"
LATEST_TMP="$BACKUP_ROOT/.LATEST.$$"
APP_STOP_TIMEOUT="${ERP_BACKUP_APP_STOP_TIMEOUT:-60}"
DB_DRAIN_ATTEMPTS="${ERP_BACKUP_DB_DRAIN_ATTEMPTS:-60}"
HEALTH_ATTEMPTS="${ERP_BACKUP_HEALTH_ATTEMPTS:-60}"
HEALTH_INTERVAL="${ERP_BACKUP_HEALTH_INTERVAL:-2}"
HEALTH_URL="${ERP_BACKUP_HEALTH_URL:-http://127.0.0.1:${ERP_HTTP_PORT:-8080}/actuator/health}"
SQL_DUMP_TMP="$STAGING_DIR/.forklift_erp.sql.$$"
SQL_ARCHIVE_TMP="$STAGING_DIR/.forklift_erp.sql.gz.$$"
UPLOADS_ARCHIVE_TMP="$STAGING_DIR/.uploads.tar.gz.$$"
PROPERTIES_TMP="$STAGING_DIR/.backup.properties.$$"
APP_WAS_RUNNING=false
APP_STOPPED_BY_BACKUP=false
APP_RESUMED=false
LOCK_ACQUIRED=false
STAGING_CREATED=false
PUBLISHED=false
QUIESCED_AT=""
RESUMED_AT=""

case "$APP_STOP_TIMEOUT" in
    ''|*[!0-9]*)
        echo "ERP_BACKUP_APP_STOP_TIMEOUT must be a non-negative integer." >&2
        exit 2
        ;;
esac
case "$HEALTH_ATTEMPTS" in
    ''|*[!0-9]*)
        echo "ERP_BACKUP_HEALTH_ATTEMPTS must be a non-negative integer." >&2
        exit 2
        ;;
esac
case "$DB_DRAIN_ATTEMPTS" in
    ''|*[!0-9]*|0)
        echo "ERP_BACKUP_DB_DRAIN_ATTEMPTS must be a positive integer." >&2
        exit 2
        ;;
esac
case "$HEALTH_INTERVAL" in
    ''|*[!0-9]*)
        echo "ERP_BACKUP_HEALTH_INTERVAL must be a non-negative integer." >&2
        exit 2
        ;;
esac

cleanup_temporary_dump() {
    rm -f -- "$SQL_DUMP_TMP" "$SQL_ARCHIVE_TMP" "$UPLOADS_ARCHIVE_TMP" "$PROPERTIES_TMP" "$LATEST_TMP"
    if [ "$PUBLISHED" != "true" ] && [ "$STAGING_CREATED" = "true" ]; then
        rm -rf -- "$STAGING_DIR"
    fi
    if [ -n "$WEEKLY_STAGING" ]; then
        rm -rf -- "$WEEKLY_STAGING"
    fi
}

running_services() {
    docker compose ps --status running --services
}

wait_for_database_quiescence() {
    attempt=0
    while [ "$attempt" -lt "$DB_DRAIN_ATTEMPTS" ]; do
        if APP_CONNECTIONS=$(docker compose exec -T mysql sh -c \
            'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -N -uroot -e "SELECT COUNT(*) FROM information_schema.processlist WHERE USER = 0x666f726b6c6966745f657270"' \
            2>/dev/null); then
            APP_CONNECTIONS=$(printf '%s' "$APP_CONNECTIONS" | tr -d '[:space:]')
            case "$APP_CONNECTIONS" in
                0) return 0 ;;
                *[!0-9]*|'') ;;
            esac
        fi
        attempt=$((attempt + 1))
        [ "$attempt" -lt "$DB_DRAIN_ATTEMPTS" ] || break
        sleep 1
    done
    echo "Database still has ERP application connections after $DB_DRAIN_ATTEMPTS attempts; backup aborted." >&2
    return 1
}

wait_for_application() {
    # A successful `docker compose up` only means the container was created;
    # wait for the HTTP readiness probe before declaring the backup complete.
    if [ "$HEALTH_ATTEMPTS" -eq 0 ]; then
        echo "WARNING: application health probing is disabled (ERP_BACKUP_HEALTH_ATTEMPTS=0)." >&2
        return 0
    fi
    if ! command -v curl >/dev/null 2>&1; then
        echo "curl is required for the application health probe." >&2
        return 1
    fi
    attempt=0
    while [ "$attempt" -lt "$HEALTH_ATTEMPTS" ]; do
        if HEALTH_JSON=$(curl -fsS "$HEALTH_URL" 2>/dev/null) \
            && printf '%s' "$HEALTH_JSON" | grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"'; then
            return 0
        fi
        attempt=$((attempt + 1))
        [ "$attempt" -lt "$HEALTH_ATTEMPTS" ] || break
        sleep "$HEALTH_INTERVAL"
    done
    echo "Application health check failed after $HEALTH_ATTEMPTS attempts: $HEALTH_URL" >&2
    return 1
}

resume_application() {
    if [ "$APP_STOPPED_BY_BACKUP" != "true" ] || [ "$APP_WAS_RUNNING" != "true" ] || [ "$APP_RESUMED" = "true" ]; then
        return 0
    fi
    echo "Resuming ERP application after consistent snapshot"
    if ! docker compose up -d --no-deps app; then
        echo "The ERP application could not be restarted after backup." >&2
        return 1
    fi
    if ! wait_for_application; then
        echo "The ERP application container was recreated but did not become healthy." >&2
        return 1
    fi
    APP_RESUMED=true
    RESUMED_AT=$(date '+%Y-%m-%dT%H:%M:%S%z')
}

cleanup() {
    status=$?
    trap - EXIT INT TERM
    cleanup_temporary_dump || status=1
    if ! resume_application; then
        status=1
    fi
    if [ "$LOCK_ACQUIRED" = "true" ]; then
        if ! rmdir -- "$LOCK_DIR" 2>/dev/null; then
            echo "WARNING: could not remove backup lock $LOCK_DIR" >&2
            status=1
        fi
    fi
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

if ! RUNNING_SERVICES=$(running_services); then
    echo "Could not inspect Compose service state; backup aborted." >&2
    exit 1
fi
if ! printf '%s\n' "$RUNNING_SERVICES" | grep -qx 'mysql'; then
    echo "The MySQL service is not running; backup aborted." >&2
    exit 1
fi

mkdir -p "$BACKUP_ROOT"
if ! mkdir "$LOCK_DIR" 2>/dev/null; then
    echo "Another backup is already running (lock: $LOCK_DIR)." >&2
    exit 1
fi
LOCK_ACQUIRED=true

mkdir -p "$DAILY_PARENT"
while :; do
    TIMESTAMP=$(date +%Y%m%d-%H%M%S)
    DAILY_DIR="$DAILY_PARENT/$TIMESTAMP"
    STAGING_DIR="$DAILY_PARENT/.$TIMESTAMP.$$"
    SQL_DUMP_TMP="$STAGING_DIR/.forklift_erp.sql.$$"
    SQL_ARCHIVE_TMP="$STAGING_DIR/.forklift_erp.sql.gz.$$"
    UPLOADS_ARCHIVE_TMP="$STAGING_DIR/.uploads.tar.gz.$$"
    PROPERTIES_TMP="$STAGING_DIR/.backup.properties.$$"
    if [ ! -e "$DAILY_DIR" ] && [ ! -e "$STAGING_DIR" ]; then
        break
    fi
    sleep 1
done
if ! mkdir "$STAGING_DIR"; then
    echo "Could not create backup staging directory: $STAGING_DIR" >&2
    exit 1
fi
STAGING_CREATED=true

if printf '%s\n' "$RUNNING_SERVICES" | grep -qx 'app'; then
    APP_WAS_RUNNING=true
    APP_STOPPED_BY_BACKUP=true
    echo "Stopping ERP application for a consistent database/uploads snapshot"
    if ! docker compose stop --timeout "$APP_STOP_TIMEOUT" app; then
        echo "The ERP application could not be stopped; backup aborted." >&2
        exit 1
    fi
    if ! RUNNING_SERVICES=$(running_services); then
        echo "Could not verify ERP application shutdown; backup aborted." >&2
        exit 1
    fi
    if printf '%s\n' "$RUNNING_SERVICES" | grep -qx 'app'; then
        echo "The ERP application is still running after stop; backup aborted." >&2
        exit 1
    fi
else
    echo "ERP application is already stopped; using the existing quiescent state"
fi
if ! wait_for_database_quiescence; then
    exit 1
fi
QUIESCED_AT=$(date '+%Y-%m-%dT%H:%M:%S%z')

echo "Backing up MySQL to $DAILY_DIR/forklift_erp.sql.gz"
if ! docker compose exec -T mysql sh -c \
    'exec mysqldump --single-transaction --routines --triggers --events -uroot -p"$MYSQL_ROOT_PASSWORD" forklift_erp' \
    > "$SQL_DUMP_TMP"; then
    echo "MySQL dump failed; backup aborted." >&2
    exit 1
fi
if [ ! -s "$SQL_DUMP_TMP" ]; then
    echo "MySQL dump was empty; backup aborted." >&2
    exit 1
fi
if ! gzip -9 -c "$SQL_DUMP_TMP" > "$SQL_ARCHIVE_TMP"; then
    echo "MySQL dump compression failed; backup aborted." >&2
    exit 1
fi
mv -f -- "$SQL_ARCHIVE_TMP" "$STAGING_DIR/forklift_erp.sql.gz"
rm -f -- "$SQL_DUMP_TMP"

echo "Backing up uploads to $DAILY_DIR/uploads.tar.gz"
mkdir -p data/uploads
if ! tar -czf "$UPLOADS_ARCHIVE_TMP" -C data uploads; then
    echo "Uploads archive failed; backup aborted." >&2
    exit 1
fi
mv -f -- "$UPLOADS_ARCHIVE_TMP" "$STAGING_DIR/uploads.tar.gz"

# Restore service availability before copying/pruning backup tiers. The dump
# and uploads archive are complete at this point, so later failures cannot
# reopen the consistency window.
if ! resume_application; then
    echo "Backup artifacts were created, but application recovery failed." >&2
    exit 1
fi

{
    echo "created_at=$(date '+%Y-%m-%dT%H:%M:%S%z')"
    echo "mode=$MODE"
    echo "erp_version=${ERP_VERSION:-unknown}"
    echo "git_commit=${ERP_GIT_COMMIT:-unknown}"
    echo "snapshot_consistency=application-quiesced"
    echo "app_was_running=$APP_WAS_RUNNING"
    echo "app_stopped_for_backup=$APP_STOPPED_BY_BACKUP"
    echo "snapshot_quiesced_at=$QUIESCED_AT"
    echo "snapshot_resumed_at=$RESUMED_AT"
} > "$PROPERTIES_TMP"
mv -f -- "$PROPERTIES_TMP" "$STAGING_DIR/backup.properties"

(
    cd "$STAGING_DIR"
    sha256sum forklift_erp.sql.gz uploads.tar.gz backup.properties > SHA256SUMS.txt
)
(
    cd "$STAGING_DIR"
    sha256sum -c SHA256SUMS.txt >/dev/null
)
if [ -e "$DAILY_DIR" ]; then
    echo "Backup destination appeared during snapshot: $DAILY_DIR" >&2
    exit 1
fi
if ! mv -- "$STAGING_DIR" "$DAILY_DIR"; then
    echo "Could not publish completed backup: $DAILY_DIR" >&2
    exit 1
fi
STAGING_CREATED=false
PUBLISHED=true

CREATE_WEEKLY=false
if [ "$MODE" = "--weekly" ]; then
    CREATE_WEEKLY=true
elif [ "$MODE" = "daily" ] && [ "$(date +%u)" = "7" ]; then
    CREATE_WEEKLY=true
fi
if [ "$CREATE_WEEKLY" = "true" ]; then
    WEEKLY_DIR="$BACKUP_ROOT/weekly/$TIMESTAMP"
    mkdir -p "$(dirname "$WEEKLY_DIR")"
    if [ -e "$WEEKLY_DIR" ]; then
        echo "Weekly backup destination already exists: $WEEKLY_DIR" >&2
        exit 1
    fi
    WEEKLY_STAGING="$BACKUP_ROOT/weekly/.$TIMESTAMP.$$"
    if ! cp -a "$DAILY_DIR" "$WEEKLY_STAGING"; then
        echo "Weekly backup copy failed: $WEEKLY_DIR" >&2
        exit 1
    fi
    if ! mv -- "$WEEKLY_STAGING" "$WEEKLY_DIR"; then
        echo "Weekly backup publish failed: $WEEKLY_DIR" >&2
        exit 1
    fi
    WEEKLY_STAGING=""
    echo "Weekly backup created at $WEEKLY_DIR"
fi

copy_independent() {
    source_dir="$1"
    tier="$2"
    if [ -z "$REMOTE_ROOT" ]; then
        echo "WARNING: ERP_BACKUP_REMOTE_DIR is not configured; independent copy skipped." >&2
        return
    fi
    target_dir="$REMOTE_ROOT/$tier/$(basename "$source_dir")"
    target_parent=$(dirname "$target_dir")
    target_staging="$target_parent/.$(basename "$source_dir").$$"
    mkdir -p "$target_parent"
    if [ -e "$target_dir" ] || [ -e "$target_staging" ]; then
        echo "Independent backup destination already exists: $target_dir" >&2
        return 1
    fi
    if ! cp -a "$source_dir" "$target_staging"; then
        rm -rf -- "$target_staging"
        echo "Independent backup copy failed: $target_dir" >&2
        return 1
    fi
    if ! mv -- "$target_staging" "$target_dir"; then
        rm -rf -- "$target_staging"
        echo "Independent backup publish failed: $target_dir" >&2
        return 1
    fi
    echo "Independent backup copy created at $target_dir"
}

copy_independent "$DAILY_DIR" daily
if [ -n "$WEEKLY_DIR" ]; then
    copy_independent "$WEEKLY_DIR" weekly
fi

prune_tier() {
    tier_dir="$1"
    keep="$2"
    [ -d "$tier_dir" ] || return 0
    case "$tier_dir" in
        "$BACKUP_ROOT"/daily|"$BACKUP_ROOT"/weekly) ;;
        "$REMOTE_ROOT"/daily|"$REMOTE_ROOT"/weekly)
            [ -n "$REMOTE_ROOT" ] || return 0
            ;;
        *)
            echo "Refusing to prune unexpected path: $tier_dir" >&2
            exit 1
            ;;
    esac
    find "$tier_dir" -mindepth 1 -maxdepth 1 -type d -name '20??????-??????' \
        -exec basename {} \; \
        | sort -r \
        | tail -n "+$((keep + 1))" \
        | while IFS= read -r old_name; do
            [ -n "$old_name" ] || continue
            echo "Pruning $tier_dir/$old_name"
            rm -rf -- "$tier_dir/$old_name"
        done
}

prune_tier "$BACKUP_ROOT/daily" 7
prune_tier "$BACKUP_ROOT/weekly" 4
if [ -n "$REMOTE_ROOT" ]; then
    prune_tier "$REMOTE_ROOT/daily" 7
    prune_tier "$REMOTE_ROOT/weekly" 4
fi

printf '%s\n' "$DAILY_DIR" > "$LATEST_TMP"
mv -f -- "$LATEST_TMP" "$BACKUP_ROOT/LATEST"
echo "Backup completed: $DAILY_DIR"
