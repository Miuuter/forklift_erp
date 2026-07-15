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
DAILY_DIR="$BACKUP_ROOT/daily/$TIMESTAMP"
WEEKLY_DIR=""

if ! docker compose ps --status running --services | grep -qx 'mysql'; then
    echo "The MySQL service is not running; backup aborted." >&2
    exit 1
fi

mkdir -p "$DAILY_DIR"
echo "Backing up MySQL to $DAILY_DIR/forklift_erp.sql.gz"
docker compose exec -T mysql sh -c \
    'exec mysqldump --single-transaction --routines --triggers --events -uroot -p"$MYSQL_ROOT_PASSWORD" forklift_erp' \
    | gzip -9 > "$DAILY_DIR/forklift_erp.sql.gz"

echo "Backing up uploads to $DAILY_DIR/uploads.tar.gz"
mkdir -p data/uploads
tar -czf "$DAILY_DIR/uploads.tar.gz" -C data uploads

{
    echo "created_at=$(date '+%Y-%m-%dT%H:%M:%S%z')"
    echo "mode=$MODE"
    echo "erp_version=${ERP_VERSION:-unknown}"
    echo "git_commit=${ERP_GIT_COMMIT:-unknown}"
} > "$DAILY_DIR/backup.properties"

(
    cd "$DAILY_DIR"
    sha256sum forklift_erp.sql.gz uploads.tar.gz backup.properties > SHA256SUMS.txt
)

CREATE_WEEKLY=false
if [ "$MODE" = "--weekly" ]; then
    CREATE_WEEKLY=true
elif [ "$MODE" = "daily" ] && [ "$(date +%u)" = "7" ]; then
    CREATE_WEEKLY=true
fi
if [ "$CREATE_WEEKLY" = "true" ]; then
    WEEKLY_DIR="$BACKUP_ROOT/weekly/$TIMESTAMP"
    mkdir -p "$(dirname "$WEEKLY_DIR")"
    cp -a "$DAILY_DIR" "$WEEKLY_DIR"
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
    mkdir -p "$(dirname "$target_dir")"
    cp -a "$source_dir" "$target_dir"
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

echo "$DAILY_DIR" > "$BACKUP_ROOT/LATEST"
echo "Backup completed: $DAILY_DIR"
