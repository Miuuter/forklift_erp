# Synology deployment

This directory is copied into every release produced by
`scripts/build-release.ps1`.

## First deployment

1. Confirm the NAS architecture with `uname -m`: `x86_64` uses
   `linux/amd64`; `aarch64` uses `linux/arm64`.
2. Copy the generated release directory to the NAS, for example
   `/volume1/docker/forklift-erp`.
3. Import `forklift-erp-<version>-<architecture>.tar` in Container Manager.
4. Rename `.env.example` to `.env`, set `ERP_VERSION` to the imported tag, and
   replace every example password and secret.
5. Create `data/mysql`, `data/uploads`, and `data/logs`. The application image
   runs as UID/GID `10001`; grant that identity write access to uploads/logs.
6. Create a Container Manager project from `compose.yaml`, or run
   `docker compose up -d` over SSH.
7. Configure `ERP_BACKUP_REMOTE_DIR` on a separate volume or mounted remote share.
8. Verify `http://<nas-ip>:<ERP_HTTP_PORT>/actuator/health`, then log in.

Do not expose MySQL port 3306. Restrict the ERP port to the LAN in the DSM
firewall. Prefer a DSM reverse proxy with HTTPS for browser PWA support.

The supplied defaults cap the application at 1536 MiB and MySQL at 768 MiB,
use a 12-connection application pool, and cap MySQL at 50 connections. They
were validated in the local 0.2.0-rc.1 stress environment; keep them as the
initial NAS settings and tune only from measured DSM/Container Manager data.

## Upgrade

1. Back up MySQL and `data/uploads` from one quiescent point. `backup.sh`
   acquires a host lock, gracefully stops the running `app` service, writes
   both archives, and starts the app again only after the uploads archive is
   complete. This short maintenance window prevents an attachment row and
   its file from landing in different backup points.
2. Import the new image TAR or pull the new immutable image tag.
3. Change only `ERP_VERSION` in `.env`.
4. Recreate the app service with `docker compose up -d app`.
5. Check health, login, attachments, and the main business pages.

The same process is automated by `update.sh`. The supplied version must match
the Maven project version and `/actuator/info`. For an offline image TAR:

```sh
sh update.sh 0.2.0-rc.1 forklift-erp-0.2.0-rc.1-linux-amd64.tar
```

When `ERP_IMAGE` points to a registry, omit the TAR and the script pulls the
new tag:

```sh
sh update.sh 0.2.0-rc.1
```

The script refuses to continue if MySQL is not running, invokes `backup.sh`,
changes `ERP_VERSION`, recreates only the application container, waits for
health `UP`, and verifies the build version from `/actuator/info`.

## Backup and restore drill

Create a daily backup manually:

```sh
sh backup.sh daily
```

The script keeps seven daily and four weekly copies. It also copies them to
`ERP_BACKUP_REMOTE_DIR`. Configure DSM Task Scheduler to run it every night.

The application is normally unavailable only while the SQL dump and uploads
archive are being written. `ERP_BACKUP_APP_STOP_TIMEOUT` controls graceful
shutdown (default 60 seconds). The script then waits up to
`ERP_BACKUP_DB_DRAIN_ATTEMPTS` seconds for the application database user to
have no remaining MySQL sessions. The `ERP_BACKUP_HEALTH_*` settings control
the readiness probe after restart. Spring drains active requests for up to
`ERP_SHUTDOWN_TIMEOUT` (default 45 seconds), with a 60-second container grace
period. A failed dump, archive, or restart leaves a
non-zero exit status; the exit handler still attempts to restart an app that
the script stopped. A second overlapping backup is rejected by
`$ERP_BACKUP_DIR/.backup.lock`; remove that directory only after confirming no
backup process is still running.

`backup.properties` records `snapshot_consistency=application-quiesced`, the
quiesce timestamp, and the application resume timestamp. `LATEST` is updated
only after all artifacts are complete and checksummed. Daily, weekly and
independent copies are built in hidden staging directories and atomically
published; an interrupted copy is therefore not selectable as the latest
backup. The readiness probe requires `curl` unless `ERP_BACKUP_HEALTH_ATTEMPTS=0`
is explicitly chosen.

The restore drill rejects backups created by older scripts because they cannot
prove that the database and uploads were captured at one point. For a
manually reviewed legacy backup only, set `ERP_ALLOW_LEGACY_BACKUP=true`; the
drill will keep the exact attachment path and size checks but reports a
warning.

Run an isolated monthly restore drill:

```sh
sh restore-drill.sh
```

The drill requires the configured ERP image to be available locally. It
validates checksums and uploads, starts an isolated MySQL container, restores
the dump, verifies exact paths and sizes for active attachments and legacy
invoice/contract files, verifies retained import source fingerprints, starts
an isolated ERP application, and checks health, build version, login,
inventory access, critical tables, the expected Flyway version, and a sample
attachment download. It removes the temporary containers, network, and
extracted files on exit. Update `ERP_RESTORE_EXPECTED_FLYWAY_VERSION` whenever
a release adds a migration.

`ERP_JWT_SECRET` and `ERP_ADMIN_PASSWORD` must be present. If the restored
database uses a different existing login, set `ERP_RESTORE_LOGIN_USERNAME` and
`ERP_RESTORE_LOGIN_PASSWORD` for the drill without changing the normal
bootstrap account settings.

Flyway upgrades the schema on startup. Rolling back therefore requires a
matching database backup as well as the previous image tag.
