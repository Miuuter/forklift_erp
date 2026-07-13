import { defineDrawerAction } from "./contracts.js";

export function createActionRegistry({
  escapeAttr,
  escapeHtml,
  icon,
  canWriteEntity,
  hasPermission
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
    label: "入库",
    icon: "plus",
    action: kind === "vehicle" ? "vehicle-stock" : "part-stock",
    visible: ["vehicle", "part"].includes(kind) && hasPermission("stock:adjust"),
    data: {
      direction: "inbound",
      id: kind === "vehicle" ? row?.id : undefined,
      partCode: kind === "part" ? row?.partCode : undefined
    }
  }));

  register("stockOut", ({ kind, row }) => defineDrawerAction({
    id: "stockOut",
    label: "出库",
    icon: "minus",
    action: "part-stock",
    visible: kind === "part" && hasPermission("stock:adjust"),
    data: { direction: "outbound", partCode: row?.partCode }
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
