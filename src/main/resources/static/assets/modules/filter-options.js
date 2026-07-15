export function createFilterOptions(deps) {
  const {
    state,
    effectiveFieldValue,
    normalizeText,
    isInvoiceUploadReady,
    isContractUploadReady
  } = deps;

function configItemOptions() {
  return [...state.data.configItems]
    .sort(compareConfigItems)
    .map(item => ({
    value: item.id,
    label: configItemLabel(item)
  }));
}

function vehicleConfigItemOptions() {
  return [...state.data.vehicleConfigItems]
    .sort((a, b) => [
      Number(a.sortOrder || 0) - Number(b.sortOrder || 0),
      String(a.specificationModel || "").localeCompare(String(b.specificationModel || ""), "zh-CN")
    ].find(result => result !== 0) || 0)
    .map(item => ({
      value: item.id,
      label: item.specificationModel || "-"
    }));
}

function vehicleConfigValueOptions() {
  const configItemId = effectiveFieldValue(state.modal?.item || {}, "configItemId");
  if (!configItemId) return [];
  return configValueOptionsForItem(configItemId);
}

function compareConfigItems(a, b) {
  return [
    String(a.category || "").localeCompare(String(b.category || ""), "zh-CN"),
    String(a.subCategory || "").localeCompare(String(b.subCategory || ""), "zh-CN"),
    String(a.itemName || "").localeCompare(String(b.itemName || ""), "zh-CN"),
    Number(a.sortOrder || 0) - Number(b.sortOrder || 0)
  ].find(result => result !== 0) || 0;
}

function configValueOptionsForItem(configItemId) {
  const item = state.data.configItems.find(configItem => String(configItem.id) === String(configItemId));
  return (state.data.configValueMap[configItemId] || [])
    .map(value => ({
      value: value.id,
      label: value.valueLabel || "-",
      meta: {
        configItemId: item?.id,
        configValueId: value.id,
        valueLabel: value.valueLabel,
        valueCode: value.valueCode,
        itemLabel: item ? configItemLabel(item) : "",
        unit: item?.unit
      }
    }));
}

function powerTypeOptions() {
  return [
    { value: "内燃叉车", label: "内燃叉车" },
    { value: "电动叉车", label: "电动叉车" },
    { value: "手动叉车", label: "手动叉车" }
  ];
}

function supplierFilterOptions() {
  return uniqueOptions(state.data.vehicles.map(item => item.supplier));
}

function stockFilterOptions() {
  return [
    { value: "inStock", label: "有库存" },
    { value: "longIdle", label: "长期未动" },
    { value: "empty", label: "库存为 0" }
  ];
}

function filterPartRows(rows = []) {
  const stock = state.filters.parts?.stock || "";
  if (!stock) return rows;
  return rows.filter(row => {
    const quantity = Number(row.quantity || 0);
    if (stock === "available") return quantity > 0;
    if (stock === "low") return quantity <= Number(row.reorderPoint ?? 5);
    return true;
  });
}

function filterOutboundOrderRows(rows = []) {
  const stage = state.filters.outboundOrders?.stage || "";
  if (!stage) return rows;
  return rows.filter(row => {
    if (stage === "payment") return !row.paymentSettled;
    if (stage === "overdue") return Number(row.overdueDays || 0) > 0;
    if (stage === "salesReport") return Boolean(row.paymentSettled) && !Boolean(row.salesReported);
    if (stage === "invoiceApplication") return Boolean(row.paymentSettled) && Boolean(row.salesReported) && !Boolean(row.invoiceApplied);
    if (stage === "invoiceFile") return isInvoiceUploadReady(row) && !row.invoiceFileAvailable;
    if (stage === "contractFile") return isContractUploadReady(row) && !row.contractFileAvailable;
    if (stage === "closed") {
      return Boolean(row.paymentSettled)
        && Boolean(row.salesReported)
        && Boolean(row.invoiceApplied)
        && (!isInvoiceUploadReady(row) || Boolean(row.invoiceFileAvailable))
        && (!isContractUploadReady(row) || Boolean(row.contractFileAvailable));
    }
    return true;
  });
}

function filterRentalRows(rows = []) {
  const status = state.filters.rentals?.status || "";
  if (!status) return rows;
  return rows.filter(row => {
    const active = row.status === "ACTIVE";
    const daysUntilEnd = rentalDaysUntilEnd(row.endDate);
    if (status === "active") return active;
    if (status === "returned") return row.status === "RETURNED";
    if (status === "dueSoon") return active && daysUntilEnd !== null && daysUntilEnd <= 7;
    if (status === "overdue") return active && daysUntilEnd !== null && daysUntilEnd < 0;
    return true;
  });
}

function filterRepairRows(rows = []) {
  const status = state.filters.repairs?.status || "";
  if (!status) return rows;
  return rows.filter(row => {
    const completed = row.status === "COMPLETED";
    if (status === "completed") return completed;
    if (status === "pending") return !completed;
    return true;
  });
}

function rentalDaysUntilEnd(value) {
  const end = dateOnlyTime(value);
  if (end === null) return null;
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  return Math.floor((end - today.getTime()) / 86400000);
}

function dateOnlyTime(value) {
  if (!value) return null;
  const [year, month, day] = String(value).split("-").map(part => Number(part));
  if (!year || !month || !day) return null;
  return new Date(year, month - 1, day).getTime();
}

function vehicleCategoryOptions() {
  return uniqueOptions([
    "电动叉车",
    "手动叉车",
    "内燃叉车",
    ...state.data.configItems.map(item => item.category)
  ]);
}

function configSubCategoryOptions() {
  return uniqueOptions([
    "货叉",
    "电池",
    "轮胎",
    ...state.data.configItems.map(item => item.subCategory),
    ...state.data.configItems.map(item => item.itemName)
  ]);
}

function configPartCategoryOptions() {
  return uniqueOptions([
    ...state.data.configItems.map(item => item.subCategory || item.itemName),
    ...state.data.parts.map(item => item.partCategory),
    "货叉",
    "电池",
    "轮胎"
  ]);
}

function configValueOptions() {
  const selectedCategory = normalizeText(state.modal?.item?.partCategory);
  return configValuesWithItems()
    .filter(entry => !selectedCategory || normalizeText(configPartCategory(entry.item)) === selectedCategory)
    .map(entry => ({
      value: entry.value.valueLabel,
      label: `${entry.value.valueLabel || "-"} · ${configItemLabel(entry.item)}`,
      meta: {
        configItemId: entry.item.id,
        configValueId: entry.value.id,
        partCategory: configPartCategory(entry.item)
      }
    }));
}

function configValuesWithItems() {
  return state.data.configItems.flatMap(item => {
    const values = state.data.configValueMap[item.id] || [];
    return values.map(value => ({ item, value }));
  });
}

function configItemLabel(item) {
  return [
    item.category,
    item.subCategory,
    item.itemName
  ].filter(Boolean).join(" / ") || "-";
}

function configPartCategory(item) {
  return item?.subCategory || item?.itemName || "";
}

function uniqueOptions(values) {
  return [...new Set((values || []).map(value => String(value || "").trim()).filter(Boolean))]
    .map(value => ({ value, label: value }));
}

function normalizePowerType(machineType) {
  const value = String(machineType || "").trim();
  if (value.includes("手动")) return "手动叉车";
  if (value.includes("电动") || value.toUpperCase().includes("CPD")) return "电动叉车";
  if (value.includes("内燃") || value.includes("燃油") || value.toUpperCase().includes("CPC")) return "内燃叉车";
  return value;
}

function isManualForklift(machineType) {
  return normalizePowerType(machineType) === "手动叉车";
}

function vehicleModelKey(item) {
  return encodeURIComponent(JSON.stringify([
    String(item?.name || "").trim(),
    String(item?.specificationModel || "").trim(),
    normalizePowerType(item?.machineType)
  ]));
}


  return {
    configItemOptions, vehicleConfigItemOptions, vehicleConfigValueOptions,
    compareConfigItems, configValueOptionsForItem, powerTypeOptions,
    supplierFilterOptions, stockFilterOptions, filterPartRows,
    filterOutboundOrderRows, filterRentalRows, filterRepairRows,
    rentalDaysUntilEnd, dateOnlyTime, vehicleCategoryOptions,
    configSubCategoryOptions, configPartCategoryOptions, configValueOptions,
    configValuesWithItems, configItemLabel, configPartCategory, uniqueOptions,
    normalizePowerType, isManualForklift, vehicleModelKey
  };
}
