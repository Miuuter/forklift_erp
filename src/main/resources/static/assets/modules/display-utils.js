export function normalizeText(value) {
  return String(value || "").trim().toLowerCase();
}

export function filterRows(rows, query, keys) {
  const keyword = String(query || "").trim().toLowerCase();
  if (!keyword) return rows;
  return rows.filter(row => keys.some(key => String(row[key] ?? "").toLowerCase().includes(keyword)));
}

export function sortById(rows, desc = true) {
  return [...(rows || [])].sort((a, b) => desc ? Number(b.id || 0) - Number(a.id || 0) : Number(a.sortOrder || a.id || 0) - Number(b.sortOrder || b.id || 0));
}

export function sortLogs(rows) {
  return [...(rows || [])].sort((a, b) => new Date(b.createdAt || 0) - new Date(a.createdAt || 0));
}

export function money(value) {
  if (value === null || value === undefined || value === "") return "";
  const numeric = Number(value);
  if (Number.isNaN(numeric)) return value;
  return new Intl.NumberFormat("zh-CN", {
    style: "currency",
    currency: "CNY",
    maximumFractionDigits: 2
  }).format(numeric);
}

export function dateValue(value) {
  if (!value) return "";
  return String(value).slice(0, 10);
}

export function dateTime(value) {
  if (!value) return "";
  return String(value).replace("T", " ").slice(0, 16);
}

export function fileSize(value) {
  const size = Number(value || 0);
  if (!Number.isFinite(size) || size <= 0) return "-";
  if (size < 1024) return `${size} B`;
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`;
  return `${(size / 1024 / 1024).toFixed(1)} MB`;
}

export function toInputValue(value, field) {
  if (value === null || value === undefined) return "";
  if (field.type === "datetime-local") return String(value).slice(0, 16);
  if (field.type === "date") return String(value).slice(0, 10);
  return value;
}

export function todayInputDate() {
  const date = new Date();
  const offset = date.getTimezoneOffset();
  return new Date(date.getTime() - offset * 60000).toISOString().slice(0, 10);
}

export function nowInputDateTime() {
  const date = new Date();
  const offset = date.getTimezoneOffset();
  return new Date(date.getTime() - offset * 60000).toISOString().slice(0, 16);
}

export function display(value) {
  if (value === null || value === undefined || value === "") return "-";
  return String(value);
}

export function emptyState(message) {
  return `
    <div class="empty-state">
      <span class="empty-state-visual" aria-hidden="true">
        <svg viewBox="0 0 64 64" fill="none" focusable="false">
          <path d="M15 23.5 22 13h20l7 10.5V47a4 4 0 0 1-4 4H19a4 4 0 0 1-4-4V23.5Z" stroke="currentColor" stroke-width="3" stroke-linejoin="round"/>
          <path d="M15 29h11l3 5h6l3-5h11" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"/>
          <path d="M25 20h14M24 43h16" stroke="currentColor" stroke-width="3" stroke-linecap="round" opacity=".62"/>
          <circle cx="46" cy="17" r="6" fill="currentColor" opacity=".12"/>
          <path d="M46 13.5v7M42.5 17h7" stroke="currentColor" stroke-width="2.4" stroke-linecap="round"/>
        </svg>
      </span>
      <strong>暂无内容</strong>
      <span>${escapeHtml(message)}</span>
    </div>
  `;
}

export function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

export function escapeAttr(value) {
  return escapeHtml(value);
}
