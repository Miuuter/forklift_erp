#!/bin/sh
set -eu

# Exercise backup orchestration without a Docker daemon. Fake commands enforce
# that database/filesystem reads happen only while the app is stopped and that
# the EXIT handler restarts it after an archive failure.

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
RESTORE_SOURCE="$REPO_ROOT/deploy/synology/restore-drill.sh"
TEST_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/forklift-erp-backup-test.XXXXXX")
trap 'rm -rf -- "$TEST_ROOT"' EXIT INT TERM

# Keep the executable orchestration test coupled to the restore safety
# contract: exact storage scope, import fingerprints, and the quiescent marker
# must remain enforced when either script is edited.
for restore_contract in snapshot_consistency storage_scope file_fingerprint invoice_stored_file_name; do
    grep -F "$restore_contract" "$RESTORE_SOURCE" >/dev/null
done

for command_name in gzip tar sha256sum; do
    command -v "$command_name" >/dev/null 2>&1 || {
        echo "Missing command required by backup test: $command_name" >&2
        exit 1
    }
done

REAL_TAR=$(command -v tar)
REAL_GZIP=$(command -v gzip)
CASE_DIR="$TEST_ROOT/case"
BIN_DIR="$TEST_ROOT/bin"
STATE_FILE="$TEST_ROOT/app-state"
EVENT_LOG="$TEST_ROOT/events.log"
mkdir -p "$CASE_DIR/data/uploads/attachments" "$BIN_DIR"
printf 'running\n' > "$STATE_FILE"
: > "$EVENT_LOG"
printf 'fixture attachment\n' > "$CASE_DIR/data/uploads/attachments/attachment-fixture.txt"
cp "$REPO_ROOT/deploy/synology/backup.sh" "$CASE_DIR/backup.sh"

cat > "$CASE_DIR/.env" <<EOF
ERP_BACKUP_DIR=$CASE_DIR/backup
ERP_BACKUP_HEALTH_ATTEMPTS=1
ERP_BACKUP_HEALTH_INTERVAL=0
ERP_BACKUP_APP_STOP_TIMEOUT=5
ERP_HTTP_PORT=18080
EOF

cat > "$BIN_DIR/docker" <<'EOF'
#!/bin/sh
set -eu
STATE_FILE=${MOCK_STATE_FILE:?}
EVENT_LOG=${MOCK_EVENT_LOG:?}
printf '%s\n' "docker:$*" >> "$EVENT_LOG"
[ "${1:-}" = compose ] || exit 2
shift
case "${1:-}" in
    ps)
        if grep -qx running "$STATE_FILE"; then printf '%s\n' mysql app; else printf '%s\n' mysql; fi
        ;;
    stop)
        grep -qx running "$STATE_FILE" || exit 3
        printf 'stopped\n' > "$STATE_FILE"
        printf '%s\n' app-stopped >> "$EVENT_LOG"
        ;;
    exec)
        grep -qx stopped "$STATE_FILE" || exit 4
        case "$*" in
            *information_schema.processlist*)
                printf '0\n'
                printf '%s\n' database-connections-drained >> "$EVENT_LOG"
                ;;
            *)
                printf '%s\n' database-fixture
                printf '%s\n' database-read-while-stopped >> "$EVENT_LOG"
                ;;
        esac
        ;;
    up)
        grep -Eq '^(stopped|running)$' "$STATE_FILE" || exit 5
        printf 'running\n' > "$STATE_FILE"
        printf '%s\n' app-started >> "$EVENT_LOG"
        ;;
    *) exit 2 ;;
esac
EOF

cat > "$BIN_DIR/tar" <<EOF
#!/bin/sh
set -eu
grep -qx stopped "$STATE_FILE" || exit 6
printf '%s\n' uploads-read-while-stopped >> "$EVENT_LOG"
[ "\${MOCK_FAIL_TAR:-0}" != 1 ] || exit 42
exec "$REAL_TAR" "\$@"
EOF

cat > "$BIN_DIR/gzip" <<EOF
#!/bin/sh
set -eu
grep -qx stopped "$STATE_FILE" || exit 7
printf '%s\n' dump-compressed-while-stopped >> "$EVENT_LOG"
exec "$REAL_GZIP" "\$@"
EOF

cat > "$BIN_DIR/curl" <<'EOF'
#!/bin/sh
set -eu
grep -qx running "${MOCK_STATE_FILE:?}"
[ "${MOCK_FAIL_HEALTH:-0}" != 1 ] || exit 22
printf '%s\n' '{"status":"UP"}'
EOF
chmod +x "$BIN_DIR/docker" "$BIN_DIR/tar" "$BIN_DIR/gzip" "$BIN_DIR/curl"

run_backup() {
    PATH="$BIN_DIR:$PATH" MOCK_STATE_FILE="$STATE_FILE" MOCK_EVENT_LOG="$EVENT_LOG" \
        ERP_GIT_COMMIT=test ERP_VERSION=test sh "$CASE_DIR/backup.sh" daily
}

run_backup
grep -qx running "$STATE_FILE"
BACKUP_DIR=$(find "$CASE_DIR/backup/daily" -mindepth 1 -maxdepth 1 -type d -name '20??????-??????' | sort | tail -n 1)
[ -n "$BACKUP_DIR" ]
[ -s "$BACKUP_DIR/forklift_erp.sql.gz" ]
[ -s "$BACKUP_DIR/uploads.tar.gz" ]
grep -q '^snapshot_consistency=application-quiesced$' "$BACKUP_DIR/backup.properties"
(cd "$BACKUP_DIR" && sha256sum -c SHA256SUMS.txt >/dev/null)
manifest_lines=$(wc -l < "$BACKUP_DIR/SHA256SUMS.txt" | tr -d '[:space:]')
[ "$manifest_lines" -eq 3 ]
grep -Eq '^[0123456789abcdefABCDEF]{64}[[:space:]]+forklift_erp\.sql\.gz$' "$BACKUP_DIR/SHA256SUMS.txt"
grep -Eq '^[0123456789abcdefABCDEF]{64}[[:space:]]+uploads\.tar\.gz$' "$BACKUP_DIR/SHA256SUMS.txt"
grep -Eq '^[0123456789abcdefABCDEF]{64}[[:space:]]+backup\.properties$' "$BACKUP_DIR/SHA256SUMS.txt"
[ ! -d "$CASE_DIR/backup/.backup.lock" ]
[ -z "$(find "$CASE_DIR/backup/daily" -mindepth 1 -maxdepth 1 -type d -name '.*' -print -quit)" ]

stop_line=$(grep -n app-stopped "$EVENT_LOG" | head -n 1 | cut -d: -f1)
drain_line=$(grep -n database-connections-drained "$EVENT_LOG" | head -n 1 | cut -d: -f1)
dump_line=$(grep -n database-read-while-stopped "$EVENT_LOG" | head -n 1 | cut -d: -f1)
tar_line=$(grep -n uploads-read-while-stopped "$EVENT_LOG" | head -n 1 | cut -d: -f1)
start_line=$(grep -n app-started "$EVENT_LOG" | head -n 1 | cut -d: -f1)
[ "$stop_line" -lt "$dump_line" ]
[ "$stop_line" -lt "$drain_line" ]
[ "$drain_line" -lt "$dump_line" ]
[ "$dump_line" -lt "$tar_line" ]
[ "$tar_line" -lt "$start_line" ]

# A failed archive must still leave the service running and release the lock.
printf 'running\n' > "$STATE_FILE"
backup_count_before=$(find "$CASE_DIR/backup/daily" -mindepth 1 -maxdepth 1 -type d -name '20??????-??????' | wc -l | tr -d '[:space:]')
latest_before=$(cat "$CASE_DIR/backup/LATEST")
set +e
MOCK_FAIL_TAR=1 run_backup >/dev/null 2>&1
failure_status=$?
set -e
[ "$failure_status" -ne 0 ]
grep -qx running "$STATE_FILE"
[ ! -d "$CASE_DIR/backup/.backup.lock" ]
backup_count_after=$(find "$CASE_DIR/backup/daily" -mindepth 1 -maxdepth 1 -type d -name '20??????-??????' | wc -l | tr -d '[:space:]')
[ "$backup_count_after" -eq "$backup_count_before" ]
[ "$(cat "$CASE_DIR/backup/LATEST")" = "$latest_before" ]
[ -z "$(find "$CASE_DIR/backup/daily" -mindepth 1 -maxdepth 1 -type d -name '.*' -print -quit)" ]

# A failed health probe must not publish the staged backup. The EXIT handler
# retries the idempotent Compose start and still releases the lock.
printf 'running\n' > "$STATE_FILE"
set +e
MOCK_FAIL_HEALTH=1 run_backup >/dev/null 2>&1
health_status=$?
set -e
[ "$health_status" -ne 0 ]
grep -qx running "$STATE_FILE"
[ ! -d "$CASE_DIR/backup/.backup.lock" ]
[ "$(find "$CASE_DIR/backup/daily" -mindepth 1 -maxdepth 1 -type d -name '20??????-??????' | wc -l | tr -d '[:space:]')" -eq "$backup_count_before" ]
[ "$(cat "$CASE_DIR/backup/LATEST")" = "$latest_before" ]
[ -z "$(find "$CASE_DIR/backup/daily" -mindepth 1 -maxdepth 1 -type d -name '.*' -print -quit)" ]

# An app that was already stopped must remain stopped after a valid backup.
starts_before=$(grep -c '^app-started$' "$EVENT_LOG")
printf 'stopped\n' > "$STATE_FILE"
run_backup >/dev/null
grep -qx stopped "$STATE_FILE"
starts_after=$(grep -c '^app-started$' "$EVENT_LOG")
[ "$starts_after" -eq "$starts_before" ]

echo "Synology backup orchestration test passed"
