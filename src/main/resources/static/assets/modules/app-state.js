import { createPageState } from "./paging.js";

export const LOG_PAGE_SIZE = 120;
export const LIST_PAGE_SIZE = 20;

export function createInitialState({ token = "", user = null } = {}) {
  return {
    token,
    user,
    activeTab: "overview",
    search: {
      vehicles: "",
      parts: "",
      modificationOrders: "",
      outboundOrders: "",
      attachments: "",
      imports: "",
      rentals: "",
      customers: "",
      suppliers: "",
      purchases: "",
      stocktakes: "",
      warehouses: "",
      stockMovements: "",
      repairs: "",
      stats: "",
      logs: "",
      configItems: "",
      configValues: "",
      vehicleConfigItems: "",
      vehicleConfigValues: "",
      users: ""
    },
    filters: {
      vehicles: {
        power: "",
        supplier: "",
        stock: ""
      },
      parts: {
        stock: ""
      },
      outboundOrders: {
        stage: ""
      },
      rentals: {
        status: ""
      },
      repairs: {
        status: ""
      },
      purchases: {
        resourceType: ""
      },
      attachments: {
        resourceType: "",
        category: "",
        includeDeleted: ""
      },
      imports: {
        importType: ""
      }
    },
    sorts: {},
    data: {
      vehicleModels: [],
      vehicles: [],
      parts: [],
      modificationOrders: [],
      outboundOrders: [],
      attachments: [],
      importJobs: [],
      todoCenter: null,
      rentals: [],
      customers: [],
      suppliers: [],
      purchaseOrders: [],
      stocktakingRecords: [],
      warehouses: [],
      stockMovements: [],
      repairs: [],
      statistics: null,
      dailyReconciliation: null,
      operationLogs: [],
      summaries: {},
      configItems: [],
      configValues: [],
      configValueMap: {},
      vehicleConfigItems: [],
      vehicleConfigValues: [],
      repairUsers: [],
      users: []
    },
    pages: {
      vehicles: createPageState(LIST_PAGE_SIZE),
      parts: createPageState(LIST_PAGE_SIZE),
      modificationOrders: createPageState(LIST_PAGE_SIZE),
      outboundOrders: createPageState(LIST_PAGE_SIZE),
      attachments: createPageState(LIST_PAGE_SIZE),
      imports: createPageState(LIST_PAGE_SIZE),
      rentals: createPageState(LIST_PAGE_SIZE),
      customers: createPageState(LIST_PAGE_SIZE),
      suppliers: createPageState(LIST_PAGE_SIZE),
      purchases: createPageState(LIST_PAGE_SIZE),
      stocktakes: createPageState(LIST_PAGE_SIZE),
      warehouses: createPageState(LIST_PAGE_SIZE),
      stockMovements: createPageState(LIST_PAGE_SIZE),
      repairs: createPageState(LIST_PAGE_SIZE),
      logs: createPageState(LOG_PAGE_SIZE),
      users: createPageState(LIST_PAGE_SIZE)
    },
    reference: {
      vehiclesLoaded: false,
      partsLoaded: false,
      customersLoaded: false,
      suppliersLoaded: false,
      warehousesLoaded: false,
      outboundOrdersLoaded: false,
      rentalsLoaded: false,
      repairUsersLoaded: false
    },
    selectedVehicleId: null,
    vehicleDetail: null,
    vehicleDetailLoading: false,
    pendingVehicleModelKey: "",
    vehicleDetailScrollTop: 0,
    selectedConfigItemId: null,
    selectedVehicleConfigItemId: null,
    configItemScrollTop: 0,
    vehicleConfigItemScrollTop: 0,
    activeConfigModule: "parts",
    selectedStatsYear: new Date().getFullYear(),
    visibleLogRows: LOG_PAGE_SIZE,
    detailDrawer: null,
    batchModes: {},
    batchSelections: {},
    overviewQueueGroup: "finance",
    overviewQueueKey: "",
    statisticsReport: "yearly",
    importValidation: null,
    importSelectedType: "vehicle-workbook",
    importSelectedMode: "BUSINESS_DOCUMENT",
    selectedReconciliationDate: "",
    vehicleDetailTab: "archive",
    modal: null
  };
}
