export function createOperationsWorkflow(deps) {
  const {
    state,
    LOG_PAGE_SIZE,
    filterRows,
    pageTotal,
    renderBackendSummary,
    renderToolbar,
    renderExportableSurface,
    renderSurface,
    renderTable,
    listTableOptions,
    renderPagination,
    summaryCard,
    emptyState,
    badge,
    icon,
    escapeAttr,
    escapeHtml,
    dateTime,
    dateValue,
    display,
    money,
    stockText,
    resourceTypeLabel,
    hasRole,
    hasPermission,
    rowActions,
    modificationStatusBadge,
    repairStatusText,
    purchaseOrderFilterControls,
    purchaseOrderSummary,
    purchaseResourceSummary,
    purchaseStatusControl,
    stocktakingSummary,
    stocktakingDifference,
    stocktakingStatusBadge,
    stocktakingActions,
    uniqueSupplierCount,
    logCategoryBadge,
    operationActionBadge,
    quantityChange
  } = deps;

  function renderModificationOrders() {
    const rows = filterRows(state.data.modificationOrders, state.search.modificationOrders, [
      "workOrderNo", "machineProductNumber", "machineName", "specificationModel", "customerName", "salesOrderNo", "status", "operator", "remark"
    ]);
  
    return `
      <div class="page">
        ${renderToolbar("modificationOrders", "搜索工单号、客户、销售单或状态", "modificationOrder", "新建改装工单", "replace:write", {
          exportType: false
        })}
        ${renderSurface("改装工单列表", renderTable([
          { label: "工单号", key: "workOrderNo" },
          { label: "车辆", html: true, render: row => modificationOrderMachineSummary(row) },
          { label: "客户", key: "customerName" },
          { label: "销售单", key: "salesOrderNo" },
          { label: "状态", html: true, render: row => modificationStatusBadge(row.status) },
          { label: "替换明细", html: true, render: row => modificationLineSummary(row.lines) },
          { label: "创建时间", key: "createdAt", formatter: dateTime }
        ], rows, listTableOptions("modificationOrder", null)))}
        ${renderPagination("modificationOrders")}
      </div>
    `;
  }
  
  function modificationOrderMachineSummary(row) {
    return `
      <div class="cell-stack">
        <strong>${escapeHtml(row.machineProductNumber || `ID ${row.machineId || "-"}`)}</strong>
        <span class="helper-inline">${escapeHtml([row.machineName, row.specificationModel].filter(Boolean).join(" / ") || "-")}</span>
      </div>
    `;
  }
  
  function renderCustomers() {
    const rows = filterRows(state.data.customers, state.search.customers, [
      "companyName", "address", "contactName", "contactPhone", "taxOrIdNumber", "remarks"
    ]);
  
    return `
      <div class="page">
        ${renderToolbar("customers", "搜索公司、联系人、电话或税号", "customer", "新增客户", "vehicle:write")}
        ${renderExportableSurface("客户列表", "customers", renderTable([
          { label: "公司名称", key: "companyName" },
          { label: "地址", key: "address" },
          { label: "联系人", key: "contactName" },
          { label: "电话", key: "contactPhone" },
          { label: "税号/身份证号", key: "taxOrIdNumber" },
          { label: "备注", key: "remarks" }
        ], rows, listTableOptions("customer", "customers")))}
        ${renderPagination("customers")}
      </div>
    `;
  }
  
  function renderSuppliers() {
    const rows = filterRows(state.data.suppliers, state.search.suppliers, [
      "supplierName", "supplierType", "contactName", "contactPhone", "address", "taxNumber", "remarks"
    ]);
  
    return `
      <div class="page">
        ${renderBackendSummary("suppliers", () => {
          const typed = rows.filter(item => item.supplierType).length;
          return `<section class="summary-grid">
            ${summaryCard("供应商总数", pageTotal("suppliers", rows.length), `${typed} 家已标记类型`)}
            ${summaryCard("入库订单供应商", uniqueSupplierCount(), "已被入库订单引用")}
            ${summaryCard("联系人", rows.filter(item => item.contactName || item.contactPhone).length, "可直接联系")}
          </section>`;
        })}
        ${renderToolbar("suppliers", "搜索供应商、联系人、电话或税号", "supplier", "新增供应商", "stock:adjust")}
        ${renderExportableSurface("供应商列表", "suppliers", renderTable([
          { label: "供应商", html: true, render: row => supplierSummary(row) },
          { label: "类型", key: "supplierType" },
          { label: "联系人", key: "contactName" },
          { label: "电话", key: "contactPhone" },
          { label: "税号", key: "taxNumber" },
          { label: "备注", key: "remarks" }
        ], rows, listTableOptions("supplier", "suppliers")))}
        ${renderPagination("suppliers")}
      </div>
    `;
  }
  
  function renderPurchaseOrders() {
    const rows = filterRows(state.data.purchaseOrders, state.search.purchases, [
      "purchaseNo", "supplierName", "resourceType", "resourceCode", "resourceName", "specificationModel", "status", "operator", "remark"
    ]);
  
    return `
      <div class="page">
        ${renderBackendSummary("purchases", () => {
          const totalAmount = rows.reduce((sum, row) => sum + Number(row.totalAmount || 0), 0);
          const received = rows.filter(row => row.status === "RECEIVED").length;
          const pending = rows.filter(row => row.status !== "RECEIVED" && row.status !== "CANCELED").length;
          const freightTotal = rows.reduce((sum, row) => sum + Number(row.freightAmount || 0), 0);
          return `<section class="summary-grid">
            ${summaryCard("入库订单", pageTotal("purchases", rows.length), `${pending} 单待跟进`)}
            ${summaryCard("入库金额", money(totalAmount), "当前筛选合计")}
            ${summaryCard("已收货", received, `运费 ${money(freightTotal)}`)}
          </section>`;
        })}
        ${renderToolbar("purchases", "搜索入库单号、供应商、配件、整车、规格型号或经办人", "purchaseOrder", "新增入库订单", "stock:adjust", {
          main: [purchaseOrderFilterControls()]
        })}
        ${renderExportableSurface("入库订单", "purchases", renderTable([
          { label: "入库单", html: true, render: row => purchaseOrderSummary(row) },
          { label: "供应商", key: "supplierName" },
          { label: "入库内容", html: true, render: row => purchaseResourceSummary(row) },
          { label: "数量", html: true, render: row => `${escapeHtml(row.quantity || 0)} <span class="helper-inline">${escapeHtml(row.unit || "")}</span>` },
          { label: "金额", key: "totalAmount", formatter: money },
          { label: "状态", html: true, render: row => purchaseStatusControl(row) }
        ], rows, listTableOptions("purchaseOrder", "purchases")))}
        ${renderPagination("purchases")}
      </div>
    `;
  }
  
  function renderStocktakes() {
    const rows = filterRows(state.data.stocktakingRecords, state.search.stocktakes, [
      "stocktakingNo", "resourceType", "resourceCode", "resourceName", "specificationModel", "status", "operator", "remark"
    ]);
  
    return `
      <div class="page">
        ${renderBackendSummary("stocktakes", () => {
          const drafts = rows.filter(row => row.status !== "COMPLETED").length;
          const completed = rows.filter(row => row.status === "COMPLETED").length;
          const differences = rows.filter(row => Number(row.differenceQuantity || 0) !== 0).length;
          return `<section class="summary-grid">
            ${summaryCard("盘点记录", pageTotal("stocktakes", rows.length), `${drafts} 条待入账`)}
            ${summaryCard("已入账", completed, "库存已同步")}
            ${summaryCard("有差异", differences, "账实不一致")}
          </section>`;
        })}
        ${renderToolbar("stocktakes", "搜索盘点单、资源编码、名称、型号或盘点人", "stocktaking", "新增盘点", "stock:adjust")}
        ${renderExportableSurface("库存盘点", "stocktakes", renderTable([
          { label: "盘点单", html: true, render: row => stocktakingSummary(row) },
          { label: "盘点对象", html: true, render: row => purchaseResourceSummary(row) },
          { label: "账面", key: "bookQuantity", formatter: stockText },
          { label: "实盘", key: "actualQuantity", formatter: stockText },
          { label: "差异", html: true, render: row => stocktakingDifference(row) },
          { label: "状态", html: true, render: row => stocktakingStatusBadge(row.status) }
        ], rows, listTableOptions("stocktaking", "stocktakes")))}
        ${renderPagination("stocktakes")}
      </div>
    `;
  }
  
  function renderWarehouses() {
    const rows = filterRows(state.data.warehouses, state.search.warehouses, [
      "warehouseCode", "warehouseName", "warehouseType", "address"
    ]);
    const vehicleTotal = rows.reduce((sum, row) => sum + Number(row.vehicleCount || 0), 0);
    const partQuantity = rows.reduce((sum, row) => sum + Number(row.partQuantity || 0), 0);
    const defaultWarehouse = rows.find(row => row.defaultWarehouse);
  
    return `
      <div class="page">
        <section class="summary-grid">
          ${summaryCard("仓库数量", pageTotal("warehouses", rows.length), defaultWarehouse ? `默认：${defaultWarehouse.warehouseName}` : "未设置默认仓")}
          ${summaryCard("整车库存", vehicleTotal, "按仓库余额汇总")}
          ${summaryCard("配件库存", partQuantity, `${rows.reduce((sum, row) => sum + Number(row.partSkuCount || 0), 0)} 个 SKU`)}
        </section>
        <div class="toolbar">
          <div class="search-box">
            ${icon("search")}
            <input type="search" placeholder="搜索仓库编码、名称、类型或地址" value="${escapeAttr(state.search.warehouses || "")}" data-search-for="warehouses">
            ${state.search.warehouses ? `<button class="icon-btn" type="button" data-action="clear-search" data-search-for="warehouses" title="清空搜索">${icon("minus")}</button>` : ""}
          </div>
          <div class="toolbar-actions">
            <button class="btn" type="button" data-action="stock-transfer">${icon("swap")}库存调拨</button>
            <button class="btn btn-primary" type="button" data-action="create" data-kind="warehouse">${icon("plus")}新增仓库</button>
          </div>
        </div>
        ${renderSurface("仓库列表", renderTable([
          { label: "仓库", html: true, render: row => warehouseSummary(row) },
          { label: "类型", key: "warehouseType" },
          { label: "默认", html: true, render: row => row.defaultWarehouse ? badge("默认", "teal") : "" },
          { label: "整车", key: "vehicleCount", formatter: stockText },
          { label: "配件 SKU", key: "partSkuCount", formatter: stockText },
          { label: "配件数量", key: "partQuantity", formatter: stockText }
        ], rows, listTableOptions("warehouse", null)))}
        ${renderPagination("warehouses")}
      </div>
    `;
  }
  
  function renderMaintenance() {
    if (!hasRole("SUPER_ADMIN")) {
      return `<div class="page">${renderSurface("数据维护", emptyState("当前账号无权访问数据维护"))}</div>`;
    }
    const canImport = hasRole("SUPER_ADMIN");
    const canBackup = hasRole("SUPER_ADMIN");
    return `
      <div class="page">
        <section class="summary-grid">
          ${summaryCard("导入中心", canImport ? "可进入" : "不可用", "模板下载、预校验和导入记录")}
          ${summaryCard("数据库备份", canBackup ? "JSON" : "仅超级管理员", canBackup ? "导出当前应用表数据" : "恢复与备份仅超级管理员可见")}
          ${summaryCard("附件文件", "未包含", "发票/合同实体文件单独保管")}
        </section>
        ${canImport ? renderSurface("导入中心", `
          <div class="action-grid">
            <button class="btn btn-primary" type="button" data-action="go-tab" data-tab="imports">${icon("fileSearch")}打开导入中心</button>
          </div>
        `) : ""}
        ${canBackup ? renderSurface("备份与恢复", `
          <div class="action-grid">
            <button class="btn btn-primary" type="button" data-action="download-backup">${icon("download")}下载数据库备份</button>
            <button class="btn" type="button" data-action="restore-backup">${icon("upload")}恢复数据库备份</button>
          </div>
        `) : renderSurface("备份与恢复", emptyState("仅超级管理员可执行备份与恢复"))}
      </div>
    `;
  }
  
  function warehouseSummary(row = {}) {
    return `
      <div class="cell-stack">
        <strong>${escapeHtml(row.warehouseName || "-")}</strong>
        <span>${escapeHtml(row.warehouseCode || "-")}${row.address ? ` · ${escapeHtml(row.address)}` : ""}</span>
      </div>
    `;
  }
  
  function stockMovementSummary(row = {}) {
    return `
      <div class="cell-stack">
        <strong>${escapeHtml(row.movementNo || "-")}</strong>
        <span>${escapeHtml(movementTypeText(row.movementType))}</span>
      </div>
    `;
  }
  
  function stockMovementResource(row = {}) {
    return `
      <div class="cell-stack">
        <strong>${escapeHtml(row.resourceCode || "-")}</strong>
        <span>${escapeHtml(resourceTypeLabel(row.resourceType))} · ${escapeHtml(row.resourceName || "-")}</span>
      </div>
    `;
  }
  
  function stockMovementDelta(row = {}) {
    const delta = Number(row.quantityDelta || 0);
    const tone = delta > 0 ? "teal" : delta < 0 ? "danger" : "muted";
    return `<span>${badge(`${delta > 0 ? "+" : ""}${delta}`, tone)}</span><span class="helper-inline">${escapeHtml(row.beforeQuantity ?? 0)} -> ${escapeHtml(row.afterQuantity ?? 0)}</span>`;
  }
  
  function movementTypeText(type) {
    return {
      INITIAL: "期初",
      INITIAL_BALANCE: "期初",
      INBOUND: "入库",
      OUTBOUND: "出库",
      ADJUST: "调整",
      TRANSFER: "调拨",
      PART_INSTALL: "装车",
      REPLACE: "替换"
    }[type] || display(type);
  }
  
  function renderOperationLogs() {
    const rows = filterRows(state.data.operationLogs, state.search.logs, [
      "category", "action", "target", "summary", "operator", "remark"
    ]);
    const movementRows = filterRows(state.data.stockMovements || [], state.search.logs, [
      "movementNo", "movementType", "resourceType", "resourceCode", "resourceName", "warehouseName", "operator", "remark"
    ]);
    const visibleRows = rows.slice(0, state.visibleLogRows);
    const hiddenCount = Math.max(0, rows.length - visibleRows.length);
  
    return `
      <div class="page page-logs">
        ${renderToolbar("logs", "搜索日志类型、对象、操作人或备注", null, "", null, {
          className: "logs-toolbar",
          exportType: false,
          actions: [`
            <div class="logs-summary">
              <span>共 ${escapeHtml(state.data.operationLogs.length)} 条</span>
              <span>当前显示 ${escapeHtml(visibleRows.length)} / ${escapeHtml(rows.length)} 条</span>
            </div>
          `]
        })}
        ${renderSurface("库存流水", `
          ${renderTable([
            { label: "时间", key: "createdAt", formatter: dateTime },
            { label: "流水", html: true, render: row => stockMovementSummary(row) },
            { label: "资源", html: true, render: row => stockMovementResource(row) },
            { label: "仓库", key: "warehouseName" },
            { label: "变动", html: true, render: row => stockMovementDelta(row) },
            { label: "经办人", key: "operator" },
            { label: "备注", key: "remark" }
          ], movementRows, listTableOptions("stockMovement", null, { selectable: false, batch: false }))}
          ${renderPagination("stockMovements")}
        `)}
        ${renderSurface("关键操作审计", renderTable([
          { label: "时间", key: "createdAt", formatter: dateTime },
          { label: "类型", html: true, render: row => logCategoryBadge(row.category) },
          { label: "动作", html: true, render: row => operationActionBadge(row.action) },
          { label: "对象", key: "target" },
          { label: "内容", key: "summary" },
          { label: "数量", html: true, render: row => quantityChange(row) },
          { label: "操作人", key: "operator" },
          { label: "备注", key: "remark" }
        ], visibleRows))}
        ${hiddenCount ? `<div class="load-more-row"><button class="btn" type="button" data-action="load-more-logs">加载更多 ${escapeHtml(Math.min(LOG_PAGE_SIZE, hiddenCount))} 条</button></div>` : ""}
        ${renderPagination("logs")}
      </div>
    `;
  }
  
  function modificationOrderActions(row) {
    if (!hasPermission("replace:write")) return "";
    const closed = row.status === "COMPLETED" || row.status === "CANCELED";
    return `
      <div class="action-row">
        ${!closed ? `<button class="btn btn-sm btn-primary" type="button" data-action="complete-modification-order" data-id="${escapeAttr(row.id)}">${icon("swap")}完成</button>` : ""}
        ${!closed ? `<button class="btn btn-sm btn-danger" type="button" data-action="cancel-modification-order" data-id="${escapeAttr(row.id)}">${icon("trash")}取消</button>` : ""}
      </div>
    `;
  }
  
  function modificationLineSummary(lines = []) {
    if (!lines.length) return "-";
    return lines.map(line => `
      <div>${escapeHtml(line.itemName || "-")}：${escapeHtml(line.oldValue || "-")} -> ${escapeHtml(line.newValue || "-")}
        <span class="helper-inline">x${escapeHtml(line.quantity || 1)}</span>
        ${line.oldPartAction === "DISCOUNT" ? `<span class="helper-inline">${Number(line.priceDifference || 0) < 0 ? "折价收入" : "折价支出"} ${escapeHtml(money(Math.abs(Number(line.priceDifference || 0))))}</span>` : ""}
      </div>
    `).join("");
  }
  
  function supplierSummary(row = {}) {
    return `
      <div class="cell-stack">
        <strong>${escapeHtml(row.supplierName || "-")}</strong>
        ${row.address ? `<span class="helper-inline">${escapeHtml(row.address)}</span>` : ""}
      </div>
    `;
  }

  return {
    renderModificationOrders,
    renderCustomers,
    renderSuppliers,
    renderPurchaseOrders,
    renderStocktakes,
    renderWarehouses,
    renderOperationLogs,
    renderMaintenance,
    modificationOrderActions,
    modificationLineSummary
  };
}
