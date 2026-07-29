# 0.2 发布清单

## 构建前

- [ ] `pom.xml`、镜像标签和 CHANGELOG 版本一致。
- [ ] V37–V45 未修改且校验和稳定，V46–V51 已通过当前 MySQL 迁移门禁。
- [ ] 历史修复 dry-run 无阻塞异常。
- [ ] 工作树只包含本次发布改动。

## 自动验证

- [ ] `npm run check`
- [ ] `npm run test:unit`
- [ ] `mvn clean verify -Dfrontend.skip=true`
- [ ] `java scripts/CheckCoverageBaseline.java`
- [ ] `mvn -Pdocker-integration-tests test -Dfrontend.skip=true`
- [ ] Playwright 核心流程冒烟
- [ ] C25/C50 分级压力和 C50 五分钟 soak 达到本次发布阈值
- [ ] `git diff --check`
- [ ] JAR、Git 构建信息和 SBOM 已生成

## 发布

- [ ] 执行 `scripts/build-release.ps1`，版本与 `pom.xml` 一致。
- [ ] 校验 `SHA256SUMS.txt`。
- [ ] Synology `.env` 已配置独立备份目录。
- [ ] 执行 `sh update.sh <version> [image-tar]`。
- [ ] 脚本返回 `health=UP` 且 `/actuator/info` 版本匹配。

## 冒烟

- [ ] 登录与权限。
- [ ] 附件上传、预览和下载。
- [ ] 采购入库、销售出库。
- [ ] 收付款和冲销。
- [ ] 维修领料、调拨、盘点。
- [ ] 租赁跨月账单和归还末期账单。
- [ ] 重复付款/导入确认只产生一份事实。

## 发布后

- [ ] 统计接口无未预期的 `dataWarnings`。
- [ ] 日志包含请求关联 ID 和构建版本。
- [ ] 日备份任务成功，独立存储存在副本。
- [ ] 记录发布日期、Git 提交、镜像摘要和备份位置。

## 0.2.0-rc.1 本地门禁记录

- [x] 前端检查、Vitest 7/7。
- [x] Java 单元测试 140/140，JaCoCo 四项覆盖率不低于基线。
- [x] MySQL Testcontainers 51/51，V1→V45 与 V36→V45 通过。
- [x] Playwright 7/7。
- [x] C50 五分钟 92,666 请求、零错误、整体 p95 425.9 ms。
- [x] 本地隔离备份恢复通过，数据库、应用和附件链路均可访问。
- [ ] 真实 Synology 镜像导入、部署、恢复和真机压力测试。

## V46-V51 本地门禁记录（2026-07-19）

- [x] 前端检查通过；Vitest 7/7，语句/函数/行 100%，分支 87.5%。
- [x] `clean verify` 通过，默认 Java 测试 222/222，JaCoCo 四项高于基线。
- [x] MySQL 8.0.43 Testcontainers 发现 64 项（20 类），0 failures、0 errors；1 项外部 MySQL 条件用例按设计跳过。
- [x] 空库 V1→V51、V18/V36/V40→V51、Flyway `validate` 和 V40 预检通过。
- [x] MySQL V51 fixture verifies payment/financial-event reversal pairing,
      amount/source/counterparty/self/chain/duplicate guards, financial sign
      and cash source-line guards, FIFO unlinked-negative and
      opposite quantity/cost guards, and the 9,999/10,000/20,001 precision
      tolerance boundaries.
- [x] Re-run the V1/V18/V36/V40-to-V51 fixtures after the latest financial
      event sign, type, source-line and counterparty identity constraints.
- [x] V51 rejects incomplete historical stock-movement reversals before
      persistent DDL, backfills complete line pairs and enforces exact
      resource/warehouse/lot/quantity/balance/money identity for every linked
      reversal line (focused MySQL 8.0.43 tests: 2/2).
- [x] JAR、CycloneDX SBOM 与 JaCoCo 报告已生成。

## V46-V51 additional release gates

- [ ] Stop every application/import writer before taking the upgrade backup.
- [ ] Run `scripts/mysql-upgrade-preflight-v40.sql` against the production
      snapshot and archive its complete output with the release evidence.
- [ ] Reconcile every non-deterministic preflight finding before deployment.
- [ ] After V51, require zero unexplained OPEN `migration_exception` rows;
      specifically review FIFO balance, historical sales posting and inferred
      historical cost findings.
- [ ] Verify `flyway_schema_history` ends at V51 and `validate` succeeds.
- [x] Run the V18/V36-to-V51 Testcontainers upgrade fixture on MySQL 8.0.43.
- [ ] Exercise invoice and contract replacement, confirming that exactly one
      active metadata row exists and the previous file disappears only after
      commit.
- [ ] Export a JSON backup in `forklift-erp-json-backup-v2` format and run both
      dry-run and isolated restore validation; v1 JSON is intentionally rejected.
- [ ] Confirm the production profile logs `baseline-on-migrate=false`.

The earlier checked V1/V36-to-V45 entries below are historical RC evidence; they
do not prove the V46-V51 gates above.
