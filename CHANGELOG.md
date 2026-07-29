# 变更日志 (Changelog)

本项目遵循 [语义化版本](https://semver.org/lang/zh-CN/) 和 [Keep a Changelog](https://keepachangelog.com/zh-CN/) 规范。

## [Unreleased] - data reliability hardening

- Split V46-V51 into deterministic legacy reconciliation, binary idempotency
  and non-null versions, ledger arithmetic/single reversal constraints,
  configuration/default guards, and direct-reference foreign keys.
- Added a read-only V40 upgrade preflight that reports exact offending rows.
- Rebuilt missing legacy receipt facts and corrected part line-total/unit-price
  migration errors without inventing unverifiable historical costs.
- Hardened JSON backup v2 with exact structure, manifests, foreign-key checks
  before deletion and row/FK verification after restore.
- Made managed invoice/contract replacement retire old metadata in the same
  transaction and remove the old file only after commit.
- Added explicit monetary precision validation and service-level payment
  precision rejection.
- Preserved exact purchase receipt cost through FIFO partial consumption and
  final-unit rounding, including the 100/3 tail-allocation case.
- Bound payments, cash events, configuration changes, work-order lines and
  repair consumption to database-enforced composite identities.
- Bound stock-movement reversal lines one-to-one to exact original
  resource/warehouse/lot/quantity/balance and monetary identities, with a
  read-only historical completeness preflight before V51 DDL.
- Rejected procurement receipt of rented/locked serialized vehicles and kept
  any inventory master with FIFO, movement or operation history immutable.
- Froze billed rental customer/start-date/monthly-price facts and added
  automatic recovery for stale `IMPORTING` jobs after a configurable timeout.
- Rejected reuse of stock-movement, receipt-lot and FIFO-consumption
  idempotency keys when the immutable resource, warehouse, source, quantity or
  cost payload differs from the original fact.
- Applied the same payload check to serialized-asset FIFO cost adjustments,
  including the target lot identity and modification source line.
- Executed the real MySQL business flows for data-import recovery (2/2),
  procurement (4/4), and rental records (4/4) in a single clean run.
- Verified the current tree with 222 default Java tests and 64 MySQL integration
  tests across 20 classes (0 failures/errors, one external-environment skip),
  frontend checks and 7/7 Vitest tests on 2026-07-19. The data-import recovery,
  procurement and rental business flows were explicitly rerun as 2/2, 4/4 and
  4/4 against MySQL 8.0.43.

## [0.2.0-rc.1] - 2026-07-15 - Codex

### 稳定化

- 明确库存/FIFO 与财务/现金唯一事实来源，统计改为数据库范围聚合并返回迁移异常警告。
- 收付款和导入确认增加请求幂等、原子状态转换、创建人和操作审计。
- 新增服务端批量完成/收货/删除事务入口，前端不再并发拼接多次写请求。
- 配件返回分仓余额并按各自 `reorderPoint` 计算低库存。
- 租赁账单支持每日补账、显式刷新和归还末期按天账单。
- 上传文件执行扩展名、MIME 和文件签名联合校验，并增加 CSP。

### 工程

- Spring Boot 升级至 3.5.16，版本统一为 `0.2.0-rc.1`。
- 拆分历史修复、维修领料、改装记账、销售文档和前端选项模块，所有文件通过质量门禁。
- 新增 GitHub Actions、Vitest、Playwright、JaCoCo 覆盖率基线、Git 构建信息和 CycloneDX SBOM。
- 新增 Synology 备份、恢复演练、部署健康等待与版本核对。
- 新增 V41–V45；V37–V40 保持不可修改。
- 收付款幂等改为数据库原子请求占位，FIFO 调拨改为按批次主键精确加锁，消除相邻配件并发调拨的 next-key gap lock 死锁。
- 日对账改为余额驱动的最近流水与当日聚合查询；财务统计只查询一次五年月度事实，当前年度月表和年度汇总在 Java 中复用聚合。
- Synology Compose 增加应用/MySQL 内存、连接池、线程池和 MySQL 性能模式保守参数，并为应用镜像增加容器健康检查。

### 验证

- 前端质量检查和 7 项 Vitest 测试通过。
- 140 项 Java 单元测试通过，JaCoCo 指令/分支/行/方法覆盖率基线分别为 24.7058% / 21.5311% / 25.8292% / 27.4336%。
- 51 项 MySQL Testcontainers 集成测试和 7 项 Playwright 端到端冒烟测试通过。
- V1 空库迁移至 V45、V36 历史夹具升级至 V45 均通过，Flyway 校验无差异。
- C50 五分钟综合读取压力测试完成 92,666 次请求，零错误，整体 p95 425.9 ms；并发库存、付款幂等、导入确认、租赁补账和批量事务场景均通过。
- 本地空白环境完整恢复 41 张表、1,450 台车辆和 uploads 文件，健康、版本、登录、库存与附件下载验证通过。

## [0.1.50] - 2026-07-13 - Codex

### 新增
- **跨对象业务事务入口**
  - 新增整车库存与整车入库订单联合创建接口，任一步失败时整体回滚。
  - 新增快速客户与整车出库订单联合创建接口，避免出库失败后遗留孤立客户。
- **采购收货状态快照**
  - 新增 Flyway `V36__purchase_order_received_status_history.sql`，记录订单标记收货前的真实状态。

### 修复
- **多仓库存一致性**
  - 库存总量变化改为按差额对账仓库余额，避免部分调拨后再次入出库导致总库存虚增。
  - 零差异盘点也会执行分仓余额对账，用盘点确认总量修复历史余额漂移。
  - 删除库存档案前校验并清理空仓库余额，避免仓库汇总残留幽灵库存。
- **锁定资源写保护**
  - 盘点、仓库调拨和改装工单统一校验车辆及配件锁定状态，并在执行阶段再次校验。
  - 直接配置替换、配件替换、配件装车及取消改装工单同样执行锁定校验，关闭旁路写入口。
- **审计操作者归责**
  - 操作审计、库存日志和库存台账统一记录当前认证用户，不再信任请求体中的操作者名称。
- **采购订单状态恢复**
  - `ORDERED`、`PARTIAL`、`ARRIVED` 标记收货时保留原状态，撤销收货时准确恢复；取消订单禁止收货。
  - 前端始终显示真实采购状态，并区分“标记收货”和“撤销收货”操作。
- **业务删除约束**
  - 禁止删除进行中的租赁记录，避免绕过租赁车辆销售限制。
  - 已归还租赁重新激活时重新校验车辆在库、锁定状态及唯一活动租赁约束。
- **重复提交与部分写入**
  - 通用弹窗提交期间禁用全部表单和关闭控件并显示提交中状态，阻止双击并发写入。
  - 整车入库订单和快速客户出库改用服务端事务接口，不再由前端串行执行两次写入。

### 验证
- 新增多仓调拨、锁定资源、审计归责、租赁删除、采购状态恢复和复合事务回滚测试。

## [0.1.49] - 2026-06-11 - Codex

### 新增
- **整车配置模板字典**
  - 新增整车配置项和整车配置值数据模型、仓储、服务与 `/api/config/vehicle-items`、`/api/config/vehicle-values` 接口，用于按规格型号维护整车默认结构化配置。
  - 新增 Flyway `V31__vehicle_config_templates.sql`，创建 `vehicle_config_item` 与 `vehicle_config_value` 表，并保留协作版本字段、排序字段、唯一规格型号约束和配置项/配置值外键约束。
  - 配置字典前端扩展为配件配置与整车配置双模块，支持维护整车规格型号及其对应的默认配置项和值。
- **入库订单支持配件/整车分流**
  - 入库订单新增 `resourceType` 筛选与后端分页查询条件，列表可按配件订单、整车订单快速过滤，统计摘要同步遵循当前筛选条件。
  - 整车入库订单支持 `MACHINE` 类型，保存时可同步创建车辆库存，并沿用整车入库表单字段与结构化配置编辑器。
  - 新增采购/入库订单集成测试，覆盖配件订单与整车订单的类型筛选。

### 变更
- **整车规格型号预填充统一**
  - 车辆库存“此车型入库”和新增整车入库订单的规格型号字段统一改为可搜索下拉，候选项只来自整车配置字典。
  - 用户选择或输入的规格型号只有精确匹配整车配置字典时，才会拉取对应模板并逐项预填充结构化配置。
  - 若规格型号未命中字典项，则清除模板预填充，不提交旧模板残留，保留用户手动新增结构化配置的能力。
- **入库/出库文案收口**
  - 采购订单页面统一改名为入库订单，空状态按钮、导航、页面标题、提示与后端返回消息同步调整。
  - 原“订单列表”导航改为“出库订单”，减少入库订单与出库订单之间的命名歧义。
- **管理入口权限收口**
  - 用户管理、导入中心和数据维护入口收口到 `SUPER_ADMIN`，并通过 `V30__restrict_admin_user_management.sql` 移除普通 `ADMIN` 的用户管理权限。
  - 管理员保留库存、配置、改装、统计等业务权限，但不再访问用户维护和导入维护模块。

### 修复
- **整车入库模板残留防护**
  - 保存入库前会按当前规格型号再次校验整车配置模板来源，避免用户改成自定义规格后仍提交上一规格型号带出的模板配置。
  - 规格型号自定义输入不再被追加进下拉候选，保证下拉列表始终代表配置字典中的可匹配项。
- **列表与日志加载一致性**
  - 入库订单分页和列表摘要共同携带资源类型筛选参数，避免筛选后卡片统计与表格结果不一致。
  - 日志页加载时同步补齐库存流水数据，保持审计记录与库存流水视图可用。

### 验证
- Node：`npm.cmd run check` 通过，前端静态资源成功构建到 `target\frontend-static`。
- Java 21：`.\mvnw.cmd test` 通过，默认测试集合 `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0`。
- Java 21 + Testcontainers：`.\mvnw.cmd -Pdocker-integration-tests test` 通过，集成测试 `Tests run: 26, Failures: 0, Errors: 0, Skipped: 0`。
- 静态检查：`git diff --check` 通过，仅保留 Windows 工作区换行符提示。

## [0.1.48] - 2026-06-10 - Codex

### 新增
- **车辆配置变更统一入口**
  - 车辆详情、车型详情和配件关联位置原本分散的“新增装车 / 替换配件 / 创建工单”入口，统一收敛为一个“车辆配置变更”弹窗入口，并在弹窗中按模式切换。
  - 统一入口保留原有装车、替换和改装工单的字段与业务校验，减少业务人员在多个位置间切换的成本。

### 变更
- **订单附件入口收口**
  - 订单页发票/合同上传继续保留，但底层统一改为通用附件系统，附件中心不再暴露发票/合同上传类型。
  - 通用附件上传订单的 `INVOICE` / `CONTRACT` 文件后，会同步刷新订单当前快照，继续兼容下载和状态判断。
- **整车与配件出库入口收口**
  - 前端不再对普通业务用户暴露直接整车库存出库，整车销售优先走销售出库订单，直接库存纠偏仅管理员可用。
  - 配件纯扣库存出库接口收口为管理员能力，业务侧继续以出库订单作为主流程。
- **导入中心下沉**
  - 导入中心从一级导航移出，收敛到数据维护区域，并限制为管理员可见。

### 修复
- **附件上传一致性**
  - 订单页从通用附件上传发票和合同时，继续沿用原有的可上传条件校验，避免出现可见但不可上传的入口错位。

### 验证
- Node：`node scripts/build-frontend.mjs` 通过，前端静态资源成功构建到 `target/frontend-static`。
- Java 21：`.\mvnw.cmd --% -Dnode.executable=C:\Users\chh00\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe test` 通过。
- 静态检查：`git diff --check` 通过，仅保留工作区里既有的换行符提示。

## [0.1.47] - 2026-06-09 - Codex

### 新增
- **全局命令面板**
  - `Ctrl+K` 从聚焦当前搜索框升级为全局命令面板，可搜索模块、日常操作、已加载车辆、配件、客户、出库订单和维修记录。
  - 命令面板支持键盘上下选择、回车执行、点击执行和空状态提示，常用业务动作可直接打开对应弹窗。

- **高频录入连续保存**
  - 整车入库、配件入库、客户、供应商、采购、盘点和维修新增“保存并继续”，适合连续录入。
  - 连续整车入库会保留车型、规格、配置、动力、供应商、仓位和价格等上下文，并聚焦下一条表单。

### 变更
- **总览阶段入口精准筛选**
  - 总览页“待收款、待报销售、待申请发票、待上传合同、维修跟进、配件预警”入口会带筛选条件跳转到目标列表。
  - 配件、出库订单和维修列表新增状态筛选按钮组，支持按库存预警、订单跟进阶段和维修状态快速缩小结果。

- **对象详情下一步动作预填**
  - 车辆详情中的维修登记会自动带入当前车辆。
  - 客户详情抽屉新增登记销售、租赁登记和维修登记入口，并自动带入当前客户。

### 优化
- **低风险状态切换减确认**
  - 单条出库订单状态、维修完成状态和采购收货状态切换改为直接生效，并通过提示说明可再次点击切回。
  - 删除、恢复备份、盘点入账和批量高影响操作继续保留确认弹窗。

### 验证
- Node：`scripts\build-frontend.mjs` 通过，前端静态资源成功构建到 `target\frontend-static`。
- Java 21：`.\mvnw.cmd test` 通过，累计 `2` 个默认测试通过，`Failures: 0, Errors: 0`。
- 静态检查：`git diff --check` 通过，仅保留 Windows 换行符提示。

## [0.1.46] - 2026-06-09 - Codex

### 新增
- **通用资源附件中心**
  - 新增 `/api/attachments` 附件接口，支持按资源类型、资源 ID、附件分类和关键词分页查询，并提供资源附件列表、上传、预览、下载和软删除能力。
  - 新增 `resource_attachment` 表、附件 DTO、仓储和服务实现，统一沉淀整车、配件、客户、维修记录和出库订单等业务资源的附件元数据。
  - 出库订单历史发票/合同文件接入资源附件记录，启动后按批次回填旧文件快照，保留原有发票与合同上传下载兼容路径。

- **Excel 数据导入任务**
  - 新增 `/api/imports` 导入接口，支持导入任务分页查询、任务详情、模板下载、文件校验和确认导入。
  - 新增整车台账和配件采购导入模板，校验阶段记录总行数、有效行、错误行和错误明细，确认阶段写入客户、整车、出库订单和配件库存。
  - 新增 `data_import_job` 表和导入文件暂存目录配置，导入任务保留状态、摘要、校验快照、错误 JSON 与操作人时间信息。

- **查询索引补强**
  - 新增 Flyway `V27`、`V28`、`V29`，补齐待办中心、锁定过滤、历史附件回填、前缀搜索和全文搜索索引。
  - 车辆库存、配件库存和出库订单新增 MySQL `FULLTEXT ... WITH PARSER ngram` 索引，用于中文名称、客户、备注等文本字段搜索。

### 变更
- **列表接口默认走分页**
  - 车辆、配件、客户、供应商、仓库、出库订单、采购订单、库存盘点、租赁、维修、改装工单和操作日志等列表接口的 `paged` 默认值改为 `true`。
  - 操作日志查询收口到分页路径，移除默认全量合并审计日志、替换日志、维修记录和库存流水后再排序的兼容分支。

- **配置项值批量加载**
  - 新增配置项批量值查询能力，前端 `data-loader.js` 改为一次性按配置项 ID 批量拉取配置值，减少配置页和表单初始化时的 N+1 HTTP 请求。
  - 前端分页列表配置抽到 `list-config.js`，数据加载模块按当前分页页签统一生成 `paged=true` 请求。

- **搜索条件改用索引友好路径**
  - 车辆库存、配件库存和出库订单搜索从 `lower(coalesce(...)) like '%keyword%'` 改为高频编码字段前缀匹配加中文/备注字段全文检索。
  - 新增 `SearchKeywordSupport` 统一生成前缀匹配和布尔全文检索关键词，避免各服务重复拼接搜索条件。

### 优化
- **生产与事务性能**
  - 默认配置新增 `spring.profiles.active: prod`，确保生产部署未显式指定 profile 时也关闭 `application-prod.yml` 中的 SQL 展示和 Hibernate 详情日志。
  - 出库、库存、工单、替换、维修等服务中不需要立即 flush 的持久化调用改为 `save`，仅在需要返回数据库版本值或保持兼容语义的位置保留 `saveAndFlush`。

- **待办与会话数据加载**
  - 待办中心改用更聚焦的仓储查询和索引条件，减少全量列表扫描后在内存中过滤的路径。
  - 前端会话缓存识别 `paged=true` 请求，并扩展页面状态、字段配置和 UI 配置模块，降低列表页加载与刷新成本。

### 修复
- **原生分页排序兼容**
  - 修复原生 SQL 分页查询叠加 Spring Data `Sort` 时生成错误 `ORDER BY` 别名的问题，车辆、配件和出库订单原生查询改由 SQL 自身控制排序。
  - 恢复出库订单最终保存阶段必要的 `saveAndFlush`，确保返回 DTO 携带最新乐观锁版本，避免紧随其后的附件/发票操作误判为版本冲突。

### 验证
- Node：`scripts\build-frontend.mjs` 通过，前端静态资源成功构建到 `target\frontend-static`。
- Java 21：`.\mvnw.cmd --% test` 通过，累计 `2` 个默认测试通过。
- Java 21 + Testcontainers：`.\mvnw.cmd --% -Pdocker-integration-tests test` 通过，累计 `25` 个集成测试通过，`Failures: 0, Errors: 0`。
- Git：提交 `1a4623b chore: backup tested erp updates` 已推送到 `origin/master`，本地 `master` 与远端对齐。

## [0.1.45] - 2026-06-01 - Codex。。。1

### 新增
- **应收款与待办中心**
  - 出库订单新增应收金额、已收金额、收款期限和最后收款日期字段，历史数据按结清状态回填应收/已收金额，并新增收款到期索引。
  - 新增 `/api/todos` 待办中心，聚合待收款、逾期收款、待报销售、待申请发票、待上传发票/合同、维修跟进、租赁跟进和配件库存预警，并按当前用户权限过滤锁定数据。
  - 总览页接入待办中心数据，新增应收/已收/未收统计、阶段入口、附件待办和可跳转的工作项列表。

- **采购供应商、采购订单与库存盘点**
  - 新增供应商档案 `/api/suppliers`，支持分页搜索、新增、编辑、删除、协作版本校验和操作审计。
  - 新增采购订单 `/api/purchase-orders`，支持关联供应商、配置项/配置值、采购数量、单价、金额、预计到货、收货状态和运费维护。
  - 新增库存盘点 `/api/stocktaking-records`，支持整车/配件盘点草稿、账面数量快照、实盘差异、确认入账和完成后禁止编辑/删除。
  - 新增 Flyway `V20`、`V21`、`V22`，创建供应商、采购订单、盘点表，并补齐采购运费、配置项/配置值关联字段。

- **测试数据库改用 Testcontainers**
  - 集成测试改为通过 Testcontainers 启动 `mysql:8.0.43`，测试库连接信息由 `TestcontainersDatabaseSupport` 动态注入，不再依赖本机固定测试数据库。
  - 新增测试工具基类，统一 `MockMvc`、`ObjectMapper`、用户/角色/权限仓储、登录、造用户、JSON 序列化和清理逻辑，减少集成测试重复代码。

- **状态值和角色值枚举化**
  - 新增 `CodedEnum`、`EnumCodes` 以及角色、职务、维修状态、租赁状态、库存状态、改装工单状态和旧件处理动作枚举。
  - 原 `RoleNames`、`JobTags`、`RepairStatuses`、`RentalStatuses`、`MachineStockStatuses` 等常量类保留为兼容层，后续调用点可逐步迁移到枚举/值对象入口。

### 变更
- **出库订单收款、附件与锁定流程扩展**
  - 出库订单 DTO、实体、创建/编辑表单和列表展示新增应收金额、已收金额、收款期限、报销售、发票申请、开票状态、合同类型等跟进字段。
  - 发票/合同上传下载继续沿用 `/api/outbound-orders/{id}/invoice` 和 `/contract`，并通过上传前置策略控制“已满足条件但未上传”的待办提示。
  - 列表操作统一补传协作 `version`，出库订单、维修记录、租赁记录、用户、改装工单等编辑/删除/状态切换继续走乐观版本校验。

- **前端 app.js 继续模块化**
  - 新增 `app-state.js`、`ui-config.js`、`field-config.js`、`display-utils.js` 和 `status-ui.js`，将页面状态、标签配置、表单字段、展示格式化、状态徽章等逻辑从 `app.js` 拆出。
  - `app.js` 进一步收敛为页面编排和业务动作入口，降低后续维护和查找问题的成本。
  - 侧边导航按业务总览、库存与订单、客户与采购、报表与审计、系统设置分组，并新增供应商、采购订单、库存盘点页面入口。

- **出库服务拆分**
  - 从 `OutboundOrderServiceImpl` 拆出文件存储、资源锁、上传前置校验和已存储文件值对象，降低单个服务类职责密度。
  - 租赁中车辆禁止销售出库的错误信息统一为中文提示，和前端及集成测试断言一致。

- **统计摘要与导出扩展**
  - `/api/statistics/list-summary` 新增供应商、采购订单、库存盘点等列表摘要卡片，配合前端列表顶部概览展示当前筛选下的数量、金额和待跟进项。
  - Excel 导出扩展到供应商、采购订单和库存盘点记录，前端导出路由同步补齐。

### 优化
- **前端可用性与错误反馈**
  - 登录页新增焦点、跳转主内容入口、图标资源和按钮状态文案，主内容区支持键盘聚焦，详情抽屉用于承载更复杂的列表详情。
  - API 客户端在请求失败时保留 HTTP 状态码和业务 `code`，便于前端区分登录过期、权限不足和普通业务错误。
  - Service Worker 缓存版本升级为 `forklift-erp-client-v54`，并把新拆分的前端模块纳入离线壳资源。

- **本地开发验证环境**
  - 项目 Maven 编译器配置启用 fork 模式，避免 JDK 21 下 Maven 内嵌编译器偶发“无法关闭编译器资源”的问题。
  - `.gitignore` 新增 Docker Desktop 安装器和 Codex Docker 临时配置目录，避免本地安装辅助文件误入提交。

### 修复
- **测试上下文 MockMvc 配置**
  - 测试工具基类统一启用 `@AutoConfigureMockMvc`，修复继承基类的上下文启动测试无法注入 `MockMvc` 的问题。
- **注解常量表达式**
  - `RoleNames` 恢复为编译期字符串常量，保证 `@PreAuthorize` 等注解表达式可正常编译，同时保留枚举作为判断和归一化入口。

### 验证
- Docker Desktop：`docker run --rm hello-world` 通过，Docker Engine `29.5.2` 可用。
- Java 21：`java -version` 使用 `C:\Users\chh00\.jdks\ms-21.0.11`，版本为 `21.0.11`。
- Node：`node --check src/main/resources/static/assets/app.js`、`display-utils.js`、`status-ui.js` 通过。
- Java 21 + Testcontainers：`.\mvnw.cmd test` 通过，累计 `24` 个测试通过，`Failures: 0, Errors: 0`。
- 静态检查：`git diff --check` 通过，仅保留 Windows 换行符提示。

## [0.1.44] - 2026-05-27 - Codex

### 新增
- **列表 Excel 导出**
  - 新增 `/api/export/{type}` Excel 导出接口，支持车辆库存、配件库存、客户档案、出库订单、租赁记录和维修记录。
  - 前端列表页接入“导出 Excel”下载动作，下载时复用当前登录令牌并按后端附件文件名保存。

### 修复
- **列表导出入口可见性**
  - 车辆库存、配件列表、订单列表、租赁记录、客户列表和维修记录的列表标题栏新增“导出 Excel”按钮，避免只在顶部工具栏时不易发现。
  - Service Worker 缓存版本升级为 `forklift-erp-client-v41`，确保前端刷新后加载新的导出入口。

### 验证
- Git：`uploads/` 已在 `.gitignore`，且 `git ls-files uploads` 无输出，上传附件不会进入本地备份提交。
- Node：`node --check src/main/resources/static/assets/app.js` 与 `node --check src/main/resources/static/assets/modules/routes.js` 通过。
- Java 21：`.\mvnw.cmd -Dtest=AdminMaintenanceIntegrationTests test` 通过，新增覆盖 `/api/export/vehicles` 返回 `.xlsx` 附件；`.\mvnw.cmd test` 通过，累计 `22` 个测试通过；`.\mvnw.cmd package -DskipTests` 通过。
- 静态产物：`target/classes/static/assets/app.js` 包含各列表标题栏导出入口，`target/classes/static/sw.js` 包含 `forklift-erp-client-v41`。

## [0.1.43] - 2026-05-27 - Codex

### 新增
- **OpenAPI 接口文档**
  - 新增 SpringDoc OpenAPI UI 依赖，本地可通过 `/swagger-ui.html` 查看接口文档，`/v3/api-docs` 提供 OpenAPI JSON。
  - 生产 profile 默认关闭 OpenAPI 与 Swagger UI，可通过 `FORKLIFT_ERP_OPENAPI_ENABLED=true` 临时开启。

### 变更
- **生产配置与上传文件治理**
  - 新增 `application-prod.yml`，生产环境关闭 SQL 详情日志和演示数据，并要求通过环境变量提供 JWT 密钥。
  - `.gitignore` 新增 `uploads/`，避免发票、合同等真实业务附件继续进入代码仓库。

### 优化
- **租赁列表分页**
  - `/api/rentals?paged=true` 改为数据库分页与关键词搜索，不再先加载全部租赁记录后在内存分页。

### 验证
- Java 21：`.\mvnw.cmd -Dtest=RentalRecordIntegrationTests test` 通过，覆盖租赁中禁止销售、归还后可销售、分页搜索和无权限访问拒绝。
- Java 21：`.\mvnw.cmd test` 通过，累计 `21` 个测试通过；`.\mvnw.cmd package -DskipTests` 通过。

## [0.1.42] - 2026-05-27 - Codex

### 修复
- **总览整车流转详情**
  - 修复总览“整车流转”的“详情”按钮只加载数据但仍停留在总览页的问题；现在会切到车辆库存页并定位到对应车辆详情。

### 新增
- **入库档案编辑入口**
  - 车辆详情与车型详情中选中库存车号后，“入库档案”区域新增“编辑档案”按钮，直接打开整车档案编辑弹窗。
  - Service Worker 缓存版本升级为 `forklift-erp-client-v39`，避免继续使用旧脚本。

## [0.1.41] - 2026-05-27 - Codex

### 变更
- **车辆库存去台账化**
  - 删除车辆库存页的台账视图、阶段筛选与“新增入库台账 / 登记销售跟进”等台账入口，车辆页回到车型库存列表与整车档案详情。
  - 总览阶段入口不再跳转车辆台账：“在库待销售”进入车型库存并筛选在库车辆，销售闭环阶段进入订单列表。
  - Service Worker 缓存版本升级为 `forklift-erp-client-v38`，避免继续使用旧的车辆台账脚本。

### 修复
- **车型库存分页位置**
  - 修复车型视图中车型库存列表翻页会自动滑到页面顶端的问题，车辆分页只刷新当前列表，不再触发全局滚动。

### 验证
- 内置 Node：`node --check src\main\resources\static\assets\app.js`、`node --check src\main\resources\static\assets\modules\routes.js` 与 `node --check src\main\resources\static\sw.js` 通过。
- 静态残留检查：`app.js` / `app.css` 不再包含 `set-vehicle-view`、`set-vehicle-workflow`、`workflow-chip`、`renderVehicleLedger`、`vehicleWorkflowRows`、`isVehicleModelView` 等旧台账视图关键字。
- Java 21：`.\mvnw.cmd test` 通过，累计 `19` 个测试通过；`.\mvnw.cmd package -DskipTests` 通过。
- 本地 jar 静态验证：`http://127.0.0.1:8105/` 返回 `200`，`/assets/app.js`、`/assets/app.css` 与 `/sw.js` 包含车型库存、装车配件和 `forklift-erp-client-v38`，且不包含旧台账视图关键字。
- 浏览器插件验证尝试打开本地服务时当前环境返回连接/策略拦截错误，因此未将其计为页面级通过信号。

## [0.1.40] - 2026-05-27 - Codex

### 新增
- **整车新增配件装车**
  - 新增 `/api/replace/install-part` 接口，从配件仓库按数量领料装到整车，自动扣减配件库存、写入库存流水、给整车新增 `WAREHOUSE` 配置并记录 `PART_INSTALL` 日志。
  - 整车详情“新增配件”改为装车领料弹窗，只需要选择配件分类、仓库配件和数量；分类来自配置字典，具体配件来自有库存的配件档案。

### 修复
- **总览统计与入口**
  - 总览“待收款 / 待报销售 / 待申请发票”改为复用车辆台账阶段筛选口径，避免统计数字与点击进入后的台账结果不一致。
  - 快捷入口“新增车型”保留车型分类菜单，但触发按钮改为与其他快捷入口同宽同风格。

### 变更
- Service Worker 缓存版本升级为 `forklift-erp-client-v37`。

### 验证
- 内置 Node：`node --check src\main\resources\static\assets\app.js` 与 `node --check src\main\resources\static\assets\modules\routes.js` 通过。
- Java 21：`.\mvnw.cmd -Dtest=ModificationWorkOrderIntegrationTests test` 通过，新增覆盖整车新增配件装车后库存扣减、整车配置新增和 `PART_INSTALL` 日志；`.\mvnw.cmd test` 通过，累计 `19` 个测试通过；`.\mvnw.cmd package -DskipTests` 通过。
- 本地 jar 静态验证：`http://127.0.0.1:8104/` 返回 `200`，`/assets/app.js`、`/assets/modules/routes.js`、`/assets/app.css` 与 `/sw.js` 包含 `vehiclePartInstall`、`installPartOptions`、`pendingPaymentRows`、`/api/replace/install-part` 和 `forklift-erp-client-v37`。
- 浏览器插件验证尝试打开 `http://127.0.0.1:8104/` 与 `http://localhost:8104/`，当前浏览器环境均返回 `ERR_BLOCKED_BY_CLIENT`，因此未将其计为页面级通过信号。

## [0.1.39] - 2026-05-27 - Codex

### 修复
- **维修人员预填交互**
  - 新增维修记录的“维修人员”默认“其他”改为灰色预填提示，打开下拉时不再用“其他”过滤候选；保存时未手动选择仍会提交“其他”。
- **维修人员候选刷新**
  - 维修记录弹窗每次打开都会重新请求 `/api/auth/repair-users`，确保刚切换为“维修”职务且仍处于启用状态的用户能立即进入候选列表。
  - 维修记录列表的“维修人”列去掉重复的“其他”标记，只保留一个维修人显示值。
- **用户启用状态切换**
  - 用户管理列表的“状态”列改为可点击切换“启用 / 停用”，新增 `/api/auth/users/{id}/enabled` 接口并保留协作版本校验和审计记录。
  - 已停用账号不再允许登录，且不会出现在维修人员候选列表中；前端禁止停用当前登录账号和超级管理员账号。

### 新增
- **用户职务标签**
  - 用户账号新增 `MANAGEMENT`、`CLERK`、`REPAIR` 三种职务标签，对应前端显示“管理 / 文员 / 维修”。
  - 用户管理列表新增“职务”列，点击职务标签即可在三种职务间循环切换；后端新增 `/api/auth/users/{id}/job-tag` 接口并保留协作版本校验和审计记录。
  - 新增 Flyway `V17__user_job_tag_and_repair_status.sql`，为历史用户补齐 `job_tag` 字段，并把管理员/超级管理员初始化为“管理”职务。
- **维修人员按职务筛选**
  - `/api/auth/repair-users` 改为只返回启用且职务标签为“维修”的用户；维修记录保存时同步校验所选用户必须是“维修”职务。
  - 新建用户弹窗支持选择职务，维修记录仍保留“其他”选项用于外部维修人员。

### 变更
- **维修状态简化**
  - 维修记录状态移除“处理中”，仅保留“待处理”和“已完成”。
  - 维修记录列表状态改为行内点击切换，新增 `/api/repairs/{id}/status` 接口，切换状态不再需要进入编辑弹窗。
  - Service Worker 缓存版本升级为 `forklift-erp-client-v36`。

### 验证
- 内置 Node：`node --check src\main\resources\static\assets\app.js` 与 `node --check src\main\resources\static\assets\modules\routes.js` 通过。
- Java 21：`.\mvnw.cmd -Dtest=AuthIntegrationTests test` 通过，覆盖职务标签决定维修人员候选；`.\mvnw.cmd test` 通过，累计 `18` 个测试通过；`.\mvnw.cmd package -DskipTests` 通过。
- 本地 jar 静态验证：`http://127.0.0.1:8103/` 返回 `200`，`/assets/app.js`、`/assets/modules/routes.js` 与 `/sw.js` 包含 `toggle-user-job-tag`、`toggle-user-enabled`、`toggle-repair-status`、`updateJobTag`、`updateEnabled`、`updateStatus` 和 `forklift-erp-client-v36`。
- 浏览器插件验证尝试打开 `http://127.0.0.1:8103/` 与 `http://localhost:8103/`，当前浏览器环境均返回 `ERR_BLOCKED_BY_CLIENT`，因此未将其计为页面级通过信号。

## [0.1.38] - 2026-05-27 - Codex

### 修复
- **总览与车辆库存数据加载稳定性**
  - 修复总览并发加载分页出库订单、租赁记录和车辆台账全量引用时，`outboundOrders` / `rentals` 前端状态被相互覆盖的问题，避免总览统计、车辆台账阶段和销售/租赁按钮在大数据量下随机不一致。
  - 修复未租赁车辆进入总览和车辆台账时，前端把空租赁记录传给 `rentalMonthlyPrice()` 导致 `Cannot read properties of null (reading 'monthlyRentalPrice')` 的问题。
  - 车辆库存页统一通过 `loadVehicleFlowReferences()` 补齐全量出库订单与租赁引用；总览先保留分页总数，再补齐车辆流转引用，避免分页数据和全量引用抢写同一状态。
  - Service Worker 缓存版本升级为 `forklift-erp-client-v33`，避免浏览器继续使用旧版总览/车辆库存脚本。

### 验证
- 内置 Node：`node --check src\main\resources\static\assets\app.js` 通过。
- Java 21：`.\mvnw.cmd test` 通过，累计 `17` 个测试通过。
- 本地接口验证：`http://127.0.0.1:8102/` 可登录并成功访问总览/车辆库存依赖接口，`/api/inventory`、`/api/parts`、`/api/repairs`、`/api/outbound-orders`、`/api/rentals` 均返回正常分页或列表数据。

## [0.1.37] - 2026-05-27 - Codex

### 新增
- **车辆租赁管理**
  - 新增 `rental_record` 租赁记录表与 `/api/rentals` 接口，可按具体车号登记租赁去向、月租价格、开始/结束日期、经办人、备注和租赁状态。
  - 租赁去向改为关联客户列表，后端保存时同步快照客户名称/地址；未填写具体去向时自动使用客户地址或公司名称。
  - 前端新增“租赁管理”页，支持新增、编辑、删除租赁记录；车辆台账会标记“租赁中”，并显示租赁去向、月租价格和起租日期。
  - 租赁中的车辆会从销售出库下拉中排除，后端也会阻止活跃租赁车辆创建整车销售出库订单；租赁状态改为“已归还”后可继续销售出库。
- **维修记录录入优化**
  - 维修记录移除手填车辆 ID 和工时录入，车号改为从车辆库存可搜索下拉选择，租赁中的车辆在列表和维修记录中显示“租赁中”标记。
  - 维修客户从客户列表选择，使用配件从配件库存选择；维修人员从普通用户列表选择并排除管理员/超级管理员，同时保留“其他”选项表示外部人员维修。
  - 维修状态改为按钮式切换；维修费和配件费填写后自动计算总费用，后端保存时也会重新按两项费用合计。

### 统计
- **租赁收入纳入财报**
  - `/api/statistics/finance` 新增租赁收入、租赁单数和年度租赁收入 TOP，年度总收入同步包含出库、维修和租赁收入。
  - 统计页新增租赁收入汇总、月度/年度租赁字段和租赁收入排行。

### 验证
- 内置 Node：`node --check src\main\resources\static\assets\app.js` 与 `node --check src\main\resources\static\assets\modules\routes.js` 通过。
- Java 21：`.\mvnw.cmd test` 与 `.\mvnw.cmd package -DskipTests` 通过，覆盖租赁记录创建、统计收入、租赁中禁止销售出库和归还后允许出库。
- 本地 jar：`http://127.0.0.1:8102/` 返回 `200`，`/assets/app.js` 包含 `monthlyRentalPrice`、`repairPersonChoice`、`repairPartOptions`、`status-toggle-group`、`repairVehicleSummary` 和 `rentalMonthlyPrice`，`/assets/modules/routes.js` 包含 `repair-users`，`/sw.js` 包含 `forklift-erp-client-v30`。
- Service Worker 缓存版本升级为 `forklift-erp-client-v30`。

## [0.1.36] - 2026-05-27 - Codex

### 新增
- **订单合同附件入口**
  - 标记为“有合同”的订单在订单列表、车辆台账和整车详情中显示“上传合同”按钮；上传后显示“下载合同”，可像发票一样替换和下载原文件。
  - 总览新增“订单附件”待办区，将待上传发票和待上传合同集中展示，并可直接打开对应上传弹窗。

### 优化
- **总览可用性**
  - 总览新增销售闭环阶段按钮，按在库待销售、待收款、待报销售、待申请发票、待上传合同、维修跟进和配件预警快速跳转到对应业务页。
  - 订单列表的发票/合同上传下载统一保留在操作列，发票跟进和上牌/合同列只显示状态与文件信息，避免按钮挤压重叠。
  - Service Worker 缓存版本升级为 `forklift-erp-client-v28`。

### 验证
- 内置 Node：`node --check src\main\resources\static\assets\app.js` 与 `node --check src\main\resources\static\sw.js` 通过。
- Java 21：`.\mvnw.cmd -Dtest=OutboundOrderIntegrationTests test`、`.\mvnw.cmd test`、`.\mvnw.cmd package -DskipTests` 均通过。
- 本地 jar：`http://127.0.0.1:8101/` 返回 `200`，`/assets/app.js` 与 `/assets/app.css` 包含合同上传入口、总览阶段按钮和附件待办样式，`/sw.js` 包含 `forklift-erp-client-v28`。

## [0.1.35] - 2026-05-27 - Codex

### 新增
- **启动自动补齐演示测试数据**
  - 新增 `DemoDataInitializer`，本地启动时幂等补齐改装工单演示数据：演示客户、演示整车 `DEMO-MOD-001`、轮胎配置字典、实心胎库存配件和一张待处理改装工单。
  - 新增 `forklift.seed-demo-data.enabled` 配置，默认本地开启，可通过 `FORKLIFT_ERP_SEED_DEMO_DATA=false` 关闭；测试环境显式关闭，避免污染集成测试库。

### 修复
- **改装工单预填与库存详情操作**
  - 修复改装工单灰色预填项在级联下拉中只显示、不参与校验/提交的问题；车型、车号、原配置保留灰色提示时也会作为有效 payload 提交。
  - 修复车辆库存详情/车型详情中的改装工单“完成”“取消”按钮依赖全局工单列表的问题，改为从当前详情卡片读取工单并按对应车辆刷新详情。
  - Service Worker 缓存版本升级为 `forklift-erp-client-v26`。

### 验证
- 内置 Node：`node --check src\main\resources\static\assets\app.js` 与 `node --check src\main\resources\static\sw.js` 通过。
- Java 21：`.\mvnw.cmd -Dtest=ModificationWorkOrderIntegrationTests test`、`.\mvnw.cmd test`、`.\mvnw.cmd package -DskipTests` 均通过。
- 启动验证：`java -jar target\forklift-erp-0.0.1-SNAPSHOT.jar --spring.main.web-application-type=none --forklift.seed-demo-data.enabled=true` 成功连接 `forklift_erp` 并完成幂等初始化。

## [0.1.34] - 2026-05-27 - Codex

### 修复
- **测试运行隔离业务库**
  - 新增 `src/test/resources/application.yml`，让 `mvnw test` 默认连接 `forklift_erp_test`，避免管理员清库集成测试误清平时使用的 `forklift_erp` 业务库。
  - 测试配置补齐独立 JWT 参数，并显式关闭 Flyway clean，保留迁移验证能力但隔离破坏性测试数据。

### 数据
- **补充系统测试数据**
  - 未执行业务数据 reset，直接通过真实 API 复用或补齐 `D:\erp\00整机进出库管理明细表.xlsx` 的整车、客户和整车出库订单数据。
  - 补充库存/配置/配件测试数据，将在库整车补到 `260` 台，为 `1450` 台整车写入配置，并新增 `24` 项常用配件样例。

### 验证
- Java 21：`.\mvnw.cmd test` 通过，累计 `16` 个测试通过；启动日志确认测试库为 `jdbc:mysql://localhost:3306/forklift_erp_test`。
- API 导入：`scripts\import_workbook_full_data.py --base-url http://127.0.0.1:8080` 完成，最终 `1450` 台整车、`445` 个客户、`1290` 条整车出库订单。
- API 种子：`scripts\seed_inventory_test_data.py --base-url http://127.0.0.1:8080 --target-instock 260` 完成，最终 `1450` 台整车、`260` 台在库、`24` 项配件、配件总库存 `586`。

## [0.1.33] - 2026-05-26 - Codex

### 新增
- **整车详情新增配件**
  - 具体整车详情和车型详情选中单车后新增“新增配件”入口，打开配件档案弹窗时自动带入来源车辆、适配车型、来源说明和默认数量。
  - 整车详情新增“关联配件”区块，按来源车辆显示已关联的配件编码、名称、分类和库存，并可直接编辑对应配件档案。

### 修复
- **订单发票上传入口**
  - 订单列表中点击发票跟进切换为“已申请发票”后立即显示“上传发票”按钮。
  - 后端发票上传校验同步调整为“已申请发票或已开票”均可上传，避免前端出现入口后接口仍拒绝。
  - Service Worker 缓存版本升级为 `forklift-erp-client-v24`。

### 验证
- 内置 Node：`node --check src\main\resources\static\assets\app.js` 与 `node --check src\main\resources\static\sw.js` 通过。
- Java 21：`.\mvnw.cmd -Dtest=OutboundOrderIntegrationTests test`、`.\mvnw.cmd test`、`.\mvnw.cmd package -DskipTests` 均通过。
- 本地 jar：`https://127.0.0.1:8097/` 返回 `200`，`/assets/app.js` 包含 `create-vehicle-part` 与 `isInvoiceUploadReady`，`/sw.js` 包含 `forklift-erp-client-v24`。

## [0.1.32] - 2026-05-26 - xrkht

### 新增
- **龙工配件采购明细导入**
  - 新增 `scripts/import_parts_purchase_workbook.py`，可读取 `D:\erp\龙工配件采购明细2024.xlsx` 的 `配件对账单明细`，按物料号汇总采购数量并通过真实 API 写入配件库存。
  - 导入时以物料号作为配件编码，自动带入项目名称、规格/型号、单位、采购均价、最近入库日期和采购备注；重复运行时会复用或更新已有配件，避免重复建档。

### 验证
- 内置 Python：`python -m py_compile scripts\import_parts_purchase_workbook.py` 通过。
- API 恢复：在 `https://127.0.0.1:8080` 从 `00整机进出库管理明细表.xlsx` 恢复 `1450` 台车辆、`445` 个客户、`1290` 条整车出库订单；从 `龙工配件采购明细2024.xlsx` 恢复 `172` 个配件，配件库存总数量 `4232`。
- API 复核：`/api/inventory` 返回 `1450` 条，`/api/parts` 返回 `172` 条，`/api/customers` 返回 `445` 条，`/api/outbound-orders` 返回 `1290` 条，`/api/logs` 返回 `6204` 条。

## [0.1.31] - 2026-05-26 - xrkht

### 修复
- **改装工单与车型入库交互**
  - 修复“改装工单”导航页未映射到 `modificationOrders` 分页状态，导致进入页面或新建后无法正常加载分页工单列表的问题。
  - 车型详情中执行“此车型入库”时，提交 payload 会锁定当前车型的名称、规格型号和动力分类，避免操作员误改字段后被聚合成一条新车型；入库成功后自动选中新入库车辆。
  - 选中车型或库存单车后自动定位到车型详情区，并为详情区补充顶部滚动边距，车型数据很多时不用长距离滚动查找详情和入库入口。
  - Service Worker 缓存版本升级为 `forklift-erp-client-v23`。

### 验证
- 内置 Node：`node --check src\main\resources\static\assets\app.js` 与 `node --check src\main\resources\static\sw.js` 通过。
- Java 21：`.\mvnw.cmd -Dtest=ModificationWorkOrderIntegrationTests test`、`.\mvnw.cmd test`、`.\mvnw.cmd package -DskipTests` 均通过。
- 浏览器验证：在 `http://127.0.0.1:8096/` 登录后创建 `CODEX-VERIFY` 临时车型、库存车、配置、配件和改装工单；改装工单页可按车号搜索显示新工单；同车型入库后车型分页仍为 `共 1 条 · 第 1 / 1 页`，同一车型库存从 `1` 台变为 `2` 台且详情显示两辆车号。验证数据已清理。

## [0.1.30] - 2026-05-26 - xrkht

### 新增
- **库存、配置与配件测试数据种子**
  - 新增 `scripts/seed_inventory_test_data.py`，可通过真实 API 为当前车辆数据补充虚拟在库数量、配置字典、车辆配置和配件库存。
  - 构造轮胎、货叉、门架、电池、充电器、属具、安全附件、驾驶室等配置项和值；大部分车辆写入标准默认配置，少量车辆写入特殊选配配置，便于测试标准车/选配车筛选与详情展示。
  - 新增轮胎、货叉、电池、充电器、液压件、保养件、安全附件、电控件等配件库存样例，覆盖配件入库、出库、维修和替换流程测试。

### 验证
- 内置 Python：`python -B -m py_compile scripts\seed_inventory_test_data.py` 通过。
- API 执行：在 `http://127.0.0.1:8086` 将在库整车补到 `240` 台，为 `1450` 台车辆写入配置，其中标准车 `1160` 台、选配车 `290` 台；新增 `24` 项配件库存，总数量 `586`。
- API 复核：标准车样本 `C22035603818` 返回 `8` 项配置且无选配项；选配车样本 `322030606802` 返回 `8` 项配置，其中 `6` 项为特殊选配；配件 `TL-PART-BAT-LI-80V205` 库存为 `8` 组。

## [0.1.29] - 2026-05-26 - xrkht

### 新增
- **整机进出库明细表全量导入**
  - 新增 `scripts/import_workbook_full_data.py`，在原抽样导入脚本基础上支持按 `00整机进出库管理明细表.xlsx` 全量录入车辆、客户和整车出库订单。
  - 导入覆盖 `入库表`、`销售表`、`其他品牌车销售`、`旧车入库`、`旧车销售` 等业务页，按车号去重，并对少量无车号但有客户的销售记录生成可追溯临时车号。
  - 导入层补齐表格中缺失的必填型号，并裁剪长文本字段到后端字段长度，避免因为历史 Excel 空值或特殊符号中断导入。

### 验证
- 内置 Python：`python -m py_compile scripts\import_workbook_full_data.py` 通过。
- API 验证：在 `http://127.0.0.1:8086` 通过真实接口重置业务数据后导入 `1450` 台车辆、`1290` 张整车出库订单、`445` 个客户；其中在库 `95` 台，已售/零库存 `1355` 台。
- API 抽样验证：`/api/inventory/vehicleProductNumber/C22035603818` 返回车辆 `C22035603818`，库存 `0`，状态 `OUTBOUND`。

## [0.1.28] - 2026-05-26 - xrkht

### 修改
- **前端结构整理与列表按需加载**
  - 将前端接口路由与分页工具拆分到 `assets/modules/routes.js` 和 `assets/modules/paging.js`，主应用保留页面状态、渲染和业务动作。
  - 车辆、配件、改装工单、出库订单、客户、维修、日志和用户列表改为进入页面时按需加载，并支持分页切换与关键字筛选。
  - 新建/编辑/出库等弹窗只在打开时补拉所需的完整引用数据，避免登录后一次性加载所有业务列表。
  - Service Worker 缓存版本升级为 `forklift-erp-client-v21`，并缓存新增前端模块。
- **列表接口分页与筛选**
  - `/api/inventory`、`/api/parts`、`/api/modification-work-orders`、`/api/outbound-orders`、`/api/customers`、`/api/repairs`、`/api/logs`、`/api/auth/users` 支持 `paged=true&page=&size=&keyword=`。
  - 未传 `paged=true` 时保持原列表返回结构，兼容现有弹窗下拉、脚本和旧调用。

### 验证
- 内置 Node：`node --check src\main\resources\static\assets\app.js`、`assets\modules\paging.js`、`assets\modules\routes.js` 通过。
- Java 21：`.\mvnw.cmd test` 通过，累计 `16` 个测试通过。
- Java 21：`.\mvnw.cmd package -DskipTests` 通过。
- API 验证：`http://127.0.0.1:8092` 登录后分页检查库存、配件、维修、客户、出库订单、日志和用户接口均返回 `page=0`、`size=5` 的分页结构；新增前端模块和 `sw.js` 静态资源均返回 `200`。

## [0.1.27] - 2026-05-26 - xrkht

### 修改
- **普通用户业务权限开放**
  - 新增 Flyway `V13__standard_user_business_permissions.sql`，为 `USER` 角色授予车辆、配件、维修、配置、改装和库存/出库等业务权限。
  - 普通用户仍不能访问“用户管理”“统计财报”“日志查看”，也不能查看管理员锁定的订单及其关联库存记录。
  - 前端普通用户兜底权限同步更新，业务菜单和操作按钮按新权限正常显示；Service Worker 缓存版本升级为 `forklift-erp-client-v20`。

### 验证
- 内置 Node：`node --check src\main\resources\static\assets\app.js` 通过。
- 内置 Node：`node --check src\main\resources\static\sw.js` 通过。
- Git：`git diff --check` 通过，仅有 Windows 换行提示。
- Java 21：`.\mvnw.cmd "-Dtest=AuthIntegrationTests,OutboundOrderIntegrationTests" test` 通过，累计 `9` 个测试通过。
- Java 21：`.\mvnw.cmd test` 通过，累计 `16` 个测试通过。
- Java 21：`.\mvnw.cmd package -DskipTests` 通过。
- API 验证：`http://127.0.0.1:8091` 创建临时 `USER` 并登录后，车辆库存、配件库存、维修、客户、出库订单、改装和配置字典接口均返回 `200`；用户管理、统计财报和日志查看接口均返回 `403`；临时账号已删除。

## [0.1.26] - 2026-05-26 - xrkht

### 修复
- **长弹窗底部按钮不可见**
  - 修复车辆库存“整车出库/销售跟进”等长表单弹窗在视口内无法滚动到保存按钮的问题。
  - 弹窗头部和底部操作区固定在可见结构中，表单主体独立滚动；移动端同步缩小弹窗边距，避免小屏遮挡底部按钮。
  - Service Worker 缓存版本升级为 `forklift-erp-client-v19`。

### 验证
- 内置 Node：`node --check src\main\resources\static\assets\app.js` 通过。
- 内置 Node：`node --check src\main\resources\static\assets\modules\session.js` 通过。
- 内置 Node：`node --check src\main\resources\static\sw.js` 通过。
- Python：`python -m py_compile scripts\import_workbook_smoke_data.py` 通过。
- Git：`git diff --check` 通过，仅有 Windows 换行提示。
- Java 21：`.\mvnw.cmd test` 通过，累计 `16` 个测试通过。
- Java 21：`.\mvnw.cmd package -DskipTests` 通过。
- 浏览器验证：`http://127.0.0.1:8086/` 登录后打开“登记销售跟进”长弹窗，默认视口下保存/取消按钮保持可见；表单主体可滚动到底部，滚动后保存按钮仍在视口内。

## [0.1.25] - 2026-05-26 - xrkht

### 新增
- **订单锁定与关联记录隔离**
  - 新增 Flyway `V12__outbound_order_lock_fields.sql`，为出库订单增加锁定状态和“由订单锁定关联资源”标记。
  - 新增 `/api/outbound-orders/{id}/lock` 管理员/超级管理员接口；锁定订单时同步锁定订单关联的整车或配件，普通用户及非管理员权限账号无法再通过订单、整车库存或配件库存接口看到对应记录。
  - 解锁订单时会释放由该订单带来的关联库存锁；若关联库存原本已被手动锁定，则不会被订单解锁误放开。
  - 订单列表新增管理员可见的“锁定/解锁”按钮，锁定后订单号旁显示“已锁定”标记；Service Worker 缓存版本升级为 `forklift-erp-client-v18`。

### 验证
- 内置 Node：`node --check src\main\resources\static\assets\app.js` 通过。
- 内置 Node：`node --check src\main\resources\static\sw.js` 通过。
- Java 21：`.\mvnw.cmd -Dtest=OutboundOrderIntegrationTests test` 通过，新增覆盖管理员锁定订单后非管理员不可见订单及关联整车/配件、管理员仍可见、解锁后恢复可见。
- Java 21：`.\mvnw.cmd test` 通过，累计 `16` 个测试通过。
- Java 21：`.\mvnw.cmd package -DskipTests` 通过。
- 浏览器验证：`http://127.0.0.1:8087/` 登录后进入订单列表，临时 `CODEX-LOCK-VERIFY` 订单显示“锁定”按钮；点击后显示“已锁定”标记和“解锁”按钮，并提示“关联记录仅管理员可见”。验证数据已用项目测试上下文按精确 ID 清理。

## [0.1.24] - 2026-05-26 - xrkht

### 修改
- **配置字典独立滚动**
  - 配置字典页将左侧配置项列表和右侧配置值列表拆成独立卡片滚动区域，配置项很多时滚动左侧不会把右侧值列表带出视野。
  - 选择下方配置项后保留左侧列表滚动位置，并让右侧值列表直接刷新为当前配置项。
  - Service Worker 缓存版本升级为 `forklift-erp-client-v17`。

### 验证
- 内置 Node：`node --check src\main\resources\static\assets\app.js` 通过。
- 内置 Node：`node --check src\main\resources\static\sw.js` 通过。
- Java 21：`.\mvnw.cmd test` 通过，累计 `15` 个测试通过。
- Java 21：`.\mvnw.cmd package -DskipTests` 通过。
- 浏览器验证：`http://127.0.0.1:8086/` 进入配置字典后，左侧配置项列表可独立滚动，右侧配置值卡片保持可见；滚动到下方选择“轮胎类型”后右侧刷新为对应值列表且控制台无错误。

## [0.1.23] - 2026-05-26 - xrkht

### 新增
- **订单关联发票**
  - 新增 Flyway `V11__outbound_order_invoice_file_fields.sql`，为出库订单保存发票文件名、原始文件名、文件类型、大小和上传时间。
  - 新增 `/api/outbound-orders/{id}/invoice` 上传与下载接口；上传仅允许已登记开票日期或开票情况为已开票的订单，支持 PDF、OFD、JPG、PNG、WEBP，单文件上限 20MB。
  - 订单列表和整机台账行新增“上传发票/下载发票”入口，已上传时展示发票文件名和大小；API 客户端支持 `FormData` 上传。

### 修改
- **发票文件保护与审计**
  - 发票文件保存到本地存储目录，默认 `uploads/invoices`，也可通过 `forklift-erp.invoice-storage-dir` 或 `FORKLIFT_ERP_INVOICE_STORAGE_DIR` 配置。
  - 上传发票会记录统一操作审计，并在再次上传时替换订单原发票文件。
- **订单列表状态快切**
  - 订单列表的收款跟进、报销售、发票跟进、上牌/合同改为可点击状态按钮，点击后直接切换是否状态并保存到订单。
  - 报销售和发票申请由未完成切换为已完成时自动补齐当天日期；上牌/合同状态在“已上牌/未上牌”和“有合同/无合同”之间切换。
  - Service Worker 缓存版本升级为 `forklift-erp-client-v16`。

### 验证
- 内置 Node：`node --check src\main\resources\static\assets\app.js` 通过。
- 内置 Node：`node --check src\main\resources\static\assets\modules\session.js` 通过。
- 内置 Node：`node --check src\main\resources\static\sw.js` 通过。
- Java 21：`.\mvnw.cmd -Dtest=OutboundOrderIntegrationTests test` 通过，新增覆盖未开票拒绝上传、已开票上传和下载发票内容。
- Java 21：`.\mvnw.cmd test` 通过，累计 `15` 个测试通过。
- Java 21：`.\mvnw.cmd package -DskipTests` 通过。
- 本地 MySQL：V11 迁移成功应用，schema 当前版本为 `11`。
- 浏览器验证：`http://127.0.0.1:8086/` 登录后进入订单列表，发票相关文案正常渲染；收款、报销售、发票申请、上牌、合同状态按钮均可点击切换并刷新为新状态，控制台无错误。

## [0.1.22] - 2026-05-26 - xrkht

### 新增
- **整机进出库表驱动验证**
  - 新增 `/api/admin/business-data/reset` 超级管理员清库接口，只清理客户、整机、配件、订单、维修、改装、库存流水和审计等业务数据，保留账号、角色、权限和配置字典。
  - 新增 `scripts/import_workbook_smoke_data.py`，可读取 `00整机进出库管理明细表.xlsx` 的入库表、销售表和旧车入库，先清库再通过真实 API 导入销售、在库和旧车样例数据。

### 修改
- **进出库台账交互**
  - 车辆库存新增“台账视图”阶段筛选，按“在库待销售、待收款、待报销售、待申请发票、已完成”汇总并过滤整机进出库明细。
  - “新增入库台账”和“销售跟进”弹窗按 Excel 表的业务块分组，字段文案对齐入库表/销售表；入库日期和销售日期默认当前日期，结构化配置改为选填。
  - 销售跟进支持在弹窗内选择现有客户或直接录入客户资料并自动创建客户，减少从销售表补录时的页面跳转。
  - 修正 `V10__outbound_order_sales_followup_fields.sql` 的 MySQL 条件加列写法，避免本地 MySQL 对 `ADD COLUMN IF NOT EXISTS` 兼容性不稳。

### 验证
- 内置 Node：`node --check src\main\resources\static\assets\app.js` 通过。
- 内置 Node：`node --check src\main\resources\static\sw.js` 通过。
- 内置 Python：`python -m py_compile scripts\import_workbook_smoke_data.py` 通过。
- Java 21：`.\mvnw.cmd test` 通过，累计 `15` 个测试通过；新增覆盖业务数据清空接口。
- Java 21：`.\mvnw.cmd package -DskipTests` 通过。
- 本地 MySQL：清理失败的 V10 Flyway 历史后，修正后的 V10 迁移成功应用，schema 当前版本为 `10`。
- API 验证：在 `http://127.0.0.1:8086` 清库后按 Excel 导入 `2` 个客户、`6` 台整机和 `2` 张整机出库订单；其中 `4` 台仍在库、`2` 台已完成销售闭环。
- 浏览器验证：台账视图显示 `全部台账 · 6`、`在库待销售 · 4`、`已完成 · 2`，新增入库台账和销售跟进弹窗均显示新的分组录入结构。

## [0.1.21] - 2026-05-25 - xrkht

### 修改
- **详情/值列表点击选中交互**
  - 车辆库存列表支持点击整行直接进入车型详情，并根据当前详情高亮选中行；原有“详情”按钮保留，方便继续显式操作。
  - 配置项卡片支持点击整卡直接切换右侧“值列表”，沿用库存车号卡片的选中态，并补充键盘 `Enter` / `Space` 触发。
  - 前端内容区统一识别 `data-select-action` 选择动作，表格行与卡片补上可聚焦样式；Service Worker 缓存版本升级到 `forklift-erp-client-v14`，避免浏览器继续命中旧静态资源。

### 验证
- 内置 Node：`node --check src\main\resources\static\assets\app.js` 通过。
- 内置 Node：`node --check src\main\resources\static\sw.js` 通过。
- Java 21：`.\mvnw.cmd test` 通过，累计 `14` 个测试通过。
- Java 21：`.\mvnw.cmd package -DskipTests` 通过。
- 本地启动验证：以 `-Djava.io.tmpdir=target\tomcat-temp` 启动 jar 后，`curl.exe -I http://127.0.0.1:8080/` 返回 `302` 并跳转到 `https://127.0.0.1:8086/`，Node HTTPS 请求首页返回 `200`。

## [0.1.20] - 2026-05-25 - xrkht

### 新增
- **客户列表与出库订单**
  - 新增 Flyway `V9__customers_and_outbound_orders.sql`，创建 `customer_profile` 和 `outbound_order`，用于维护客户公司信息并统一记录整车/配件出库订单。
  - 新增 `/api/customers` 客户接口，支持公司名称、地址、联系人、电话、税号/身份证号和备注的新增、编辑、删除与下拉读取。
  - 新增 `/api/outbound-orders` 出库订单接口；整车出库会按具体车号扣减库存、写入库存流水、记录客户与结算价，配件出库也同步生成订单并扣减配件库存。
  - 订单列表新增车款结清、报销售、申请发票三项状态，并支持编辑报销售日期、发票申请日期和订单备注。
### 修改
- **车辆库存出库与前端入口**
  - 车辆库存车型行在“入库”后新增“整车出库”按钮，弹窗只拉取该车型仍在库的具体车号，避免仅按车型出库导致标识不准。
  - 整车/配件出库弹窗均从客户列表拉取公司信息；整车出库必填结算价和客户，配件出库也会把客户、结算价与备注写入订单。
  - 左侧导航新增“订单列表”和“客户列表”，总览快捷操作补充“新增客户”，Service Worker 缓存版本升级为 `forklift-erp-client-v12`。
### 验证
- 内置 Node：`node --check src\main\resources\static\assets\app.js` 通过。
- 内置 Node：`node --check src\main\resources\static\sw.js` 通过。
- Java 21：`.\mvnw.cmd test` 通过，累计 `14` 个测试通过；新增覆盖客户创建、整车出库订单、订单状态编辑和配件出库订单。
- Java 21：`.\mvnw.cmd package -DskipTests` 通过。
- 浏览器验证通过：车辆库存页按临时车型搜索后可打开“整车出库”，车号下拉只出现对应在库车号，客户下拉能读取客户列表；“订单列表”和“客户列表”导航及页面正常显示，验证数据已清理。

## [0.1.19] - 2026-05-25 - xrkht

### 新增
- **车型模板与按车型入库**
  - 新增 Flyway `V8__machine_model_template.sql`，为整车库存增加 `model_only` 标记，车型模板不再占用真实库存车数量。
  - “新增车型”改为悬停菜单，支持直接选择新增内燃叉车、电动叉车或手动叉车；新增车型只维护车型、型号和动力等模板信息，不再要求车号、发动机号或配置说明。
  - 车型入库时新增配置字典选择区，先选配置类型，再选具体配置；后端按配置字典 ID 解析配置项名称和值，不再信任前端自由文本。
  - 手动叉车按车型入库时只显示车型、型号和配置项，唯一车号由系统自动生成，且不再要求发动机号、车架号或保修卡号。
  - 车辆库存搜索框右侧新增动力、供应商、库存筛选；供应商选项从整车数据自动汇总，库存筛选支持有库存和库存为 0。

### 修改
- **预填占位与下拉交互**
  - 通用弹窗表单的默认值/预填值统一改为灰色占位提示，用户不填写时提交阶段自动补入预填值，不再直接写进输入框。
  - 通用下拉控件新增右侧箭头按钮；点击输入框或箭头展开下拉，再次点击箭头收起下拉。
  - 车型列表聚合时排除 `model_only` 模板对车辆数和库存数的影响，统计财报也不再把车型模板计入库存价值或低库存提示。
  - Service Worker 缓存版本升级为 `forklift-erp-client-v11`。

### 验证
- 内置 Node：`node --check src\main\resources\static\assets\app.js` 通过。
- 内置 Node：`node --check src\main\resources\static\assets\modules\session.js` 通过。
- Java 21：`.\mvnw.cmd test` 通过，累计 `12` 个测试通过；新增覆盖车型模板、手动叉车自动车号和入库配置字典解析。
- Java 21：`.\mvnw.cmd package -DskipTests` 通过。
- 浏览器验证通过：车辆库存页显示动力/供应商/库存筛选；“新增车型”悬停/聚焦后展开三类车型菜单；下拉箭头可展开并再次收起；普通车型入库显示车号与车辆信息预填占位；手动叉车入库仅显示车型、型号和配置项。

## [0.1.18] - 2026-05-25 - xrkht

### 新增
- **车型库存与单车识别**
  - 车辆库存列表改为按“车辆名称 + 规格型号”汇总展示车型，不再用车号/产品号代表车型。
  - 同一车型详情中新增库存单车卡片，以每辆车的“车号”区分具体整车；点选单车后展示发动机号、车架号、保修卡号、结算价和当前配置。
  - 新增整车发动机号字段，补充 Flyway `V7__machine_engine_number.sql`，并贯通实体、创建/更新 DTO 和列表/详情 VO。

### 修改
- **车型入库与改装工单预填**
  - 在车型详情中点击“入库”时，保留车型名称、规格型号等共性信息，并提示用户填入此辆车唯一的车号、发动机号、车架号、保修卡号和结算价。
  - 新建改装工单改为先选车型，再从该车型下的车号下拉中选择具体车辆，避免只靠车型或车架号无法准确定位单车。
  - 从车辆详情进入新建改装工单时，车型、车号、原配置和旧件处理均使用灰色占位提示，不写入真实下拉值；手动选择后才进入提交值。
  - Service Worker 缓存版本升级为 `forklift-erp-client-v10`，确保浏览器刷新后加载车型库存和占位预填的新前端逻辑。

### 验证
- 内置 Node：`node --check src\main\resources\static\assets\app.js` 通过。
- 内置 Node：`node --check src\main\resources\static\sw.js` 通过。
- Java 21：`.\mvnw.cmd test` 通过，累计 `11` 个测试通过；Flyway V7 已在本地 MySQL 应用，schema 当前版本为 `7`。
- Java 21：`.\mvnw.cmd package -DskipTests` 通过。
- 通过 API 写入测试数据：车型 `测试车型-灰占位-0525035746 / CPCD30-0525035746`，车号 `TEST-CAR-0525035746-01`、`TEST-CAR-0525035746-02`，配件 `TEST-PART-0525035746-HIGH-GUARD`，并创建改装工单 `MO-20260525035747247-6DAFAA`。
- Chromium/CDP 验证通过：车型列表行只显示车型/型号，不显示车号；车型详情显示两个车号卡片；从单车详情进入改装工单时下拉框真实值为空、灰色占位显示预填内容；选择车型后车号下拉仅列出该车型两辆车，选择“不入库”后可成功提交工单。

## [0.1.17] - 2026-05-25 - xrkht

### 修改
- **改装工单旧件处理下拉**
  - 新建改装工单时，旧件处理不再把“拆下件入库”真实写入下拉输入框，而是用灰色占位提示“默认：拆下件入库”
  - 未手动选择旧件处理时，提交工单仍由前后端兜底按 `STOCK_IN` 处理；手动选择“不入库”时才写入 `DISCARD`
  - Service Worker 缓存版本升级为 `forklift-erp-client-v9`，确保浏览器刷新后加载新的下拉逻辑

### 验证
- 内置 Node：`node --check src\main\resources\static\assets\app.js` 通过
- 内置 Node：`node --check src\main\resources\static\sw.js` 通过
- `.\mvnw.cmd test` 通过，累计 `11` 个测试通过
- `.\mvnw.cmd package -DskipTests` 通过
- Chromium 验证通过：旧件处理初始输入值和隐藏值为空，灰色提示显示“默认：拆下件入库”，下拉展开后“拆下件入库”和“不入库”均可直接选择
- 通过接口写入测试配置、车辆、配件和两张改装工单：默认旧件处理工单完成后生成拆下件入库记录，`DISCARD` 工单完成后不生成拆下件入库记录

## [0.1.16] - 2026-05-24 - xrkht

### 新增
- **配置字典与配件选型**
  - 配置项编码支持自动生成 `CFG-0001` 风格递增值，用户未手动填写时由前后端共同兜底填充
  - 新增可输入筛选的通用下拉控件，配置项大类、子类、配置值、整车、配件、状态等弹窗下拉均支持输入关键字缩小候选范围
  - 新增配件时，配件名称可直接从配置字典配置值中选择，选择后按配置项子类自动回填配件分类

### 修改
- **改装工单配置匹配**
  - 改装工单和配件替换的同类型判断改为优先按配置字典子类匹配，兼容“电动车/手动车/内燃车”大类下的“货叉/电池/轮胎”等配件类型
  - 改装工单原配置下拉展示配置字典的大类、子类和配置项名称，库存配件筛选同步使用配置字典分类

### 验证
- `.\mvnw.cmd test` 通过，累计 `11` 个测试通过，新增覆盖配置项编码自动递增和字典子类匹配的改装工单流程
- `.\mvnw.cmd -DskipTests package` 通过
- 内置 Node：`node --check src\main\resources\static\assets\app.js` 通过
- Chromium 验证通过：登录后配置项编码自动填充，输入“电”仅筛出“电动车”，新增配件输入“充”筛出“充气胎”并自动回填分类“轮胎”

## [0.1.15] - 2026-05-24 - xrkht

### 新增
- **改装工单模块**
  - 新增 Flyway `V6__modification_work_order.sql`，创建 `modification_work_order` 和 `modification_work_order_line`，用于记录客户出库前改装需求、替换明细和执行状态
  - 新增改装工单实体、DTO、Repository、Service 和 `/api/modification-work-orders` 接口，支持工单列表、按车辆查询、新建、完成和取消
  - 工单完成时统一调用配件替换流程，自动扣减新配件库存、按旧件处理方式回收入库、更新整车真实配置，并将 `stock_movement` 来源标记为 `MODIFICATION_WORK_ORDER`
  - 新增 `ModificationWorkOrderIntegrationTests`，覆盖创建工单、完成工单、库存流水、拆下件回库和车辆配置更新

### 修改
- **整车入库与状态模型**
  - 整车新建默认库存数量为 `1`，有库存时默认入库时间为当前时间，库存状态默认进入 `IN_STOCK`
  - 入库配置明细支持 `FACTORY_STANDARD` 与 `FACTORY_OPTIONAL`，厂家选配不再和后期仓库改装混在一起
  - 整车库存状态补齐 `PENDING_INBOUND`、`PENDING_MODIFICATION`、`MODIFYING`、`PENDING_OUTBOUND`、`OUTBOUND`，出库后状态进入 `OUTBOUND`
  - 配件替换支持数量和旧件处理方式，默认旧件入库，也允许标记为不入库
- **前端工作流**
  - 新增“改装工单”导航与列表，展示工单号、车辆、客户、销售单、状态和替换明细
  - 车辆详情中的直接配件替换入口改为“生成工单”，弹窗自动带出原配置、按配置类别筛选新配件，并默认旧件拆下入库
  - 改装工单列表支持完成和取消，完成动作再触发库存与配置变化
  - Service Worker 缓存版本升级为 `forklift-erp-client-v8`

### 验证
- `.\mvnw.cmd test` 通过，累计 `10` 个测试通过，包含新增 `ModificationWorkOrderIntegrationTests`
- `.\mvnw.cmd package -DskipTests` 通过
- 浏览器验证通过：登录后可见“改装工单”菜单，工单列表和新建弹窗正常渲染，控制台无错误

## [0.1.14] - 2026-05-24 - xrkht

### 新增
- **仓库与库存台账结构**
  - 新增 Flyway `V5__inventory_ledger.sql`，补充 `warehouse`、`stock_balance`、`stock_movement`、`stock_movement_line` 与 `repair_part_usage` 表
  - 为整车与配件库存增加 `warehouse_id`，为整车增加 `stock_status`，并把历史库存初始化到默认仓库与库存余额表
  - 新增 `Warehouse`、`StockBalance`、`StockMovement`、`StockMovementLine` 实体与对应仓储接口
  - 新增 `StockLedgerService`，统一维护默认仓库解析、库存余额同步和库存移动明细写入

### 修改
- **库存写入接入台账**
  - 整车/配件新增档案时，初始数量会同步写入库存余额和库存移动明细
  - 整车/配件编辑档案导致数量变化时，会按 `ADJUST` 写入库存流水与新台账
  - 整车/配件出入库、配置替换消耗新件、拆下件自动入库均同步维护 `stock_balance` 和 `stock_movement_line`
  - 前端整车表单、列表与详情页补充库存状态展示，Service Worker 缓存版本升级为 `forklift-erp-client-v7`

### 验证
- 内置 Node：`node --check src\main\resources\static\assets\app.js` 通过
- 内置 Node：`node --check src\main\resources\static\sw.js` 通过
- `.\mvnw.cmd test` 通过，累计 `9` 个测试通过，包含新增 `InventoryLedgerIntegrationTests`
- `.\mvnw.cmd package -DskipTests` 通过
- Flyway V5 在本地 MySQL 8.0.43 上应用成功，schema 当前版本为 `5`

## [0.1.13] - 2026-05-24 - xrkht

### 修改
- **前端壳层与日志页交互优化**
  - 左侧导航栏改为桌面端 `sticky` 固定显示，页面下滑时保持可见，并为导航自身增加滚动能力
  - 顶部栏在桌面端固定显示，窄屏端恢复普通流式布局，避免和顶部导航互相遮挡
  - 日志查看页新增显示数量提示，默认分批渲染日志并提供“加载更多”，降低大量日志一次性撑高页面的问题
  - 日志表格增加独立滚动区域，表头继续固定，长列表滚动时侧栏和表头都保持可用
  - 切换模块时自动回到工作区顶部，避免从长日志页切到其他页面后仍停留在页面下方
  - Service Worker 缓存版本升级为 `forklift-erp-client-v6`

### 验证
- 内置 Node：`node --check src\main\resources\static\assets\app.js` 通过
- 内置 Node：`node --check src\main\resources\static\sw.js` 通过
- `.\mvnw.cmd test` 通过，累计 `8` 个测试通过
- `.\mvnw.cmd package -DskipTests` 通过

## [0.1.12] - 2026-05-24 - xrkht

### 新增
- **多用户协同写入控制**
  - 新增协作版本字段 `version`、`last_modified_by`、`last_modified_role`、`last_modified_priority`，覆盖整车、配件、维修、配置、车辆配置和用户等核心可写数据
  - 新增 `CollaborationService`，并按 `SUPER_ADMIN > ADMIN > USER` 处理同一数据的并发旧版本写入优先级
  - 新增悲观写锁查询方法，更新、删除、出入库、配件替换、配置替换和用户维护等关键写入流程先锁定目标数据再校验版本
  - 新增全局并发冲突处理，JPA 乐观锁、悲观锁获取失败和业务版本冲突统一返回 HTTP `409`
  - 新增 `CollaborationIntegrationTests`，覆盖管理员先写入、超级管理员用旧版本优先覆盖、管理员继续用旧版本被拒绝的协作场景

### 修改
- **前端协作版本传递**
  - 编辑、删除、用户维护、库存调整和配件替换提交时自动携带当前 `version`
  - 保存接口改为 `saveAndFlush` / `saveAllAndFlush`，确保响应中的 `version` 是数据库刷新后的最新值
  - Flyway 新增 `V4__collaboration_priority_control.sql`，由迁移脚本统一补齐协作字段
  - Service Worker 缓存版本升级为 `forklift-erp-client-v5`

### 验证
- `.\mvnw.cmd test` 通过，累计 `8` 个测试通过
- 内置 Node：`node --check src\main\resources\static\assets\app.js` 通过
- 内置 Node：`node --check src\main\resources\static\sw.js` 通过
- `.\mvnw.cmd package -DskipTests` 通过
- V4 Flyway 迁移在本地 MySQL 8.0.43 上应用成功，schema 当前版本为 `4`

## [0.1.11] - 2026-05-24 - xrkht

### 新增
- **HTTPS 访问**
  - 默认服务端口升级为 `8443` HTTPS，新增本地开发自签名证书 `src/main/resources/certs/forklift-erp-dev.p12`
  - 新增 `HttpsRedirectConfig`，在启用 SSL 时保留 `8080` HTTP 入口并统一跳转到 HTTPS
  - 示例 `.http` 请求地址更新为 `https://localhost:8443`

### 验证
- `.\mvnw.cmd test` 通过，累计 `7` 个测试通过
- `.\mvnw.cmd package -DskipTests` 通过
- 启动验证通过：Tomcat 启动在 `8443 (https)` 和 `8080 (http)`
- HTTPS/跳转验证通过：OpenSSL 请求 `https://127.0.0.1:8443/` 返回 `200 text/html`，`http://127.0.0.1:8080/` 返回 `302 Location: https://127.0.0.1:8443/`

## [0.1.10] - 2026-05-23 - xrkht

### 新增
- **统一关键操作审计**
  - 新增 `operation_audit_log` 表与 `OperationAuditLog`、`OperationAuditService`
  - 车辆、配件、维修、配置替换、库存出入库和用户维护等关键写操作统一写入审计日志
  - `/api/logs` 优先展示统一审计流，并兼容历史维修、替换和库存流水记录

- **统计财报**
  - 新增 `/api/statistics/finance` 统计接口，按年份输出月度财报、年度对比、资源分类收支、出库收益 TOP、当前库存价值和低库存预警
  - 入库按采购价/结算价估算成本，出库按销售价估算收入并按采购价估算成本，维修总费用单独计入服务收入
  - 前端新增“统计财报”菜单，展示年度总收入、入库成本、出库毛利、维修收入与多张统计表

### 修改
- **日志与前端缓存**
  - 日志页标题调整为“关键操作审计”，动作标签补充新增、更新、删除、锁定、密码维护等业务动作
  - Service Worker 缓存版本升级为 `forklift-erp-client-v4`

### 验证
- 内置 Node：`node --check src\main\resources\static\assets\app.js` 通过
- 内置 Node：`node --check src\main\resources\static\assets\modules\session.js` 通过
- `.\mvnw.cmd test` 通过，累计 `7` 个测试通过
- `.\mvnw.cmd package -DskipTests` 通过
- 浏览器验证通过：`http://localhost:8086/` 登录后可见“统计财报”和“日志查看”，统计页包含月度财报、年度对比、当前库存价值和低库存预警，控制台无错误

## [0.1.9] - 2026-05-23 - xrkht

### 新增
- **精细权限模型**
  - 新增 `permissions` 与 `role_permissions` 表，并通过 `V2__permission_model.sql` 初始化权限点
  - 新增 `Permission`、`PermissionRepository`、`PermissionService` 与 `PermissionCodes`
  - 登录响应与用户列表响应新增 `permissions` 字段，前端可直接按权限点控制菜单和按钮

### 修改
- **接口鉴权**
  - 库存、配件、维修、配置、替换、日志与用户接口从角色判断切换为权限点判断
  - 保留 `ROLE_` authority 兼容旧服务层判断，同时新增 `PERM_` authority 支持细粒度授权
  - `ADMIN` 默认拥有业务写入、库存调整、日志查看、用户创建/普通用户密码重置权限，但不拥有 `user:admin`
- **前端权限感知**
  - 日志查看和用户管理菜单改为读取 `log:read`、`user:read`
  - 新增、编辑、删除、出入库、配件替换、用户维护等按钮按对应权限显隐
  - Service Worker 缓存版本升级为 `forklift-erp-client-v3`

### 验证
- 内置 Node：`node --check src\main\resources\static\assets\app.js` 通过
- 内置 Node：`node --check src\main\resources\static\assets\modules\session.js` 通过
- `.\mvnw.cmd test` 通过，累计 `6` 个测试通过
- `.\mvnw.cmd package -DskipTests` 通过
- 浏览器验证通过：`http://localhost:8080/` 正常加载，`/assets/app.js` 以 `type="module"` 运行，控制台无错误，超级管理员可见 `log:read` / `user:read` 菜单

## [0.1.8] - 2026-05-23 - xrkht

### 新增
- **数据库迁移管理**
  - 新增 Flyway 依赖与 `src/main/resources/db/migration/V1__init_schema.sql`
  - 首版迁移脚本覆盖用户、角色、库存、配置、维修、替换日志和库存流水等核心表
  - 启用 `baseline-on-migrate`，让已有 MySQL 非空库可平滑接入 Flyway 管理

- **用户权限集成测试**
  - 新增 `AuthIntegrationTests`，覆盖超级管理员创建、改名、改密、删除用户流程
  - 覆盖普通管理员禁止调用超级管理员专用用户维护接口
  - 覆盖管理员不能通过旧重置密码接口修改超级管理员密码
  - 覆盖管理员用户列表仅能看到普通用户

- **前端基础模块**
  - 新增 `assets/modules/session.js`，统一管理 token、用户登录态和 API 请求封装
  - `app.js` 改为 ES module 入口，减少登录态和请求逻辑散落

### 修改
- **数据库启动策略**
  - `spring.jpa.hibernate.ddl-auto` 从 `update` 调整为 `validate`，结构变更改由 Flyway 迁移控制
  - Service Worker 缓存版本升级为 `forklift-erp-client-v2`，并缓存新增前端模块

### 验证
- `node --check src\main\resources\static\assets\app.js` 通过
- `node --check src\main\resources\static\assets\modules\session.js` 通过
- `.\mvnw.cmd test` 通过，累计 `5` 个测试通过
- `.\mvnw.cmd package -DskipTests` 通过
- 浏览器验证通过：登录页正常加载，`/assets/app.js` 以 `type="module"` 运行，控制台无错误

## [0.1.7] - 2026-05-23 - xrkht

### 新增
- **超级管理员用户维护**
  - 新增 `PUT /api/auth/users/{id}/username`，支持超级管理员修改管理员或普通用户的用户名
  - 新增 `PUT /api/auth/users/{id}/password`，支持超级管理员重置管理员或普通用户的登录密码
  - 新增 `DELETE /api/auth/users/{id}`，支持超级管理员删除管理员或普通用户账号
  - 用户管理前端列表新增“改用户名”“改密码”“删除”行内操作，仅超级管理员可见

### 修改
- **用户权限保护**
  - 禁止通过新接口修改或删除超级管理员账号，避免误操作导致最高权限账号不可用
  - 删除用户时禁止删除当前登录账号
  - 收紧旧的 `/api/auth/reset-password` 权限判断，管理员不再可能重置超级管理员密码

### 验证
- `node --check src\main\resources\static\assets\app.js` 通过
- `.\mvnw.cmd test` 通过
- `.\mvnw.cmd package -DskipTests` 通过
- 接口验证通过：超级管理员创建用户、修改用户名、修改密码、删除用户；普通管理员调用修改用户名返回 `403`；旧密码和已删除用户登录均返回 `401`
- 浏览器验证通过：超级管理员进入用户管理页后可见“改用户名”“改密码”“删除”操作按钮

## [0.1.6] - 2026-05-23 - xrkht

### 新增
- **业务日志查看**
  - 新增 `/api/logs` 聚合日志接口，仅 `ADMIN`、`SUPER_ADMIN` 可访问
  - 日志查看界面汇总配件替换、维修记录、整车出入库、配件出入库流水
  - 新增 `stock_operation_log` 库存流水表，记录出入库对象、数量、前后库存、操作人和备注

### 修改
- **配件替换入口**
  - 前端移除重复的“配置替换”入口，仅保留“配件替换”
  - 配件替换弹窗直接按车辆配置筛选同类型库存配件，下拉选择后执行替换
  - 配件替换继续写入 `ConfigReplaceLog`，并同步记录新配件出库、拆下件入库流水

### 验证
- `node --check src\main\resources\static\assets\app.js` 通过
- `.\mvnw.cmd test` 通过
- `.\mvnw.cmd package -DskipTests` 通过
- 接口验证：管理员访问 `/api/logs` 返回 `200`，未登录访问返回 `401`

## [0.1.5] - 2026-05-23 - xrkht

### 新增
- **出入库前端操作**
  - 车辆库存页新增整车入库、整车出库入口，支持对已有整车库存数量做增减
  - 配件库存页新增配件入库、配件出库入口，支持按配件编码调整库存数量
  - 列表行操作同步增加入库、出库快捷按钮，便于直接处理当前记录

- **配件替换**
  - 车辆详情新增配件替换入口，可选择车辆已安装配置并从库存中挑选同类型配件替换
  - 替换时自动扣减新配件库存，并将拆下来的旧配件以来源 `REMOVED` 自动写入配件仓库
  - 新增配件替换接口 `/api/replace/part`，替换日志会记录新旧配件和拆下件入库信息

### 修改
- **整车库存接口**
  - 新增 `/api/inventory/{id}/inbound` 与 `/api/inventory/{id}/outbound`，管理员和超级管理员可对已有整车库存做入库、出库调整

### 验证
- `.\mvnw.cmd test` 通过
- `.\mvnw.cmd package -DskipTests` 通过
- `node --check src\main\resources\static\assets\app.js` 通过
- 浏览器验证：登录后车辆库存页显示整车入库/出库，配件库存页显示配件入库/出库，车辆详情页显示配件替换入口

## [0.1.4] - 2026-05-22 - xrkht

### 新增
- **方法级权限保护**
  - 为整车库存、配件库存、配置项、配置值、维修记录、配置替换等写操作补充 `@PreAuthorize`
  - 新增/修改/删除/入库/出库类接口统一限制为 `ADMIN` 或 `SUPER_ADMIN` 角色
  - 普通 `USER` 保留查询能力，禁止直接执行业务数据写操作

- **权限异常处理**
  - `GlobalExceptionHandler` 新增 `AccessDeniedException` 处理，方法级权限不足时统一返回 `403`

### 修改
- **Java 与构建配置**
  - 项目编译目标由 Java 25 调整为 Java 21 LTS，降低 Lombok、Mockito、Maven 插件与新 JDK 的兼容风险
  - `pom.xml` 显式指定 Lombok `1.18.46`
  - 新增 `maven-compiler-plugin` 配置，使用 `release 21` 并明确 Lombok annotation processor

- **认证入口策略**
  - `SecurityConfig` 仅放行 `/api/auth/login`
  - `/api/auth/register` 改为必须登录后由管理员或超级管理员调用，避免未登录注册与方法权限校验冲突

- **数据库配置**
  - MySQL 连接串增加 `allowPublicKeyRetrieval=true`，解决 MySQL 8/9 `caching_sha2_password` 场景下启动失败问题
  - 移除显式 `database-platform` 配置，交由 Hibernate 自动识别 MySQL 方言，减少启动告警

- **Maven Wrapper**
  - 修复 `mvnw.cmd` 在 Windows PowerShell 环境下 `.m2` 目录 Target 判断为空导致无法启动的问题
  - 增加本地 Maven wrapper 缓存复用逻辑，避免已有 Maven 发行版时仍重复下载

### 修复
- 修复源码执行 `mvn test` 时 Lombok getter/setter 与 `@Slf4j` 日志字段无法生成导致的编译失败
- 修复应用默认配置连接 MySQL 报 `Public Key Retrieval is not allowed` 导致无法启动的问题
- 修复业务异常 HTTP 状态码与响应体 `code` 不一致的问题：
  - 未认证/错误密码返回 `401`
  - 权限不足返回 `403`
  - 资源不存在返回 `404`
  - 数据重复、库存不足等业务冲突返回 `409`
- 修复未登录调用注册接口返回 `500 SYSTEM_ERROR` 的问题
- 修复普通用户可新增/删除整车等业务数据的权限过宽问题

### 验证
- `.\mvnw.cmd test` 通过
- 接口回归验证：管理员登录、创建普通用户、普通用户登录、未登录注册拦截、普通用户写操作拦截均通过

## [0.1.3] - 2026-05-18 - xrkht

### 新增
- **用户认证与授权模块**
  - 新增 `User`、`Role` 实体，支持用户启用状态、角色多对多关联及创建/更新时间审计字段
  - 新增 `UserRepository`、`RoleRepository`，支持按用户名、角色名查询及用户名重复校验
  - 新增 `AuthController`，提供登录、用户创建、重置密码接口，继续沿用统一 `Result<T>` 响应格式
  - 新增 `LoginRequest`、`TokenResponse`、`RegisterRequest`、`ResetPasswordRequest` 等认证请求/响应 DTO，并加入必填参数校验

- **JWT 无状态认证**
  - 新增 `JwtTokenProvider`，支持 Token 生成、用户名解析和有效性校验
  - 新增 `JwtAuthenticationFilter`，从 `Authorization: Bearer` 请求头读取 Token 并写入 Spring Security 上下文
  - 新增 `UserDetailsServiceImpl`，将系统用户和角色转换为 Spring Security 的认证用户及 `ROLE_` 权限

- **权限与初始化配置**
  - 新增 `SecurityConfig`，启用 Spring Security、方法级权限控制和无状态 Session 策略
  - 新增 `SecurityExceptionHandler`，统一处理 401 未认证和 403 无权限响应
  - 新增 `DataInitializer`，系统启动时自动初始化 `SUPER_ADMIN`、`ADMIN`、`USER` 角色及默认超级管理员账号
  - 新增 `SecurityUtils`，封装当前用户角色判断能力，便于后续业务模块复用权限判断

### 修改
- **接口访问控制**
  - 除认证相关入口外，业务接口默认需要通过 JWT 认证后访问
  - 用户创建和密码重置接口增加 `@PreAuthorize` 权限限制，仅管理员及超级管理员可操作
  - 角色分配增加层级约束：禁止普通管理员创建管理员，禁止通过接口创建超级管理员

- **依赖与配置**
  - `pom.xml` 新增 `spring-boot-starter-security` 与 `jjwt-api`、`jjwt-impl`、`jjwt-jackson` 依赖
  - `application.yml` 新增 JWT 密钥、过期时间和请求头配置
  - `ResultCode` 补充 `UNAUTHORIZED`、`FORBIDDEN`、`PARAM_ERROR`、`DATA_DUPLICATE` 等认证与参数相关状态码

### 修复
- 统一认证失败和权限不足时的 JSON 返回格式，避免 Spring Security 默认错误页或非统一响应体
- 登录、创建用户、重置密码等认证接口加入参数校验，减少空用户名、空密码等无效请求进入业务逻辑

## [0.1.2] - 2026-05-08 - xrkht

### 新增
- **DTO 层（数据隔离）**
  - 新增 `MachineInventoryVO`、`MachineInventoryCreateDTO`、`MachineConfigVO`、`MachineDetailVO`
  - 新增 `PartInventoryVO`、`PartInventoryCreateDTO`
  - 新增 `RepairRecordVO`、`RepairRecordCreateDTO`
  - 新增 `ConfigItemVO`、`ConfigValueVO`、`ConfigReplaceLogVO`
  - 新增 `ConfigReplaceRequestDTO`，替换请求专用 DTO
  - 所有 DTO 均实现 `toEntity()` 和 `updateEntity()` 方法，与实体层解耦

- **Bean Validation 参数校验**
  - `MachineInventoryCreateDTO`、`PartInventoryCreateDTO`、`RepairRecordCreateDTO` 等加入 `@NotBlank`、`@NotNull`、`@Size`、`@Min` 等校验注解
  - `InboundRequestDTO.ConfigSelection` 增加必填校验
  - `ConfigReplaceRequestDTO` 增加 `@NotNull`、`@NotBlank` 校验
  - 校验失败统一由 `GlobalExceptionHandler` 返回 400 错误

- **业务增强**
  - `ConfigReplaceService` + `ConfigReplaceServiceImpl`：实现完整配置替换流程（更新/新增车辆配置、扣减配件库存、生成替换日志）
  - `PartInventoryService` 增加配件出入库业务（`inbound`/`outbound`），包含库存不足检查
  - `PartInventoryServiceImpl` 增加配件编码唯一性检查
  - `ConfigItemServiceImpl` 删除配置项/配置值时增加车辆引用检查，已用则抛出 `CONFIG_IN_USE`

### 修改
- **Controller 全量重构**
  - `MachineInventoryController`、`PartInventoryController`、`RepairRecordController`、`ConfigItemController`、`ConfigReplaceController` 全面改为接收 DTO、返回 VO
  - 所有接口统一添加 `@Valid`，启用参数校验
  - 将原有的 `ResponseEntity` 返回全部替换为 `Result<T>` 格式（`ConfigItemController`、`ConfigReplaceController` 遗留接口已改造）
- **`InboundRequestDTO` 调整**：`machineInventory` 字段类型由 `MachineInventory` 实体改为 `MachineInventoryCreateDTO`，封装前端必要字段
- **`PartInventoryCreateDTO` 扩展**：补充 `partBrand`、`manufacturingDate`、`inboundDate` 等字段，支持完整配件信息传递
- **`ConfigReplaceController` 重构**：复用 `ConfigReplaceService.performReplace()` 执行替换，原有纯日志记录接口保留

### 修复
- 解决 `InboundRequestDTO` 内实体无 `toEntity()` 方法导致的类型错误
- 修正 MySQL 列名 `vehicle_number` 与实体字段映射不一致问题（测试辅助脚本）

## [0.1.2] - 2026-05-04 - xrkht

### 新增
- **配置替换业务模块**
  - 新增 `ConfigReplaceService` 接口及 `ConfigReplaceServiceImpl` 实现，完成配置替换核心逻辑（更新车辆配置、配件库存变动、记录替换日志）
  - 新增 `ConfigReplaceRequestDTO`，封装替换请求参数（车辆、配置项、新旧值、替换类型、配件处理等）

- **配件库存业务模块**
  - 新增 `PartInventoryService` 接口及 `PartInventoryServiceImpl` 实现，支持配件 CRUD、按分类/来源/库存查询、出入库操作
  - 配件出入库时进行库存校验，库存不足抛出 `BusinessException`

- **维修记录业务模块**
  - 新增 `RepairRecordService` 接口及 `RepairRecordServiceImpl` 实现，支持维修记录 CRUD、按车辆/人员/状态/日期查询

- **Controller 层统一响应格式改造**
  - `ConfigItemController` 全部接口返回 `Result<T>`，添加 `@Valid` 校验，异常通过全局处理器统一处理
  - `ConfigReplaceController` 新增 `performReplace` 接口，调用替换业务并返回统一格式
  - `RepairRecordController` 完成基础 CRUD 及条件查询，返回统一 `Result`

- **MachineInventory 实体**
  - 新增 `annualInspectionDate` 字段（年审日期）

### 修改
- `ConfigItemServiceImpl`
  - 删除配置项/配置值时增加车辆引用检查，存在引用则抛出 `CONFIG_IN_USE` 业务异常，防止误删
- `PartInventoryServiceImpl`
  - 新增配件编码唯一性校验，保存时自动检查重复编码

## [0.1.1] - 2026-05-03 - xrkht

### 新增
- **统一响应格式**
  - 新增 `Result<T>` 通用响应类（`common/Result.java`）
  - 新增 `ResultCode` 状态码枚举（`common/ResultCode.java`）
  - 统一封装 code/message/data/timestamp 四个字段
  - @JsonInclude 过滤 null 值

- **全局异常处理**
  - 新增 `BusinessException` 业务异常类（`exception/BusinessException.java`）
  - 新增 `GlobalExceptionHandler` 全局异常处理器（`handler/GlobalExceptionHandler.java`）
  - 支持统一的异常拦截与响应格式

### 修改
- `MachineInventoryController.java`
  - 返回类型从 ResponseEntity 统一改为 Result<T>
  - 使用 BusinessException 替代硬编码的 notFound 响应
  - 添加 @Valid 参数校验注解
  - 添加 @Slf4j 操作日志记录
  - 保留原有业务注释

- `application.yml`
  - 修复注释乱码
  - 新增 spring.mvc.throw-exception-if-no-handler-found: true
  - 新增 spring.web.resources.add-mappings: false
  - 优化日志级别配置

---

## [0.1.0] - 2026-05-02 - xrkht

### 新增
#### 整车配置管理模块
- 创建车辆实际配置实体类 `MachineConfig`，记录每台车具体装了什么配置
- 创建 `MachineConfigRepository` 数据访问层，支持按车辆ID查询、按配置项ID/配置值ID反查、按车辆ID删除
- 创建 `MachineConfigService` 接口及其实现类，提供配置明细的增删改查
- 实现车辆完整信息查询接口 `GET /api/inventory/{id}/detail`，返回车辆基本信息及配置明细
- 实现完整的入库接口 `POST /api/inventory/inbound`，可一次性保存车辆基本信息和多条配置明细
- 实现车辆配置更新接口 `PUT /api/inventory/{id}/configs`，先删除旧配置再保存新配置
- 创建入库请求 DTO `InboundRequestDTO`，封装车辆信息和配置选择列表
- 删除车辆时级联删除关联的配置明细

#### 系统字典（配置项）管理模块
- 创建配置项实体类 `ConfigItem`，支持一级分类、二级分类、输入类型（SELECT/MULTI_SELECT/NUMBER/TEXT/BOOLEAN）、是否必选、排序等
- 创建配置项可选值实体类 `ConfigValue`，支持值标签、值编码、默认值标记、排序
- 创建 `ConfigItemRepository`，支持按编码查询、按分类查询、按排序号排序查询
- 创建 `ConfigValueRepository`，支持按配置项ID查询可选值、查询默认值、按配置项ID删除所有可选值
- 创建 `ConfigItemService` 接口及其实现类，提供配置项 CRUD 和可选值管理
- 创建 `ConfigItemController` REST API 控制器
  - `GET /api/config/items` - 查询所有配置项
  - `GET /api/config/items/category/{category}` - 按分类查询
  - `GET /api/config/items/{id}` - 查询单个配置项
  - `POST /api/config/items` - 新增配置项
  - `DELETE /api/config/items/{id}` - 删除配置项（级联删除可选值）
  - `GET /api/config/items/{itemId}/values` - 查询某配置项的所有可选值
  - `POST /api/config/values` - 新增可选值
  - `DELETE /api/config/values/{id}` - 删除可选值

#### 配置替换日志模块
- 创建配置替换记录实体类 `ConfigReplaceLog`，记录车辆配置的变更历史（旧值→新值、替换类型、操作人等）
- 创建 `ConfigReplaceLogRepository`，支持按车辆ID查询替换记录（按时间倒序）
- 创建 `ConfigReplaceLogService` 接口及其实现类
- 创建 `ConfigReplaceController` REST API 控制器
  - `GET /api/replace/machine/{machineId}` - 查询某车辆的所有替换记录
  - `POST /api/replace` - 记录一次配置替换

#### 配件库存模块
- 创建配件库存实体类 `PartInventory`，支持配件编码（唯一）、品牌、名称、规格、分类、适用车型、来源（采购/拆车/退货）、来源车辆编号、库存数量等完整字段
- 创建 `PartInventoryRepository`，支持按配件编码查询、按分类查询、按来源查询、按来源车辆查询、按库存数量大于阈值查询

#### 外出维修记录模块
- 创建维修记录实体类 `RepairRecord`，支持维修日期、关联车辆、客户信息、故障描述、维修内容、维修人员、使用配件、工时/费用明细、状态（待维修/维修中/已完成）
- 创建 `RepairRecordRepository`，支持按车辆查询、按维修人员查询、按状态查询、按日期区间查询

### 变更
- `MachineInventoryController` 由 `@Controller` 改为 `@RestController`，确保返回 JSON 格式
- 车辆删除接口新增级联删除配置明细的逻辑
- `MachineInventory` 实体新增 `machineType` 字段（所属车辆类型），支持内燃叉车、电动叉车、托盘搬运车等多种类型

---

## [0.0.1] - 2026-05-01 - xrkht

### 新增
- 初始化叉车进销存ERP系统项目
- 创建整机库存实体类 `MachineInventory`，包含入库、出库、销售等完整字段
- 创建配置项实体类 `ConfigItem`，用于系统字典数据管理
- 实现整机库存数据访问层 `MachineInventoryRepository`，继承 JpaRepository
- 实现整机库存业务逻辑层 `MachineInventoryService` 及其实现类
- 实现整机库存 REST API 控制器 `MachineInventoryController`，提供 CRUD 接口
- 配置 MySQL 数据库连接（localhost:3306/forklift_erp）
- 配置 JPA 自动建表策略（ddl-auto: update）
- 启用 SQL 日志输出和格式化显示
- 添加 Lombok 依赖简化代码开发（getter/setter/toString 等）
- 实现实体审计字段（createdAt/updatedAt）自动填充
- 添加车号/产品编号唯一性约束
- 实现根据车号查询的自定义方法

### 技术栈
- Spring Boot 3.5.14
- Java 25
- MySQL 8.0
- Spring Data JPA
- Lombok
- Maven 构建工具

### 配置
- 服务器端口：8080
- 数据库时区：Asia/Shanghai
- 字符编码：UTF-8
- 日志级别：debug（com.example.forklift_erp 包）

---

## 版本说明

### 版本号规则

- **主版本号（Major）**：不兼容的API修改（如 1.0.0 → 2.0.0）
- **次版本号（Minor）**：向后兼容的功能性新增（如 1.0.0 → 1.1.0）
- **修订号（Patch）**：向后兼容的问题修正（如 1.0.0 → 1.0.1）

### 变更记录类型

- **Added（新增）**：新功能
- **Changed（变更）**：现有功能的变更
- **Deprecated（即将废弃）**：即将在未来版本中移除的功能
- **Removed（移除）**：已移除的功能
- **Fixed（修复）**：Bug 修复
- **Security（安全）**：安全性相关的修复或改进

---

**作者**: xrkht
**项目**: 叉车进销存ERP系统 (Forklift ERP)
**仓库**: D:\erp\forklift-erp
