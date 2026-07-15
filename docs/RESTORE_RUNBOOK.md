# 备份与恢复手册

## 备份策略

- 每日执行一次 `deploy/synology/backup.sh daily`。
- 每周日自动生成周备份；也可执行 `backup.sh --weekly`。
- 本地保留 7 个日备份、4 个周备份。
- `ERP_BACKUP_REMOTE_DIR` 必须指向独立卷、另一台 NAS 或已挂载的远程存储。
- 备份包含 MySQL 逻辑导出、`data/uploads`、元数据和 SHA-256 校验文件。

DSM 任务计划示例：

```text
每日 02:30: cd /volume1/docker/forklift-erp && sh backup.sh daily
每月 1 日 04:00: cd /volume1/docker/forklift-erp && sh restore-drill.sh
```

## 月度恢复演练

```sh
cd /volume1/docker/forklift-erp
sh restore-drill.sh
```

脚本会：

1. 校验备份 SHA-256；
2. 解包并检查 uploads；
3. 启动隔离网络和 MySQL 8 临时容器；
4. 导入数据库并核对活动附件元数据对应的文件；
5. 使用当前 `ERP_IMAGE:ERP_VERSION` 启动恢复后的隔离应用；
6. 验证健康、版本、登录、库存 API、Flyway、关键表和样例附件下载；
7. 清理临时应用、MySQL、网络和解压文件。

演练前必须确保：

- 目标应用镜像已经导入本机；
- `.env` 中存在 `ERP_JWT_SECRET`、`ERP_ADMIN_PASSWORD` 和正确的 `ERP_VERSION`；
- 若备份中的登录账号与引导账号不同，临时设置 `ERP_RESTORE_LOGIN_USERNAME`、`ERP_RESTORE_LOGIN_PASSWORD`；
- Docker CLI 可直接访问 daemon，不要把 Docker socket 暴露给不受信任的第三方容器。

可指定某次备份：

```sh
sh restore-drill.sh /volume2/forklift-erp-backup/daily/20260715-023000
```

## 正式恢复

1. 停止 app，保留 MySQL 容器。
2. 再备份当前故障现场。
3. 校验目标备份 `SHA256SUMS.txt`。
4. 清空或重建目标数据库后导入 `forklift_erp.sql.gz`。
5. 将 `uploads.tar.gz` 解压到部署目录，确认属主允许 UID/GID `10001` 读取。
6. 使用与该备份数据库版本匹配的镜像启动应用。
7. 检查健康、Flyway、登录、附件和核心业务。

数据库迁移不可简单通过旧镜像回滚；发生结构升级后，回滚必须同时恢复匹配的数据库备份。

## 0.2.0-rc.1 本地演练记录

2026-07-15 至 2026-07-16 的隔离恢复已验证 41 张表、1,450 台车辆、445 个客户、1,290 张出库订单、25 项配件、1 个活动附件和 2 个 uploads 文件。恢复后的应用通过 V45、健康、版本、登录、库存和附件访问检查。真实 Synology 演练仍需在目标 NAS 上重复执行并记录镜像摘要、备份路径和耗时。
