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
- V46：历史收款/配件单价确定性修复，以及不可证明成本异常登记。
- V47：非空乐观锁版本和统一二进制技术幂等键。
- V48：账本算术恒等式，以及 `financial_event` 和库存流水表头的来源/资源身份、单次冲销和冲销链外键保护；财务冲销同时匹配对手方身份，非冲销事实不得为负，现金事件不得绑定明细行；库存流水明细的经济相反性仍需业务对账。
- V49：默认仓/默认配置唯一性及配置值归属约束。
- V50：客户、仓库、配置、移动、批次和工单的直接关系外键；付款记录与现金财务事件的冲销配对；FIFO 消耗的批次/来源/数量/单价/总额复合配对，以及六位小数单位成本和分币总成本。
- V51：库存流水冲销明细的完整集合预检、确定性一对一回填，以及资源/仓库/批次/数量前后值和报表金额身份的复合外键保护。

## 从 V36 快照升级

1. 停止业务写入。
2. 运行 `deploy/synology/backup.sh --pre-release`，确认数据库和 uploads 校验文件存在。
3. 在旧版本或兼容维护环境调用历史修复 dry-run：

   ```text
   GET /api/data-quality/historical-repair/dry-run
   ```

4. 处理报告中的阻塞异常，尤其是仓库、供应商、FIFO 数量、维修领料追踪和旧件估值。
5. 再次运行 dry-run，确认没有会阻止外键或数量约束的异常。
6. 运行 `scripts/mysql-upgrade-preflight-v40.sql` 并处理全部非确定性异常。
7. 部署目标版本，让 Flyway 顺序执行 V41–V51。
8. 检查 `/actuator/health`、Flyway 启动日志和统计接口 `dataWarnings`。

自动化回归通过 `FlywayUpgradeIntegrationTests` 从 V18 写入历史已结清配件销售，再在 V36/V40 写入事实夹具，执行 V40 预检并升级到 V51，最后执行 `validate` 和 JSON v2 全库恢复往返。该测试不替代生产升级前的脱敏快照演练。

## 全新建库

空库直接启动应用，Flyway 应从 V1 执行到最新版本。验证：

- `flyway_schema_history` 无失败记录；
- JPA `ddl-auto=validate` 通过；
- 能登录并访问库存、财务、租赁和附件接口。

## 失败处理

- 不要修改已执行迁移的校验和。
- 不要在约束失败时把旧字段静默复制回新事实表。
- 保留失败日志和 dry-run 报告，恢复升级前备份后再修复数据。
## V46-V51 reliability gate (2026-07-19)

V37-V45 remain immutable. The current schema hardening is deliberately split
into small migrations so a MySQL DDL failure has a narrower recovery boundary:

- V46 reconstructs only provable legacy receipts and part unit prices. Costs
  previously inferred from mutable master prices are cleared and recorded as
  OPEN `migration_exception` rows; they are never silently re-inferred.
- V47 makes every optimistic-lock version non-null and gives all technical
  idempotency keys the same binary collation.
- V48 enforces ledger arithmetic and one reversal per financial fact, with
  exact event/source/counterparty identity matching. Original facts cannot be
  negative, cash facts cannot carry a source-line identity, and unknown event
  types are rejected. Its stock-movement rule protects the
  movement header only; it does not prove that every movement line is present
  or economically opposite. Append-only behavior is also a service and
  database-permission contract, not a trigger that forbids every direct
  UPDATE/DELETE.
- V49 enforces one default warehouse/value and configuration item/value
  ownership.
- V50 adds database foreign keys for direct customer, warehouse, configuration,
  movement, lot and work-order relationships. Payment reversals must point to
  the same original payment and its exact reversed cash event. FIFO reversal
  rows must point to the same lot/resource/warehouse/source identity with
  opposite quantity and total cost; unlinked negative consumption rows are
  rejected. New FIFO lots carry authoritative cent-valued original and
  remaining cost amounts for deterministic rounding-tail allocation. Historical
  values are backfilled from legacy unit cost and are not proof of lost
  historical rounding tails. `unit_cost` is a six-decimal average; the final
  check permits the documented quantity-dependent rounding envelope around the
  authoritative `total_cost`. Polymorphic source pairs remain application
  validated.
- V51 binds each stock-movement reversal line to one original line. The V40
  preflight and the migration's own pre-DDL gate reject zero-line, missing-line
  and multiset mismatches before any persistent V51 schema change. Exact
  composite foreign keys then require the same resource, warehouse, lot,
  source-line and monetary snapshots, with opposite quantity and swapped
  before/after balances; one original line can be reversed only once.

MySQL does not provide deferred cross-row assertions. V51 therefore rejects
incomplete historical reversals before DDL and makes every inserted reversal
line exact, but a newly inserted reversal header exists briefly before its
child rows are written. Header and full line coverage must be created in one
application transaction; direct header-only database writes remain forbidden
by the operational database-permission contract and must be caught by the same
preflight during maintenance checks.

Before deploying V41 or later from a V40 snapshot, stop all writers and run:

```sh
mysql --database=forklift_erp --table \
  < scripts/mysql-upgrade-preflight-v40.sql
```

Every returned row must be reconciled or explicitly classified as a
deterministic V46 repair. The V40 preflight is read-only and does not exhaust
the V48/V50/V51 composite reversal-identity checks; the MySQL upgrade fixture and
post-migration validation must cover those cases. After migration, all OPEN
`migration_exception` rows are release blockers for financial/FIFO trust.
Never use `flyway repair` to
resume a partially applied MySQL DDL migration; restore the consistent
pre-upgrade database and uploads backup, correct the preflight findings, then
retry.

`FlywayUpgradeIntegrationTests` now carries both a V18 legacy settled part sale
and V36/V40 facts through V51, then checks receipt conservation, unit-price
repair, binary idempotency, non-null versions, arithmetic checks, defaults and
direct foreign keys.

On 2026-07-19 this fixture passed on MySQL 8.0.43 for both V18 and V36/V40
upgrade paths. The read-only V40 preflight also passed with both the historical
`utf8mb4_unicode_ci` columns and an `utf8mb4_0900_ai_ci` client connection;
its UNION output is explicitly collated so connection defaults cannot change
the result.
