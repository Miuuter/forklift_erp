# 现有功能维护基线

本文件记录 0.2.0-rc.1 开发阶段的维护基线。它只用于保护已有功能，不代表新增业务需求。

## 事实来源

- 库存与成本：`stock_movement`、`stock_movement_line`、`stock_balance`、`stock_lot`、`stock_lot_consumption`。
- 财务与现金：`financial_event`、`payment_record`。
- 查询统计：数据库聚合和投影；主档数量、旧日志和历史价格只作为兼容信息。
- 文件：附件元数据与对应 uploads 文件必须同时可追溯。
- 并发：请求幂等表、乐观锁、服务端事务和按 ID 排序锁定。

## 维护门禁

每次涉及业务写入、查询、前端提交或数据库迁移的改动，至少执行：

```powershell
npm.cmd run check
npm.cmd run test:unit
.\mvnw.cmd --batch-mode test "-Dfrontend.skip=true"
```

涉及数据库或并发时，追加：

```powershell
.\mvnw.cmd --batch-mode -Pdocker-integration-tests test "-Dfrontend.skip=true"
```

闭环测试库重建、种子和只读校验流程见根目录 `README.md` 及 `TESTING.md`。

## 当前已知维护重点

1. 后端库存/FIFO、采购、出库、改装和附件服务较大，应按业务边界渐进拆分。
2. 前端 `app.js` 仍是较大的入口文件，应继续把请求、状态、渲染和工作流迁移到现有 ES Module。
3. 浏览器测试当前重点是核心模块加载；真实写入、冲销和并发失败反馈需要继续补齐。
4. 所有历史 Flyway 迁移保持不可修改，新迁移从 V52 开始。
5. 构建必须使用仓库和 CI 的明确 Git 信息来源，不能依赖受限的用户级 JGit 配置。

## 完成定义

- 没有库存/FIFO/财务不一致、重复事实、孤立外键或未解释 `dataWarnings`。
- 并发失败表现为可识别的冲突，不能产生部分提交。
- API 错误可通过 `X-Request-ID` 或响应体 `requestId` 定位。
- 前端写操作具备加载、成功、失败、冲突和重试反馈。
- 测试、制品、备份、恢复和部署检查均可重复执行。
