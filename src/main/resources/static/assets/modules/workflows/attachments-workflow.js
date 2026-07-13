export function createAttachmentWorkflow(deps) {
  const {
    state,
    api,
    endpoints,
    resetPage,
    loadPagedTab,
    renderCurrentTab,
    fetchProtectedBlob,
    downloadProtectedFile,
    showToast,
    showContextSuccess,
    confirmDanger,
    entityDisplayName,
    findEntity,
    filterRows,
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
    fileSize,
    dateTime,
    display,
    icon,
    resourceTypeLabel,
    vehicleNumberLabel,
    hasPermission
  } = deps;

  async function uploadOrderManagedAttachment(order, file, category, label) {
    const formData = new FormData();
    formData.append("resourceType", "OUTBOUND_ORDER");
    formData.append("resourceId", order.id);
    formData.append("category", category);
    formData.append("attachmentLabel", label);
    formData.append("uploadNote", `${label}上传入口`);
    formData.append("files", file);
    await api(endpoints.attachments.upload, {
      method: "POST",
      body: formData
    });
  }

  async function uploadAttachmentsFromModal(form) {
    const resourceType = String(form.elements.resourceType?.value || "").trim();
    const resourceId = String(form.elements.resourceId?.value || "").trim();
    const category = String(form.elements.attachmentCategory?.value || "OTHER").trim();
    const files = Array.from(form.elements.files?.files || []);
    if (!resourceType) throw new Error("请选择业务对象类型");
    if (!resourceId) throw new Error("请选择业务对象");
    if (!files.length) throw new Error("请选择附件文件");

    const formData = new FormData();
    formData.append("resourceType", resourceType);
    formData.append("resourceId", resourceId);
    formData.append("category", category);
    formData.append("attachmentLabel", String(form.elements.attachmentLabel?.value || "").trim());
    formData.append("uploadNote", String(form.elements.uploadNote?.value || "").trim());
    files.forEach(file => formData.append("files", file));

    const created = await api(endpoints.attachments.upload, {
      method: "POST",
      body: formData
    });
    state.filters.attachments = {
      ...(state.filters.attachments || {}),
      resourceType,
      resourceId,
      category
    };
    if (state.pages.attachments) resetPage(state.pages.attachments);
    showContextSuccess("附件已上传", attachmentResourceTypeLabel(resourceType), `${created?.length || files.length} 个文件`);
  }

  async function previewAttachment(row = {}) {
    if (!row?.id || row.deleted) {
      showToast("附件不可预览", "error");
      return;
    }
    if (!row.previewable) {
      await downloadAttachment(row);
      return;
    }
    const { blob, filename } = await fetchProtectedBlob(endpoints.attachments.preview(row.id), row.originalName || "attachment");
    const objectUrl = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = objectUrl;
    link.target = "_blank";
    link.rel = "noopener";
    link.download = filename;
    document.body.appendChild(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(objectUrl), 60000);
  }

  async function downloadAttachment(row = {}) {
    if (!row?.id || row.deleted) {
      showToast("附件不可下载", "error");
      return;
    }
    await downloadProtectedFile(endpoints.attachments.download(row.id), row.originalName || "attachment");
  }

  async function deleteAttachment(row = {}) {
    if (!row?.id || row.deleted) return;
    if (!canManageAttachmentType(row.resourceType)) {
      showToast("当前账号无权删除该附件", "error");
      return;
    }
    if (!(await confirmDanger({
      title: "删除附件",
      target: entityDisplayName("attachment", row),
      impact: "删除后文件会被移除，系统会保留删除人、时间和原因审计。"
    }))) return;
    const reason = window.prompt("请输入删除原因（可选）", "");
    if (reason === null) return;
    const params = new URLSearchParams();
    if (String(reason || "").trim()) params.set("reason", String(reason).trim());
    await api(`${endpoints.attachments.delete(row.id)}${params.toString() ? "?" + params.toString() : ""}`, { method: "DELETE" });
    showToast("附件已删除", "success");
    await loadPagedTab("attachments", { force: true });
    renderCurrentTab();
  }

  function renderAttachments() {
    const filters = state.filters.attachments || {};
    const rows = filterRows(state.data.attachments || [], state.search.attachments, [
      "resourceType", "resourceCode", "resourceName", "attachmentCategory", "attachmentLabel", "originalName",
      "uploadNote", "uploadedBy", "deletedBy", "deleteReason"
    ]);
    const activeScope = attachmentScopeLabel(filters);
    const deletedCount = rows.filter(row => Boolean(row.deleted)).length;
    const previewableCount = rows.filter(row => row.previewable && !row.deleted).length;
    const readyCount = rows.filter(row => !row.deleted).length;

    return `
      <div class="page">
        <section class="summary-grid">
          ${summaryCard("附件总数", pageTotal("attachments", rows.length), `${previewableCount} 个可预览`)}
          ${summaryCard("有效附件", readyCount, activeScope || "全部业务对象")}
          ${summaryCard("已删除", deletedCount, "删除审计已保留")}
        </section>
        ${renderToolbar("attachments", "搜索业务对象、文件名、标题、备注或上传人", "attachmentUpload", "上传附件", null, {
          create: canUploadAttachment(),
          exportType: false,
          main: [renderAttachmentFilterBar()]
        })}
        ${activeScope ? `<div class="helper-inline">当前范围：${escapeHtml(activeScope)}</div>` : ""}
        ${renderSurface("附件列表", renderTable([
          { label: "业务对象", html: true, render: row => attachmentResourceSummary(row) },
          { label: "类型", html: true, render: row => attachmentCategoryBadge(row) },
          { label: "文件", html: true, render: row => attachmentFileSummary(row) },
          { label: "上传信息", html: true, render: row => attachmentUploadSummary(row) },
          { label: "状态", html: true, render: row => attachmentStatusSummary(row) },
          { label: "备注", key: "uploadNote" }
        ], rows, listTableOptions("attachment", "attachments", {
          batch: false,
          emptyState: () => attachmentEmptyState()
        })))}
        ${renderPagination("attachments")}
      </div>
    `;
  }

  function renderAttachmentFilterBar() {
    const filters = state.filters.attachments || {};
    return `
      <div class="filter-row">
        ${filterButtonGroup("attachments", "resourceType", filters.resourceType || "", "业务对象", "全部对象", attachmentAllResourceTypeOptions())}
        ${filterButtonGroup("attachments", "category", filters.category || "", "附件类型", "全部类型", attachmentCategoryOptions())}
        ${filterButtonGroup("attachments", "includeDeleted", filters.includeDeleted || "", "删除状态", "仅有效", [{ value: "true", label: "含已删除" }])}
      </div>
    `;
  }

  function attachmentAllResourceTypeOptions() {
    return [
      { value: "MACHINE", label: "车辆" },
      { value: "REPAIR", label: "维修" },
      { value: "OUTBOUND_ORDER", label: "订单" },
      { value: "PART", label: "配件" },
      { value: "CUSTOMER", label: "客户" }
    ];
  }

  function attachmentResourceTypeOptions() {
    return attachmentAllResourceTypeOptions().filter(option => canManageAttachmentType(option.value));
  }

  function attachmentCategoryOptions() {
    return [
      { value: "PHOTO", label: "照片/图片" },
      { value: "INVOICE", label: "发票" },
      { value: "CONTRACT", label: "合同" },
      { value: "OTHER", label: "其他附件" }
    ];
  }

  function attachmentUploadCategoryOptions() {
    return [
      { value: "PHOTO", label: "照片/图片" },
      { value: "OTHER", label: "其他附件" }
    ];
  }

  function attachmentResourceOptions() {
    const type = String(state.modal?.item?.resourceType || "MACHINE").toUpperCase();
    const rows = {
      MACHINE: (state.data.vehicles || []).filter(item => !item.modelOnly),
      REPAIR: state.data.repairs || [],
      OUTBOUND_ORDER: state.data.outboundOrders || [],
      PART: state.data.parts || [],
      CUSTOMER: state.data.customers || []
    }[type] || [];
    return rows.map(item => ({
      value: item.id,
      label: attachmentResourceOptionLabel(type, item)
    }));
  }

  function attachmentResourceOptionLabel(type, item = {}) {
    if (type === "MACHINE") return vehicleNumberLabel(item);
    if (type === "REPAIR") return `${item.vehicleNumber || item.id || "-"} · ${item.customerName || item.faultDescription || "-"}`;
    if (type === "OUTBOUND_ORDER") return `${item.orderNo || item.id || "-"} · ${item.resourceCode || item.resourceName || item.customerName || "-"}`;
    if (type === "PART") return `${item.partCode || item.id || "-"} · ${item.partName || "-"}`;
    if (type === "CUSTOMER") return `${item.companyName || item.id || "-"} · ${item.contactName || "-"}`;
    return `${item.id || "-"} · ${item.name || item.resourceName || "-"}`;
  }

  function attachmentDefaultCategoryForResourceType(resourceType) {
    const type = String(resourceType || "").toUpperCase();
    if (type === "OUTBOUND_ORDER") return "OTHER";
    return "PHOTO";
  }

  function attachmentResourceTypeLabel(value) {
    return attachmentAllResourceTypeOptions().find(option => option.value === String(value || "").toUpperCase())?.label
      || resourceTypeLabel(value)
      || value
      || "-";
  }

  function attachmentCategoryLabel(value) {
    return attachmentCategoryOptions().find(option => option.value === String(value || "").toUpperCase())?.label
      || value
      || "-";
  }

  function attachmentCategoryBadge(row = {}) {
    const type = String(row.attachmentCategory || "").toUpperCase();
    const tone = type === "INVOICE" ? "warn" : type === "CONTRACT" ? "teal" : type === "PHOTO" ? "primary" : "primary";
    return badge(attachmentCategoryLabel(type), tone);
  }

  function attachmentScopeLabel(filters = {}) {
    const parts = [];
    if (filters.resourceType) {
      parts.push(attachmentResourceTypeLabel(filters.resourceType));
    }
    if (filters.resourceId) {
      parts.push(attachmentResourceNameById(filters.resourceType, filters.resourceId));
    }
    if (filters.category) {
      parts.push(attachmentCategoryLabel(filters.category));
    }
    if (filters.includeDeleted === "true") {
      parts.push("含已删除");
    }
    return parts.filter(Boolean).join(" / ");
  }

  function attachmentResourceNameById(resourceType, resourceId) {
    if (!resourceId) return "";
    const type = String(resourceType || "").toUpperCase();
    const row = {
      MACHINE: state.data.vehicles.find(item => Number(item.id) === Number(resourceId)),
      REPAIR: state.data.repairs.find(item => Number(item.id) === Number(resourceId)),
      OUTBOUND_ORDER: state.data.outboundOrders.find(item => Number(item.id) === Number(resourceId)),
      PART: state.data.parts.find(item => Number(item.id) === Number(resourceId)),
      CUSTOMER: state.data.customers.find(item => Number(item.id) === Number(resourceId))
    }[type];
    return row ? attachmentResourceOptionLabel(type, row) : `ID ${resourceId}`;
  }

  function attachmentResourceSummary(row = {}) {
    return `
      <div class="cell-stack">
        <strong>${escapeHtml(attachmentResourceTypeLabel(row.resourceType))}</strong>
        <span class="helper-inline">${escapeHtml(row.resourceCode || `ID ${row.resourceId || "-"}`)}</span>
        <span class="helper-inline">${escapeHtml(row.resourceName || "-")}</span>
      </div>
    `;
  }

  function attachmentFileSummary(row = {}) {
    return `
      <div class="cell-stack">
        <strong>${escapeHtml(row.originalName || row.attachmentLabel || "-")}</strong>
        <span class="helper-inline">${escapeHtml(fileSize(row.fileSize))}${row.contentType ? ` · ${escapeHtml(row.contentType)}` : ""}</span>
        ${row.previewable ? `<span class="helper-inline">可预览</span>` : ""}
      </div>
    `;
  }

  function attachmentUploadSummary(row = {}) {
    return `
      <div class="cell-stack">
        <span>${escapeHtml(row.uploadedBy || "-")}</span>
        <span class="helper-inline">${escapeHtml(dateTime(row.uploadedAt) || "-")}</span>
      </div>
    `;
  }

  function attachmentStatusSummary(row = {}) {
    if (!row.deleted) return badge("有效", "teal");
    return `
      <div class="cell-stack">
        ${badge("已删除", "danger")}
        <span class="helper-inline">${escapeHtml(row.deletedBy || "-")} · ${escapeHtml(dateTime(row.deletedAt) || "-")}</span>
        ${row.deleteReason ? `<span class="helper-inline">${escapeHtml(row.deleteReason)}</span>` : ""}
      </div>
    `;
  }

  function attachmentActions(row = {}) {
    if (row.deleted) return "";
    return `
      <div class="action-row">
        ${row.previewable ? `<button class="btn btn-sm" type="button" data-action="preview-attachment" data-id="${escapeAttr(row.id)}">${icon("eye")}预览</button>` : ""}
        <button class="btn btn-sm" type="button" data-action="download-attachment" data-id="${escapeAttr(row.id)}">${icon("download")}下载</button>
        ${canManageAttachmentType(row.resourceType) ? `<button class="btn btn-sm btn-danger" type="button" data-action="delete-attachment" data-id="${escapeAttr(row.id)}">${icon("trash")}删除</button>` : ""}
      </div>
    `;
  }

  function attachmentEmptyState() {
    return `
      <div class="empty-state empty-state-actionable">
        <div class="empty-state-main">
          <strong>暂无附件</strong>
          ${canUploadAttachment() ? `<button class="btn btn-primary" type="button" data-action="create" data-kind="attachmentUpload">${icon("paperclip")}上传附件</button>` : ""}
        </div>
        <span>可将车辆照片、维修照片和其他业务文件按对象统一挂载；订单发票/合同请从订单入口上传。</span>
      </div>
    `;
  }

  function canUploadAttachment() {
    return attachmentResourceTypeOptions().length > 0;
  }

  function canManageAttachmentType(resourceType) {
    if (hasPermission("stock:adjust")) return true;
    const permissions = {
      MACHINE: "vehicle:write",
      REPAIR: "repair:write",
      OUTBOUND_ORDER: "stock:adjust",
      PART: "part:write",
      CUSTOMER: "vehicle:write"
    };
    const permission = permissions[String(resourceType || "").toUpperCase()];
    return permission ? hasPermission(permission) : false;
  }

  function attachmentResourceTypeForKind(kind) {
    return {
      vehicle: "MACHINE",
      repair: "REPAIR",
      outboundOrder: "OUTBOUND_ORDER",
      part: "PART",
      customer: "CUSTOMER"
    }[kind] || "";
  }

  return {
    uploadOrderManagedAttachment,
    uploadAttachmentsFromModal,
    previewAttachment,
    downloadAttachment,
    deleteAttachment,
    renderAttachments,
    renderAttachmentFilterBar,
    attachmentAllResourceTypeOptions,
    attachmentResourceTypeOptions,
    attachmentCategoryOptions,
    attachmentUploadCategoryOptions,
    attachmentResourceOptions,
    attachmentResourceOptionLabel,
    attachmentDefaultCategoryForResourceType,
    attachmentResourceTypeLabel,
    attachmentCategoryLabel,
    attachmentCategoryBadge,
    attachmentScopeLabel,
    attachmentResourceNameById,
    attachmentResourceSummary,
    attachmentFileSummary,
    attachmentUploadSummary,
    attachmentStatusSummary,
    attachmentActions,
    attachmentEmptyState,
    canUploadAttachment,
    canManageAttachmentType,
    attachmentResourceTypeForKind
  };
}
