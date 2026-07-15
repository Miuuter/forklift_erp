export function createOutboundWorkflow(deps) {
  const {
    state,
    filterRows,
    filterOutboundOrderRows,
    renderBackendSummary,
    summaryCard,
    money,
    renderToolbar,
    outboundOrderFilterControls,
    renderExportableSurface,
    renderTable,
    listTableOptions,
    renderPagination,
    dateValue,
    fileSize,
    statusBadge,
    badge,
    hasPermission,
    hasAnyRole,
    escapeAttr,
    escapeHtml,
    icon
  } = deps;

  function renderOutboundOrders() {
    const baseRows = filterRows(state.data.outboundOrders, state.search.outboundOrders, [
      "orderNo", "resourceType", "resourceCode", "resourceName", "customerName", "operator", "orderRemark",
      "paymentRemark", "invoiceStatus", "invoiceOriginalName", "registrationStatus", "contractType", "contractOriginalName"
    ]);
    const rows = filterOutboundOrderRows(baseRows);

    return `
      <div class="page">
        ${renderBackendSummary("outboundOrders", () => {
          const unsettledOrders = baseRows.filter(item => !item.paymentSettled).length;
          const pendingReports = baseRows.filter(item => !item.salesReported).length;
          const pendingInvoices = baseRows.filter(item => !item.invoiceApplied).length;
          const settledOrders = baseRows.filter(item => item.paymentSettled).length;
          const uploadedInvoices = baseRows.filter(item => item.invoiceFileAvailable).length;
          const uploadedContracts = baseRows.filter(item => item.contractFileAvailable).length;
          const outstandingAmount = receivableOutstandingTotal(baseRows);
          const overdueOrders = baseRows.filter(item => Number(item.overdueDays || 0) > 0).length;
          return `<section class="summary-grid">
            ${summaryCard("出库订单", baseRows.length, `${unsettledOrders} 单待收款`)}
            ${summaryCard("应收欠款", money(outstandingAmount), `${overdueOrders} 单逾期`)}
            ${summaryCard("待报销售", pendingReports, `${baseRows.filter(item => item.resourceType === "MACHINE").length} 单整机订单`)}
            ${summaryCard("待申请发票", pendingInvoices, `${uploadedInvoices} 单已上传发票`)}
            ${summaryCard("已结清车款", settledOrders, `${uploadedContracts} 单已上传合同`)}
          </section>`;
        })}
        ${renderToolbar("outboundOrders", "搜索订单号、客户、车号、收款情况、开票情况或备注", null, "", null, {
          main: [outboundOrderFilterControls()]
        })}
        ${renderExportableSurface("出库订单列表", "outboundOrders", renderTable([
          { label: "订单号", html: true, render: row => orderNoSummary(row) },
          { label: "出库项", html: true, render: row => orderResourceSummary(row) },
          { label: "客户", key: "customerName" },
          { label: "销售日期", key: "salesDate", formatter: dateValue },
          { label: "价格", html: true, render: row => orderPriceSummary(row) },
          { label: "收款跟进", html: true, render: row => orderPaymentSummary(row) },
          { label: "报销售", html: true, render: row => orderSalesReportSummary(row) },
          { label: "发票跟进", html: true, render: row => orderInvoiceSummary(row) },
          { label: "上牌/合同", html: true, render: row => orderContractSummary(row) },
          { label: "订单备注", key: "orderRemark" },
          { label: "操作", html: true, render: row => outboundOrderActions(row) }
        ], rows, listTableOptions("outboundOrder", "outboundOrders")))}
        ${renderPagination("outboundOrders")}
      </div>
    `;
  }

  function orderPriceSummary(row) {
    return `
      <div class="cell-stack">
        <span>单价 ${escapeHtml(money(row.unitSalePrice ?? row.settlementPrice ?? row.salePrice) || "-")}</span>
        <span class="helper-inline">行金额 ${escapeHtml(money(row.lineAmount ?? row.receivableAmount) || "-")}</span>
        <span class="helper-inline">应收 ${escapeHtml(money(row.receivableAmount ?? row.lineAmount) || "-")}</span>
      </div>
    `;
  }

  function orderNoSummary(row) {
    return `
      <div class="cell-stack">
        <strong>${escapeHtml(row.orderNo || "-")}</strong>
        ${row.isLocked ? `<span>${badge("已锁定", "danger")}</span>` : ""}
      </div>
    `;
  }

  function orderPaymentSummary(row) {
    const outstanding = receivableOutstanding(row);
    return `
      <div class="cell-stack">
        ${orderStatusToggle(row, "paymentSettled", Boolean(row.paymentSettled), "已结清", "未结清")}
        <span class="helper-inline">已收 ${escapeHtml(money(row.receivedAmount) || "¥0.00")} · 欠款 ${escapeHtml(money(outstanding) || "¥0.00")}</span>
        <span class="helper-inline">到期 ${escapeHtml(dateValue(row.paymentDueDate) || "-")} · 最近收款 ${escapeHtml(dateValue(row.lastPaymentDate) || "-")}</span>
        ${Number(row.overdueDays || 0) > 0 ? `<span class="helper-inline danger-text">逾期 ${escapeHtml(row.overdueDays)} 天</span>` : ""}
        ${row.paymentRemark ? `<span class="helper-inline">${escapeHtml(row.paymentRemark)}</span>` : `<span class="helper-inline">未填写收款情况</span>`}
      </div>
    `;
  }

  function receivableOutstanding(row = {}) {
    if (row.outstandingAmount !== undefined && row.outstandingAmount !== null) {
      return Number(row.outstandingAmount);
    }
    return Math.max(0, Number(row.receivableAmount ?? row.lineAmount ?? 0) - Number(row.receivedAmount ?? 0));
  }

  function receivableOutstandingTotal(rows = []) {
    return rows.reduce((sum, row) => sum + receivableOutstanding(row), 0);
  }

  function orderSalesReportSummary(row) {
    return `
      <div class="cell-stack">
        ${orderStatusToggle(row, "salesReported", Boolean(row.salesReported), "已报销售", "未报销售")}
        <span class="helper-inline">${escapeHtml(dateValue(row.salesReportDate) || "未登记报销售日期")}</span>
      </div>
    `;
  }

  function orderInvoiceSummary(row) {
    return `
      <div class="cell-stack">
        ${orderStatusToggle(row, "invoiceApplied", Boolean(row.invoiceApplied), "已申请发票", "未申请发票")}
        ${row.invoiceStatus ? `<span class="helper-inline">${escapeHtml(row.invoiceStatus)}</span>` : ""}
        <span class="helper-inline">申请 ${escapeHtml(dateValue(row.invoiceApplicationDate) || "-")} · 开票 ${escapeHtml(dateValue(row.invoiceIssuedDate) || "-")}</span>
        ${row.invoiceFileAvailable ? `<span class="helper-inline">发票 ${escapeHtml(row.invoiceOriginalName || "已上传")} · ${escapeHtml(fileSize(row.invoiceFileSize))}</span>` : `<span class="helper-inline">未上传发票文件</span>`}
      </div>
    `;
  }

  function isInvoiceIssued(row = {}) {
    if (row.invoiceIssuedDate) return true;
    const status = String(row.invoiceStatus || "").trim();
    const lowerStatus = status.toLowerCase();
    return status.includes("已开")
      || status.includes("开票完成")
      || status.includes("完成开票")
      || status.includes("已出票")
      || lowerStatus.includes("issued")
      || lowerStatus.includes("invoiced");
  }

  function isInvoiceUploadReady(row = {}) {
    return Boolean(row.invoiceApplied) || isInvoiceIssued(row);
  }

  function isContractUploadReady(row = {}) {
    return yesNoFromStatusText(row.contractType);
  }

  function orderContractSummary(row) {
    const registered = yesNoFromStatusText(row.registrationStatus);
    const contracted = yesNoFromStatusText(row.contractType);
    return `
      <div class="cell-stack">
        ${orderStatusToggle(row, "registrationStatus", registered, "已上牌", "未上牌")}
        ${orderStatusToggle(row, "contractType", contracted, "有合同", "无合同")}
        <span class="helper-inline">${escapeHtml([row.registrationStatus, row.contractType].filter(Boolean).join(" · ") || "未登记")}</span>
        ${row.contractFileAvailable ? `<span class="helper-inline">合同 ${escapeHtml(row.contractOriginalName || "已上传")} · ${escapeHtml(fileSize(row.contractFileSize))}</span>` : `<span class="helper-inline">${contracted ? "未上传合同文件" : "标记有合同后可上传"}</span>`}
      </div>
    `;
  }

  function orderStatusToggle(row, field, active, activeLabel, inactiveLabel) {
    if (!hasPermission("stock:adjust")) {
      return active ? badge(activeLabel, "teal") : badge(inactiveLabel, "primary");
    }
    const label = active ? activeLabel : inactiveLabel;
    const type = active ? "teal" : "primary";
    return `<button class="status-toggle ${escapeAttr(type)}" type="button" data-action="toggle-order-status" data-id="${escapeAttr(row.id)}" data-field="${escapeAttr(field)}" aria-pressed="${active ? "true" : "false"}" title="点击切换状态">${escapeHtml(label)}</button>`;
  }

  function yesNoFromStatusText(value) {
    const normalized = String(value || "").trim();
    if (!normalized) return false;
    const lower = normalized.toLowerCase();
    if (["否", "无", "未", "不", "no", "none", "false"].some(prefix => lower.startsWith(prefix))) return false;
    if (normalized.includes("未登记") || normalized.includes("未上牌") || normalized.includes("无合同")) return false;
    return true;
  }

  function outboundOrderActions(row) {
    if (!hasPermission("stock:adjust")) return "";
    const uploadReady = isInvoiceUploadReady(row);
    const contractReady = isContractUploadReady(row);
    const nextLocked = !Boolean(row.isLocked);
    return `
      <div class="action-row">
        <button class="btn btn-sm" type="button" data-action="edit" data-kind="outboundOrder" data-id="${escapeAttr(row.id)}">${icon("edit")}编辑</button>
        <button class="btn btn-sm btn-primary" type="button" data-action="record-payment" data-source-type="OUTBOUND_ORDER" data-source-id="${escapeAttr(row.id)}" data-direction="RECEIPT">${icon("money")}登记收款</button>
        <button class="btn btn-sm" type="button" data-action="reverse-payment" data-source-type="OUTBOUND_ORDER" data-source-id="${escapeAttr(row.id)}">${icon("refresh")}收款冲销</button>
        ${canManageOrderLock() ? `<button class="btn btn-sm ${row.isLocked ? "" : "btn-danger"}" type="button" data-action="toggle-order-lock" data-id="${escapeAttr(row.id)}" data-locked="${escapeAttr(nextLocked)}">${icon(row.isLocked ? "unlock" : "lock")}${row.isLocked ? "解锁" : "锁定"}</button>` : ""}
        ${uploadReady ? `<button class="btn btn-sm" type="button" data-action="upload-invoice" data-id="${escapeAttr(row.id)}">${icon("upload")}上传发票</button>` : ""}
        ${row.invoiceFileAvailable ? `<button class="btn btn-sm" type="button" data-action="download-invoice" data-id="${escapeAttr(row.id)}">${icon("download")}下载发票</button>` : ""}
        ${contractReady ? `<button class="btn btn-sm" type="button" data-action="upload-contract" data-id="${escapeAttr(row.id)}">${icon("upload")}上传合同</button>` : ""}
        ${row.contractFileAvailable ? `<button class="btn btn-sm" type="button" data-action="download-contract" data-id="${escapeAttr(row.id)}">${icon("download")}下载合同</button>` : ""}
      </div>
    `;
  }

  function orderResourceSummary(row) {
    return `
      <div>
        <div>${escapeHtml(row.resourceCode || "-")}</div>
        <div class="helper-inline">${escapeHtml(row.resourceName || "-")}${row.specificationModel ? ` · ${escapeHtml(row.specificationModel)}` : ""}</div>
      </div>
    `;
  }

  function canManageOrderLock() {
    return hasAnyRole("ADMIN", "SUPER_ADMIN");
  }

  return {
    renderOutboundOrders,
    orderPriceSummary,
    orderNoSummary,
    orderPaymentSummary,
    receivableOutstanding,
    receivableOutstandingTotal,
    orderSalesReportSummary,
    orderInvoiceSummary,
    isInvoiceIssued,
    isInvoiceUploadReady,
    isContractUploadReady,
    orderContractSummary,
    orderStatusToggle,
    yesNoFromStatusText,
    outboundOrderActions,
    orderResourceSummary,
    canManageOrderLock
  };
}

export function buildVehicleOutboundOrderPayload(payload, machine, customerId) {
  return {
    machineId: payload.machineId,
    machineVersion: machine.version,
    warehouseId: payload.warehouseId,
    customerId,
    salesDate: payload.salesDate,
    unitSalePrice: payload.unitSalePrice,
    lineAmount: payload.lineAmount,
    // Compatibility aliases for older API consumers. They are always derived
    // from the explicit unit/line semantics above.
    settlementPrice: payload.unitSalePrice,
    salePrice: payload.unitSalePrice,
    receivableAmount: payload.lineAmount,
    receivedAmount: payload.receivedAmount,
    paymentDueDate: payload.paymentDueDate,
    lastPaymentDate: payload.lastPaymentDate,
    paymentSettled: payload.paymentSettled,
    paymentRemark: payload.paymentRemark,
    salesReported: payload.salesReported,
    salesReportDate: payload.salesReportDate,
    invoiceApplied: payload.invoiceApplied,
    invoiceApplicationDate: payload.invoiceApplicationDate,
    invoiceStatus: payload.invoiceStatus,
    invoiceIssuedDate: payload.invoiceIssuedDate,
    registrationStatus: payload.registrationStatus,
    contractType: payload.contractType,
    operator: payload.operator,
    orderRemark: payload.orderRemark
  };
}
