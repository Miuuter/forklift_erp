export function createVehicleDetailLoader({
  state,
  api,
  hasPermission,
  sortById,
  fetchVehicleModelVehicles,
  modelSummaryForKey,
  decodeVehicleModelKey,
  ensureVehicleDetailTab,
  renderCurrentTab
}) {
  let requestSequence = 0;

  function clearVehicleSelection() {
    requestSequence += 1;
    state.vehicleDetail = null;
    state.selectedVehicleId = null;
    state.vehicleDetailLoading = false;
    state.pendingVehicleModelKey = "";
    state.vehicleDetailScrollTop = 0;
  }

  async function loadVehicleDetail(id, shouldRender = true) {
    const requestId = ++requestSequence;
    const machineId = Number(id);
    ensureVehicleDetailTab();
    beginLoading("", shouldRender);
    try {
      const [detail, logs, workOrders, parts] = await Promise.all([
        api(`/api/inventory/${machineId}/detail`),
        api(`/api/replace/machine/${machineId}`),
        hasPermission("replace:write") ? api(`/api/modification-work-orders/machine/${machineId}`) : Promise.resolve([]),
        api(`/api/parts/sourceMachine/${machineId}`)
      ]);
      if (requestId !== requestSequence) return;
      state.selectedVehicleId = machineId;
      state.vehicleDetail = {
        ...detail,
        logs: sortById(logs),
        workOrders: sortById(workOrders),
        parts: sortById(parts)
      };
    } finally {
      finishLoading(requestId, shouldRender);
    }
  }

  async function loadVehicleModelDetail(modelKey, selectedMachineId = null, shouldRender = true) {
    const requestId = ++requestSequence;
    ensureVehicleDetailTab();
    beginLoading(modelKey, shouldRender);
    try {
      const vehicles = await fetchVehicleModelVehicles(modelKey);
      const model = modelSummaryForKey(modelKey) || { ...decodeVehicleModelKey(modelKey), modelKey, vehicles };
      const selectedId = Number(selectedMachineId || state.selectedVehicleId || vehicles[0]?.id || 0);
      const selected = vehicles.find(vehicle => Number(vehicle.id) === selectedId) || vehicles[0];
      if (!selected) {
        if (requestId !== requestSequence) return;
        state.selectedVehicleId = null;
        state.vehicleDetail = {
          modelKey,
          model,
          vehicles,
          selectedMachineId: null,
          selectedDetail: { machine: null, configs: [], logs: [], workOrders: [], parts: [] }
        };
        return;
      }
      const [detail, logs, workOrders, parts] = await Promise.all([
        api(`/api/inventory/${selected.id}/detail`),
        api(`/api/replace/machine/${selected.id}`),
        hasPermission("replace:write") ? api(`/api/modification-work-orders/machine/${selected.id}`) : Promise.resolve([]),
        api(`/api/parts/sourceMachine/${selected.id}`)
      ]);
      if (requestId !== requestSequence) return;
      state.selectedVehicleId = Number(selected.id);
      state.vehicleDetail = {
        modelKey,
        model,
        vehicles,
        selectedMachineId: Number(selected.id),
        selectedDetail: {
          ...detail,
          logs: sortById(logs),
          workOrders: sortById(workOrders),
          parts: sortById(parts)
        }
      };
    } finally {
      finishLoading(requestId, shouldRender);
    }
  }

  function beginLoading(modelKey, shouldRender) {
    if (!shouldRender) return;
    state.vehicleDetailLoading = true;
    state.pendingVehicleModelKey = modelKey || "";
    state.vehicleDetailScrollTop = 0;
    renderCurrentTab();
  }

  function finishLoading(requestId, shouldRender) {
    if (requestId !== requestSequence) return;
    state.vehicleDetailLoading = false;
    state.pendingVehicleModelKey = "";
    if (shouldRender) renderCurrentTab();
  }

  return {
    clearVehicleSelection,
    loadVehicleDetail,
    loadVehicleModelDetail
  };
}
