import { defineDrawerAction } from "./contracts.js";

export function createActionRegistry({
  escapeAttr,
  escapeHtml,
  icon,
  canWriteEntity,
  hasPermission,
  hasAnyRole
}) {
  const definitions = new Map();

  register("detail", ({ row }) => defineDrawerAction({
    id: "detail",
    label: "详情",
    icon: "eye",
    action: "detail-vehicle",
    visible: Boolean(row?.id),
    data: { id: row?.id }
  }));

  register("stockIn", ({ kind, row }) => defineDrawerAction({
    id: "stockIn",
    label: "入库调整",
    icon: "plus",
    action: kind === "vehicle" ? "vehicle-stock" : "part-stock",
    visible: ["vehicle", "part"].includes(kind) && !Boolean(row?.isLocked) && hasPermission("stock:adjust"),
    data: {
      direction: "inbound",
      id: kind === "vehicle" ? row?.id : undefined,
      partCode: kind === "part" ? row?.partCode : undefined
    }
  }));

  register("stockOut", ({ kind, row }) => defineDrawerAction({
    id: "stockOut",
    label: "销售出库",
    icon: "minus",
    action: "part-stock",
    visible: kind === "part" && !Boolean(row?.isLocked) && hasPermission("stock:adjust"),
    data: { direction: "outbound", partCode: row?.partCode }
  }));

  register("stockAdjustOut", ({ kind, row }) => defineDrawerAction({
    id: "stockAdjustOut",
    label: "库存减少",
    icon: "minus",
    action: kind === "vehicle" ? "vehicle-stock" : "part-stock",
    visible: ["vehicle", "part"].includes(kind)
      && hasPermission("stock:adjust")
      && hasAnyRole("ADMIN", "SUPER_ADMIN")
      && !Boolean(row?.isLocked)
      && Number(kind === "vehicle" ? row?.inventoryCount : row?.quantity) > 0,
    data: {
      direction: "adjustOutbound",
      id: kind === "vehicle" ? row?.id : undefined,
      partCode: kind === "part" ? row?.partCode : undefined
    }
  }));

  register("valueRemovedPart", ({ kind, row }) => defineDrawerAction({
    id: "valueRemovedPart",
    label: "确认旧件估值",
    icon: "money",
    action: "value-removed-part",
    visible: kind === "part"
      && row?.source === "REMOVED"
      && Boolean(row?.isLocked)
      && hasAnyRole("ADMIN", "SUPER_ADMIN"),
    data: { id: row?.id }
  }));

  register("edit", ({ kind, row }) => defineDrawerAction({
    id: "edit",
    label: "编辑",
    icon: "edit",
    action: "edit",
    visible: Boolean(row?.id) && canWriteEntity(kind),
    data: { kind, id: row?.id }
  }));

  register("delete", ({ kind, row }) => defineDrawerAction({
    id: "delete",
    label: "删除",
    icon: "trash",
    tone: "danger",
    action: "delete",
    visible: Boolean(row?.id) && canWriteEntity(kind),
    data: { kind, id: row?.id }
  }));

  function register(id, resolver) {
    definitions.set(id, resolver);
  }

  function resolve(kind, row, ids = []) {
    return ids
      .map(id => definitions.get(id)?.({ kind, row }))
      .filter(action => action?.visible !== false);
  }

  function render(kind, row, ids = []) {
    const actions = resolve(kind, row, ids);
    if (!actions.length) return "";
    return `
      <div class="action-row">
        ${actions.map(renderButton).join("")}
      </div>
    `;
  }

  function renderButton(action) {
    const data = Object.entries(action.data || {})
      .filter(([, value]) => value !== undefined && value !== null && value !== "")
      .map(([key, value]) => `data-${toDataAttrName(key)}="${escapeAttr(value)}"`)
      .join(" ");
    return `<button class="btn btn-sm${action.tone === "danger" ? " btn-danger" : ""}" type="button" data-action="${escapeAttr(action.action)}" ${data}${action.disabled ? " disabled" : ""}>${icon(action.icon || "swap")}${escapeHtml(action.label)}</button>`;
  }

  function toDataAttrName(name) {
    return String(name || "").replace(/[A-Z]/g, letter => `-${letter.toLowerCase()}`);
  }

  return {
    register,
    resolve,
    render
  };
}
