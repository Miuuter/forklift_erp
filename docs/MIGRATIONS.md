# 数据库迁移说明

## 版本规则

- 已提交的 Flyway 迁移不可修改。
- V37–V40 视为冻结基线。
- 0.2 稳定化数据库变更从 V41 开始。
- 生产升级必须使用与目标 Git 提交一起构建的 JAR，禁止手工改表后绕过 Flyway。

## 本轮迁移

- V41：导入错误明细、付款请求 ID/创建人、财务创建人、配件补货点。
- V42：租赁账单、库存批次消耗、付款财务事件、导入行等直接关系外键。
- V43：数量约束和统计/对账索引。
- V44：`request_idempotency` 原子请求占位表，用于收付款及冲销的并发幂等。
- V45：库存流水与财务事件报表覆盖索引，支持日期范围聚合和日对账查询。

## 从 V36 快照升级

1. 停止业务写入。
2. 运行 `deploy/synology/backup.sh --pre-release`，确认数据库和 uploads 校验文件存在。
3. 在旧版本或兼容维护环境调用历史修复 dry-run：

   ```text
   GET /api/data-quality/historical-repair/dry-run
   ```

4. 处理报告中的阻塞异常，尤其是仓库、供应商、FIFO 数量、维修领料追踪和旧件估值。
5. 再次运行 dry-run，确认没有会阻止外键或数量约束的异常。
6. 部署目标版本，让 Flyway 顺序执行 V41–V45。
7. 检查 `/actuator/health`、Flyway 启动日志和统计接口 `dataWarnings`。

自动化回归通过 `FlywayUpgradeIntegrationTests` 先将同一 MySQL 8 数据库迁移到 V36，写入历史夹具，再依次升级到 V40 和 V45，并执行 `validate`。该测试不替代生产升级前的脱敏快照演练。

## 全新建库

空库直接启动应用，Flyway 应从 V1 执行到最新版本。验证：

- `flyway_schema_history` 无失败记录；
- JPA `ddl-auto=validate` 通过；
- 能登录并访问库存、财务、租赁和附件接口。

## 失败处理

- 不要修改已执行迁移的校验和。
- 不要在约束失败时把旧字段静默复制回新事实表。
- 保留失败日志和 dry-run 报告，恢复升级前备份后再修复数据。
