export function createRentalsWorkflow(deps) {
  const {
    state,
    filterRows,
    filterRentalRows,
    renderBackendSummary,
    summaryCard,
    money,
    renderToolbar,
    rentalFilterControls,
    renderExportableSurface,
    renderTable,
    rentalStatusBadge,
    rowActions,
    listTableOptions,
    renderPagination,
    escapeHtml,
    dateTime,
    dateValue
  } = deps;

  function renderRentals() {
    const rows = filterRentalRows(filterRows(state.data.rentals, state.search.rentals, [
      "rentalNo", "vehicleNumber", "machineName", "specificationModel", "customerName", "customerAddress", "destination", "status", "operator", "remark"
    ]));

    return `
      <div class="page">
        ${renderBackendSummary("rentals", () => {
          const activeRows = rows.filter(item => item.status === "ACTIVE");
          const returnedRows = rows.filter(item => item.status === "RETURNED");
          const rentalIncome = rows.reduce((sum, item) => sum + rentalAccruedIncome(item), 0);
          return `<section class="summary-grid">
            ${summaryCard("租赁记录", rows.length, `${activeRows.length} 台租赁中`)}
            ${summaryCard("已计租赁收入", money(rentalIncome), `${returnedRows.length} 单已归还`)}
            ${summaryCard("租赁车辆", new Set(rows.map(item => item.machineId).filter(Boolean)).size, "按具体车号记录")}
            ${summaryCard("待归还", activeRows.length, activeRows.length ? "请持续跟进租赁去向" : "暂无进行中租赁")}
          </section>`;
        })}
        ${renderToolbar("rentals", "搜索租赁单、车号、客户、去向或经办人", "rental", "新增租赁", "stock:adjust", {
          main: [rentalFilterControls()]
        })}
        ${renderExportableSurface("租赁记录", "rentals", renderTable([
          { label: "租赁单", html: true, render: row => rentalNoSummary(row) },
          { label: "车辆", html: true, render: row => rentalVehicleSummary(row) },
          { label: "去向", html: true, render: row => rentalCustomerSummary(row) },
          { label: "月租价", html: true, render: row => money(rentalMonthlyPrice(row)) },
          { label: "租期", html: true, render: row => rentalPeriodSummary(row) },
          { label: "状态", html: true, render: row => rentalStatusBadge(row.status) },
          { label: "经办人", key: "operator" },
          { label: "备注", key: "remark" }
        ], rows, listTableOptions("rental", "rentals")))}
        ${renderPagination("rentals")}
      </div>
    `;
  }

  function rentalNoSummary(row) {
    return `
      <div class="cell-stack">
        <strong>${escapeHtml(row.rentalNo || "-")}</strong>
        <span class="helper-inline">${escapeHtml(dateTime(row.createdAt) || "-")}</span>
      </div>
    `;
  }

  function rentalVehicleSummary(row) {
    return `
      <div class="cell-stack">
        <strong>${escapeHtml(row.vehicleNumber || `ID ${row.machineId || "-"}`)}</strong>
        <span class="helper-inline">${escapeHtml([row.machineName, row.specificationModel].filter(Boolean).join(" / ") || "-")}</span>
      </div>
    `;
  }

  function rentalCustomerSummary(row) {
    return `
      <div class="cell-stack">
        <strong>${escapeHtml(row.customerName || row.destination || "-")}</strong>
        <span class="helper-inline">${escapeHtml(row.destination || row.customerAddress || "-")}</span>
      </div>
    `;
  }

  function rentalMonthlyPrice(row) {
    return row?.monthlyRentalPrice ?? row?.rentalPrice ?? 0;
  }

  function rentalAccruedIncome(row = {}) {
    const period = rentalEffectivePeriod(row);
    const monthlyPrice = Number(rentalMonthlyPrice(row) || 0);
    if (!period || monthlyPrice <= 0) return 0;
    let total = 0;
    let cursor = { year: period.start.year, month: period.start.month };
    const last = { year: period.end.year, month: period.end.month };
    while (compareYearMonth(cursor, last) <= 0) {
      const monthStart = { year: cursor.year, month: cursor.month, day: 1 };
      const monthEnd = { year: cursor.year, month: cursor.month, day: daysInMonth(cursor.year, cursor.month) };
      const segmentStart = maxDate(period.start, monthStart);
      const segmentEnd = minDate(period.end, monthEnd);
      const days = daysBetween(segmentStart, segmentEnd) + 1;
      if (days > 0) {
        total += monthlyPrice * days / daysInMonth(cursor.year, cursor.month);
      }
      cursor = nextYearMonth(cursor);
    }
    return Number(total.toFixed(2));
  }

  function rentalEffectivePeriod(row = {}) {
    const start = parseLocalDate(row.startDate) || parseLocalDate(row.createdAt);
    if (!start) return null;
    let end = null;
    if (row.status === "RETURNED") {
      end = parseLocalDate(row.endDate) || parseLocalDate(row.updatedAt) || start;
    } else {
      end = todayLocalDate();
    }
    return end && compareDates(end, start) >= 0 ? { start, end } : null;
  }

  function parseLocalDate(value) {
    const text = dateValue(value);
    const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(text || "");
    if (!match) return null;
    return { year: Number(match[1]), month: Number(match[2]), day: Number(match[3]) };
  }

  function todayLocalDate() {
    const today = new Date();
    return { year: today.getFullYear(), month: today.getMonth() + 1, day: today.getDate() };
  }

  function compareDates(left, right) {
    return dayNumber(left) - dayNumber(right);
  }

  function compareYearMonth(left, right) {
    return left.year === right.year ? left.month - right.month : left.year - right.year;
  }

  function maxDate(left, right) {
    return compareDates(left, right) >= 0 ? left : right;
  }

  function minDate(left, right) {
    return compareDates(left, right) <= 0 ? left : right;
  }

  function daysBetween(start, end) {
    return Math.floor((dayNumber(end) - dayNumber(start)));
  }

  function dayNumber(value) {
    return Math.floor(Date.UTC(value.year, value.month - 1, value.day) / 86400000);
  }

  function daysInMonth(year, month) {
    return new Date(Date.UTC(year, month, 0)).getUTCDate();
  }

  function nextYearMonth(value) {
    return value.month === 12 ? { year: value.year + 1, month: 1 } : { year: value.year, month: value.month + 1 };
  }

  function rentalPeriodSummary(row) {
    return `
      <div class="cell-stack">
        <span>${escapeHtml(dateValue(row.startDate) || "-")}</span>
        <span class="helper-inline">至 ${escapeHtml(dateValue(row.endDate) || "未定")}</span>
      </div>
    `;
  }

  return {
    renderRentals,
    rentalNoSummary,
    rentalVehicleSummary,
    rentalCustomerSummary,
    rentalMonthlyPrice,
    rentalAccruedIncome,
    rentalPeriodSummary
  };
}
