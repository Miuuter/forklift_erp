import { createRequestId } from "./request-id.js";

export function createDownloadActions({
  getToken,
  endpoints,
  todayInputDate,
  showToast
}) {
  async function fetchProtectedBlob(url, fallbackName) {
    return requestBlob(url, fallbackName, "\u6587\u4ef6\u8bfb\u53d6\u5931\u8d25\uff1a", true);
  }

  async function downloadInvoice(order) {
    if (!order?.id || !order.invoiceFileAvailable) {
      showToast("\u8be5\u8ba2\u5355\u8fd8\u6ca1\u6709\u53d1\u7968\u6587\u4ef6", "error");
      return;
    }
    const { blob, filename } = await requestBlob(
      endpoints.outboundOrder.downloadInvoice(order.id),
      order.invoiceOriginalName || `${order.orderNo || "invoice"}-\u53d1\u7968`,
      "\u4e0b\u8f7d\u5931\u8d25\uff1a",
      false
    );
    saveBlob(blob, filename);
  }

  async function downloadContract(order) {
    if (!order?.id || !order.contractFileAvailable) {
      showToast("\u8be5\u8ba2\u5355\u8fd8\u6ca1\u6709\u5408\u540c\u6587\u4ef6", "error");
      return;
    }
    const { blob, filename } = await requestBlob(
      endpoints.outboundOrder.downloadContract(order.id),
      order.contractOriginalName || `${order.orderNo || "contract"}-\u5408\u540c`,
      "\u4e0b\u8f7d\u5931\u8d25\uff1a",
      false
    );
    saveBlob(blob, filename);
  }

  async function downloadExcel(type) {
    const url = endpoints.export?.[type];
    if (!url) {
      showToast("\u6682\u4e0d\u652f\u6301\u8be5\u5217\u8868\u5bfc\u51fa", "error");
      return;
    }
    await downloadProtectedFile(url, `${exportFileLabel(type)}-${todayInputDate()}.xlsx`);
    showToast("\u5bfc\u51fa\u5df2\u5f00\u59cb", "success");
  }

  async function downloadProtectedFile(url, fallbackName) {
    const { blob, filename } = await requestBlob(url, fallbackName, "\u4e0b\u8f7d\u5931\u8d25\uff1a", true);
    saveBlob(blob, filename);
  }

  async function downloadDataBackup() {
    await downloadProtectedFile(endpoints.admin.backup, `forklift-erp-backup-${todayInputDate()}.json`);
  }

  async function requestBlob(url, fallbackName, fallbackPrefix, markAuthExpired) {
    const requestId = createRequestId("download");
    const response = await fetch(url, {
      headers: {
        ...authHeaders(getToken?.()),
        "X-Request-ID": requestId
      }
    });
    if (response.status === 401 && markAuthExpired) {
      const error = new Error("\u672a\u767b\u5f55\u6216\u767b\u5f55\u5df2\u8fc7\u671f");
      error.authExpired = true;
      error.requestId = response.headers.get("X-Request-ID") || requestId;
      throw error;
    }
    if (!response.ok) {
      const payload = await response.json().catch(() => null);
      const error = new Error(payload?.message || `${fallbackPrefix}${response.status}`);
      error.requestId = payload?.requestId || response.headers.get("X-Request-ID") || requestId;
      throw error;
    }
    const blob = await response.blob();
    const filename = filenameFromDisposition(response.headers.get("Content-Disposition")) || fallbackName;
    return { blob, filename };
  }

  return {
    fetchProtectedBlob,
    downloadInvoice,
    downloadContract,
    downloadExcel,
    downloadProtectedFile,
    downloadDataBackup
  };
}

function authHeaders(token) {
  return token ? { Authorization: `Bearer ${token}` } : {};
}

function saveBlob(blob, filename) {
  const objectUrl = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = objectUrl;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(objectUrl), 1000);
}

function exportFileLabel(type) {
  return {
    vehicles: "\u8f66\u8f86\u5e93\u5b58",
    parts: "\u914d\u4ef6\u5e93\u5b58",
    customers: "\u5ba2\u6237\u6863\u6848",
    outboundOrders: "\u51fa\u5e93\u8ba2\u5355",
    rentals: "\u79df\u8d41\u8bb0\u5f55",
    repairs: "\u7ef4\u4fee\u8bb0\u5f55"
  }[type] || "\u5bfc\u51fa\u6570\u636e";
}

function filenameFromDisposition(disposition = "") {
  const encodedMatch = /filename\*=UTF-8''([^;]+)/i.exec(disposition);
  if (encodedMatch) {
    try {
      return decodeURIComponent(encodedMatch[1]);
    } catch {
      return encodedMatch[1];
    }
  }
  const plainMatch = /filename="?([^";]+)"?/i.exec(disposition);
  return plainMatch ? plainMatch[1] : "";
}
