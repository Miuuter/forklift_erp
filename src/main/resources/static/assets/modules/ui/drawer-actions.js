export function createDrawerActions({
  hasPermission,
  canUpdateUserJobTag,
  canUpdateUserEnabled,
  rowActions,
  modificationOrderActions,
  outboundOrderActions,
  yesNoFromStatusText,
  stocktakingActions,
  attachmentActions,
  importJobActions,
  userActions,
  escapeAttr,
  escapeHtml,
  icon
}) {
  function render(kind, item) {
    const actions = {
      part: rowActions("part", item, ["stockIn", "stockOut", "edit", "delete"]),
      modificationOrder: modificationOrderActions(item),
      outboundOrder: orderActions(item),
      rental: rowActions("rental", item, ["edit", "delete"]),
      customer: `${customerActions(item)}${rowActions("customer", item, ["edit", "delete"])}`,
      supplier: rowActions("supplier", item, ["edit", "delete"]),
      purchaseOrder: purchaseActions(item),
      stocktaking: stocktakingActions(item),
      repair: repairActions(item),
      attachment: attachmentActions(item),
      importJob: importJobActions(item),
      user: accountActions(item)
    }[kind] || "";
    return actions ? `<div class="detail-drawer-actions">${actions}</div>` : "";
  }

  function orderActions(item = {}) {
    if (!hasPermission("stock:adjust")) return outboundOrderActions(item);
    return `
      <div class="drawer-status-actions">
        ${orderStatusButton(item, "paymentSettled", Boolean(item.paymentSettled), "车款已结清", "标记车款结清")}
        ${orderStatusButton(item, "salesReported", Boolean(item.salesReported), "已报销售", "标记已报销售")}
        ${orderStatusButton(item, "invoiceApplied", Boolean(item.invoiceApplied), "已申请发票", "标记申请发票")}
        ${orderStatusButton(item, "registrationStatus", yesNoFromStatusText(item.registrationStatus), "已上牌", "标记已上牌")}
        ${orderStatusButton(item, "contractType", yesNoFromStatusText(item.contractType), "有合同", "标记有合同")}
      </div>
      ${outboundOrderActions(item)}
    `;
  }

  function orderStatusButton(item, field, active, activeLabel, inactiveLabel) {
    return `<button class="btn btn-sm${active ? " btn-ghost" : " btn-primary"}" type="button" data-action="toggle-order-status" data-id="${escapeAttr(item.id)}" data-field="${escapeAttr(field)}">${icon(active ? "refresh" : "swap")}${escapeHtml(active ? `撤销${activeLabel}` : inactiveLabel)}</button>`;
  }

  function purchaseActions(item = {}) {
    if (!hasPermission("stock:adjust")) return "";
    const received = item.status === "RECEIVED";
    const receivable = ["ORDERED", "PARTIAL", "ARRIVED"].includes(item.status);
    return `
      <div class="action-row">
        ${received || receivable ? `<button class="btn btn-sm${received ? " btn-ghost" : " btn-primary"}" type="button" data-action="toggle-purchase-received" data-id="${escapeAttr(item.id)}">${icon(received ? "refresh" : "download")}${received ? "撤销收货" : "标记收货"}</button>` : ""}
        ${received ? `<button class="btn btn-sm" type="button" data-action="purchase-freight" data-id="${escapeAttr(item.id)}">${icon("edit")}修改运费</button>` : ""}
        ${rowActions("purchaseOrder", item, ["edit", "delete"])}
      </div>
    `;
  }

  function repairActions(item = {}) {
    return `
      <div class="action-row">
        ${hasPermission("repair:write") ? `<button class="btn btn-sm${item.status === "COMPLETED" ? " btn-ghost" : " btn-primary"}" type="button" data-action="toggle-repair-status" data-id="${escapeAttr(item.id)}">${icon("swap")}${item.status === "COMPLETED" ? "恢复待处理" : "标记完成"}</button>` : ""}
        ${rowActions("repair", item, ["edit", "delete"])}
      </div>
    `;
  }

  function accountActions(item = {}) {
    const statusActions = [
      canUpdateUserJobTag(item) ? `<button class="btn btn-sm" type="button" data-action="toggle-user-job-tag" data-id="${escapeAttr(item.id)}">${icon("swap")}切换职务</button>` : "",
      canUpdateUserEnabled(item) ? `<button class="btn btn-sm${item.enabled ? " btn-danger" : " btn-primary"}" type="button" data-action="toggle-user-enabled" data-id="${escapeAttr(item.id)}">${icon(item.enabled ? "lock" : "unlock")}${item.enabled ? "停用用户" : "启用用户"}</button>` : ""
    ].filter(Boolean).join("");
    return `${statusActions ? `<div class="action-row">${statusActions}</div>` : ""}${userActions(item)}`;
  }

  function customerActions(customer = {}) {
    if (!customer?.id) return "";
    const actions = [
      hasPermission("stock:adjust") ? `<button class="btn btn-sm" type="button" data-action="create" data-kind="vehicleOutbound" data-customer-id="${escapeAttr(customer.id)}">${icon("minus")}登记销售</button>` : "",
      hasPermission("stock:adjust") ? `<button class="btn btn-sm" type="button" data-action="create" data-kind="rental" data-customer-id="${escapeAttr(customer.id)}">${icon("plus")}租赁登记</button>` : "",
      hasPermission("repair:write") ? `<button class="btn btn-sm" type="button" data-action="create" data-kind="repair" data-customer-id="${escapeAttr(customer.id)}">${icon("plus")}维修登记</button>` : ""
    ].filter(Boolean);
    return actions.length ? `<div class="action-row">${actions.join("")}</div>` : "";
  }

  return { render };
}
