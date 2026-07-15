export function createMutationRefresh({
  state,
  pagedTabs,
  activePagedTab,
  resetPage,
  hasPermission,
  loadTodoCenter,
  loadVehicleModelData,
  loadPagedTab,
  loadCurrentTab,
  loadStatistics,
  ensureConfigData,
  loadVehicleModelDetail,
  loadVehicleDetail
}) {
  function markReferenceDataStale(kinds = []) {
    if (!kinds.length || kinds.includes("vehicle")) state.reference.vehiclesLoaded = false;
    if (!kinds.length || kinds.includes("part")) state.reference.partsLoaded = false;
    if (!kinds.length || kinds.includes("customer")) state.reference.customersLoaded = false;
    if (!kinds.length || kinds.includes("supplier")) state.reference.suppliersLoaded = false;
    if (!kinds.length || kinds.includes("warehouse")) state.reference.warehousesLoaded = false;
    if (!kinds.length || kinds.includes("outboundOrder")) state.reference.outboundOrdersLoaded = false;
    if (!kinds.length || kinds.includes("rental")) state.reference.rentalsLoaded = false;
    if (!kinds.length || kinds.includes("repairUser")) state.reference.repairUsersLoaded = false;
  }

  function referenceKindsForMutation(kind) {
    const mapping = {
      vehicle: ["vehicle"],
      vehicleModel: ["vehicle"],
      vehicleInbound: ["vehicle"],
      vehicleOutbound: ["vehicle", "customer", "outboundOrder"],
      vehicleStock: ["vehicle"],
      part: ["part"],
      partStock: ["part", "customer", "outboundOrder"],
      partReplace: ["vehicle", "part"],
      vehiclePartInstall: ["vehicle", "part"],
      modificationOrder: ["vehicle", "part"],
      outboundOrder: ["vehicle", "part", "outboundOrder"],
      rental: ["vehicle", "rental"],
      repair: ["vehicle", "part", "customer", "rental"],
      customer: ["customer"],
      supplier: ["supplier"],
      warehouse: ["warehouse"],
      stockTransfer: ["vehicle", "part", "warehouse"],
      purchaseOrder: ["supplier", "vehicle"],
      purchaseFreight: [],
      vehicleConfigItem: [],
      vehicleConfigValue: [],
      stocktaking: ["vehicle", "part"],
      invoiceUpload: ["outboundOrder"],
      contractUpload: ["outboundOrder"],
      user: ["repairUser"],
      userJobTag: ["repairUser"]
    };
    return mapping[kind] || [];
  }

  function tabsForMutation(kind) {
    const mapping = {
      vehicle: ["vehicles"],
      vehicleModel: ["vehicles"],
      vehicleInbound: ["vehicles"],
      vehicleOutbound: ["vehicles", "outboundOrders", "customers"],
      vehicleStock: ["vehicles"],
      part: ["parts"],
      partStock: ["parts", "outboundOrders", "customers"],
      partReplace: ["vehicles", "parts"],
      vehiclePartInstall: ["vehicles", "parts"],
      modificationOrder: ["modificationOrders", "vehicles", "parts"],
      outboundOrder: ["outboundOrders", "vehicles", "parts"],
      invoiceUpload: ["outboundOrders", "attachments"],
      contractUpload: ["outboundOrders", "attachments"],
      rental: ["rentals", "vehicles"],
      repair: ["repairs", "vehicles", "parts", "customers", "rentals"],
      customer: ["customers"],
      supplier: ["suppliers"],
      warehouse: ["warehouses"],
      stockTransfer: ["warehouses", "stockMovements", "vehicles", "parts"],
      purchaseOrder: ["purchases", "vehicles"],
      purchaseFreight: ["purchases"],
      vehicleConfigItem: ["configs"],
      vehicleConfigValue: ["configs"],
      stocktaking: ["stocktakes", "vehicles", "parts"],
      user: ["users"],
      userUsername: ["users"],
      userPassword: ["users"],
      userJobTag: ["users"]
    };
    return mapping[kind] || [];
  }

  function resetPageAfterMutation(kind) {
    for (const tab of tabsForMutation(kind)) {
      if (!state.pages[tab]) continue;
      resetPage(state.pages[tab]);
    }
  }

  async function refreshAfterMutation(kind, options = {}) {
    const affectedTabs = new Set(tabsForMutation(kind));
    const requests = [];
    const activeTab = state.activeTab;
    const activePageKey = activePagedTab();

    if (activeTab === "overview") {
      if (hasPermission("stock:adjust")) {
        requests.push(loadTodoCenter());
      }
      for (const tab of affectedTabs) {
        if (tab === "vehicles") {
          requests.push(loadVehicleModelData({ force: true, silent: true }));
        } else if (pagedTabs[tab]) {
          requests.push(loadPagedTab(tab, { force: true, silent: true }));
        }
      }
    } else if (activeTab === "vehicles" && affectedTabs.has("vehicles")) {
      requests.push(loadVehicleModelData({ force: true }));
    } else if (activeTab === "logs" && affectedTabs.has("stockMovements")) {
      requests.push(loadCurrentTab({ force: true }));
    } else if (activePageKey && affectedTabs.has(activePageKey)) {
      requests.push(loadPagedTab(activePageKey, { force: true }));
    } else if (activeTab === "stats" && mutationTouchesFinance(kind) && hasPermission("log:read")) {
      requests.push(loadStatistics(state.selectedStatsYear));
    } else if (activeTab === "configs" && ["configItem", "configValue", "vehicleConfigItem", "vehicleConfigValue"].includes(kind)) {
      requests.push(ensureConfigData(true));
    }

    if (hasPermission("stock:adjust") && state.data.todoCenter && activeTab !== "overview" && mutationTouchesTodoCenter(kind)) {
      requests.push(loadTodoCenter());
    }

    await Promise.all(requests);
    await refreshSelectedVehicleDetail(kind, options);
  }

  async function refreshSelectedVehicleDetail(kind, options = {}) {
    if (options.refreshDetail === false) return;
    if (!mutationTouchesVehicleDetail(kind)) return;
    const targetMachineId = Number(options.machineId || state.selectedVehicleId || 0);
    if (!targetMachineId) return;
    const modelKey = options.activeModelKey || state.vehicleDetail?.modelKey || "";
    if (modelKey) {
      await loadVehicleModelDetail(modelKey, targetMachineId, false);
    } else {
      await loadVehicleDetail(targetMachineId, false);
    }
  }

  function mutationTouchesTodoCenter(kind) {
    return [
      "vehicle",
      "vehicleModel",
      "vehicleInbound",
      "vehicleOutbound",
      "vehicleStock",
      "part",
      "partStock",
      "partReplace",
      "vehiclePartInstall",
      "modificationOrder",
      "outboundOrder",
      "invoiceUpload",
      "contractUpload",
      "rental",
      "repair",
      "stockTransfer",
      "stocktaking",
      "purchaseOrder"
    ].includes(kind);
  }

  function mutationTouchesVehicleDetail(kind) {
    return [
      "vehicle",
      "vehicleInbound",
      "vehicleOutbound",
      "vehicleStock",
      "partReplace",
      "vehiclePartInstall",
      "modificationOrder",
      "rental",
      "repair",
      "stockTransfer",
      "stocktaking",
      "purchaseOrder"
    ].includes(kind);
  }

  function mutationTouchesFinance(kind) {
    return mutationTouchesTodoCenter(kind)
      || ["purchaseOrder", "purchaseFreight"].includes(kind);
  }

  return {
    markReferenceDataStale,
    referenceKindsForMutation,
    tabsForMutation,
    resetPageAfterMutation,
    refreshAfterMutation,
    refreshSelectedVehicleDetail,
    mutationTouchesTodoCenter,
    mutationTouchesVehicleDetail,
    mutationTouchesFinance
  };
}
