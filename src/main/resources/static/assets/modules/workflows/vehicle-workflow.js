export function createVehicleWorkflow(deps) {
  const {
    state,
    vehicleDetailTabs,
    ensureVehicleDetailTab,
    activeRentalForMachine,
    latestVehicleOutboundOrder,
    yesNoFromText,
    yesNoText,
    renderToolbar,
    renderSurface,
    renderTable,
    renderDetailGrid,
    detailItem,
    renderPagination,
    filterButtonGroup,
    hasPermission,
    hasAnyRole,
    icon,
    escapeAttr,
    escapeHtml,
    dateValue,
    dateTime,
    fileSize,
    money,
    emptyState,
    badge,
    stockBadge,
    stockStatusBadge,
    stockStatusLabel,
    rentalMonthlyPrice,
    rentalStatusBadge,
    modificationStatusBadge,
    modificationLineSummary,
    modificationOrderActions,
    repairCustomerSummary,
    repairPersonSummary,
    repairStatusToggle,
    rowActions,
    receivableOutstanding,
    isInvoiceUploadReady,
    isContractUploadReady,
    isManualForklift,
    vehicleModelLabel,
    powerTypeOptions,
    stockFilterOptions,
    supplierFilterOptions,
    normalizePowerType
  } = deps;

  function renderVehicles() {
    const modelRows = filterVehicleRows(state.data.vehicleModels || []);
  
    return `
      <div class="page">
        ${renderToolbar("vehicles", "搜索车型、型号、供应商或车号", null, "", null, {
          main: [vehicleFilterControls()],
          actions: [hasPermission("vehicle:write") ? renderVehicleModelMenu() : ""]
        })}
        <section class="vehicle-master-detail">
          <div class="vehicle-master-pane">
            ${renderVehicleModelPanel(modelRows)}
          </div>
          <div class="vehicle-detail-pane">
            ${renderVehicleDetail()}
          </div>
        </section>
      </div>
    `;
  }
  
  function renderVehicleModelPanel(rows) {
    const body = state.pages.vehicles?.loading
      ? `<div class="loading-state"><span class="loading-spinner" aria-hidden="true"></span><span>正在加载车型库存...</span></div>`
      : rows.length
        ? `<div class="vehicle-model-list">${rows.map(renderVehicleModelCard).join("")}</div>`
        : emptyState("暂无符合条件的车型库存");
    return `
      <section class="surface vehicle-model-list-surface">
        <div class="surface-head">
          <h2 class="surface-title">车型库存列表</h2>
          <span class="surface-count">${escapeHtml(state.pages.vehicles?.totalElements || rows.length)} 个车型</span>
        </div>
        <div class="surface-body">
          ${body}
          ${renderPagination("vehicles")}
        </div>
      </section>
    `;
  }

  function renderVehicleModelCard(row) {
    const pending = state.vehicleDetailLoading && state.pendingVehicleModelKey === row.modelKey;
    const active = pending || state.vehicleDetail?.modelKey === row.modelKey;
    const supplierLocation = [row.supplier, row.warehouseName].filter(Boolean).join(" · ") || "未设置供应商或仓位";
    return `
      <button class="vehicle-model-card${active ? " is-active" : ""}${pending ? " is-loading" : ""}" type="button"
        data-action="detail-vehicle" data-model-key="${escapeAttr(row.modelKey)}"
        aria-pressed="${active ? "true" : "false"}"
        aria-label="查看车型 ${escapeAttr(vehicleModelLabel(row))} 详情">
        <span class="vehicle-model-card-head">
          <span>
            <strong>${escapeHtml(vehicleModelLabel(row))}</strong>
            <small>${escapeHtml(row.specificationModel || "未填写规格型号")}</small>
          </span>
          ${stockBadge(row.inventoryCount, "台")}
        </span>
        <span class="vehicle-model-card-meta">
          ${badge(row.machineType || "未分类", "primary")}
          <span>${escapeHtml(supplierLocation)}</span>
        </span>
        <span class="vehicle-model-card-metrics">
          <span><small>车辆数</small><strong>${escapeHtml(row.unitCount || 0)}</strong></span>
          <span><small>当前库存</small><strong>${escapeHtml(row.inventoryCount || 0)} 台</strong></span>
          <span><small>销售单价</small><strong>${escapeHtml(money(row.salePrice))}</strong></span>
        </span>
        ${pending ? `<span class="vehicle-model-card-loading"><span class="loading-spinner" aria-hidden="true"></span>加载详情中</span>` : ""}
      </button>
    `;
  }

  function vehicleFlowRows() {
    return state.data.vehicles
      .filter(item => !item.modelOnly)
      .map(machine => {
        const order = latestVehicleOutboundOrder(machine.id);
        const rental = activeRentalForMachine(machine.id);
        return {
          ...machine,
          orderId: order?.id || null,
          rentalId: rental?.id || null,
          rentalNo: rental?.rentalNo || "",
          rentalDestination: rental?.destination || "",
          rentalPrice: rentalMonthlyPrice(rental),
          rentalStartDate: rental?.startDate || "",
          rentalEndDate: rental?.endDate || "",
          customerName: order?.customerName || machine.destination1 || "",
          paymentSettled: Boolean(order?.paymentSettled),
          paymentRemark: order?.paymentRemark || "",
          salesReported: order ? Boolean(order.salesReported) : yesNoFromText(machine.isSalesReported),
          invoiceApplied: order ? Boolean(order.invoiceApplied) : yesNoFromText(machine.isInvoiceApplied),
          salesDate: order?.salesDate || machine.salesDate || "",
          salesReportDate: order?.salesReportDate || machine.salesReportDate || "",
          invoiceApplicationDate: order?.invoiceApplicationDate || "",
          invoiceStatus: order?.invoiceStatus || "",
          invoiceIssuedDate: order?.invoiceIssuedDate || "",
          invoiceFileAvailable: Boolean(order?.invoiceFileAvailable),
          invoiceOriginalName: order?.invoiceOriginalName || "",
          invoiceFileSize: order?.invoiceFileSize || null,
          invoiceUploadedAt: order?.invoiceUploadedAt || "",
          salePrice: order?.salePrice ?? machine.salePrice,
          settlementPrice: order?.settlementPrice ?? machine.settlementPrice,
          registrationStatus: order?.registrationStatus || "",
          contractType: order?.contractType || "",
          contractFileAvailable: Boolean(order?.contractFileAvailable),
          contractOriginalName: order?.contractOriginalName || "",
          contractFileSize: order?.contractFileSize || null,
          contractUploadedAt: order?.contractUploadedAt || "",
          orderRemark: order?.orderRemark || "",
          destinationText: joinVehicleDestinations(machine)
        };
      })
      .sort((left, right) => {
        const leftKey = `${left.inboundDate || ""}-${String(left.id || "").padStart(8, "0")}`;
        const rightKey = `${right.inboundDate || ""}-${String(right.id || "").padStart(8, "0")}`;
        return rightKey.localeCompare(leftKey);
      });
  }
  
  function joinVehicleDestinations(machine = {}) {
    return [machine.destination1, machine.destination2, machine.destination3, machine.destination4, machine.destination5]
      .filter(value => value !== null && value !== undefined && String(value).trim() !== "")
      .join(" / ");
  }
  
  function vehicleFlowStage(row) {
    if (row?.rentalId) {
      return { label: "租赁中", type: "warn" };
    }
    if (!row?.orderId) {
      return Number(row?.inventoryCount || 0) > 0
        ? { label: "在库待销售", type: "teal" }
        : { label: "仅保留入库档案", type: "primary" };
    }
    if (!row.paymentSettled) {
      return { label: "待收款", type: "warn" };
    }
    if (!row.salesReported) {
      return { label: "待报销售", type: "primary" };
    }
    if (!row.invoiceApplied) {
      return { label: "待申请发票", type: "warn" };
    }
    return { label: "销售闭环完成", type: "success" };
  }
  
  function vehicleFlowActions(row) {
    return `
      <div class="action-row">
        <button class="btn btn-sm" type="button" data-action="detail-vehicle" data-id="${escapeAttr(row.id)}">${icon("eye")}详情</button>
        ${row.orderId && hasPermission("stock:adjust") ? `<button class="btn btn-sm" type="button" data-action="edit" data-kind="outboundOrder" data-id="${escapeAttr(row.orderId)}">${icon("edit")}销售跟进</button>` : ""}
        ${row.orderId && hasPermission("stock:adjust") && isInvoiceUploadReady(row) ? `<button class="btn btn-sm" type="button" data-action="upload-invoice" data-id="${escapeAttr(row.orderId)}">${icon("upload")}发票</button>` : ""}
        ${row.orderId && hasPermission("stock:adjust") && row.invoiceFileAvailable ? `<button class="btn btn-sm" type="button" data-action="download-invoice" data-id="${escapeAttr(row.orderId)}">${icon("download")}发票</button>` : ""}
        ${row.orderId && hasPermission("stock:adjust") && isContractUploadReady(row) ? `<button class="btn btn-sm" type="button" data-action="upload-contract" data-id="${escapeAttr(row.orderId)}">${icon("upload")}合同</button>` : ""}
        ${row.orderId && hasPermission("stock:adjust") && row.contractFileAvailable ? `<button class="btn btn-sm" type="button" data-action="download-contract" data-id="${escapeAttr(row.orderId)}">${icon("download")}合同</button>` : ""}
        ${!row.orderId && !row.rentalId && Number(row.inventoryCount || 0) > 0 && hasPermission("stock:adjust") ? `<button class="btn btn-sm" type="button" data-action="vehicle-rental-direct" data-machine-id="${escapeAttr(row.id)}">${icon("plus")}登记租赁</button>` : ""}
        ${!row.orderId && !row.rentalId && Number(row.inventoryCount || 0) > 0 && hasPermission("stock:adjust") && hasAnyRole("ADMIN", "SUPER_ADMIN") ? `<button class="btn btn-sm btn-danger" type="button" data-action="vehicle-stock" data-direction="adjustOutbound" data-id="${escapeAttr(row.id)}">${icon("minus")}库存减少</button>` : ""}
        ${hasPermission("vehicle:write") ? `<button class="btn btn-sm" type="button" data-action="edit" data-kind="vehicle" data-id="${escapeAttr(row.id)}">${icon("edit")}编辑档案</button>` : ""}
      </div>
    `;
  }
  
  function vehicleFlowMachineSummary(row) {
    return `
      <div class="cell-stack">
        <strong>${escapeHtml(row.vehicleProductNumber || `ID ${row.id}`)}</strong>
        <span class="helper-inline">${escapeHtml(row.name || "-")} · ${escapeHtml(row.specificationModel || "-")}</span>
        ${row.configuration ? `<span class="helper-inline">${escapeHtml(row.configuration)}</span>` : ""}
      </div>
    `;
  }
  
  function vehicleFlowStatusSummary(row) {
    const stage = vehicleFlowStage(row);
    return `
      <div class="cell-stack">
        ${badge(stage.label, stage.type)}
        ${stockStatusBadge(row.stockStatus)}
        <span class="helper-inline">库存 ${escapeHtml(row.inventoryCount || 0)} 台</span>
        ${row.rentalId ? `<span class="helper-inline">租赁去向 ${escapeHtml(row.rentalDestination || "-")}</span>` : ""}
      </div>
    `;
  }
  
  function vehicleFlowFollowupSummary(row) {
    return `
      <div class="cell-stack">
        <span>${escapeHtml(dateValue(row.salesDate) || "未登记销售日期")}</span>
        <span class="helper-inline">车款 ${escapeHtml(yesNoText(row.paymentSettled))} · 报销售 ${escapeHtml(yesNoText(row.salesReported))} · 发票 ${escapeHtml(yesNoText(row.invoiceApplied))}</span>
        ${row.invoiceFileAvailable ? `<span class="helper-inline">发票文件 ${escapeHtml(row.invoiceOriginalName || "已上传")}</span>` : ""}
        ${row.contractFileAvailable ? `<span class="helper-inline">合同文件 ${escapeHtml(row.contractOriginalName || "已上传")}</span>` : ""}
        ${row.paymentRemark ? `<span class="helper-inline">${escapeHtml(row.paymentRemark)}</span>` : ""}
      </div>
    `;
  }
  
  function renderVehicleArchiveSurface(machine = {}) {
    const actions = machine?.id && hasPermission("vehicle:write")
      ? `<button class="btn btn-sm" type="button" data-action="edit" data-kind="vehicle" data-id="${escapeAttr(machine.id)}">${icon("edit")}编辑档案</button>`
      : "";
    return renderSurface("入库档案", `
      ${renderDetailGrid([
      { label: "名称", value: machine.name },
      { label: "规格型号", value: machine.specificationModel },
      { label: "配置", value: machine.configuration },
      { label: "动力", value: machine.machineType },
      { label: "供应商", value: machine.supplier },
      { label: "经销商/仓位", value: machine.warehouseName },
      { label: "样机申请单号", value: machine.applicationNumber },
      { label: "物料号", value: machine.materialNumber },
      { label: "发动机号", value: machine.engineNumber },
      { label: "车架号", value: machine.frameNumber },
      { label: "保修卡号", value: machine.warrantyCardNumber },
      { label: "制造日期", value: dateValue(machine.manufacturingDate) },
      { label: "入库时间", value: dateTime(machine.inboundDate) },
      { label: "库存状态", value: stockStatusLabel(machine.stockStatus) },
      { label: "结算价", value: money(machine.settlementPrice) },
      { label: "销售单价", value: money(machine.salePrice) }
    ])}
    `, actions);
  }
  
  function renderVehicleSalesSurface(machine = {}, order = null) {
    return renderSurface("销售跟进", `
      ${renderDetailGrid([
        { label: "销售日期", value: dateValue(order?.salesDate || machine.salesDate) },
        { label: "车款结清", value: yesNoText(order?.paymentSettled) },
        { label: "应收金额", value: money(order?.receivableAmount ?? order?.settlementPrice) },
        { label: "已收金额", value: money(order?.receivedAmount) },
        { label: "欠款金额", value: money(order ? receivableOutstanding(order) : 0) },
        { label: "收款到期日", value: dateValue(order?.paymentDueDate) },
        { label: "最近收款日", value: dateValue(order?.lastPaymentDate) },
        { label: "收款情况", value: order?.paymentRemark },
        { label: "报销售状态", value: machine.isSalesReported || yesNoText(order?.salesReported) },
        { label: "报销售日期", value: dateValue(order?.salesReportDate || machine.salesReportDate) },
        { label: "发票申请", value: machine.isInvoiceApplied || yesNoText(order?.invoiceApplied) },
        { label: "发票申请日期", value: dateValue(order?.invoiceApplicationDate) },
        { label: "开票情况", value: order?.invoiceStatus },
        { label: "开票日期", value: dateValue(order?.invoiceIssuedDate) },
        { label: "发票文件", value: order?.invoiceFileAvailable ? `${order.invoiceOriginalName || "已上传"} · ${fileSize(order.invoiceFileSize)}` : "" },
        { label: "发票上传时间", value: dateTime(order?.invoiceUploadedAt) },
        { label: "上牌情况", value: order?.registrationStatus },
        { label: "合同", value: order?.contractType },
        { label: "合同文件", value: order?.contractFileAvailable ? `${order.contractOriginalName || "已上传"} · ${fileSize(order.contractFileSize)}` : "" },
        { label: "合同上传时间", value: dateTime(order?.contractUploadedAt) },
        { label: "订单备注", value: order?.orderRemark }
      ])}
      ${order?.id && hasPermission("stock:adjust") ? `<div class="toolbar-actions detail-action-row">
        <button class="btn" type="button" data-action="edit" data-kind="outboundOrder" data-id="${escapeAttr(order.id)}">${icon("edit")}编辑销售跟进</button>
        ${isInvoiceUploadReady(order) ? `<button class="btn" type="button" data-action="upload-invoice" data-id="${escapeAttr(order.id)}">${icon("upload")}上传发票</button>` : ""}
        ${order.invoiceFileAvailable ? `<button class="btn" type="button" data-action="download-invoice" data-id="${escapeAttr(order.id)}">${icon("download")}下载发票</button>` : ""}
        ${isContractUploadReady(order) ? `<button class="btn" type="button" data-action="upload-contract" data-id="${escapeAttr(order.id)}">${icon("upload")}上传合同</button>` : ""}
        ${order.contractFileAvailable ? `<button class="btn" type="button" data-action="download-contract" data-id="${escapeAttr(order.id)}">${icon("download")}下载合同</button>` : ""}
      </div>` : ""}
    `);
  }
  
  function renderVehicleDestinationSurface(machine = {}) {
    return renderSurface("去向追踪", renderDetailGrid([
      { label: "去向1", value: machine.destination1 },
      { label: "去向2", value: machine.destination2 },
      { label: "去向3", value: machine.destination3 },
      { label: "去向4", value: machine.destination4 },
      { label: "去向5", value: machine.destination5 },
      { label: "备注", value: machine.remarks }
    ]));
  }
  
  function renderVehiclePartsSurface(machine = {}, parts = []) {
    const actions = hasPermission("replace:write")
      ? `<button class="btn btn-sm" type="button" data-action="vehicle-config-change" data-mode="INSTALL" data-machine-id="${escapeAttr(machine.id || "")}">${icon("swap")}配置变更</button>`
      : "";
    const body = parts.length ? renderTable([
      { label: "编码", key: "partCode" },
      { label: "名称", key: "partName" },
      { label: "分类", key: "partCategory" },
      { label: "库存", render: row => `${row.quantity ?? 0}${row.unit || ""}` },
      { label: "操作", html: true, render: row => hasPermission("part:write") ? `<button class="btn btn-sm" type="button" data-action="edit" data-kind="part" data-id="${escapeAttr(row.id)}">${icon("edit")}编辑</button>` : "" }
    ], parts) : emptyState("此车还没有关联配件");
    return renderSurface(`关联配件 · ${escapeHtml(machine.vehicleProductNumber || "-")}`, `
      ${body}
    `, actions);
  }
  
  function renderVehicleDetailTabs(activeKey) {
    return `
      <div class="detail-tabs" role="tablist" aria-label="车辆详情">
        ${vehicleDetailTabs.map(tab => `
          <button class="detail-tab${tab.key === activeKey ? " is-active" : ""}" type="button" role="tab" aria-selected="${tab.key === activeKey ? "true" : "false"}" data-action="set-vehicle-detail-tab" data-tab="${escapeAttr(tab.key)}">
            ${escapeHtml(tab.label)}
          </button>
        `).join("")}
      </div>
    `;
  }
  
  function renderVehicleDetailTabContent(activeKey, machine, detail) {
    const configs = detail.configs || [];
    const logs = detail.logs || [];
    const workOrders = detail.workOrders || [];
    const vehicleParts = detail.vehicleParts || [];
    const order = detail.order || null;
    if (!machine?.id && activeKey !== "archive") return emptyState("请先选择具体车号");
    if (activeKey === "config") return `<div class="grid-two">${renderVehicleConfigSurface(machine, configs)}${renderVehiclePartsSurface(machine, vehicleParts)}</div>`;
    if (activeKey === "outbound") return renderVehicleSalesSurface(machine, order);
    if (activeKey === "rental") return renderVehicleRentalSurface(machine);
    if (activeKey === "repair") return renderVehicleRepairSurface(machine);
    if (activeKey === "modification") return `<div class="grid-two">${renderVehicleModificationOrdersSurface(workOrders)}${renderVehicleReplaceLogsSurface(logs)}</div>`;
    if (activeKey === "attachments") return renderVehicleAttachmentsSurface(machine, order);
    return `<div class="grid-two">${renderVehicleArchiveSurface(machine)}${renderVehicleDestinationSurface(machine)}</div>`;
  }
  
  function renderVehicleConfigSurface(machine = {}, configs = []) {
    return renderSurface("当前配置", renderTable([
      { label: "配置项", key: "itemName" },
      { label: "当前值", key: "selectedValue" },
      { label: "来源", key: "configSource" },
      { label: "操作", html: true, render: row => `
        <div class="action-row">
          ${hasPermission("replace:write") ? `<button class="btn btn-sm" type="button" data-action="vehicle-config-change" data-mode="WORK_ORDER" data-machine-id="${escapeAttr(machine.id)}" data-machine-config-id="${escapeAttr(row.id)}">${icon("swap")}配置变更</button>` : ""}
        </div>
      ` }
    ], configs));
  }
  
  function renderVehicleModificationOrdersSurface(workOrders = []) {
    return renderSurface("改装工单", renderTable([
      { label: "工单号", key: "workOrderNo" },
      { label: "客户", key: "customerName" },
      { label: "状态", html: true, render: row => modificationStatusBadge(row.status) },
      { label: "替换明细", html: true, render: row => modificationLineSummary(row.lines) },
      { label: "操作", html: true, render: row => modificationOrderActions(row) }
    ], workOrders));
  }
  
  function renderVehicleReplaceLogsSurface(logs = []) {
    return renderSurface("替换记录", renderTable([
      { label: "配置项", key: "itemName" },
      { label: "旧值", key: "oldValue" },
      { label: "新值", key: "newValue" },
      { label: "类型", key: "replaceType" },
      { label: "时间", key: "createdAt", formatter: dateTime }
    ], logs));
  }
  
  function renderVehicleRentalSurface(machine = {}) {
    const rental = activeRentalForMachine(machine.id);
    const actions = machine?.id && hasPermission("stock:adjust")
      ? `<button class="btn btn-sm" type="button" data-action="vehicle-rental-direct" data-machine-id="${escapeAttr(machine.id)}">${icon("plus")}租赁登记</button>`
      : "";
    const body = rental ? renderDetailGrid([
      { label: "租赁单", value: rental.rentalNo },
      { label: "客户", value: rental.customerName },
      { label: "去向", value: rental.destination },
      { label: "月租价", value: money(rentalMonthlyPrice(rental)) },
      { label: "开始日期", value: dateValue(rental.startDate) },
      { label: "预计结束", value: dateValue(rental.endDate) },
      { label: "状态", value: rentalStatusBadge(rental.status), html: true }
    ]) : emptyState("当前车辆暂无进行中的租赁");
    return renderSurface("租赁记录", body, actions);
  }
  
  function renderVehicleRepairSurface(machine = {}) {
    const repairs = vehicleRepairsForMachine(machine);
    const actions = hasPermission("repair:write")
      ? `<button class="btn btn-sm" type="button" data-action="create" data-kind="repair" data-machine-id="${escapeAttr(machine.id || "")}">${icon("plus")}维修登记</button>`
      : "";
    const body = repairs.length ? renderTable([
      { label: "维修时间", key: "repairDate", formatter: dateTime },
      { label: "客户", html: true, render: row => repairCustomerSummary(row) },
      { label: "维修人", html: true, render: row => repairPersonSummary(row) },
      { label: "状态", html: true, render: row => repairStatusToggle(row) },
      { label: "客户应收", key: "totalFee", formatter: money },
      { label: "操作", html: true, render: row => rowActions("repair", row, ["edit", "delete"]) }
    ], repairs) : emptyState("当前车辆暂无维修记录");
    return renderSurface("维修记录", body, actions);
  }
  
  function vehicleRepairsForMachine(machine = {}) {
    const id = Number(machine.id || 0);
    const vehicleNumber = String(machine.vehicleProductNumber || "").trim();
    return (state.data.repairs || []).filter(item => {
      if (id && Number(item.machineId || 0) === id) return true;
      return vehicleNumber && String(item.vehicleNumber || "").trim() === vehicleNumber;
    });
  }
  
  function renderVehicleAttachmentsSurface(machine = {}, order = null) {
    const hasOrder = Boolean(order?.id);
    const body = hasOrder ? renderDetailGrid([
      { label: "订单号", value: order.orderNo },
      { label: "发票", value: order.invoiceFileAvailable ? `${order.invoiceOriginalName || "已上传"} ${fileSize(order.invoiceFileSize)}` : "未上传" },
      { label: "发票上传时间", value: dateTime(order.invoiceUploadedAt) },
      { label: "合同", value: order.contractFileAvailable ? `${order.contractOriginalName || "已上传"} ${fileSize(order.contractFileSize)}` : "未上传" },
      { label: "合同上传时间", value: dateTime(order.contractUploadedAt) }
    ]) : emptyState("暂无销售出库订单附件");
    const actions = hasOrder ? `
      <div class="toolbar-actions">
        ${isInvoiceUploadReady(order) ? `<button class="btn btn-sm" type="button" data-action="upload-invoice" data-id="${escapeAttr(order.id)}">${icon("upload")}上传发票</button>` : ""}
        ${order.invoiceFileAvailable ? `<button class="btn btn-sm" type="button" data-action="download-invoice" data-id="${escapeAttr(order.id)}">${icon("download")}下载发票</button>` : ""}
        ${isContractUploadReady(order) ? `<button class="btn btn-sm" type="button" data-action="upload-contract" data-id="${escapeAttr(order.id)}">${icon("upload")}上传合同</button>` : ""}
        ${order.contractFileAvailable ? `<button class="btn btn-sm" type="button" data-action="download-contract" data-id="${escapeAttr(order.id)}">${icon("download")}下载合同</button>` : ""}
      </div>
    ` : "";
    return renderSurface(`附件资料 · ${escapeHtml(machine.vehicleProductNumber || "-")}`, body, actions);
  }
  
  function renderVehicleDetail() {
    if (state.vehicleDetailLoading) {
      return `
        <section class="surface vehicle-detail-surface" id="vehicleDetailSurface" aria-busy="true">
          <div class="surface-head">
            <h2 class="surface-title">车型详情</h2>
          </div>
          <div class="surface-body">
            <div class="loading-state vehicle-detail-loading">
              <span class="loading-spinner" aria-hidden="true"></span>
              <span>正在加载车型与库存车号...</span>
            </div>
          </div>
        </section>
      `;
    }
    if (!state.vehicleDetail) {
      return `
        <section class="surface vehicle-detail-surface" id="vehicleDetailSurface">
          <div class="surface-head">
            <h2 class="surface-title">车型详情</h2>
          </div>
          <div class="surface-body">
            ${emptyState("请从左侧选择车型查看库存、车号和业务详情")}
          </div>
        </section>
      `;
    }
    if (state.vehicleDetail.modelKey) {
      return renderVehicleModelDetail();
    }
    const machine = state.vehicleDetail.machine || {};
    const configs = state.vehicleDetail.configs || [];
    const logs = state.vehicleDetail.logs || [];
    const workOrders = state.vehicleDetail.workOrders || [];
    const vehicleParts = state.vehicleDetail.parts || [];
    const order = latestVehicleOutboundOrder(machine.id);
    const activeTab = ensureVehicleDetailTab();
    return `
      <section class="surface detail-surface-pop vehicle-detail-surface" id="vehicleDetailSurface">
        <div class="surface-head">
          <h2 class="surface-title">车辆详情 · ${escapeHtml(machine.vehicleProductNumber || "")}</h2>
          <div class="toolbar-actions">
            ${order?.id && hasPermission("stock:adjust") ? `<button class="btn" type="button" data-action="edit" data-kind="outboundOrder" data-id="${escapeAttr(order.id)}">${icon("edit")}销售跟进</button>` : ""}
        ${hasPermission("replace:write") ? `<button class="btn btn-primary" type="button" data-action="vehicle-config-change" data-mode="INSTALL" data-machine-id="${escapeAttr(machine.id || "")}">${icon("swap")}车辆配置变更</button>` : ""}
          </div>
        </div>
        <div class="surface-body detail-tab-body">
          ${renderVehicleDetailTabs(activeTab)}
          <div class="detail-tab-panel">
            ${renderVehicleDetailTabContent(activeTab, machine, { configs, logs, workOrders, vehicleParts, order })}
          </div>
        </div>
      </section>
    `;
  }
  
  function renderVehicleModelDetail() {
    const detail = state.vehicleDetail;
    const model = detail.model || {};
    const selected = detail.selectedDetail?.machine || {};
    const configs = detail.selectedDetail?.configs || [];
    const logs = detail.selectedDetail?.logs || [];
    const workOrders = detail.selectedDetail?.workOrders || [];
    const vehicleParts = detail.selectedDetail?.parts || [];
    const manualSelected = isManualForklift(selected.machineType || model.machineType);
    const order = latestVehicleOutboundOrder(selected.id);
    const activeTab = ensureVehicleDetailTab();
    return `
      <section class="surface detail-surface-pop vehicle-detail-surface" id="vehicleDetailSurface">
        <div class="surface-head">
          <h2 class="surface-title">车型详情 · ${escapeHtml(vehicleModelLabel(model))}</h2>
          <div class="toolbar-actions">
            ${hasPermission("vehicle:write") ? `<button class="btn btn-primary" type="button" data-action="model-inbound" data-model-key="${escapeAttr(detail.modelKey)}">${icon("plus")}此车型入库</button>` : ""}
            ${hasPermission("stock:adjust") ? `<button class="btn" type="button" data-action="model-outbound" data-model-key="${escapeAttr(detail.modelKey)}">${icon("minus")}销售出库</button>` : ""}
          </div>
        </div>
        <div class="surface-body detail-tab-body">
          <div class="detail-grid">
            ${detailItem("车型名称", model.name)}
            ${detailItem("规格型号", model.specificationModel)}
            ${detailItem("默认配置", model.configuration)}
            ${detailItem("车辆类型", model.machineType)}
            ${detailItem("供应商", model.supplier)}
            ${detailItem("经销商/仓位", model.warehouseName)}
            ${detailItem("库存台数", `${model.inventoryCount || 0} 台`)}
            ${detailItem("销售单价", money(model.salePrice))}
          </div>
          <div class="grid-two vehicle-model-detail-grid">
            ${renderSurface("库存车号", `
              <div class="vehicle-unit-grid">
                ${detail.vehicles.length ? detail.vehicles.map(vehicle => renderVehicleUnitCard(vehicle, detail.modelKey, vehicle.id === selected.id)).join("") : emptyState("此车型还没有库存车")}
              </div>
            `)}
            ${renderSurface(`当前车号 · ${escapeHtml(selected.vehicleProductNumber || "请选择车号")}`, `
              <div class="detail-grid detail-grid-compact">
                ${detailItem("车号", selected.vehicleProductNumber)}
                ${detailItem("库存状态", stockStatusLabel(selected.stockStatus))}
                ${manualSelected ? "" : detailItem("发动机号", selected.engineNumber)}
                ${manualSelected ? "" : detailItem("车架号", selected.frameNumber)}
                ${manualSelected ? "" : detailItem("保修卡号", selected.warrantyCardNumber)}
              </div>
              <div class="toolbar-actions detail-action-row">
                ${order?.id && hasPermission("stock:adjust") ? `<button class="btn" type="button" data-action="edit" data-kind="outboundOrder" data-id="${escapeAttr(order.id)}">${icon("edit")}销售跟进</button>` : ""}
                ${selected.id && hasPermission("replace:write") ? `<button class="btn btn-primary" type="button" data-action="vehicle-config-change" data-mode="INSTALL" data-machine-id="${escapeAttr(selected.id || "")}">${icon("swap")}车辆配置变更</button>` : ""}
              </div>
            `)}
          </div>
          ${renderVehicleDetailTabs(activeTab)}
          <div class="detail-tab-panel">
            ${renderVehicleDetailTabContent(activeTab, selected, { configs, logs, workOrders, vehicleParts, order })}
          </div>
        </div>
      </section>
    `;
  }
  
  function renderVehicleUnitCard(vehicle, modelKey, active) {
    const manual = isManualForklift(vehicle.machineType);
    return `
      <button class="vehicle-unit-card${active ? " is-active" : ""}" type="button" data-action="select-model-vehicle" data-model-key="${escapeAttr(modelKey)}" data-machine-id="${escapeAttr(vehicle.id)}">
        <span class="vehicle-unit-title">${escapeHtml(vehicle.vehicleProductNumber || `ID ${vehicle.id}`)}</span>
        <span class="vehicle-unit-meta">${escapeHtml(stockStatusLabel(vehicle.stockStatus))}${manual ? " · 系统车号" : ` · ${escapeHtml(vehicle.engineNumber || "未填发动机号")}`}</span>
        ${manual ? "" : `<span class="vehicle-unit-meta">${escapeHtml(vehicle.frameNumber || "未填车架号")}</span>`}
      </button>
    `;
  }
  
  function vehicleFilterControls() {
    const filters = state.filters.vehicles;
    return `
      <div class="filter-row">
        ${filterButtonGroup("vehicles", "power", filters.power, "动力", "全部动力", powerTypeOptions())}
        ${filterButtonGroup("vehicles", "stock", filters.stock, "库存", "全部库存", stockFilterOptions())}
        ${filterButtonGroup("vehicles", "supplier", filters.supplier, "供应商", "全部供应商", supplierFilterOptions())}
      </div>
    `;
  }
  
  function renderVehicleModelMenu(options = {}) {
    const triggerClass = options.triggerClass || "btn btn-primary hover-menu-trigger";
    return `
      <div class="hover-menu">
        <button class="${escapeAttr(triggerClass)}" type="button">${icon("plus")}新增车型</button>
        <div class="hover-menu-panel">
          ${powerTypeOptions().map(option => `
            <button type="button" data-action="create-vehicle-model" data-power-type="${escapeAttr(option.value)}">${escapeHtml(`新增${option.label}`)}</button>
          `).join("")}
        </div>
      </div>
    `;
  }
  
  function vehicleModelActions(row) {
    return `
      <div class="action-row">
        <button class="btn btn-sm" type="button" data-action="detail-vehicle" data-model-key="${escapeAttr(row.modelKey)}">${icon("eye")}详情</button>
        ${hasPermission("vehicle:write") ? `<button class="btn btn-sm btn-primary" type="button" data-action="model-inbound" data-model-key="${escapeAttr(row.modelKey)}">${icon("plus")}入库</button>` : ""}
        ${hasPermission("stock:adjust") ? `<button class="btn btn-sm" type="button" data-action="model-outbound" data-model-key="${escapeAttr(row.modelKey)}">${icon("minus")}销售出库</button>` : ""}
      </div>
    `;
  }
  
  function filterVehicleRows(rows) {
    const filters = state.filters.vehicles || {};
    return rows.filter(row => {
      if (filters.power && normalizePowerType(row.machineType) !== filters.power) return false;
      if (filters.supplier && row.supplier !== filters.supplier) return false;
      if (filters.stock === "inStock" && Number(row.inventoryCount || 0) <= 0) return false;
      if (filters.stock === "empty" && Number(row.inventoryCount || 0) !== 0) return false;
      return true;
    });
  }

  return {
    renderVehicles,
    vehicleFlowRows,
    vehicleFlowMachineSummary,
    vehicleFlowStatusSummary,
    vehicleFlowFollowupSummary,
    vehicleFlowActions,
    ensureVehicleDetailTab
  };
}
