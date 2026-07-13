export function createImportWorkflow(deps) {
  const {
    state,
    api,
    endpoints,
    resetPage,
    getContentElement,
    loadPagedTab,
    loadAllData,
    renderCurrentTab,
    downloadProtectedFile,
    showToast,
    confirmDanger,
    entityDisplayName,
    findEntity,
    markReferenceDataStale,
    filterRows,
    hasRole,
    pageTotal,
    renderToolbar,
    renderSurface,
    renderTable,
    listTableOptions,
    renderPagination,
    filterButtonGroup,
    summaryCard,
    emptyState,
    badge,
    escapeAttr,
    escapeHtml,
    dateTime,
    display,
    icon
  } = deps;

  async function downloadImportTemplate(type) {
    const importType = String(type || state.importSelectedType || "vehicle-workbook").trim();
    state.importSelectedType = importType;
    await downloadProtectedFile(endpoints.imports.template(importType), `${importType}-template.xlsx`);
    showToast("模板下载已开始", "success");
  }

  async function validateImportFromPage() {
    const typeSelect = getContentElement()?.querySelector("[data-import-type-select]");
    const fileInput = getContentElement()?.querySelector("[data-import-file]");
    const importType = String(typeSelect?.value || state.importSelectedType || "vehicle-workbook").trim();
    const file = fileInput?.files?.[0];
    if (!file) {
      showToast("请选择 Excel 文件", "error");
      return;
    }
    state.importSelectedType = importType;
    const formData = new FormData();
    formData.append("file", file);
    const validation = await api(endpoints.imports.validate(importType), {
      method: "POST",
      body: formData
    });
    state.importValidation = validation;
    if (state.pages.imports) resetPage(state.pages.imports);
    await loadPagedTab("imports", { force: true });
    renderCurrentTab();
    showToast(validation.importable ? "预校验通过，可确认导入" : `预校验发现 ${(validation.errors || []).length} 个问题`, validation.importable ? "success" : "error");
  }

  async function confirmImportJob(id) {
    const validationJob = state.importValidation?.job || {};
    let job = findEntity("importJob", id);
    if (!job?.id && Number(validationJob.id) === Number(id)) {
      job = validationJob;
    }
    if (!id) return;
    if (!(await confirmDanger({
      title: "确认导入",
      target: entityDisplayName("importJob", job),
      impact: "确认后会写入业务数据，并刷新车辆、客户、订单或配件相关列表。"
    }))) return;
    const result = await api(endpoints.imports.confirm(id), { method: "POST" });
    state.importValidation = result;
    markReferenceDataStale();
    resetPage(state.pages.imports);
    await loadAllData({ force: true });
    renderCurrentTab();
    showToast("导入完成", "success");
  }

  function renderImportTypeFilterBar() {
    const filters = state.filters.imports || {};
    return `
      <div class="filter-row">
        ${filterButtonGroup("imports", "importType", filters.importType || "", "导入类型", "全部导入类型", importTypeOptions())}
      </div>
    `;
  }

  function renderImportWorkspace() {
    const selectedType = state.importSelectedType || state.filters.imports?.importType || "vehicle-workbook";
    const templateLabel = importTypeLabel(selectedType);
    const confirmable = Boolean(state.importValidation?.importable && state.importValidation?.job?.id);
    return `
      <div class="import-workspace">
        <div class="import-grid">
          <label class="field">
            <span>导入类型</span>
            <select data-import-type-select>
              ${importTypeOptions().map(option => `<option value="${escapeAttr(option.value)}"${String(option.value) === String(selectedType) ? " selected" : ""}>${escapeHtml(option.label)}</option>`).join("")}
            </select>
          </label>
          <label class="field">
            <span>Excel 文件</span>
            <input type="file" data-import-file accept=".xls,.xlsx,application/vnd.ms-excel,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet">
          </label>
        </div>
        <div class="toolbar-actions">
          <button class="btn btn-ghost" type="button" data-action="download-import-template" data-import-type="${escapeAttr(selectedType)}">${icon("download")}模板下载</button>
          <button class="btn btn-primary" type="button" data-action="validate-import-file">${icon("fileSearch")}预校验</button>
          ${confirmable ? `<button class="btn btn-primary" type="button" data-action="confirm-import-job" data-id="${escapeAttr(state.importValidation.job.id)}">${icon("fileCheck")}确认导入</button>` : ""}
          ${state.importValidation ? `<button class="btn btn-ghost" type="button" data-action="clear-import-validation">${icon("minus")}清除结果</button>` : ""}
        </div>
        <div class="helper-inline">当前模板：${escapeHtml(templateLabel)}</div>
      </div>
    `;
  }

  function renderImportValidationPanel() {
    const validation = state.importValidation;
    if (!validation) {
      return emptyState("请选择导入类型和 Excel 文件后点击预校验。");
    }
    const job = validation.job || {};
    const errors = validation.errors || [];
    const errorRows = errors.length ? renderTable([
      { label: "Sheet", key: "sheetName" },
      { label: "行号", key: "rowNumber" },
      { label: "字段", key: "fieldName" },
      { label: "错误提示", key: "message" },
      { label: "值", key: "value" }
    ], errors, listTableOptions("importJob", "imports", { selectable: false, batch: false, emptyState: false })) : emptyState("未发现校验错误");
    return `
      <div class="import-validation">
        <section class="summary-grid">
          ${summaryCard("状态", importStatusLabel(job.status), validation.importable ? "可以确认导入" : "请修正错误后重试")}
          ${summaryCard("总行数", display(job.totalRows), `${display(job.validRows)} 行通过校验`)}
          ${summaryCard("错误行", display(job.errorRows), `${display(job.importedRows)} 行已导入`)}
        </section>
        <div class="helper-inline">${escapeHtml(job.summary || "暂无摘要")}</div>
        <div class="form-section-title">错误行提示</div>
        ${errors.length ? errorRows : emptyState("本次预校验没有错误行。")}
      </div>
    `;
  }

  function renderImports() {
    if (!hasRole("SUPER_ADMIN")) {
      return `<div class="page">${renderSurface("导入中心", emptyState("当前账号无权访问导入中心"))}</div>`;
    }
    const filters = state.filters.imports || {};
    const rows = filterRows(state.data.importJobs || [], state.search.imports, [
      "importType", "templateName", "originalFileName", "status", "summary", "createdBy", "importedBy"
    ]);
    const readyCount = rows.filter(row => row.status === "READY").length;
    const failedCount = rows.filter(row => ["FAILED", "VALIDATION_FAILED"].includes(row.status)).length;
    const importedCount = rows.reduce((sum, row) => sum + Number(row.importedRows || 0), 0);

    return `
      <div class="page">
        <section class="summary-grid">
          ${summaryCard("导入记录", pageTotal("imports", rows.length), `${readyCount} 条待确认`)}
          ${summaryCard("已导入行数", importedCount, `${failedCount} 条失败记录`)}
          ${summaryCard("当前筛选", importTypeLabel(filters.importType || "全部"), "支持模板下载与预校验")}
        </section>
        ${renderToolbar("imports", "搜索导入类型、模板名、文件名、状态或摘要", null, "", null, {
          create: false,
          exportType: false,
          main: [renderImportTypeFilterBar()],
          actions: [`<button class="btn btn-ghost" type="button" data-action="download-import-template" data-import-type="${escapeAttr(state.importSelectedType || filters.importType || "vehicle-workbook")}">${icon("download")}模板下载</button>`]
        })}
        ${renderSurface("导入工作区", renderImportWorkspace())}
        ${renderSurface("预校验结果", renderImportValidationPanel())}
        ${renderSurface("导入记录", renderTable([
          { label: "导入类型", html: true, render: row => importTypeBadge(row.importType) },
          { label: "模板", key: "templateName" },
          { label: "文件名", key: "originalFileName" },
          { label: "状态", html: true, render: row => importStatusBadge(row.status) },
          { label: "行数", html: true, render: row => importCountSummary(row) },
          { label: "摘要", key: "summary" },
          { label: "提交人", key: "createdBy" },
          { label: "完成时间", key: "finishedAt", formatter: dateTime }
        ], rows, listTableOptions("importJob", "imports", {
          batch: false,
          emptyState: () => emptyState("暂无导入记录，先下载模板并上传文件。")
        })))}
        ${renderPagination("imports")}
      </div>
    `;
  }

  function importTypeOptions() {
    return [
      { value: "vehicle-workbook", label: "车辆工作簿" },
      { value: "parts-purchase", label: "配件采购" }
    ];
  }

  function importTypeLabel(value) {
    const normalized = String(value || "").toLowerCase();
    if (!normalized || normalized === "全部") return "全部";
    return importTypeOptions().find(option => option.value === normalized)?.label || value || "-";
  }

  function importTypeBadge(value) {
    const normalized = String(value || "").toLowerCase();
    const tone = normalized.includes("part") ? "teal" : "primary";
    return badge(importTypeLabel(value), tone);
  }

  function importStatusLabel(status) {
    const labels = {
      VALIDATING: "校验中",
      READY: "待确认",
      VALIDATION_FAILED: "校验失败",
      IMPORTING: "导入中",
      COMPLETED: "已完成",
      FAILED: "失败",
      DRAFT: "草稿"
    };
    return labels[status] || status || "-";
  }

  function importStatusBadge(status) {
    const tones = {
      READY: "warn",
      COMPLETED: "teal",
      IMPORTING: "primary",
      VALIDATION_FAILED: "danger",
      FAILED: "danger"
    };
    return badge(importStatusLabel(status), tones[status] || "primary");
  }

  function importCountSummary(row = {}) {
    return `
      <div class="cell-stack">
        <span>总行 ${escapeHtml(display(row.totalRows))}</span>
        <span class="helper-inline">有效 ${escapeHtml(display(row.validRows))} / 错误 ${escapeHtml(display(row.errorRows))}</span>
        <span class="helper-inline">导入 ${escapeHtml(display(row.importedRows))} / 跳过 ${escapeHtml(display(row.skippedRows))}</span>
      </div>
    `;
  }

  function importJobActions(row = {}) {
    if (row.status !== "READY") return "";
    return `
      <div class="action-row">
        <button class="btn btn-sm btn-primary" type="button" data-action="confirm-import-job" data-id="${escapeAttr(row.id)}">${icon("fileCheck")}确认导入</button>
      </div>
    `;
  }

  return {
    downloadImportTemplate,
    validateImportFromPage,
    confirmImportJob,
    renderImportTypeFilterBar,
    renderImportWorkspace,
    renderImportValidationPanel,
    renderImports,
    importTypeOptions,
    importTypeLabel,
    importTypeBadge,
    importStatusLabel,
    importStatusBadge,
    importCountSummary,
    importJobActions
  };
}
