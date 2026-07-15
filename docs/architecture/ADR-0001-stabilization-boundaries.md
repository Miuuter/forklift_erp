# ADR-0001：0.2 稳定化事实来源与事务边界

状态：已接受
日期：2026-07-15

## 背景

早期版本同时维护主档数量、库存日志、库存流水、FIFO 批次、旧价格字段和财务快照，报表存在新旧数据混算风险。前端还通过多个并发 HTTP 请求模拟批量操作，重复提交可能产生部分成功。

## 决策

1. 库存事实由 `stock_movement*`、`stock_balance` 和 `stock_lot*` 共同表达。
2. 财务事实由只追加的 `financial_event` 与 `payment_record` 表达；冲销使用反向记录，不删除原记录。
3. 主档数量和旧日志只保留为兼容缓存，写操作结束时对账更新。
4. 收付款和冲销先在 `request_idempotency` 原子插入请求占位，再生成业务事实；导入确认使用原子状态转换保证幂等。
5. 批量完成、收货和删除操作在服务端单事务内按 ID 排序加锁，保持全成全败。
6. 多仓配件必须携带来源仓；配件主档 `warehouseId` 仅是偏好仓。
7. 查询接口保持只读。租赁账单由显式刷新、归还流程或定时任务幂等生成。
8. 统计使用数据库范围查询和聚合投影，不使用 `findAll()` 后内存过滤。
9. 原生 ES Modules 前端继续保留；不在本轮引入前端框架。
10. FIFO 消耗先无锁定位候选批次主键，再按主键精确悲观锁定，避免范围查询对相邻配件产生 next-key gap lock 环。

## 拆分边界

- `HistoricalReferenceRepairService`：历史供应商和仓库引用修复。
- `RepairPartUsageService`：维修领料、FIFO 消耗、恢复和费用汇总。
- `ModificationAccountingService`：改装成本与财务事件。
- `OutboundDocumentService`：出库发票/合同存储和审计。
- `BatchBusinessOperationService`：批量事务入口。
- `StatisticsProjectionRepository`：统计聚合查询。

## 暂不采用

- Redis、消息队列和微服务。
- Spring Boot 4.x。
- 前端框架重写。
- 对多态 `sourceType/sourceId` 强加数据库外键。

## 结果

优点是事实来源明确、重复请求可恢复、库存与财务路径可通过 MySQL 集成测试验证。代价是兼容缓存仍需在写事务尾部维护，且旧数据升级前必须先运行历史修复和对账。
