# 测试手册

## 前端

安装锁定依赖并执行静态检查：

```powershell
npm.cmd ci --cache target/npm-cache
npm.cmd run check
```

Vitest 单元测试及 V8 覆盖率：

```powershell
npm.cmd run test:unit
```

当前前端单元测试覆盖请求 ID 复用和批量请求按 ID 排序/版本传递。

## Java 单元测试与覆盖率

默认测试不要求 Docker，并排除 `docker-integration` 标签：

```powershell
.\mvnw.cmd clean verify "-Dfrontend.skip=true"
java scripts/CheckCoverageBaseline.java
```

JaCoCo 报告位于 `target/site/jacoco`。基线记录在 `scripts/jacoco-baseline.properties`，CI 禁止仓库级指令、分支、行或方法覆盖率下降。

## MySQL Testcontainers

```powershell
.\mvnw.cmd -Pdocker-integration-tests test "-Dfrontend.skip=true"
```

要求 Docker daemon 可用。测试使用 `mysql:8.0.43`，数据源由 `TestcontainersDatabaseSupport` 动态覆盖，不应指向日常业务库。

重点验证：

- 付款和冲销幂等；
- 导入任务并发确认；
- 采购、销售、维修、改装、调拨和盘点后的分仓/FIFO 对账；
- 统计 MySQL 聚合 SQL；
- Flyway 新建库与升级路径。

## Playwright

针对已启动的测试环境：

```powershell
$env:E2E_BASE_URL = "http://127.0.0.1:8080"
$env:E2E_USERNAME = "<username>"
$env:E2E_PASSWORD = "<password>"
npx playwright install chromium
npm.cmd run test:e2e
```

未提供账号时测试会明确跳过。CI 使用临时 MySQL 和测试账号启动应用后执行核心模块加载冒烟。

## 发布前命令

```powershell
npm.cmd run check
npm.cmd run test:unit
.\mvnw.cmd clean verify "-Dfrontend.skip=true"
java scripts/CheckCoverageBaseline.java
.\mvnw.cmd -Pdocker-integration-tests test "-Dfrontend.skip=true"
git diff --check
```

还应验证：

- `target/forklift-erp-0.2.0-rc.1.jar` 存在；
- `target/classes/META-INF/sbom/application.cdx.json` 存在；
- `/actuator/health` 为 `UP`；
- `/actuator/info` 的构建版本和 Git 信息正确。

## 数据恢复测试

应用内数据恢复先调用超级管理员 dry-run，再使用确认短语执行。Synology 数据库和附件恢复演练使用：

```sh
sh deploy/synology/restore-drill.sh [backup-directory]
```
