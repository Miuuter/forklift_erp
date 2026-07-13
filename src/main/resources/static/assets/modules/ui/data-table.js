import { defineListViewConfig } from "./contracts.js";

const COLUMN_PROFILES = {
  vehicles: ["车型", "规格型号", "供应商", "经销商/仓位", "车辆数", "库存", "销售单价"],
  parts: ["编码", "名称", "品牌", "分类", "数量", "销售价"],
  modificationOrder: ["工单号", "车辆", "客户", "状态", "替换明细", "创建时间"],
  outboundOrders: ["订单号", "出库项", "客户", "销售日期", "价格", "收款跟进", "发票跟进"],
  rentals: ["租赁单", "车辆", "去向", "月租价", "租期", "状态"],
  customers: ["公司名称", "地址", "联系人", "电话", "税号/身份证号"],
  suppliers: ["供应商", "类型", "联系人", "电话", "税号"],
  purchases: ["入库单", "供应商", "入库内容", "数量", "金额", "状态"],
  stocktaking: ["盘点单", "盘点对象", "账面数量", "实盘数量", "差异", "状态"],
  warehouse: ["仓库", "类型", "默认", "整车", "配件 SKU", "配件数量"],
  repairs: ["维修时间", "车号", "客户", "维修人", "状态", "客户应收"],
  users: ["用户名", "角色", "职务", "状态", "创建时间"],
  attachments: ["附件", "业务对象", "类型", "大小", "上传时间", "状态"],
  imports: ["文件", "导入类型", "状态", "成功", "失败", "创建时间"]
};

export function createDataTable({
  state,
  escapeAttr,
  escapeHtml,
  display,
  renderEmptyState,
  createListEmptyState,
  entityLabel,
  selectedIdSet,
  renderBatchToolbar,
  batchActionsForKind,
  isBatchMode
}) {
  function listTableOptions(kind, exportType, options = {}) {
    return defineListViewConfig({
      tableKey: options.tableKey || exportType || kind,
      emptyState: options.emptyState === undefined ? () => createListEmptyState(kind) : options.emptyState,
      selectableRow: options.selectable === false ? null : row => ({
        action: "open-detail",
        data: { kind, id: row.id },
        active: state.detailDrawer?.kind === kind && Number(state.detailDrawer?.id) === Number(row.id),
        label: `查看${entityLabel(kind)}详情`
      }),
      batch: options.batch === false ? null : {
        kind,
        exportType,
        actions: batchActionsForKind(kind)
      }
    });
  }

  function renderTable(columns, rows, options = {}) {
    const tableKey = options.tableKey || null;
    const preparedColumns = prepareTableColumns(columns);
    const visibleRows = tableKey ? sortRowsForTable(tableKey, preparedColumns, rows) : rows;
    if (!visibleRows.length) return renderTableEmptyState(options);

    const batch = options.batch;
    const batchActive = Boolean(batch && isBatchMode(batch.kind));
    const visibleIds = visibleRows.map(row => String(row.id)).filter(Boolean);
    const selectedIds = batch ? selectedIdSet(batch.kind) : new Set();
    const allVisibleSelected = batchActive && visibleIds.length > 0 && visibleIds.every(id => selectedIds.has(id));
    const dataColumns = tableKey ? visibleTableColumns(tableKey, preparedColumns) : preparedColumns.filter(column => column.label !== "操作");
    const tableColumns = batchActive ? [
      {
        label: `<input class="row-check" type="checkbox" data-action="toggle-visible-select" data-batch-kind="${escapeAttr(batch.kind)}" data-visible-ids="${escapeAttr(visibleIds.join(","))}" aria-label="选择当前页" ${allVisibleSelected ? "checked" : ""}>`,
        htmlLabel: true,
        html: true,
        render: row => `<input class="row-check" type="checkbox" data-action="toggle-row-select" data-batch-kind="${escapeAttr(batch.kind)}" data-id="${escapeAttr(row.id)}" aria-label="选择此行" ${selectedIds.has(String(row.id)) ? "checked" : ""}>`
      },
      ...dataColumns
    ] : dataColumns;

    return `
      ${batch ? renderSelectionMode(batch, batchActive) : ""}
      ${batchActive ? renderBatchToolbar(batch, visibleRows) : ""}
      <div class="table-wrap" tabindex="0" aria-label="数据表格，可横向滚动">
        <table>
          <thead>
            <tr>${tableColumns.map(column => renderTableHeader(column, tableKey)).join("")}</tr>
          </thead>
          <tbody>
            ${visibleRows.map(row => {
              const selectable = options.selectableRow ? options.selectableRow(row) : null;
              const rowClass = [
                selectable ? "table-row-selectable" : "",
                selectable?.active ? "table-row-active" : "",
                batchActive && selectedIds.has(String(row.id)) ? "table-row-checked" : ""
              ].filter(Boolean).join(" ");
              return `
                <tr${rowClass ? ` class="${rowClass}"` : ""}${renderSelectableAttrs(selectable)}>
                  ${tableColumns.map(column => `<td data-label="${escapeAttr(tableCellLabel(column))}">${renderCell(column, row)}</td>`).join("")}
                </tr>
              `;
            }).join("")}
          </tbody>
        </table>
      </div>
    `;
  }

  function renderSelectionMode(batch, active) {
    const available = Boolean(batch.exportType || batch.actions?.length);
    if (!available) return "";
    return `
      <div class="selection-mode-bar${active ? " is-active" : ""}">
        <div>
          <strong>${active ? "批量选择已开启" : "需要批量处理？"}</strong>
          <span>${active ? "勾选当前页数据后执行批量操作" : "开启后显示复选框和批量工具栏"}</span>
        </div>
        <button class="btn btn-sm${active ? " btn-ghost" : ""}" type="button" data-action="toggle-batch-mode" data-batch-kind="${escapeAttr(batch.kind)}" aria-pressed="${active ? "true" : "false"}">${active ? "退出批量" : "批量选择"}</button>
      </div>
    `;
  }

  function renderTableEmptyState(options) {
    if (typeof options.emptyState === "function") return options.emptyState();
    if (options.emptyState) return options.emptyState;
    return renderEmptyState("暂无数据");
  }

  function prepareTableColumns(columns) {
    return (columns || []).map((column, index) => ({
      ...column,
      sortId: column.sortKey || column.key || `column-${index}`,
      sortable: column.sortable !== false && !column.htmlLabel && !column.html && Boolean(column.sortValue || column.key || column.render)
    }));
  }

  function visibleTableColumns(tableKey, columns) {
    const withoutActions = columns.filter(column => column.label !== "操作");
    const profile = COLUMN_PROFILES[tableKey];
    if (!profile) return withoutActions;
    const profileSet = new Set(profile);
    const selected = withoutActions.filter(column => profileSet.has(String(column.label || "")));
    return selected.length ? selected : withoutActions;
  }

  function renderTableHeader(column, tableKey) {
    if (column.htmlLabel) return `<th>${column.label}</th>`;
    const label = escapeHtml(column.label);
    if (!tableKey || !column.sortable) return `<th>${label}</th>`;
    const sort = state.sorts?.[tableKey];
    const active = sort?.key === column.sortId;
    const direction = active ? sort.direction : "";
    const indicator = active ? (direction === "desc" ? "↓" : "↑") : "↕";
    return `
      <th aria-sort="${active ? (direction === "desc" ? "descending" : "ascending") : "none"}">
        <button class="table-sort-button${active ? " is-active" : ""}" type="button" data-action="sort-table" data-table-key="${escapeAttr(tableKey)}" data-sort-key="${escapeAttr(column.sortId)}">
          <span>${label}</span><span class="sort-indicator" aria-hidden="true">${indicator}</span>
        </button>
      </th>
    `;
  }

  function tableCellLabel(column) {
    if (column.cardLabel !== undefined) return column.cardLabel;
    if (column.htmlLabel) return "选择";
    return String(column.label || "");
  }

  function applyTableSort(tableKey, sortKey) {
    if (!tableKey || !sortKey) return;
    const current = state.sorts?.[tableKey];
    const nextDirection = current?.key === sortKey && current.direction === "asc" ? "desc" : "asc";
    state.sorts = {
      ...(state.sorts || {}),
      [tableKey]: { key: sortKey, direction: nextDirection }
    };
  }

  function sortRowsForTable(tableKey, columns, rows) {
    const sort = state.sorts?.[tableKey];
    if (!sort?.key) return rows;
    const column = columns.find(item => item.sortId === sort.key);
    if (!column) return rows;
    const direction = sort.direction === "desc" ? -1 : 1;
    return [...rows].sort((left, right) => compareTableValues(tableSortValue(column, left), tableSortValue(column, right)) * direction);
  }

  function tableSortValue(column, row) {
    if (column.sortValue) return column.sortValue(row);
    if (column.key) return row[column.key];
    if (column.render && !column.html) return column.render(row);
    return "";
  }

  function compareTableValues(left, right) {
    const leftEmpty = left === null || left === undefined || left === "";
    const rightEmpty = right === null || right === undefined || right === "";
    if (leftEmpty && rightEmpty) return 0;
    if (leftEmpty) return 1;
    if (rightEmpty) return -1;
    const leftNumber = Number(left);
    const rightNumber = Number(right);
    if (Number.isFinite(leftNumber) && Number.isFinite(rightNumber)) return leftNumber - rightNumber;
    return String(left).localeCompare(String(right), "zh-CN", { numeric: true, sensitivity: "base" });
  }

  function renderCell(column, row) {
    const value = column.render ? column.render(row) : row[column.key];
    if (column.html) return value ?? "";
    const formatted = column.formatter ? column.formatter(value, row) : value;
    return escapeHtml(display(formatted));
  }

  function renderSelectableAttrs(selectable) {
    if (!selectable) return "";
    const attrs = [
      `tabindex="0"`,
      `role="button"`,
      `aria-label="${escapeAttr(selectable.label || "查看详情")}"`,
      `data-select-action="${escapeAttr(selectable.action || "open-detail")}"`
    ];
    Object.entries(selectable.data || {}).forEach(([key, value]) => {
      attrs.push(`data-${toDataAttrName(key)}="${escapeAttr(value)}"`);
    });
    return ` ${attrs.join(" ")}`;
  }

  function toDataAttrName(name) {
    return String(name || "").replace(/[A-Z]/g, letter => `-${letter.toLowerCase()}`);
  }

  return {
    listTableOptions,
    renderTable,
    applyTableSort,
    renderSelectableAttrs
  };
}
