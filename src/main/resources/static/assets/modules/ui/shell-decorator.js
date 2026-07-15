const TAB_ICON_NAMES = {
  overview: "dashboard",
  vehicles: "forklift",
  parts: "package",
  modifications: "tools",
  outboundOrders: "outbound",
  rentals: "rental",
  warehouses: "warehouse",
  repairs: "repair",
  customers: "customer",
  suppliers: "supplier",
  purchases: "purchase",
  stocktakes: "checklist",
  stats: "chart",
  logs: "audit",
  attachments: "paperclip",
  configs: "sliders",
  users: "users",
  maintenance: "database"
};

const KIND_ICON_NAMES = {
  vehicleOutbound: "outbound",
  rental: "rental",
  repair: "repair"
};

const ROLE_LABELS = {
  SUPER_ADMIN: "超级管理员",
  ADMIN: "管理员",
  USER: "业务用户"
};

const JOB_TAG_LABELS = {
  MANAGEMENT: "管理岗",
  SALES: "销售岗",
  REPAIR: "维修岗",
  WAREHOUSE: "仓管岗"
};

export function decorateShell({ els, icons, escapeHtml }) {
  if (!els?.mainNav || els.mainNav.dataset.decorated === "true") return;

  els.mainNav.querySelectorAll(".nav-item").forEach(button => {
    decorateButton(button, navIconName(button), "nav-icon", "nav-label", icons, escapeHtml);
  });

  els.newBusinessMenu?.querySelectorAll("button").forEach(button => {
    decorateButton(button, navIconName(button), "menu-icon", "menu-label", icons, escapeHtml);
  });

  decorateButton(els.switchUserBtn, "swap", "menu-icon", "menu-label", icons, escapeHtml);
  decorateButton(els.logoutBtn, "logout", "menu-icon", "menu-label", icons, escapeHtml);

  if (els.newBusinessBtn) {
    els.newBusinessBtn.innerHTML = `${shellIcon(icons.plus, "btn-icon")}<span>新建业务</span>`;
  }
  if (els.userMenuChevron) {
    els.userMenuChevron.innerHTML = icons.chevronDown || "";
  }

  els.mainNav.dataset.decorated = "true";
}

export function syncUserShell(els, user) {
  if (!els) return;
  const username = String(user?.username || "未登录");
  const roleLabels = (user?.roles || []).map(role => ROLE_LABELS[role] || role);
  const jobTag = JOB_TAG_LABELS[user?.jobTag] || "";
  const roleSummary = [...roleLabels, jobTag].filter(Boolean).join(" · ") || "账户与权限";

  if (els.currentUser) els.currentUser.textContent = username;
  if (els.currentUserRole) els.currentUserRole.textContent = roleSummary;
  if (els.userAvatar) {
    els.userAvatar.textContent = username.toLowerCase() === "admin"
      ? "管"
      : ([...username][0] || "用").toUpperCase();
  }
  if (els.userMenuBtn) {
    els.userMenuBtn.title = `${username} · ${roleSummary}`;
  }
}

function decorateButton(button, iconName, iconClass, labelClass, icons, escapeHtml) {
  if (!button || button.dataset.decorated === "true") return;
  const label = String(button.dataset.label || button.textContent || "").trim();
  const iconSvg = icons[iconName] || icons.dashboard || "";
  button.dataset.label = label;
  button.title ||= label;
  button.innerHTML = `${shellIcon(iconSvg, iconClass)}<span class="${labelClass}">${escapeHtml(label)}</span>`;
  button.dataset.decorated = "true";
}

function shellIcon(svg, className) {
  return `<span class="${className}" aria-hidden="true">${svg || ""}</span>`;
}

function navIconName(button) {
  if (button?.dataset?.tab) return TAB_ICON_NAMES[button.dataset.tab] || "dashboard";
  if (button?.dataset?.action === "quick-vehicle-inbound") return "inbound";
  if (button?.dataset?.action === "part-stock") {
    return button.dataset.direction === "outbound" ? "outbound" : "packageIn";
  }
  if (button?.dataset?.action === "stock-transfer") return "transfer";
  if (button?.dataset?.action === "create") return KIND_ICON_NAMES[button.dataset.kind] || "plus";
  return "dashboard";
}
