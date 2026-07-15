export function createEntityOptions(deps) {
  const {
    state, api, endpoints, sortById, effectiveFieldValue, stockStatusLabel,
    activeRentalForMachine, purchaseOrderResourceType, normalizeText, setPrefill,
    dateValue, money, vehicleModelKey, normalizePowerType,
    configValueOptionsForItem, configItemLabel, configPartCategory, compareConfigItems
  } = deps;

function decodeVehicleModelKey(key) {
  try {
    const [name, specificationModel, machineType] = JSON.parse(decodeURIComponent(key || ""));
    return { name, specificationModel, machineType };
  } catch (error) {
    return { name: "", specificationModel: "", machineType: "" };
  }
}

function vehicleModelLabel(model) {
  return `${model?.name || "-"} · ${model?.specificationModel || "-"}`;
}

function vehicleNumberLabel(item) {
  return `${item?.vehicleProductNumber || item?.id || "-"} · ${item?.name || "-"} / ${item?.specificationModel || "-"}`;
}

function vehiclePartDefaultsForMachine(machine = {}) {
  const entity = {
    __placeholders: {
      partCode: machine.vehicleProductNumber ? `请输入 ${machine.vehicleProductNumber} 的配件编码` : "请输入配件编码",
      partName: "请选择或输入配件名称",
      partCategory: "例如：轮胎 / 电池 / 属具 / 安全附件",
      remarks: machine.vehicleProductNumber ? `来自整车 ${vehicleNumberLabel(machine)}` : "记录配件来源或安装说明"
    }
  };
  const modelLabel = machine.name || machine.specificationModel ? vehicleModelLabel(machine) : "";
  setPrefill(entity, "source", "整车新增", "预填：整车新增");
  setPrefill(entity, "sourceMachineId", machine.id, machine.id ? `预填：${vehicleNumberLabel(machine)}` : undefined);
  setPrefill(entity, "applicableModels", modelLabel, modelLabel ? `预填：${modelLabel}` : undefined);
  setPrefill(entity, "quantity", 1, "默认：1");
  return entity;
}

function vehiclePartInstallDefaultsForMachine(machine = {}) {
  const entity = {};
  setPrefill(entity, "machineId", machine.id, machine.id ? `预填：${vehicleNumberLabel(machine)}` : undefined);
  setPrefill(entity, "quantity", 1, "默认：1");
  return entity;
}

function vehicleModelGroups() {
  const groups = new Map();
  for (const vehicle of state.data.vehicles) {
    const modelKey = vehicleModelKey(vehicle);
    const modelOnly = Boolean(vehicle.modelOnly);
    if (!groups.has(modelKey)) {
      groups.set(modelKey, {
        id: modelKey,
        modelKey,
        name: vehicle.name,
        specificationModel: vehicle.specificationModel,
        machineType: normalizePowerType(vehicle.machineType),
        supplier: vehicle.supplier,
        warehouseName: vehicle.warehouseName,
        purchasePrice: vehicle.purchasePrice,
        salePrice: vehicle.salePrice,
        settlementPrice: vehicle.settlementPrice,
        modelTemplateId: modelOnly ? vehicle.id : null,
        vehicles: [],
        vehicleNumbers: "",
        unitCount: 0,
        inventoryCount: 0
      });
    }
    const group = groups.get(modelKey);
    if (!group.supplier && vehicle.supplier) group.supplier = vehicle.supplier;
    if (!group.warehouseName && vehicle.warehouseName) group.warehouseName = vehicle.warehouseName;
    if (!group.purchasePrice && vehicle.purchasePrice) group.purchasePrice = vehicle.purchasePrice;
    if (!group.salePrice && vehicle.salePrice) group.salePrice = vehicle.salePrice;
    if (!group.settlementPrice && vehicle.settlementPrice) group.settlementPrice = vehicle.settlementPrice;
    if (modelOnly) {
      group.modelTemplateId = vehicle.id;
      continue;
    }
    group.vehicles.push(vehicle);
    group.unitCount += 1;
    group.inventoryCount += Number(vehicle.inventoryCount || 0);
  }
  return [...groups.values()]
    .map(group => ({
      ...group,
      vehicles: [...group.vehicles].sort((a, b) => String(a.vehicleProductNumber || "").localeCompare(String(b.vehicleProductNumber || ""), "zh-CN")),
      vehicleNumbers: group.vehicles.map(vehicle => vehicle.vehicleProductNumber).filter(Boolean).join(" ")
    }))
    .sort((a, b) => vehicleModelLabel(a).localeCompare(vehicleModelLabel(b), "zh-CN"));
}

function vehiclesForModelKey(modelKey) {
  if (!modelKey) return [];
  return state.data.vehicles
    .filter(vehicle => vehicleModelKey(vehicle) === modelKey && !vehicle.modelOnly)
    .sort((a, b) => String(a.vehicleProductNumber || "").localeCompare(String(b.vehicleProductNumber || ""), "zh-CN"));
}

function prepareVehicleModelSummary(row = {}) {
  const model = {
    ...row,
    modelQueryMachineType: String(row.machineType || "").trim(),
    machineType: normalizePowerType(row.machineType),
    unitCount: Number(row.unitCount || 0),
    inventoryCount: Number(row.inventoryCount || 0),
    vehicleNumbers: row.vehicleNumbers || "",
    vehicles: []
  };
  model.modelKey = vehicleModelKey(model);
  model.id = model.modelKey;
  return model;
}

function modelSummaryForKey(modelKey) {
  if (!modelKey) return null;
  return (state.data.vehicleModels || []).find(item => item.modelKey === modelKey)
    || vehicleModelGroups().find(item => item.modelKey === modelKey)
    || null;
}

async function fetchVehicleModelVehicles(modelKey) {
  if (!modelKey) return [];
  const model = modelSummaryForKey(modelKey) || decodeVehicleModelKey(modelKey);
  const params = new URLSearchParams();
  params.set("name", model.name || "");
  params.set("specificationModel", model.specificationModel || "");
  params.set("machineType", model.modelQueryMachineType ?? model.machineType ?? "");
  params.set("stock", state.filters.vehicles?.stock || "");
  const rows = await api(`${endpoints.vehicle.modelVehicles}?${params.toString()}`);
  return sortById(rows, false).sort((a, b) => String(a.vehicleProductNumber || "").localeCompare(String(b.vehicleProductNumber || ""), "zh-CN"));
}
function vehicleModelOptions() {
  return vehicleModelGroups().map(group => ({
    value: group.modelKey,
    label: `${vehicleModelLabel(group)}（${group.machineType || "未分类"} / ${group.inventoryCount} 台库存）`
  }));
}

function vehicleNumberOptions() {
  const modelKey = effectiveFieldValue(state.modal?.item, "vehicleModelKey");
  return vehiclesForModelKey(modelKey).map(item => ({
    value: item.id,
    label: `${item.vehicleProductNumber || item.id}（${stockStatusLabel(item.stockStatus)}）`,
    meta: {
      modelKey,
      vehicleProductNumber: item.vehicleProductNumber,
      frameNumber: item.frameNumber,
      engineNumber: item.engineNumber
    }
  }));
}

function vehicleOutboundOptions() {
  const modelKey = state.modal?.item?.__modelKey;
  const vehicles = modelKey ? vehiclesForModelKey(modelKey) : state.data.vehicles.filter(item => !item.modelOnly);
  return vehicles
    .filter(canOutboundVehicle)
    .map(item => ({
      value: item.id,
      label: `${item.vehicleProductNumber || item.id} · ${item.name || "-"} / ${item.specificationModel || "-"}（在库 ${item.inventoryCount || 0} 台）`,
      meta: {
        settlementPrice: item.settlementPrice,
        salePrice: item.salePrice,
        stockStatus: item.stockStatus
      }
    }));
}

function canOutboundVehicle(item) {
  return !item.modelOnly
    && Number(item.inventoryCount || 0) > 0
    && !["OUTBOUND", "RENTED"].includes(item.stockStatus)
    && !activeRentalForMachine(item.id);
}

function vehicleRentalOptions() {
  const currentRentalId = state.modal?.item?.id;
  return state.data.vehicles
    .filter(item => !item.modelOnly)
    .filter(item => Number(item.inventoryCount || 0) > 0 && !["OUTBOUND", "RENTED"].includes(item.stockStatus))
    .filter(item => {
      const rental = activeRentalForMachine(item.id);
      return !rental || Number(rental.id) === Number(currentRentalId);
    })
    .map(item => ({
      value: item.id,
      label: `${item.vehicleProductNumber || item.id} · ${item.name || "-"} / ${item.specificationModel || "-"}`
    }));
}

function vehicleOptions() {
  return state.data.vehicles.filter(item => !item.modelOnly).map(item => ({
    value: item.id,
    label: `${vehicleNumberLabel(item)}${activeRentalForMachine(item.id) ? " · 租赁中" : ""}`
  }));
}

function customerOptions() {
  return state.data.customers.map(item => ({
    value: item.id,
    label: `${item.companyName || "-"}${item.contactName ? " · " + item.contactName : ""}`,
    meta: {
      contactPhone: item.contactPhone,
      taxOrIdNumber: item.taxOrIdNumber
    }
  }));
}

function customerEntryModeOptions() {
  return [
    { value: "existing", label: "选择现有客户" },
    { value: "quickCreate", label: "直接录入并新建客户" }
  ];
}

function supplierOptions() {
  const currentSupplierId = Number(effectiveFieldValue(state.modal?.item || {}, "supplierId") || 0);
  return state.data.suppliers
    .filter(item => item.active !== false || Number(item.id) === currentSupplierId)
    .map(item => ({
    value: item.id,
    label: `${item.supplierName || "-"}${item.active === false ? "（已停用）" : ""}${item.contactName ? " / " + item.contactName : ""}`,
    meta: {
      contactPhone: item.contactPhone,
      supplierType: item.supplierType
    }
  }));
}

function purchaseResourceTypeOptions() {
  return [
    { value: "PART", label: "配件订单" },
    { value: "MACHINE", label: "整车订单" }
  ];
}

function purchaseResourceOptions() {
  if (purchaseOrderResourceType() !== "PART") {
    return [];
  }
  return state.data.parts.filter(part => !part.isLocked).map(part => ({
    value: part.id,
    label: `${part.partCode || part.id} · ${part.partName || "-"} / ${part.specification || "-"}（当前 ${part.quantity ?? 0}${part.unit || ""}）`,
    meta: {
      partCode: part.partCode,
      partName: part.partName,
      specification: part.specification,
      unit: part.unit,
      warehouseId: part.warehouseId
    }
  }));
}

function purchaseStatusOptions() {
  return [
    { value: "ORDERED", label: "已下单" },
    { value: "PARTIAL", label: "部分到货" },
    { value: "ARRIVED", label: "已到货" },
    { value: "RECEIVED", label: "已收货" },
    { value: "CANCELED", label: "已取消" }
  ];
}
function purchaseConfigValueOptions() {
  const configItemId = effectiveFieldValue(state.modal?.item || {}, "configItemId");
  if (!configItemId) return [];
  return configValueOptionsForItem(configItemId);
}

function purchaseSpecificationModelOptions() {
  return [...state.data.vehicleConfigItems]
    .sort((left, right) => [
      Number(left.sortOrder || 0) - Number(right.sortOrder || 0),
      String(left.specificationModel || "").localeCompare(String(right.specificationModel || ""), "zh-CN")
    ].find(result => result !== 0) || 0)
    .map(item => ({
      value: item.specificationModel || "",
      label: item.specificationModel || "-"
    }))
    .filter(option => option.value);
}

function stocktakingResourceTypeOptions() {
  return [
    { value: "PART", label: "配件" },
    { value: "MACHINE", label: "整车" }
  ];
}

function stocktakingResourceOptions() {
  const type = String(effectiveFieldValue(state.modal?.item || {}, "resourceType") || "PART").toUpperCase();
  if (type === "MACHINE") {
    return state.data.vehicles
      .filter(item => !item.modelOnly)
      .map(item => ({
        value: item.id,
        label: `${vehicleNumberLabel(item)} / 总库存 ${item.inventoryCount ?? 0}`
      }));
  }
  return state.data.parts.map(item => ({
    value: item.id,
    label: `${item.partCode || "-"} / ${item.partName || "-"} / 总库存 ${item.quantity ?? 0}${item.unit || ""}`
  }));
}

function stocktakingStatusOptions() {
  return [
    { value: "DRAFT", label: "草稿" }
  ];
}

function warehouseOptions() {
  return state.data.warehouses.map(item => ({
    value: item.id,
    label: `${item.warehouseName || "-"}（${item.warehouseCode || "-"}）`
  }));
}

function warehouseNameById(id) {
  return state.data.warehouses.find(item => Number(item.id) === Number(id))?.warehouseName || "未分配仓库";
}

function transferResourceTypeOptions() {
  return [
    { value: "PART", label: "配件" },
    { value: "MACHINE", label: "整车" }
  ];
}

function stockTransferResourceOptions() {
  const type = String(effectiveFieldValue(state.modal?.item || {}, "resourceType") || "PART").toUpperCase();
  if (type === "MACHINE") {
    return state.data.vehicles
      .filter(item => !item.modelOnly)
      .filter(item => Number(item.inventoryCount || 0) > 0)
      .filter(item => item.stockStatus !== "RENTED" && !activeRentalForMachine(item.id))
      .map(item => ({
        value: item.id,
        label: `${vehicleNumberLabel(item)} / ${warehouseNameById(item.warehouseId)} / 库存 ${item.inventoryCount ?? 0}`,
        meta: {
          warehouseId: item.warehouseId,
          version: item.version
        }
      }));
  }
  return state.data.parts
    .filter(item => Number(item.quantity || 0) > 0)
    .map(item => ({
      value: item.id,
      label: `${item.partCode || "-"} · ${item.partName || "-"} / ${warehouseNameById(item.warehouseId)} / 库存 ${item.quantity ?? 0}${item.unit || ""}`,
      meta: {
        warehouseId: item.warehouseId,
        version: item.version
      }
    }));
}

function stockTransferResourceName(payload = {}) {
  const type = String(payload.resourceType || "PART").toUpperCase();
  const rows = type === "MACHINE" ? state.data.vehicles : state.data.parts;
  const resource = rows.find(item => Number(item.id) === Number(payload.resourceId));
  if (!resource) return "调拨对象";
  return type === "MACHINE" ? vehicleNumberLabel(resource) : `${resource.partCode || "-"} · ${resource.partName || "-"}`;
}

function partCodeOptions() {
  return state.data.parts.filter(item => !item.isLocked).map(item => ({
    value: item.partCode,
    label: `${item.partCode || "-"} · ${item.partName || "-"}（库存 ${item.quantity ?? 0}${item.unit || ""}）`
  }));
}

function machineConfigOptions() {
  return (state.modal?.context?.machineConfigs || []).map(item => ({
    value: item.id,
    label: machineConfigOptionLabel(item)
  }));
}

function machineConfigOptionLabel(item) {
  return `${machineConfigDictionaryLabel(item)} · ${item.selectedValue || "-"}`;
}

function machineConfigDictionaryLabel(config) {
  const item = state.data.configItems.find(configItem => String(configItem.id) === String(config.configItemId));
  return item ? configItemLabel(item) : (config.itemName || "-");
}

function compatiblePartOptions() {
  return (state.modal?.context?.compatibleParts || []).map(item => ({
    value: item.id,
    label: `${item.partCode || "-"} · ${item.partName || "-"}（库存 ${item.quantity ?? 0}${item.unit || ""}）`
  }));
}

function discountConfigValueOptions() {
  const configId = effectiveFieldValue(state.modal?.item || {}, "machineConfigId");
  const config = (state.modal?.context?.machineConfigs || [])
    .find(item => String(item.id) === String(configId));
  return (state.data.configValueMap[config?.configItemId] || []).map(value => ({
    value: value.id,
    label: value.valueLabel || "-"
  }));
}

function installPartCategoryOptions() {
  const availableCategories = new Set(state.data.parts
    .filter(part => !part.isLocked)
    .filter(part => Number(part.quantity || 0) > 0)
    .map(part => normalizeText(part.partCategory))
    .filter(Boolean));
  return state.data.configItems
    .filter(item => availableCategories.has(normalizeText(configPartCategory(item))))
    .sort(compareConfigItems)
    .map(item => ({
      value: item.id,
      label: configItemLabel(item)
    }));
}

function installPartOptions() {
  const selectedItem = state.data.configItems.find(item => String(item.id) === String(state.modal?.item?.configItemId));
  const selectedCategory = normalizeText(configPartCategory(selectedItem));
  return state.data.parts
    .filter(part => !part.isLocked)
    .filter(part => Number(part.quantity || 0) > 0)
    .filter(part => !selectedCategory || normalizeText(part.partCategory) === selectedCategory)
    .map(part => ({
      value: part.id,
      label: `${part.partCode || "-"} · ${part.partName || "-"}（库存 ${part.quantity ?? 0}${part.unit || ""}）`
    }));
}

function statusOptions() {
  return [
    { value: "PENDING", label: "待处理" },
    { value: "COMPLETED", label: "已完成" }
  ];
}

function stockStatusOptions() {
  return [
    { value: "PENDING_INBOUND", label: "待入库" },
    { value: "IN_STOCK", label: "在库" },
    { value: "RENTED", label: "租赁中" },
    { value: "PENDING_MODIFICATION", label: "待改装" },
    { value: "MODIFYING", label: "改装中" },
    { value: "PENDING_OUTBOUND", label: "待出库" },
    { value: "OUTBOUND", label: "已出库" }
  ];
}

function oldPartActionOptions() {
  return [
    { value: "DISCOUNT", label: "旧件折价" },
    { value: "STOCK_IN", label: "拆下件入库" },
    { value: "DISCARD", label: "不入库" }
  ];
}

function inputTypeOptions() {
  return [
    { value: "SELECT", label: "下拉选择" },
    { value: "TEXT", label: "文本输入" },
    { value: "NUMBER", label: "数字输入" },
    { value: "BOOLEAN", label: "开关选项" }
  ];
}


function repairPartOptions() {
  return state.data.parts.filter(item => !item.isLocked).map(item => ({
    value: item.id,
    label: `${item.partCode || "-"} · ${item.partName || "-"}（库存 ${item.quantity ?? 0}${item.unit || ""}）`
  }));
}

function repairPersonOptions() {
  return [
    ...state.data.repairUsers.map(item => ({
      value: item.id,
      label: item.username || `用户 ${item.id}`
    })),
    { value: "OTHER", label: "其他" }
  ];
}

function rentalStatusOptions() {
  if (state.modal?.item?.financialPosted) {
    return [{ value: "RETURNED", label: "已归还（已生成账单）" }];
  }
  return [
    { value: "ACTIVE", label: "租赁中" },
    { value: "RETURNED", label: "已归还" }
  ];
}

function paymentReversalOptions() {
  const records = state.modal?.item?.paymentRecords || [];
  const reversedIds = new Set(records
    .map(record => record.reversalOfPaymentId)
    .filter(Boolean)
    .map(String));
  return records
    .filter(record =>
      Number(record.amount || 0) > 0
      && !record.reversalOfPaymentId
      && !reversedIds.has(String(record.id))
    )
    .map(record => ({
      value: record.id,
      label: `${record.sourceLabel ? `${record.sourceLabel} · ` : ""}${record.paymentNo || `#${record.id}`} · ${dateValue(record.paymentDate) || "-"} · ${record.direction === "PAYMENT" ? "付款" : "收款"} ${money(record.amount)}`
    }));
}


  return {
    decodeVehicleModelKey, vehicleModelLabel, vehicleNumberLabel,
    vehiclePartDefaultsForMachine, vehiclePartInstallDefaultsForMachine,
    vehicleModelGroups, vehiclesForModelKey, prepareVehicleModelSummary,
    modelSummaryForKey, fetchVehicleModelVehicles, vehicleModelOptions,
    vehicleNumberOptions, vehicleOutboundOptions, canOutboundVehicle,
    vehicleRentalOptions, vehicleOptions, customerOptions, customerEntryModeOptions,
    supplierOptions, purchaseResourceTypeOptions, purchaseResourceOptions,
    purchaseStatusOptions, purchaseConfigValueOptions,
    purchaseSpecificationModelOptions, stocktakingResourceTypeOptions,
    stocktakingResourceOptions, stocktakingStatusOptions, warehouseOptions,
    warehouseNameById, transferResourceTypeOptions, stockTransferResourceOptions,
    stockTransferResourceName, partCodeOptions, machineConfigOptions,
    machineConfigOptionLabel, machineConfigDictionaryLabel, compatiblePartOptions,
    discountConfigValueOptions, installPartCategoryOptions, installPartOptions,
    statusOptions, stockStatusOptions, oldPartActionOptions, inputTypeOptions,
    repairPartOptions, repairPersonOptions, rentalStatusOptions, paymentReversalOptions
  };
}
