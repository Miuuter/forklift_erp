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

## Upgrade

1. Back up MySQL with a consistent `mysqldump` and snapshot `data/uploads`.
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

Run an isolated monthly restore drill:

```sh
sh restore-drill.sh
```

The drill validates checksums and uploads, starts a temporary MySQL container,
restores the dump, checks the restored tables, and then removes the container.

Flyway upgrades the schema on startup. Rolling back therefore requires a
matching database backup as well as the previous image tag.
