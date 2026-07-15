const SUMMARY_ICON_RULES = [
  { pattern: /逾期|异常|风险|预警/, icon: "warning", tone: "danger" },
  { pattern: /待处理|待报|待申请|待上传|待收|到期/, icon: "clock", tone: "warn" },
  { pattern: /成本|支出|费用/, icon: "money", tone: "warn" },
  { pattern: /收入|收款|应收|款项|车款|结清|金额|现金流|毛利/, icon: "money", tone: "teal" },
  { pattern: /整车|车辆|车型/, icon: "forklift", tone: "primary" },
  { pattern: /配件|库存/, icon: "package", tone: "primary" },
  { pattern: /租赁/, icon: "rental", tone: "teal" },
  { pattern: /维修|服务/, icon: "repair", tone: "warn" },
  { pattern: /订单|出库|销售/, icon: "receipt", tone: "primary" },
  { pattern: /附件|发票|合同|文件/, icon: "paperclip", tone: "teal" },
  { pattern: /导入/, icon: "fileUp", tone: "primary" },
  { pattern: /数据库|备份|恢复/, icon: "database", tone: "primary" },
  { pattern: /客户/, icon: "customer", tone: "primary" },
  { pattern: /供应商|采购/, icon: "supplier", tone: "warn" }
];

export function createSummaryCardRenderer({ icons, escapeAttr, escapeHtml }) {
  return function summaryCard(label, value, foot, options = {}) {
    const iconMeta = summaryIconMeta(label, options);
    const dataAttrs = Object.entries(options.data || {})
      .map(([key, itemValue]) => `data-${toDataAttrName(key)}="${escapeAttr(itemValue)}"`)
      .join(" ");
    const attrs = options.action
      ? `type="button" class="summary-card summary-card-button tone-${escapeAttr(iconMeta.tone)}${options.active ? " is-active" : ""}" data-action="${escapeAttr(options.action)}" ${dataAttrs}`
      : `class="summary-card tone-${escapeAttr(iconMeta.tone)}${options.active ? " is-active" : ""}"`;
    const tag = options.action ? "button" : "article";
    const safeValue = escapeHtml(value);
    const safeFoot = escapeHtml(foot);
    return `
      <${tag} ${attrs}>
        <div class="summary-card-head">
          <div class="summary-label">${escapeHtml(label)}</div>
          <span class="summary-card-icon" aria-hidden="true">${icons[iconMeta.icon] || icons.dashboard || ""}</span>
        </div>
        <div class="summary-value" title="${escapeAttr(value)}">${safeValue}</div>
        <div class="summary-foot" title="${escapeAttr(foot)}">${safeFoot}</div>
      </${tag}>
    `;
  };
}

function summaryIconMeta(label, options = {}) {
  if (options.icon) {
    return { icon: options.icon, tone: options.tone || "primary" };
  }
  const normalized = String(label || "");
  const matched = SUMMARY_ICON_RULES.find(rule => rule.pattern.test(normalized));
  return matched || { icon: "dashboard", tone: options.tone || "primary" };
}

function toDataAttrName(value) {
  return String(value || "").replace(/[A-Z]/g, match => `-${match.toLowerCase()}`);
}
