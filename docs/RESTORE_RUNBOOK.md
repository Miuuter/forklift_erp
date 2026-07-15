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
3. 启动隔离的 MySQL 8 临时容器；
4. 导入数据库并确认至少恢复一张表；
5. 清理临时容器和文件。

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
