import { assignPageState, pageContent, pageUrl } from "./paging.js";

export const REFERENCE_PAGE_SIZE = 200;

export function referencePageUrl(url, keyword = "", size = REFERENCE_PAGE_SIZE) {
  const params = new URLSearchParams();
  params.set("paged", "true");
  params.set("page", "0");
  params.set("size", String(Math.max(1, Number(size || REFERENCE_PAGE_SIZE))));
  if (keyword && keyword.trim()) {
    params.set("keyword", keyword.trim());
  }
  return `${url}?${params.toString()}`;
}

export function rowsFromPayload(payload) {
  return Array.isArray(payload) ? payload : pageContent(payload);
}

export function createDataLoaders({
  state,
  api,
  endpoints,
  pagedTabs,
  summaryTabs,
  activePagedTab,
  hasPermission,
  sortById,
  sortLogs,
  prepareVehicleModelSummary,
  todayInputDate,
  loadVehicleDetail,
  loadVehicleModelDetail,
  renderCurrentTab,
  restoreConfigItemScroll,
  restoreVehicleConfigItemScroll
}) {
  async function loadAllData(options = {}) {
    const activeModelKey = state.vehicleDetail?.modelKey;
    await ensureConfigData();
    await loadCurrentTab({ force: options.force !== false });
    if (state.selectedVehicleId) {
      if (activeModelKey) {
        await loadVehicleModelDetail(activeModelKey, state.selectedVehicleId, false);
      } else {
        await loadVehicleDetail(state.selectedVehicleId, false);
      }
    }
  }

  async function loadCurrentTab(options = {}) {
    const tab = state.activeTab;
    if (tab === "overview") {
      await loadOverviewData(options);
      return;
    }
    if (tab === "stats") {
      if (hasPermission("log:read")) {
        await loadStatistics(state.selectedStatsYear);
      } else {
        state.data.statistics = null;
        state.data.dailyReconciliation = null;
      }
      return;
    }
    if (tab === "configs") {
      await ensureConfigData(true);
      return;
    }
    if (tab === "vehicles") {
      await loadVehicleModelData(options);
      return;
    }
    if (tab === "logs") {
      await Promise.all([
        loadPagedTab("logs", options),
        loadPagedTab("stockMovements", { ...options, keyword: state.search.logs || "", silent: true })
      ]);
      return;
    }
    const pagedTab = activePagedTab();
    if (pagedTab) {
      await loadPagedTab(pagedTab, options);
    }
  }

  async function ensureConfigData(force = false) {
    if (!force && state.data.configItems.length && state.data.vehicleConfigItems.length) return;
    state.data.configItems = sortById(await api(endpoints.configItem.list), false);
    state.data.vehicleConfigItems = sortById(await api(endpoints.vehicleConfigItem.list), false);
    await loadConfigValueCache(state.data.configItems);
    if (!state.selectedConfigItemId && state.data.configItems.length) {
      state.selectedConfigItemId = state.data.configItems[0].id;
    }
    if (state.selectedConfigItemId) {
      state.data.configValues = sortById(
        state.data.configValueMap[state.selectedConfigItemId] || await fetchConfigValues(state.selectedConfigItemId),
        false
      );
    }
    if (!state.selectedVehicleConfigItemId && state.data.vehicleConfigItems.length) {
      state.selectedVehicleConfigItemId = state.data.vehicleConfigItems[0].id;
    }
    if (state.selectedVehicleConfigItemId) {
      state.data.vehicleConfigValues = sortById(
        await fetchVehicleConfigValues(state.selectedVehicleConfigItemId),
        false
      );
    }
  }

  async function loadOverviewData() {
    await Promise.all([
      hasPermission("log:read") ? loadStatistics(state.selectedStatsYear) : Promise.resolve(),
      hasPermission("stock:adjust") ? loadTodoCenter() : Promise.resolve(),
      loadVehicleModelData({ force: true, silent: true }),
      loadPagedTab("parts", { force: true, silent: true }),
      loadPagedTab("repairs", { force: true, silent: true }),
      loadPagedTab("outboundOrders", { force: true, silent: true }),
      loadPagedTab("rentals", { force: true, silent: true })
    ]);
  }

  async function loadTodoCenter() {
    state.data.todoCenter = await api(endpoints.todos);
  }

  async function loadVehicleModelData(options = {}) {
    const pageState = state.pages.vehicles;
    if (options.force === false && pageState.loaded) return;
    pageState.loading = true;
    const baseUrl = pageUrl(endpoints.vehicle.models, pageState, state.search.vehicles || "");
    const extraParams = pagedTabs.vehicles?.buildParams?.(state, options) || {};
    const query = new URLSearchParams(Object.entries(extraParams)
      .filter(([, value]) => value !== null && value !== undefined && String(value).trim() !== ""));
    const payload = await api(query.size ? `${baseUrl}&${query.toString()}` : baseUrl);
    state.data.vehicleModels = pageContent(payload).map(prepareVehicleModelSummary);
    assignPageState(pageState, payload);
  }

  async function loadPagedTab(tab, options = {}) {
    const config = pagedTabs[tab];
    if (!config) return;
    if (config.permission && !hasPermission(config.permission)) {
      state.data[config.dataKey] = [];
      assignPageState(state.pages[tab], {
        content: [],
        page: 0,
        size: state.pages[tab].size,
        totalElements: 0,
        totalPages: 0,
        first: true,
        last: true
      });
      return;
    }

    const pageState = state.pages[tab];
    pageState.loading = true;
    const keyword = options.keyword ?? state.search[config.searchKey] ?? "";
    const extraParams = typeof config.buildParams === "function" ? config.buildParams(state, options) || {} : {};
    const baseUrl = pageUrl(config.endpoint, pageState, keyword);
    const url = Object.keys(extraParams).length
      ? `${baseUrl}&${new URLSearchParams(Object.entries(extraParams).filter(([, value]) => value !== null && value !== undefined && String(value).trim() !== "")).toString()}`
      : baseUrl;
    const [payload, summary] = await Promise.all([
      api(url),
      loadListSummary(tab, keyword, extraParams)
    ]);
    const rows = pageContent(payload);
    state.data[config.dataKey] = config.dataKey === "operationLogs"
      ? sortLogs(rows)
      : sortById(rows, config.sortDesc !== false);
    if (summary) {
      state.data.summaries[tab] = summary;
    } else if (summaryTabs.has(tab)) {
      delete state.data.summaries[tab];
    }
    assignPageState(pageState, payload);

    if (tab === "vehicles" && options.withVehicleReferences !== false) {
      await loadVehicleFlowReferences({ force: true });
    }
    if (tab === "repairs" && hasPermission("stock:adjust") && !state.reference.rentalsLoaded) {
      state.data.rentals = sortById(rowsFromPayload(await api(referencePageUrl(endpoints.rental.list))));
      state.reference.rentalsLoaded = true;
    }
  }

  async function loadListSummary(tab, keyword = "", extraParams = {}) {
    if (!summaryTabs.has(tab) || !endpoints.listSummary) return null;
    const params = new URLSearchParams({ type: tab });
    if (keyword && keyword.trim()) {
      params.set("keyword", keyword.trim());
    }
    Object.entries(extraParams || {}).forEach(([key, value]) => {
      if (value !== null && value !== undefined && String(value).trim() !== "") {
        params.set(key, String(value).trim());
      }
    });
    try {
      return await api(`${endpoints.listSummary}?${params.toString()}`);
    } catch (error) {
      console.warn("List summary failed", error);
      return null;
    }
  }

  async function loadVehicleFlowReferences(options = {}) {
    if (!hasPermission("stock:adjust")) {
      state.data.outboundOrders = [];
      state.data.rentals = [];
      state.reference.outboundOrdersLoaded = true;
      state.reference.rentalsLoaded = true;
      return;
    }
    if (options.force === false && state.reference.outboundOrdersLoaded && state.reference.rentalsLoaded) {
      return;
    }
    const [orders, rentals] = await Promise.all([
      api(referencePageUrl(endpoints.outboundOrder.list)),
      api(referencePageUrl(endpoints.rental.list))
    ]);
    state.data.outboundOrders = sortById(rowsFromPayload(orders));
    state.data.rentals = sortById(rowsFromPayload(rentals));
    state.reference.outboundOrdersLoaded = true;
    state.reference.rentalsLoaded = true;
  }

  async function loadStatistics(year) {
    state.selectedStatsYear = Number(year || new Date().getFullYear());
    const reconciliationDate = state.selectedReconciliationDate || todayInputDate();
    state.selectedReconciliationDate = reconciliationDate;
    const [statistics, reconciliation] = await Promise.all([
      api(`${endpoints.statistics}?year=${state.selectedStatsYear}`),
      api(`${endpoints.dailyReconciliation}?date=${encodeURIComponent(reconciliationDate)}`)
    ]);
    state.data.statistics = statistics;
    state.data.dailyReconciliation = reconciliation;
  }

  async function loadDailyReconciliation(date) {
    const selectedDate = String(date || state.selectedReconciliationDate || todayInputDate());
    state.selectedReconciliationDate = selectedDate;
    state.data.dailyReconciliation = await api(`${endpoints.dailyReconciliation}?date=${encodeURIComponent(selectedDate)}`);
  }

  async function fetchConfigValues(itemId) {
    if (!itemId) return [];
    return api(`/api/config/items/${itemId}/values`);
  }

  async function fetchVehicleConfigValues(itemId) {
    if (!itemId) return [];
    return api(endpoints.vehicleConfigValue.listByItem(itemId));
  }

  async function fetchConfigValueMap(itemIds = []) {
    const ids = [...new Set((itemIds || [])
      .map(id => Number(id))
      .filter(id => Number.isFinite(id) && id > 0))];
    if (!ids.length) return {};
    const params = new URLSearchParams();
    ids.forEach(id => params.append("itemIds", String(id)));
    return api(`${endpoints.configValue.listByItems}?${params.toString()}`);
  }

  async function loadConfigValueCache(items = state.data.configItems) {
    const safeItems = items || [];
    const valueMap = await fetchConfigValueMap(safeItems.map(item => item.id));
    state.data.configValueMap = Object.fromEntries(safeItems.map(item => {
      const values = valueMap[item.id] || valueMap[String(item.id)] || [];
      return [item.id, sortById(values, false)];
    }));
  }

  async function loadConfigValues(itemId, options = {}) {
    state.selectedConfigItemId = Number(itemId);
    const values = sortById(await fetchConfigValues(itemId), false);
    state.data.configValueMap[state.selectedConfigItemId] = values;
    state.data.configValues = values;
    if (options.render !== false) {
      renderCurrentTab();
    }
    if (options.restoreConfigScroll) {
      restoreConfigItemScroll();
    }
  }

  async function loadVehicleConfigValues(itemId, options = {}) {
    state.selectedVehicleConfigItemId = Number(itemId);
    const values = sortById(await fetchVehicleConfigValues(itemId), false);
    state.data.vehicleConfigValues = values;
    if (options.render !== false) {
      renderCurrentTab();
    }
    if (options.restoreConfigScroll) {
      restoreVehicleConfigItemScroll();
    }
  }

  return {
    ensureConfigData,
    fetchConfigValues,
    fetchVehicleConfigValues,
    fetchConfigValueMap,
    loadAllData,
    loadConfigValueCache,
    loadConfigValues,
    loadVehicleConfigValues,
    loadCurrentTab,
    loadListSummary,
    loadPagedTab,
    loadStatistics,
    loadDailyReconciliation,
    loadTodoCenter,
    loadVehicleFlowReferences,
    loadVehicleModelData
  };
}
