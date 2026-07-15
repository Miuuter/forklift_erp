export function createRepairsWorkflow(deps) {
  const {
    state,
    filterRows,
    filterRepairRows,
    renderToolbar,
    repairFilterControls,
    renderExportableSurface,
    renderTable,
    listTableOptions,
    renderPagination,
    dateTime,
    money,
    rowActions,
    activeRentalForMachine,
    hasPermission,
    statusBadge,
    repairStatusText,
    badge,
    escapeAttr,
    escapeHtml
  } = deps;

  function renderRepairs() {
    const rows = filterRepairRows(filterRows(state.data.repairs, state.search.repairs, [
      "vehicleNumber", "customerName", "repairPerson", "status", "faultDescription"
    ]));

    return `
      <div class="page">
        ${renderToolbar("repairs", "搜索客户、车号、维修人、状态", "repair", "新增维修", "repair:write", {
          main: [repairFilterControls()]
        })}
        ${renderExportableSurface("维修记录", "repairs", renderTable([
          { label: "维修时间", key: "repairDate", formatter: dateTime },
          { label: "车号", html: true, render: row => repairVehicleSummary(row) },
          { label: "客户", html: true, render: row => repairCustomerSummary(row) },
          { label: "维修人", html: true, render: row => repairPersonSummary(row) },
          { label: "状态", html: true, render: row => repairStatusToggle(row) },
          { label: "客户应收", key: "totalFee", formatter: money },
          { label: "操作", html: true, render: row => rowActions("repair", row, ["edit", "delete"]) }
        ], rows, listTableOptions("repair", "repairs")))}
        ${renderPagination("repairs")}
      </div>
    `;
  }

  function repairVehicleSummary(row = {}) {
    const rental = activeRentalForMachine(row.machineId);
    return `
      <div class="cell-stack">
        <strong>${escapeHtml(row.vehicleNumber || "-")}</strong>
        ${rental ? `<span>${badge("租赁中", "warn")}</span>` : ""}
      </div>
    `;
  }

  function repairCustomerSummary(row = {}) {
    return `
      <div class="cell-stack">
        <strong>${escapeHtml(row.customerName || "-")}</strong>
        ${row.customerAddress ? `<span class="helper-inline">${escapeHtml(row.customerAddress)}</span>` : ""}
      </div>
    `;
  }

  function repairPersonSummary(row = {}) {
    return escapeHtml(row.repairExternal ? (row.repairPerson || "其他") : (row.repairPerson || "-"));
  }

  function repairStatusToggle(row = {}) {
    if (!hasPermission("repair:write")) {
      return statusBadge(row.status);
    }
    const normalized = row.status === "COMPLETED" ? "COMPLETED" : "PENDING";
    const active = normalized === "COMPLETED";
    return `<button class="status-toggle ${active ? "teal" : "warn"}" type="button" data-action="toggle-repair-status" data-id="${escapeAttr(row.id)}" aria-pressed="${active ? "true" : "false"}" title="点击切换维修状态">${escapeHtml(repairStatusText(normalized))}</button>`;
  }

  return {
    renderRepairs,
    repairVehicleSummary,
    repairCustomerSummary,
    repairPersonSummary,
    repairStatusToggle
  };
}
