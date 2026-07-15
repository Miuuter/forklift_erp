import "./modules/client-reset-query.js";
import { clearSession, createApiClient, readStoredToken, readStoredUser, saveSession } from "./modules/session.js";
import { endpoints } from "./modules/routes.js";
import { resetPage } from "./modules/paging.js";
import { createInitialState, LOG_PAGE_SIZE, LIST_PAGE_SIZE } from "./modules/app-state.js";
import { activeTabPageKeys, pagedTabs, summaryTabs } from "./modules/list-config.js";
import { icons, tabs } from "./modules/ui-config.js";
import { createFields } from "./modules/field-config.js";
import { dateTime, dateValue, display, emptyState, escapeAttr, escapeHtml, fileSize, filterRows, money, normalizeText, nowInputDateTime, sortById, sortLogs, todayInputDate, toInputValue } from "./modules/display-utils.js";
import { badge, jobTagBadge, jobTagLabel, jobTagType, logCategoryBadge, modificationStatusBadge, nextJobTag, normalizeJobTag, operationActionBadge, purchaseStatusBadge, purchaseStatusLabel, quantityChange, rentalStatusBadge, repairStatusText, resourceTypeLabel, roleBadges, statusBadge, stockBadge, stockStatusBadge, stockStatusLabel, stockText, yesNoBadge } from "./modules/status-ui.js";
import { createDataLoaders, referencePageUrl, rowsFromPayload } from "./modules/data-loader.js";
import { createDashboardView } from "./modules/dashboard-view.js";
import { createMutationRefresh } from "./modules/mutation-refresh.js";
import { createModalRenderer, setModalSubmitting } from "./modules/modal-renderer.js";
import { createDetailDrawer } from "./modules/detail-drawer.js";
import { createCommandPalette } from "./modules/command-palette.js";
import { createOverlayManager } from "./modules/ui/overlay-manager.js";
import { createConfirmDialog } from "./modules/ui/confirm-dialog.js";
import { createDataTable } from "./modules/ui/data-table.js";
import { buildFormWorkspaceConfig } from "./modules/ui/form-workspace.js";
import { createActionRegistry } from "./modules/ui/action-registry.js";
import { createDrawerActions } from "./modules/ui/drawer-actions.js";
import { decorateShell, syncUserShell } from "./modules/ui/shell-decorator.js";
import { createSummaryCardRenderer } from "./modules/ui/summary-card.js";
import { ensureVehicleDetailTab as normalizeVehicleDetailTab, VEHICLE_DETAIL_TABS } from "./modules/ui/state-utils.js";
import { createAttachmentWorkflow } from "./modules/workflows/attachments-workflow.js";
import { createImportWorkflow } from "./modules/workflows/imports-workflow.js";
import { createUserWorkflow } from "./modules/workflows/users-workflow.js";
import { createPartsWorkflow } from "./modules/workflows/parts-workflow.js";
import { createRentalsWorkflow } from "./modules/workflows/rentals-workflow.js";
import { buildVehicleOutboundOrderPayload, createOutboundWorkflow } from "./modules/workflows/outbound-workflow.js";
import { createRepairsWorkflow } from "./modules/workflows/repairs-workflow.js";
import { createStatisticsWorkflow } from "./modules/workflows/statistics-workflow.js";
import { createVehicleWorkflow } from "./modules/workflows/vehicle-workflow.js";
import { createConfigWorkflow } from "./modules/workflows/configs-workflow.js";
import { createOperationsWorkflow } from "./modules/workflows/operations-workflow.js";
import { createDownloadActions } from "./modules/downloads.js";
import { createVehicleDetailLoader } from "./modules/vehicle-detail-loader.js";
import { createRequestId } from "./modules/request-id.js";
import { versionedBatchPayload } from "./modules/batch-operations.js";
import { createAccessControl, normalizeUser } from "./modules/access-control.js";
import { createFilterOptions } from "./modules/filter-options.js";
import { createEntityOptions } from "./modules/entity-options.js";

const LIST_STATE_STORAGE_KEY = "forklift-erp:list-state:v1";
const DETAIL_DRAWER_TRANSITION_MS = 240;
const REMOTE_COMBO_DEBOUNCE_MS = 250;
const REMOTE_COMBO_PAGE_SIZE = 30;

const state = createInitialState({
  token: readStoredToken(),
  user: normalizeUser(readStoredUser())
});
restorePersistedListState();

const api = createApiClient(() => state.token);
const { hasRole, hasAnyRole, hasPermission, hasAnyPermission, canAccessTab, canWriteEntity } = createAccessControl({ state });
const { configItemOptions, vehicleConfigItemOptions, vehicleConfigValueOptions, compareConfigItems, configValueOptionsForItem, powerTypeOptions, supplierFilterOptions, stockFilterOptions, filterPartRows, filterOutboundOrderRows, filterRentalRows, filterRepairRows, rentalDaysUntilEnd, dateOnlyTime, vehicleCategoryOptions, configSubCategoryOptions, configPartCategoryOptions, configValueOptions, configValuesWithItems, configItemLabel, configPartCategory, uniqueOptions, normalizePowerType, isManualForklift, vehicleModelKey } = createFilterOptions({
  state, effectiveFieldValue, normalizeText,
  isInvoiceUploadReady: (...args) => isInvoiceUploadReady(...args),
  isContractUploadReady: (...args) => isContractUploadReady(...args)
});
const { decodeVehicleModelKey, vehicleModelLabel, vehicleNumberLabel, vehiclePartDefaultsForMachine, vehiclePartInstallDefaultsForMachine, vehicleModelGroups, vehiclesForModelKey, prepareVehicleModelSummary, modelSummaryForKey, fetchVehicleModelVehicles, vehicleModelOptions, vehicleNumberOptions, vehicleOutboundOptions, canOutboundVehicle, vehicleRentalOptions, vehicleOptions, customerOptions, customerEntryModeOptions, supplierOptions, purchaseResourceTypeOptions, purchaseResourceOptions, purchaseStatusOptions, purchaseConfigValueOptions, purchaseSpecificationModelOptions, stocktakingResourceTypeOptions, stocktakingResourceOptions, stocktakingStatusOptions, warehouseOptions, warehouseNameById, transferResourceTypeOptions, stockTransferResourceOptions, stockTransferResourceName, partCodeOptions, machineConfigOptions, machineConfigOptionLabel, machineConfigDictionaryLabel, compatiblePartOptions, discountConfigValueOptions, installPartCategoryOptions, installPartOptions, statusOptions, stockStatusOptions, oldPartActionOptions, inputTypeOptions, repairPartOptions, repairPersonOptions, rentalStatusOptions, paymentReversalOptions } = createEntityOptions({
  state, api, endpoints, sortById, effectiveFieldValue, stockStatusLabel,
  activeRentalForMachine, purchaseOrderResourceType, normalizeText, setPrefill,
  dateValue, money, vehicleModelKey, normalizePowerType,
  configValueOptionsForItem, configItemLabel, configPartCategory, compareConfigItems
});

const summaryCard = createSummaryCardRenderer({ icons, escapeAttr, escapeHtml });

let els;
let searchReloadTimer = null;
let remoteComboSearchTimer = null;
let remoteComboRequestSequence = 0;
let modalPointerDownStartedOnOverlay = false;
let detailPointerDownStartedOnOverlay = false;

const overlayManager = createOverlayManager({
  getBackgroundRoots: () => [els?.appScreen, els?.loginScreen].filter(Boolean)
});

const { confirmDanger } = createConfirmDialog({
  overlayManager,
  escapeHtml,
  icon
});

const dataTable = createDataTable({
  state,
  escapeAttr,
  escapeHtml,
  display,
  icon,
  renderEmptyState: emptyState,
  createListEmptyState,
  entityLabel,
  selectedIdSet,
  renderBatchToolbar,
  batchActionsForKind,
  isBatchMode
});

const {
  listTableOptions,
  renderTable,
  applyTableSort,
  renderSelectableAttrs
} = dataTable;

const {
  clearVehicleSelection,
  loadVehicleDetail,
  loadVehicleModelDetail
} = createVehicleDetailLoader({
  state,
  api,
  hasPermission,
  sortById,
  fetchVehicleModelVehicles,
  modelSummaryForKey,
  decodeVehicleModelKey,
  ensureVehicleDetailTab: () => normalizeVehicleDetailTab(state),
  renderCurrentTab
});

const entityActionRegistry = createActionRegistry({
  escapeAttr,
  escapeHtml,
  icon,
  canWriteEntity,
  hasPermission,
  hasAnyRole
});

const {
  fetchProtectedBlob,
  downloadInvoice,
  downloadContract,
  downloadExcel,
  downloadProtectedFile,
  downloadDataBackup
} = createDownloadActions({
  getToken: () => state.token,
  endpoints,
  todayInputDate,
  showToast
});

const {
  ensureConfigData,
  loadAllData,
  loadConfigValues,
  loadVehicleConfigValues,
  loadCurrentTab,
  loadPagedTab,
  loadStatistics,
  loadDailyReconciliation,
  loadTodoCenter,
  loadVehicleModelData
} = createDataLoaders({
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
});

const {
  markReferenceDataStale,
  referenceKindsForMutation,
  tabsForMutation,
  resetPageAfterMutation,
  refreshAfterMutation,
  refreshSelectedVehicleDetail,
  mutationTouchesTodoCenter,
  mutationTouchesVehicleDetail,
  mutationTouchesFinance
} = createMutationRefresh({
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
});

const attachmentWorkflow = createAttachmentWorkflow({
  state,
  api,
  endpoints,
  resetPage,
  loadPagedTab,
  renderCurrentTab,
  fetchProtectedBlob,
  downloadProtectedFile,
  showToast,
  showContextSuccess,
  confirmDanger,
  entityDisplayName,
  findEntity,
  filterRows,
  pageTotal,
  renderToolbar,
  renderSurface,
  renderTable,
  listTableOptions,
  renderPagination,
  filterButtonGroup,
  summaryCard,
  emptyState,
  badge,
  escapeAttr,
  escapeHtml,
  fileSize,
  dateTime,
  display,
  icon,
  resourceTypeLabel,
  vehicleNumberLabel,
  hasPermission
});

const importWorkflow = createImportWorkflow({
  state,
  api,
  endpoints,
  resetPage,
  getContentElement: () => els?.content,
  loadPagedTab,
  loadAllData,
  renderCurrentTab,
  downloadProtectedFile,
  showToast,
  confirmDanger,
  entityDisplayName,
  findEntity,
  markReferenceDataStale,
  filterRows,
  hasRole,
  pageTotal,
  renderToolbar,
  renderSurface,
  renderTable,
  listTableOptions,
  renderPagination,
  filterButtonGroup,
  summaryCard,
  emptyState,
  badge,
  escapeAttr,
  escapeHtml,
  dateTime,
  display,
  icon
});

const userWorkflow = createUserWorkflow({
  state,
  api,
  endpoints,
  openEntityModal,
  markReferenceDataStale,
  refreshAfterMutation,
  renderCurrentTab,
  showToast,
  confirmDanger,
  entityDisplayName,
  findEntity,
  filterRows,
  hasRole,
  hasPermission,
  renderToolbar,
  renderSurface,
  renderTable,
  listTableOptions,
  renderPagination,
  roleBadges,
  jobTagBadge,
  jobTagLabel,
  jobTagType,
  nextJobTag,
  normalizeJobTag,
  badge,
  emptyState,
  escapeAttr,
  escapeHtml,
  dateTime,
  icon
});

const {
  uploadOrderManagedAttachment,
  uploadAttachmentsFromModal,
  previewAttachment,
  downloadAttachment,
  deleteAttachment,
  renderAttachments,
  attachmentAllResourceTypeOptions,
  attachmentResourceTypeOptions,
  attachmentCategoryOptions,
  attachmentUploadCategoryOptions,
  attachmentResourceOptions,
  attachmentDefaultCategoryForResourceType,
  attachmentResourceTypeLabel,
  attachmentCategoryLabel,
  canManageAttachmentType,
  attachmentResourceTypeForKind
} = attachmentWorkflow;

const {
  downloadImportTemplate,
  validateImportFromPage,
  confirmImportJob,
  renderImports,
  importTypeOptions,
  importTypeLabel
} = importWorkflow;

const {
  openUserJobTag,
  toggleUserJobTag,
  toggleUserEnabled,
  renderUsers,
  jobTagControl,
  userEnabledControl,
  userActions,
  userRoleOptions,
  jobTagOptions,
  canUpdateUserJobTag,
  canUpdateUserEnabled
} = userWorkflow;

const fields = createFields({
  nowInputDateTime,
  todayInputDate,
  powerTypeOptions,
  stockStatusOptions,
  configValueOptions,
  configPartCategoryOptions,
  vehicleOptions,
  customerOptions,
  supplierOptions,
  repairPersonOptions,
  repairPartOptions,
  statusOptions,
  vehicleCategoryOptions,
  configSubCategoryOptions,
  nextConfigItemCode,
  inputTypeOptions,
  configItemOptions,
  customerEntryModeOptions,
  vehicleOutboundOptions,
  partCodeOptions,
  machineConfigOptions,
  compatiblePartOptions,
  discountConfigValueOptions,
  oldPartActionOptions,
  installPartCategoryOptions,
  installPartOptions,
  vehicleModelOptions,
  vehicleRentalOptions,
  vehicleNumberOptions,
  userRoleOptions,
  jobTagOptions,
  rentalStatusOptions,
  purchaseConfigValueOptions,
  purchaseResourceOptions,
  purchaseSpecificationModelOptions,
  purchaseStatusOptions,
  paymentReversalOptions,
  stocktakingResourceTypeOptions,
  stocktakingResourceOptions,
  stocktakingStatusOptions,
  warehouseOptions,
  transferResourceTypeOptions,
  stockTransferResourceOptions,
  attachmentResourceTypeOptions,
  attachmentResourceOptions,
  attachmentCategoryOptions,
  attachmentUploadCategoryOptions,
  vehicleConfigChangeModeOptions,
  vehicleConfigItemOptions,
  vehicleConfigValueOptions
});


const partsWorkflow = createPartsWorkflow({
  state,
  filterRows,
  filterPartRows,
  renderToolbar,
  partFilterControls,
  hasPermission,
  hasAnyRole,
  icon,
  renderExportableSurface,
  renderTable,
  stockBadge,
  money,
  rowActions,
  listTableOptions,
  renderPagination
});

const rentalsWorkflow = createRentalsWorkflow({
  state,
  filterRows,
  filterRentalRows,
  renderBackendSummary,
  summaryCard,
  money,
  renderToolbar,
  rentalFilterControls,
  renderExportableSurface,
  renderTable,
  rentalStatusBadge,
  rowActions,
  listTableOptions,
  renderPagination,
  escapeHtml,
  dateTime,
  dateValue
});

const {
  renderRentals,
  rentalMonthlyPrice,
  rentalAccruedIncome,
  rentalPeriodSummary
} = rentalsWorkflow;

const outboundWorkflow = createOutboundWorkflow({
  state,
  filterRows,
  filterOutboundOrderRows,
  renderBackendSummary,
  summaryCard,
  money,
  renderToolbar,
  outboundOrderFilterControls,
  renderExportableSurface,
  renderTable,
  listTableOptions,
  renderPagination,
  dateValue,
  fileSize,
  statusBadge,
  badge,
  hasPermission,
  hasAnyRole,
  escapeAttr,
  escapeHtml,
  icon
});

const {
  renderOutboundOrders,
  receivableOutstanding,
  receivableOutstandingTotal,
  isInvoiceUploadReady,
  isContractUploadReady,
  yesNoFromStatusText,
  outboundOrderActions,
  canManageOrderLock
} = outboundWorkflow;

const repairsWorkflow = createRepairsWorkflow({
  state,
  filterRows,
  filterRepairRows,
  renderToolbar,
  repairFilterControls,
  renderExportableSurface,
  renderTable,
  listTableOptions,
  renderPagination,
  dateTime,
  money,
  rowActions,
  activeRentalForMachine,
  hasPermission,
  statusBadge,
  repairStatusText,
  badge,
  escapeAttr,
  escapeHtml
});

const {
  renderParts
} = partsWorkflow;

const {
  renderRepairs,
  repairCustomerSummary,
  repairPersonSummary,
  repairStatusToggle
} = repairsWorkflow;

const operationsWorkflow = createOperationsWorkflow({
  state,
  LOG_PAGE_SIZE,
  filterRows,
  pageTotal,
  renderBackendSummary,
  renderToolbar,
  renderExportableSurface,
  renderSurface,
  renderTable,
  listTableOptions,
  renderPagination,
  summaryCard,
  emptyState,
  badge,
  icon,
  escapeAttr,
  escapeHtml,
  dateTime,
  dateValue,
  display,
  money,
  stockText,
  resourceTypeLabel,
  hasRole,
  hasPermission,
  rowActions,
  modificationStatusBadge,
  repairStatusText,
  purchaseOrderFilterControls,
  purchaseOrderSummary,
  purchaseResourceSummary,
  purchaseStatusControl,
  stocktakingSummary,
  stocktakingDifference,
  stocktakingStatusBadge,
  stocktakingActions,
  uniqueSupplierCount,
  logCategoryBadge,
  operationActionBadge,
  quantityChange
});

const {
  renderModificationOrders,
  renderCustomers,
  renderSuppliers,
  renderPurchaseOrders,
  renderStocktakes,
  renderWarehouses,
  renderOperationLogs,
  renderMaintenance,
  modificationOrderActions,
  modificationLineSummary
} = operationsWorkflow;

const vehicleWorkflow = createVehicleWorkflow({
  state,
  vehicleDetailTabs: VEHICLE_DETAIL_TABS,
  ensureVehicleDetailTab: () => normalizeVehicleDetailTab(state),
  activeRentalForMachine,
  latestVehicleOutboundOrder,
  yesNoFromText,
  yesNoText,
  renderToolbar,
  renderSurface,
  renderTable,
  renderDetailGrid,
  detailItem,
  renderPagination,
  filterButtonGroup,
  hasPermission,
  hasAnyRole,
  icon,
  escapeAttr,
  escapeHtml,
  dateValue,
  dateTime,
  fileSize,
  money,
  emptyState,
  badge,
  stockBadge,
  stockStatusBadge,
  stockStatusLabel,
  rentalMonthlyPrice,
  rentalStatusBadge,
  modificationStatusBadge,
  modificationLineSummary,
  modificationOrderActions,
  repairCustomerSummary,
  repairPersonSummary,
  repairStatusToggle,
  rowActions,
  receivableOutstanding,
  isInvoiceUploadReady,
  isContractUploadReady,
  isManualForklift,
  vehicleModelLabel,
  powerTypeOptions,
  stockFilterOptions,
  supplierFilterOptions,
  normalizePowerType
});

const {
  renderVehicles,
  vehicleFlowRows,
  vehicleFlowMachineSummary,
  vehicleFlowStatusSummary,
  vehicleFlowFollowupSummary,
  vehicleFlowActions,
  ensureVehicleDetailTab
} = vehicleWorkflow;

const configWorkflow = createConfigWorkflow({
  state,
  filterRows,
  renderToolbar,
  renderSurface,
  renderTable,
  renderSelectableAttrs,
  rowActions,
  hasPermission,
  badge,
  emptyState,
  escapeAttr,
  escapeHtml,
  icon
});

const {
  renderConfigsV2
} = configWorkflow;

const dashboardView = createDashboardView({
  state,
  hasPermission,
  pageTotal,
  vehicleFlowRows,
  renderSurface,
  summaryCard,
  money,
  badge,
  emptyState,
  escapeAttr,
  escapeHtml,
  dateValue,
  stockText,
  icon,
  renderTable,
  renderYearOptions,
  renderFinanceTrend,
  compactTable,
  vehicleModelLabel,
  repairStatusText,
  rentalStatusBadge,
  resourceTypeLabel,
  isInvoiceUploadReady,
  isContractUploadReady,
  receivableOutstandingTotal,
  vehicleFlowMachineSummary,
  vehicleFlowStatusSummary,
  vehicleFlowFollowupSummary,
  vehicleFlowActions,
  toDataAttrName,
  repairStatusToggle
});

const statisticsWorkflow = createStatisticsWorkflow({
  dashboardView
});

const {
  renderStatistics
} = statisticsWorkflow;

const { closeModal, requestCloseModal, renderModal } = createModalRenderer({
  state,
  getEls: () => els,
  overlayManager,
  resetModalPointerDown: () => {
    modalPointerDownStartedOnOverlay = false;
  },
  modalTitle,
  modalSubtitle,
  getFields,
  renderModalFields,
  usesVehicleInboundConfigEditor,
  renderConfigSelectionEditor,
  canSaveAndContinue,
  formWorkspaceConfig: (kind, modalFields) => buildFormWorkspaceConfig(kind, modalFields, canSaveAndContinue, state.modal?.item),
  confirmDiscard: () => confirmDanger({
    title: "放弃未保存的修改？",
    target: modalTitle(state.modal?.kind, state.modal?.item),
    impact: "当前表单中尚未保存的输入将丢失。",
    confirmText: "放弃修改"
  }),
  icon,
  escapeAttr,
  escapeHtml
});

const drawerActions = createDrawerActions({
  hasPermission,
  canUpdateUserJobTag,
  canUpdateUserEnabled,
  rowActions,
  modificationOrderActions,
  outboundOrderActions,
  yesNoFromStatusText,
  stocktakingActions,
  attachmentActions: attachmentWorkflow.attachmentActions,
  importJobActions: importWorkflow.importJobActions,
  userActions,
  escapeAttr,
  escapeHtml,
  icon
});

const { openDetailDrawer, closeDetailDrawer, renderDetailDrawer } = createDetailDrawer({
  state,
  getEls: () => els,
  overlayManager,
  transitionMs: DETAIL_DRAWER_TRANSITION_MS,
  findEntity,
  renderCurrentTab,
  detailTitle,
  entityLabel,
  detailFields,
  renderDetailGrid,
  renderDetailDrawerActions: drawerActions.render,
  icon,
  escapeHtml
});

const { openCommandPalette, closeCommandPalette } = createCommandPalette({
  state,
  getEls: () => els,
  icons,
  tabs,
  normalizeText,
  escapeAttr,
  escapeHtml,
  resetPage,
  defaultEntity,
  vehicleInboundDefaultsForModel,
  openEntityModal,
  canAccessTab,
  goToTab,
  renderLoading,
  loadCurrentTab,
  loadVehicleDetail,
  renderCurrentTab,
  repairStatusText,
  handleActionError
});

document.addEventListener("DOMContentLoaded", () => {
  if (window.__FORKLIFT_CLIENT_RESETTING__) return;

  els = {
    loginScreen: document.getElementById("loginScreen"),
    appScreen: document.getElementById("appScreen"),
    loginForm: document.getElementById("loginForm"),
    mainNav: document.getElementById("mainNav"),
    pageTitle: document.getElementById("pageTitle"),
    pageSubtitle: document.getElementById("pageSubtitle"),
    currentUser: document.getElementById("currentUser"),
    currentUserRole: document.getElementById("currentUserRole"),
    userAvatar: document.getElementById("userAvatar"),
    globalSearchBtn: document.getElementById("globalSearchBtn"),
    newBusinessBtn: document.getElementById("newBusinessBtn"),
    newBusinessMenu: document.getElementById("newBusinessMenu"),
    userMenuBtn: document.getElementById("userMenuBtn"),
    userMenuChevron: document.querySelector("[data-user-menu-chevron]"),
    userMenu: document.getElementById("userMenu"),
    switchUserBtn: document.getElementById("switchUserBtn"),
    logoutBtn: document.getElementById("logoutBtn"),
    content: document.getElementById("moduleContent"),
    modalOverlay: document.getElementById("modalOverlay"),
    modalCard: document.getElementById("modalCard"),
    detailDrawerOverlay: document.getElementById("detailDrawerOverlay"),
    detailDrawer: document.getElementById("detailDrawer"),
    toastHost: document.getElementById("toastHost")
  };

  decorateShell({ els, icons, escapeHtml });
  els.loginForm.addEventListener("submit", handleLogin);
  els.globalSearchBtn.addEventListener("click", openCommandPalette);
  els.newBusinessBtn.addEventListener("click", () => toggleTopbarMenu(els.newBusinessBtn, els.newBusinessMenu));
  els.userMenuBtn.addEventListener("click", () => toggleTopbarMenu(els.userMenuBtn, els.userMenu));
  els.newBusinessMenu.addEventListener("click", handleTopbarBusinessAction);
  els.switchUserBtn.addEventListener("click", () => openEntityModal("switchUser", { username: "" }));
  els.logoutBtn.addEventListener("click", () => logout("已退出登录"));
  els.mainNav.addEventListener("click", handleNav);
  els.content.addEventListener("click", handleContentClick);
  els.content.addEventListener("keydown", handleContentKeydown);
  els.content.addEventListener("pointerover", handleContentPointerOver);
  els.content.addEventListener("pointerout", handleContentPointerOut);
  els.content.addEventListener("focusin", handleContentFocusIn);
  els.content.addEventListener("focusout", handleContentFocusOut);
  els.content.addEventListener("input", handleContentInput);
  els.content.addEventListener("change", handleContentChange);
  els.modalOverlay.addEventListener("pointerdown", handleModalOverlayPointerDown);
  els.modalOverlay.addEventListener("click", handleOverlayClick);
  els.modalCard.addEventListener("click", handleModalClick);
  els.modalCard.addEventListener("input", handleModalInput);
  els.modalCard.addEventListener("focusin", handleModalFocusIn);
  els.modalCard.addEventListener("focusout", handleModalFocusOut);
  els.modalCard.addEventListener("change", handleModalChange);
  els.modalCard.addEventListener("submit", handleModalSubmit);
  els.modalCard.addEventListener("invalid", handleModalInvalid, true);
  els.detailDrawerOverlay.addEventListener("pointerdown", handleDetailDrawerOverlayPointerDown);
  els.detailDrawerOverlay.addEventListener("click", handleDetailDrawerOverlayClick);
  els.detailDrawer.addEventListener("click", handleDetailDrawerClick);
  document.addEventListener("click", event => {
    if (!event.target.closest(".topbar-create, .user-menu-wrap")) closeTopbarMenus();
  });
  document.addEventListener("keydown", event => {
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "k" && !state.modal) {
      event.preventDefault();
      openCommandPalette();
    }
  });

  if (state.token) {
    enterApp();
  } else {
    showLogin();
  }

  registerClientWorker();
});

function toggleTopbarMenu(button, menu) {
  const opening = !menu.classList.contains("is-open");
  closeTopbarMenus();
  menu.classList.toggle("is-open", opening);
  button.setAttribute("aria-expanded", opening ? "true" : "false");
}

function closeTopbarMenus() {
  [
    [els?.newBusinessBtn, els?.newBusinessMenu],
    [els?.userMenuBtn, els?.userMenu]
  ].forEach(([button, menu]) => {
    menu?.classList.remove("is-open");
    button?.setAttribute("aria-expanded", "false");
  });
}

async function handleTopbarBusinessAction(event) {
  const control = event.target.closest("[data-action]");
  if (!control) return;
  closeTopbarMenus();
  await handleContentClick({ target: control });
}

function registerClientWorker() {
  if (!("serviceWorker" in navigator)) return;
  if (["localhost", "127.0.0.1", "::1"].includes(window.location.hostname)) return;
  navigator.serviceWorker.register("/sw.js").catch(() => {});
}

function restorePersistedListState() {
  let persisted = null;
  try {
    persisted = JSON.parse(localStorage.getItem(LIST_STATE_STORAGE_KEY) || "null");
  } catch (error) {
    return;
  }
  if (!persisted || typeof persisted !== "object") return;
  if (persisted.activeTab && tabs[persisted.activeTab]) {
    state.activeTab = persisted.activeTab;
  }
  if (persisted.search && typeof persisted.search === "object") {
    state.search = { ...state.search, ...pickKnownKeys(persisted.search, state.search) };
  }
  if (persisted.filters && typeof persisted.filters === "object") {
    state.filters = {
      ...state.filters,
      ...Object.fromEntries(Object.entries(persisted.filters).map(([key, value]) => [
        key,
        { ...(state.filters[key] || {}), ...(value || {}) }
      ]))
    };
  }
  if (persisted.pages && typeof persisted.pages === "object") {
    for (const [key, value] of Object.entries(persisted.pages)) {
      if (!state.pages[key] || !value) continue;
      state.pages[key].page = Math.max(0, Number(value.page || 0));
      state.pages[key].size = Math.max(1, Number(value.size || state.pages[key].size));
    }
  }
  if (persisted.sorts && typeof persisted.sorts === "object") {
    state.sorts = persisted.sorts;
  }
  if (Number.isFinite(Number(persisted.selectedStatsYear))) {
    state.selectedStatsYear = Number(persisted.selectedStatsYear);
  }
  if (Number.isFinite(Number(persisted.visibleLogRows))) {
    state.visibleLogRows = Math.max(LOG_PAGE_SIZE, Number(persisted.visibleLogRows));
  }
  if (typeof persisted.overviewQueueGroup === "string") state.overviewQueueGroup = persisted.overviewQueueGroup;
  if (typeof persisted.overviewQueueKey === "string") state.overviewQueueKey = persisted.overviewQueueKey;
  if (typeof persisted.statisticsReport === "string") state.statisticsReport = persisted.statisticsReport;
}

function persistListState() {
  try {
    const pages = Object.fromEntries(Object.entries(state.pages).map(([key, page]) => [
      key,
      { page: Number(page.page || 0), size: Number(page.size || LIST_PAGE_SIZE) }
    ]));
    localStorage.setItem(LIST_STATE_STORAGE_KEY, JSON.stringify({
      activeTab: state.activeTab,
      search: state.search,
      filters: state.filters,
      pages,
      sorts: state.sorts || {},
      selectedStatsYear: state.selectedStatsYear,
      visibleLogRows: state.visibleLogRows,
      overviewQueueGroup: state.overviewQueueGroup,
      overviewQueueKey: state.overviewQueueKey,
      statisticsReport: state.statisticsReport
    }));
  } catch (error) {
    // Storage may be unavailable in private browsing or locked-down WebViews.
  }
}

function pickKnownKeys(source, targetShape) {
  return Object.fromEntries(Object.entries(source).filter(([key]) => Object.prototype.hasOwnProperty.call(targetShape, key)));
}

async function handleLogin(event) {
  event.preventDefault();
  const submitButton = event.currentTarget.querySelector("button[type='submit']");
  const form = new FormData(event.currentTarget);
  const username = String(form.get("username") || "").trim();
  const password = String(form.get("password") || "");

  setLoginPending(submitButton, true);
  try {
    const data = await api("/api/auth/login", {
      method: "POST",
      body: { username, password },
      auth: false
    });
    state.token = data.token;
    state.user = normalizeUser(data);
    saveSession(state.token, state.user);
    showToast("登录成功", "success");
    await enterApp();
  } catch (error) {
    showToast(error.message || "登录失败", "error");
  } finally {
    setLoginPending(submitButton, false);
  }
}

function setLoginPending(button, pending) {
  if (!button) return;
  button.disabled = pending;
  button.setAttribute("aria-busy", pending ? "true" : "false");
  const label = button.querySelector("[data-login-label]");
  if (label) label.textContent = pending ? "登录中..." : "登录";
}

async function enterApp() {
  els.loginScreen.classList.add("is-hidden");
  els.appScreen.classList.remove("is-hidden");
  els.content.innerHTML = renderLoading();
  syncShell();
  try {
    await loadAllData();
    renderCurrentTab();
  } catch (error) {
    if (isAuthExpiredError(error)) {
      logout("登录已过期，请重新登录");
      return;
    }
    console.error("初始化数据失败", error);
    els.content.innerHTML = renderLoadError(error);
    showToast(error.message || "初始化数据失败", "error");
  }
}

function showLogin() {
  els.appScreen.classList.add("is-hidden");
  els.loginScreen.classList.remove("is-hidden");
}

function logout(message) {
  state.token = "";
  state.user = null;
  clearVehicleSelection();
  state.detailDrawer = null;
  state.batchModes = {};
  state.batchSelections = {};
  clearSession();
  closeModal();
  closeDetailDrawer();
  showLogin();
  if (message) showToast(message, "info");
}

function restoreConfigItemScroll() {
  requestAnimationFrame(() => {
    const list = els.content.querySelector(".config-items-pane .config-list");
    if (list) list.scrollTop = state.configItemScrollTop || 0;
  });
}

function restoreVehicleConfigItemScroll() {
  requestAnimationFrame(() => {
    const list = els.content.querySelector(".vehicle-config-items-pane .config-list");
    if (list) list.scrollTop = state.vehicleConfigItemScrollTop || 0;
  });
}

function rememberVehicleDetailScroll() {
  const body = els.content.querySelector(".vehicle-detail-pane > .surface > .surface-body");
  state.vehicleDetailScrollTop = body ? body.scrollTop : 0;
}

function restoreVehicleDetailScroll() {
  requestAnimationFrame(() => {
    const body = els.content.querySelector(".vehicle-detail-pane > .surface > .surface-body");
    if (!body) return;
    body.scrollTop = state.vehicleDetailScrollTop || 0;
    body.querySelector(".detail-tab.is-active")?.focus({ preventScroll: true });
  });
}

async function handleNav(event) {
  const actionButton = event.target.closest("[data-action], [data-select-action]");
  if (actionButton) {
    await handleContentClick(event);
    return;
  }
  const button = event.target.closest("[data-tab]");
  if (!button) return;
  if (button.dataset.tab === state.activeTab) {
    scrollToWorkspaceTop();
    return;
  }
  state.activeTab = button.dataset.tab;
  els.content.innerHTML = renderLoading();
  syncShell();
  try {
    await loadCurrentTab({ force: true });
    renderCurrentTab();
  } catch (error) {
    handleActionError(error);
  }
  scrollToWorkspaceTop();
}

async function handleContentClick(event) {
  const control = event.target.closest("[data-action], [data-select-action]");
  if (!control) return;

  const action = control.dataset.action || control.dataset.selectAction;
  const kind = control.dataset.kind;
  const id = control.dataset.id ? Number(control.dataset.id) : null;

  try {
    if (action === "refresh") {
      els.content.innerHTML = renderLoading();
      await loadAllData();
      renderCurrentTab();
      showToast("数据已刷新", "success");
      return;
    }
    if (action === "clear-search") {
      const key = control.dataset.searchFor;
      if (!key) return;
      state.search[key] = "";
      const tab = tabForSearchKey(key);
      if (key === "logs") state.visibleLogRows = LOG_PAGE_SIZE;
      if (tab && tab === activePagedTab()) {
        resetPage(state.pages[tab]);
        if (tab === "logs" && state.pages.stockMovements) resetPage(state.pages.stockMovements);
        if (tab === "vehicles") {
          clearVehicleSelection();
          const request = loadVehicleModelData({ force: true });
          renderCurrentTab();
          await request;
        } else if (tab === "logs") {
          els.content.innerHTML = renderLoading();
          await loadCurrentTab({ force: true });
        } else {
          els.content.innerHTML = renderLoading();
          await loadPagedTab(tab, { force: true });
        }
      }
      renderCurrentTab();
      const nextInput = els.content.querySelector(`[data-search-for="${key}"]`);
      if (nextInput) nextInput.focus();
      return;
    }
    if (action === "open-detail") {
      openDetailDrawer(kind, id);
      return;
    }
    if (action === "close-detail") {
      closeDetailDrawer();
      return;
    }
    if (action === "apply-filter") {
      const key = control.dataset.filterFor;
      const name = control.dataset.filterName;
      if (!key || !name) return;
      state.filters[key] = {
        ...(state.filters[key] || {}),
        [name]: control.dataset.filterValue || ""
      };
      if (key === "attachments" && name === "resourceType") {
        state.filters.attachments.resourceId = "";
      }
      if (state.pages[key]) resetPage(state.pages[key]);
      if (pagedTabs[key] && key === activePagedTab()) {
        if (key === "vehicles") {
          clearVehicleSelection();
          const request = loadVehicleModelData({ force: true });
          renderCurrentTab();
          await request;
        } else {
          els.content.innerHTML = renderLoading();
          await loadPagedTab(key, { force: true });
        }
        renderCurrentTab();
        return;
      }
      renderCurrentTab();
      return;
    }
    if (action === "set-overview-queue-group") {
      state.overviewQueueGroup = control.dataset.group || "finance";
      state.overviewQueueKey = "";
      renderCurrentTab();
      return;
    }
    if (action === "set-overview-queue") {
      state.overviewQueueKey = control.dataset.queue || "";
      renderCurrentTab();
      return;
    }
    if (action === "set-statistics-report") {
      state.statisticsReport = control.dataset.report || "yearly";
      renderCurrentTab();
      return;
    }
    if (action === "sort-table") {
      applyTableSort(control.dataset.tableKey, control.dataset.sortKey);
      persistListState();
      renderCurrentTab();
      return;
    }
    if (action === "toggle-row-select") {
      toggleBatchSelection(control.dataset.batchKind, id, control.checked);
      renderCurrentTab();
      return;
    }
    if (action === "toggle-batch-mode") {
      toggleBatchMode(control.dataset.batchKind);
      renderCurrentTab();
      return;
    }
    if (action === "toggle-visible-select") {
      toggleVisibleBatchSelection(control.dataset.batchKind, (control.dataset.visibleIds || "").split(",").filter(Boolean), control.checked);
      renderCurrentTab();
      return;
    }
    if (action === "clear-batch-selection") {
      clearBatchSelection(control.dataset.batchKind);
      renderCurrentTab();
      return;
    }
    if (action === "batch-complete-repairs") {
      await batchCompleteRepairs();
      return;
    }
    if (action === "batch-receive-purchases") {
      await batchReceivePurchases();
      return;
    }
    if (action === "batch-complete-stocktakes") {
      await batchCompleteStocktakes();
      return;
    }
    if (action === "batch-delete-stocktake-drafts") {
      await batchDeleteStocktakeDrafts();
      return;
    }
    if (action === "export-excel") {
      await downloadExcel(control.dataset.exportType);
      return;
    }
    if (action === "download-backup") {
      await downloadDataBackup();
      return;
    }
    if (action === "restore-backup") {
      await openEntityModal("dataRestore", { confirmation: "RESTORE-DATA-BACKUP" });
      return;
    }
    if (action === "stock-transfer") {
      await openEntityModal("stockTransfer", {});
      return;
    }
    if (action === "quick-vehicle-inbound") {
      await openEntityModal("vehicleInbound", vehicleInboundDefaultsForModel({}));
      return;
    }
    if (action === "page-list") {
      const tab = control.dataset.pageTab;
      if (!pagedTabs[tab]) return;
      state.pages[tab].page = Math.max(0, Number(control.dataset.page || 0));
      if (tab === "vehicles") {
        clearVehicleSelection();
        const request = loadVehicleModelData({ force: true });
        renderCurrentTab();
        await request;
        renderCurrentTab();
        return;
      }
      els.content.innerHTML = renderLoading();
      const loadOptions = state.activeTab === "logs" && tab === "stockMovements"
        ? { force: true, keyword: state.search.logs || "" }
        : { force: true };
      await loadPagedTab(tab, loadOptions);
      renderCurrentTab();
      return;
    }
    if (action === "load-more-logs") {
      state.visibleLogRows += LOG_PAGE_SIZE;
      renderCurrentTab();
      return;
    }
    if (action === "create-vehicle-model") {
      await openEntityModal("vehicleModel", vehicleModelDefaults(control.dataset.powerType));
      return;
    }
    if (action === "create") {
      await openEntityModal(kind, defaultEntity(kind, control.dataset));
      return;
    }
    if (action === "model-inbound") {
      const group = modelSummaryForKey(control.dataset.modelKey);
      await openEntityModal("vehicleInbound", vehicleInboundDefaultsForModel(group));
      return;
    }
    if (action === "model-outbound") {
      const group = modelSummaryForKey(control.dataset.modelKey);
      await openEntityModal("vehicleOutbound", vehicleOutboundDefaultsForModel(group));
      return;
    }
    if (action === "vehicle-outbound-order") {
      const machine = findEntity("vehicle", Number(control.dataset.machineId || id || 0));
      await openEntityModal("vehicleOutbound", vehicleOutboundDefaultsForMachine(machine));
      return;
    }
    if (action === "vehicle-rental-direct") {
      const machine = findEntity("vehicle", Number(control.dataset.machineId || id || 0));
      await openEntityModal("rental", rentalDefaultsForMachine(machine));
      return;
    }
    if (action === "vehicle-stock") {
      const vehicle = findEntity("vehicle", id || Number(control.dataset.machineId || ""));
      if (["outbound", "adjustOutbound"].includes(control.dataset.direction) && !hasAnyRole("ADMIN", "SUPER_ADMIN")) {
        showToast("整车库存纠偏仅管理员可用", "error");
        return;
      }
      const modalItem = {
        version: vehicle.version,
        direction: control.dataset.direction
      };
      setPrefill(modalItem, "machineId", id || Number(control.dataset.machineId || ""), `预填：${vehicleNumberLabel(vehicle)}`);
      await openEntityModal("vehicleStock", modalItem);
      return;
    }
    if (action === "part-stock") {
      const part = state.data.parts.find(item => item.partCode === control.dataset.partCode) || {};
      const modalItem = {
        version: part.version,
        direction: control.dataset.direction
      };
      setPrefill(modalItem, "partCode", control.dataset.partCode || "", `预填：${part.partCode || ""} · ${part.partName || ""}`);
      if (control.dataset.direction === "outbound") {
        const price = part.settlementPrice || part.salePrice || "";
        setPrefill(modalItem, "unitSalePrice", price, price ? `预填：${money(price)}` : undefined);
      }
      await openEntityModal("partStock", modalItem);
      return;
    }
    if (action === "value-removed-part") {
      const part = findEntity("part", id);
      await openEntityModal("removedPartValuation", {
        id: part.id,
        version: part.version,
        sourceKind: "part",
        partLabel: entityDisplayName("part", part),
        unitCost: Number(part.landedUnitCost || part.purchasePrice || 0) > 0
          ? part.landedUnitCost || part.purchasePrice
          : "",
        businessDate: todayInputDate()
      });
      return;
    }
    if (action === "edit") {
      await openEntityModal(kind, findEntity(kind, id));
      return;
    }
    if (action === "upload-invoice") {
      const order = findEntity("outboundOrder", id);
      if (!isInvoiceUploadReady(order)) {
        showToast("请先将发票跟进改为已申请发票", "error");
        return;
      }
      await openEntityModal("invoiceUpload", order);
      return;
    }
    if (action === "download-invoice") {
      await downloadInvoice(findEntity("outboundOrder", id));
      return;
    }
    if (action === "upload-contract") {
      const order = findEntity("outboundOrder", id);
      if (!isContractUploadReady(order)) {
        showToast("请先将合同状态标记为有合同", "error");
        return;
      }
      await openEntityModal("contractUpload", order);
      return;
    }
    if (action === "download-contract") {
      await downloadContract(findEntity("outboundOrder", id));
      return;
    }
    if (action === "preview-attachment") {
      await previewAttachment(findEntity("attachment", id));
      return;
    }
    if (action === "download-attachment") {
      await downloadAttachment(findEntity("attachment", id));
      return;
    }
    if (action === "delete-attachment") {
      await deleteAttachment(findEntity("attachment", id));
      return;
    }
    if (action === "download-import-template") {
      await downloadImportTemplate(control.dataset.importType || state.filters.imports?.importType || "vehicle-workbook");
      return;
    }
    if (action === "validate-import-file") {
      await validateImportFromPage();
      return;
    }
    if (action === "confirm-import-job") {
      await confirmImportJob(id);
      return;
    }
    if (action === "clear-import-validation") {
      state.importValidation = null;
      renderCurrentTab();
      return;
    }
    if (action === "go-tab") {
      await goToTab(control.dataset.tab, control.dataset);
      return;
    }
    if (action === "toggle-order-status") {
      await toggleOutboundOrderStatus(id, control.dataset.field);
      return;
    }
    if (action === "toggle-repair-status") {
      await toggleRepairStatus(id);
      return;
    }
    if (action === "set-user-job-tag") {
      await openUserJobTag(id);
      return;
    }
    if (action === "toggle-user-job-tag") {
      await toggleUserJobTag(id);
      return;
    }
    if (action === "toggle-user-enabled") {
      await toggleUserEnabled(id);
      return;
    }
    if (action === "toggle-order-lock") {
      await toggleOutboundOrderLock(id, control.dataset.locked === "true");
      return;
    }
    if (action === "toggle-purchase-received") {
      await togglePurchaseReceived(id);
      return;
    }
    if (action === "purchase-freight") {
      await openEntityModal("purchaseFreight", findEntity("purchaseOrder", id));
      return;
    }
    if (action === "record-payment") {
      await openPaymentRecordModal(
        control.dataset.sourceType,
        Number(control.dataset.sourceId || id || 0),
        control.dataset.direction
      );
      return;
    }
    if (action === "reverse-payment") {
      await openPaymentReversalModal(
        control.dataset.sourceType,
        Number(control.dataset.sourceId || id || 0)
      );
      return;
    }
    if (action === "record-rental-payment") {
      await openRentalPaymentModal(Number(control.dataset.rentalId || id || 0));
      return;
    }
    if (action === "reverse-rental-payment") {
      await openRentalPaymentReversalModal(Number(control.dataset.rentalId || id || 0));
      return;
    }
    if (action === "complete-stocktaking") {
      await completeStocktaking(id);
      return;
    }
    if (action === "user-username") {
      await openEntityModal("userUsername", findEntity("user", id));
      return;
    }
    if (action === "user-password") {
      await openEntityModal("userPassword", findEntity("user", id));
      return;
    }
    if (action === "delete") {
      await deleteEntity(kind, id);
      return;
    }
    if (action === "detail-vehicle") {
      if (state.activeTab !== "vehicles") {
        state.activeTab = "vehicles";
        els.content.innerHTML = renderLoading();
        await loadCurrentTab({ force: true });
      }
      if (control.dataset.modelKey) {
        await loadVehicleModelDetail(control.dataset.modelKey);
      } else {
        await loadVehicleDetail(id);
      }
      return;
    }
    if (action === "select-model-vehicle") {
      await loadVehicleModelDetail(control.dataset.modelKey, Number(control.dataset.machineId || 0));
      return;
    }
    if (action === "set-vehicle-detail-tab") {
      rememberVehicleDetailScroll();
      state.vehicleDetailTab = control.dataset.tab || "archive";
      ensureVehicleDetailTab();
      renderCurrentTab();
      restoreVehicleDetailScroll();
      return;
    }
    if (action === "set-config-module") {
      state.activeConfigModule = control.dataset.module === "vehicles" ? "vehicles" : "parts";
      await ensureConfigData();
      renderCurrentTab();
      return;
    }
    if (action === "select-config-item") {
      const configList = control.closest(".config-list");
      state.configItemScrollTop = configList ? configList.scrollTop : 0;
      await loadConfigValues(id, { restoreConfigScroll: true });
      return;
    }
    if (action === "select-vehicle-config-item") {
      const configList = control.closest(".config-list");
      state.vehicleConfigItemScrollTop = configList ? configList.scrollTop : 0;
      await loadVehicleConfigValues(id, { restoreConfigScroll: true });
      return;
    }
    if (["vehicle-config-change", "part-replace", "create-modification-order", "create-vehicle-part"].includes(action)) {
      await openVehicleConfigChangeModal(control, action);
      return;
    }
    if (action === "complete-modification-order") {
      await completeModificationOrder(id);
      return;
    }
    if (action === "cancel-modification-order") {
      await cancelModificationOrder(id);
      return;
    }
  } catch (error) {
    handleActionError(error);
  }
}

async function openVehicleConfigChangeModal(control, action) {
  const machineId = Number(control.dataset.machineId || state.selectedVehicleId || "");
  const machine = findEntity("vehicle", machineId);
  const configId = control.dataset.machineConfigId ? Number(control.dataset.machineConfigId) : null;
  const modeByAction = {
    "create-vehicle-part": "INSTALL",
    "part-replace": "REPLACE",
    "create-modification-order": "WORK_ORDER"
  };
  const mode = control.dataset.mode || modeByAction[action] || "INSTALL";
  await openEntityModal("vehicleConfigChange", vehicleConfigChangeDefaults(machine, mode, configId));
}

function handleContentKeydown(event) {
  if (event.key !== "Enter" && event.key !== " ") return;
  if (event.target.closest("button, a, input, select, textarea")) return;
  const selectable = event.target.closest("[data-select-action], [data-action]");
  if (!selectable || !els.content.contains(selectable)) return;
  event.preventDefault();
  selectable.click();
}

function handleFormWorkspaceNavigation(event) {
  const anchor = event.target.closest("[data-action='scroll-form-section']");
  if (!anchor) return false;
  const section = els.modalCard.querySelector(`#${CSS.escape(anchor.dataset.sectionId || "")}`);
  if (!section) return true;
  els.modalCard.querySelectorAll(".form-anchor").forEach(button => {
    button.classList.toggle("is-active", button === anchor);
  });
  section.scrollIntoView({ behavior: "smooth", block: "start" });
  return true;
}

function handleContentPointerOver(event) {
  const column = event.target.closest(".finance-chart-column");
  if (!column || !els.content.contains(column)) return;
  setActiveFinanceColumn(column);
}

function handleContentPointerOut(event) {
  const chart = event.target.closest(".finance-chart");
  if (!chart) return;
  if (event.relatedTarget && chart.contains(event.relatedTarget)) return;
  clearActiveFinanceChart(chart);
}

function handleContentFocusIn(event) {
  const column = event.target.closest(".finance-chart-column");
  if (!column || !els.content.contains(column)) return;
  setActiveFinanceColumn(column);
}

function handleContentFocusOut(event) {
  const chart = event.target.closest(".finance-chart");
  if (!chart) return;
  window.setTimeout(() => {
    if (chart.contains(document.activeElement)) return;
    clearActiveFinanceChart(chart);
  }, 0);
}

function setActiveFinanceColumn(column) {
  const chart = column.closest(".finance-chart");
  if (!chart) return;
  const activeIndex = column.dataset.financeColumnIndex || "";
  if (chart.dataset.activeIndex === activeIndex) return;
  chart.dataset.activeIndex = activeIndex;
  chart.querySelectorAll(".finance-chart-column.is-active, .finance-point.is-active").forEach(item => {
    item.classList.remove("is-active");
  });
  column.classList.add("is-active");
  chart.querySelectorAll("[data-finance-point-index]").forEach(point => {
    if (point.dataset.financePointIndex === activeIndex) {
      point.classList.add("is-active");
    }
  });
}

function clearActiveFinanceChart(chart) {
  delete chart.dataset.activeIndex;
  chart.querySelectorAll(".finance-chart-column.is-active, .finance-point.is-active").forEach(item => {
    item.classList.remove("is-active");
  });
  if (chart.contains(document.activeElement) && typeof document.activeElement.blur === "function") {
    document.activeElement.blur();
  }
}

function handleContentInput(event) {
  const input = event.target.closest("[data-search-for]");
  if (!input) return;
  state.search[input.dataset.searchFor] = input.value;
  if (input.dataset.searchFor === "logs") {
    state.visibleLogRows = LOG_PAGE_SIZE;
  }
  const tab = tabForSearchKey(input.dataset.searchFor);
  if (tab && tab === activePagedTab()) {
    resetPage(state.pages[tab]);
    if (tab === "logs" && state.pages.stockMovements) resetPage(state.pages.stockMovements);
    if (tab === "vehicles") {
      clearVehicleSelection();
      renderCurrentTab();
      const nextInput = els.content.querySelector(`[data-search-for="${input.dataset.searchFor}"]`);
      if (nextInput) {
        nextInput.focus();
        nextInput.setSelectionRange(input.value.length, input.value.length);
      }
    }
    schedulePagedReload(tab);
    return;
  }
  renderCurrentTab();
  const nextInput = els.content.querySelector(`[data-search-for="${input.dataset.searchFor}"]`);
  if (nextInput) {
    nextInput.focus();
    nextInput.setSelectionRange(input.value.length, input.value.length);
  }
}

function tabForSearchKey(key) {
  return Object.entries(pagedTabs)
    .find(([, config]) => config.searchKey === key)?.[0] || null;
}

function activePagedTab() {
  return activeTabPageKeys[state.activeTab] || (pagedTabs[state.activeTab] ? state.activeTab : null);
}

function schedulePagedReload(tab) {
  window.clearTimeout(searchReloadTimer);
  searchReloadTimer = window.setTimeout(async () => {
    try {
      if (tab === "vehicles") {
        const request = loadVehicleModelData({ force: true });
        renderCurrentTab();
        await request;
      } else if (tab === "logs") {
        await loadCurrentTab({ force: true });
      } else {
        await loadPagedTab(tab, { force: true });
      }
      renderCurrentTab();
      const input = els.content.querySelector(`[data-search-for="${pagedTabs[tab].searchKey}"]`);
      if (input) {
        input.focus();
        input.setSelectionRange(input.value.length, input.value.length);
      }
    } catch (error) {
      handleActionError(error);
    }
  }, 250);
}

async function handleContentChange(event) {
  const importTypeSelect = event.target.closest("[data-import-type-select]");
  if (importTypeSelect) {
    state.importSelectedType = importTypeSelect.value || "vehicle-workbook";
    state.importSelectedMode = String(state.importSelectedType).toLowerCase().includes("part")
      ? "OPENING_MIGRATION"
      : "BUSINESS_DOCUMENT";
    state.importValidation = null;
    renderCurrentTab();
    return;
  }

  const importModeSelect = event.target.closest("[data-import-mode-select]");
  if (importModeSelect) {
    state.importSelectedMode = importModeSelect.value || "BUSINESS_DOCUMENT";
    state.importValidation = null;
    renderCurrentTab();
    return;
  }

  const filter = event.target.closest("[data-filter-for]");
  if (filter) {
    const key = filter.dataset.filterFor;
    const name = filter.dataset.filterName;
    state.filters[key] = {
      ...(state.filters[key] || {}),
      [name]: filter.value
    };
    if (key === "attachments" && name === "resourceType") {
      state.filters.attachments.resourceId = "";
    }
    if (state.pages[key]) resetPage(state.pages[key]);
    if (key === "vehicles") {
      clearVehicleSelection();
      const request = loadVehicleModelData({ force: true });
      renderCurrentTab();
      await request;
      renderCurrentTab();
      return;
    }
    if (["attachments", "imports"].includes(key) && key === activePagedTab()) {
      els.content.innerHTML = renderLoading();
      await loadPagedTab(key, { force: true });
      renderCurrentTab();
      return;
    }
    renderCurrentTab();
    return;
  }

  const statsSelect = event.target.closest("[data-action='select-stats-year']");
  if (statsSelect) {
    try {
      els.content.innerHTML = renderLoading();
      await loadStatistics(statsSelect.value);
      renderCurrentTab();
    } catch (error) {
      handleActionError(error);
    }
    return;
  }

  const reconciliationDate = event.target.closest("[data-action='select-reconciliation-date']");
  if (reconciliationDate) {
    try {
      els.content.innerHTML = renderLoading();
      await loadDailyReconciliation(reconciliationDate.value);
      renderCurrentTab();
    } catch (error) {
      handleActionError(error);
    }
    return;
  }

  const select = event.target.closest("[data-action='select-config-item']");
  if (!select) return;
  try {
    await loadConfigValues(select.value);
  } catch (error) {
    handleActionError(error);
  }
}

function handleModalOverlayPointerDown(event) {
  modalPointerDownStartedOnOverlay = event.target === els.modalOverlay;
}

function handleOverlayClick(event) {
  if (event.target === els.modalOverlay && modalPointerDownStartedOnOverlay) {
    void requestCloseModal();
  }
  modalPointerDownStartedOnOverlay = false;
}

function handleDetailDrawerOverlayPointerDown(event) {
  detailPointerDownStartedOnOverlay = event.target === els.detailDrawerOverlay;
}

function handleDetailDrawerOverlayClick(event) {
  if (event.target === els.detailDrawerOverlay && detailPointerDownStartedOnOverlay) {
    closeDetailDrawer();
  }
  detailPointerDownStartedOnOverlay = false;
}

function handleDetailDrawerClick(event) {
  if (event.target.closest("[data-action='close-detail']")) {
    closeDetailDrawer();
    return;
  }
  if (event.target.closest("[data-action], [data-select-action]")) {
    handleContentClick(event);
  }
}

async function handleModalClick(event) {
  const invalidFieldLink = event.target.closest("[data-action='focus-invalid-field']");
  if (invalidFieldLink) {
    const field = els.modalCard.querySelector(`[name="${CSS.escape(invalidFieldLink.dataset.name || "")}"]`);
    field?.focus();
    field?.scrollIntoView({ block: "center", behavior: "smooth" });
    return;
  }
  if (handleFormWorkspaceNavigation(event)) return;
  const repairPartAction = event.target.closest("[data-repair-part-action]");
  if (repairPartAction) {
    event.preventDefault();
    const form = repairPartAction.closest("form");
    const rows = ensureRepairPartUsageRows(state.modal.item);
    const currentRows = collectRepairPartUsages(form);
    state.modal.item.partUsages = currentRows;
    if (repairPartAction.dataset.repairPartAction === "add") {
      state.modal.item.partUsages.push({
        quantity: 1,
        warehouseId: null,
        chargeUnitPrice: null,
        discountAmount: null,
        remark: ""
      });
    } else if (repairPartAction.dataset.repairPartAction === "remove") {
      state.modal.item.partUsages.splice(Number(repairPartAction.dataset.index || -1), 1);
    }
    renderModal();
    return;
  }
  const configAction = event.target.closest("[data-config-action]");
  if (configAction) {
    event.preventDefault();
    if (configAction.dataset.configAction === "remove") {
      const confirmed = await confirmDanger({
        title: "确认移除配置",
        target: "当前入库配置行",
        impact: "该配置行会从当前表单中移除；保存前不会写入后台。"
      });
      if (!confirmed) return;
    }
    updateConfigSelectionRows(configAction);
    return;
  }
  const comboOption = event.target.closest("[data-combo-option]");
  if (comboOption) {
    event.preventDefault();
    selectComboOption(comboOption);
    return;
  }
  const comboToggle = event.target.closest("[data-combo-toggle]");
  if (comboToggle) {
    event.preventDefault();
    toggleCombo(comboToggle.closest("[data-combo]"));
    return;
  }
  const comboInput = event.target.closest("[data-combo-input]");
  if (comboInput) {
    const combo = comboInput.closest("[data-combo]");
    openCombo(combo, comboInput.value.trim());
    scheduleRemoteComboSearch(combo, comboInput.value.trim(), { immediate: combo.dataset.remoteLoaded !== "true" });
    return;
  }
  const toggleOption = event.target.closest("[data-toggle-option]");
  if (toggleOption) {
    event.preventDefault();
    setToggleFieldValue(toggleOption);
    return;
  }
  if (!event.target.closest("[data-combo]")) {
    closeAllCombos();
  }
  if (event.target.closest("[data-close-modal]")) {
    await requestCloseModal();
  }
}

function handleModalInput(event) {
  clearModalValidationSummaryIfValid(event.target.closest("form"));
  const input = event.target.closest("[data-combo-input]");
  if (!input) {
    const form = event.target.closest("form");
    if (form?.dataset.kind === "repair" && event.target.closest("[data-repair-part-field]")) {
      syncRepairPartUsageEditor(form, event.target.closest("[data-repair-part-row]"));
      return;
    }
    if (form?.dataset.kind === "repair" && ["repairFee", "repairExpense", "partsFee", "passThroughAmount"].includes(event.target.name)) {
      syncRepairTotalFee(form);
    }
    if (["vehicleOutbound", "partStock", "outboundOrder"].includes(form?.dataset.kind) && isPaymentField(event.target.name)) {
      syncPaymentFields(form, event.target.name);
    }
    if (form?.dataset.kind === "purchaseOrder" && ["quantity", "unitPrice", "totalAmount"].includes(event.target.name)) {
      syncPurchaseAmount(form, event.target.name);
    }
    return;
  }
  const combo = input.closest("[data-combo]");
  const hidden = combo.querySelector("input[type='hidden']");
  const query = input.value.trim();
  const allowCustom = combo.dataset.allowCustom === "true";
  const exact = findComboOption(combo, query);
  input.dataset.userEdited = "true";
  if (exact) {
    setComboValue(combo, exact.dataset.value, exact.dataset.label, exact.dataset.meta, false);
  } else if (allowCustom) {
    hidden.value = query;
    hidden.dataset.selectedLabel = query;
    hidden.dataset.selectedMeta = "";
  } else {
    hidden.value = "";
    hidden.dataset.selectedLabel = "";
    hidden.dataset.selectedMeta = "";
  }
  openCombo(combo, query);
  scheduleRemoteComboSearch(combo, query);
}

function handleModalInvalid(event) {
  const form = event.target.closest("form");
  if (!form) return;
  requestAnimationFrame(() => showModalValidationSummary(form));
}

function showModalValidationSummary(form, message = "") {
  const summary = els.modalCard.querySelector("[data-validation-summary]");
  if (!summary) return;
  const invalidFields = [...form.querySelectorAll(":invalid")].filter(field => field.name);
  const items = invalidFields.map(field => {
    const label = field.closest(".field")?.querySelector(":scope > span")?.childNodes?.[0]?.textContent?.trim() || field.name;
    return `<button type="button" data-action="focus-invalid-field" data-name="${escapeAttr(field.name)}">${escapeHtml(label)}</button>`;
  });
  summary.innerHTML = `
    <strong>${escapeHtml(message || "请检查以下必填或格式错误字段")}</strong>
    ${items.length ? `<div>${items.join("")}</div>` : ""}
  `;
  summary.classList.remove("is-hidden");
}

function clearModalValidationSummaryIfValid(form) {
  if (!form || form.querySelector(":invalid")) return;
  els.modalCard.querySelector("[data-validation-summary]")?.classList.add("is-hidden");
}

function handleModalFocusIn(event) {
  const input = event.target.closest("[data-combo-input]");
  if (!input) return;
  const combo = input.closest("[data-combo]");
  openCombo(combo, input.value.trim());
  scheduleRemoteComboSearch(combo, input.value.trim(), { immediate: combo.dataset.remoteLoaded !== "true" });
}

function handleModalFocusOut(event) {
  const input = event.target.closest("[data-combo-input]");
  if (!input) return;
  const combo = input.closest("[data-combo]");
  if (!combo || combo.dataset.name !== "specificationModel" || combo.dataset.allowCustom !== "true") return;
  if (event.relatedTarget && combo.contains(event.relatedTarget)) return;
  if (event.relatedTarget?.closest?.("button")) return;
  const hidden = combo.querySelector("input[type='hidden']");
  if (!hidden) return;
  const query = input.value.trim();
  const exact = findComboOption(combo, query);
  if (exact) {
    setComboValue(combo, exact.dataset.value, exact.dataset.label, exact.dataset.meta, true);
    return;
  }
  hidden.value = query;
  hidden.dataset.selectedLabel = query;
  hidden.dataset.selectedMeta = "";
  hidden.dispatchEvent(new Event("change", { bubbles: true }));
}

function updateConfigSelectionRows(button) {
  if (!usesVehicleInboundConfigEditor(state.modal?.kind, state.modal?.item)) return;
  const rows = ensureConfigSelections(state.modal.item);
  if (button.dataset.configAction === "add") {
    rows.push({ configItemId: "", configValueId: "" });
  }
  if (button.dataset.configAction === "remove") {
    const index = Number(button.dataset.configIndex);
    rows.splice(index, 1);
    if (!rows.length) rows.push({ configItemId: "", configValueId: "" });
  }
  renderModal();
}

async function handleModalChange(event) {
  const form = event.target.closest("form");
  if (!form) return;
  const kind = form.dataset.kind;
  if (kind === "repair" && event.target.closest("[data-repair-part-field]")) {
    syncRepairPartUsageEditor(form, event.target.closest("[data-repair-part-row]"));
    return;
  }
  if (usesVehicleInboundConfigEditor(kind, state.modal?.item) && event.target.dataset.configField) {
    const rows = ensureConfigSelections(state.modal.item);
    const index = Number(event.target.dataset.configIndex);
    const fieldName = event.target.dataset.configField;
    rows[index] = {
      ...(rows[index] || {}),
      [fieldName]: event.target.value
    };
    if (event.target.value) {
      clearConfigSelectionPrefill(rows[index], fieldName);
    }
    if (fieldName === "configItemId") {
      rows[index].configValueId = "";
      clearConfigSelectionPrefill(rows[index], "configValueId");
      renderModal();
    }
    return;
  }
  if (state.modal?.item && event.target.name) {
    state.modal.item[event.target.name] = event.target.type === "checkbox" ? event.target.checked : event.target.value;
  }
  if (kind === "vehicle" && event.target.name === "stockStatus" && event.target.value === "IN_STOCK" && Number(form.elements.inventoryCount?.value || 0) <= 0) { setFormFieldValue(form, "inventoryCount", 1, false); state.modal.item.inventoryCount = 1; }

  if (kind === "vehicleConfigChange" && event.target.name === "changeMode") {
    await resetVehicleConfigChangeMode(event.target.value);
    renderModal();
    return;
  }

  if (usesVehicleInboundConfigEditor(kind, state.modal?.item) && event.target.name === "specificationModel") {
    await prepareVehicleInboundTemplate(state.modal.item);
    renderModal();
    return;
  }

  if (kind === "attachmentUpload" && event.target.name === "resourceType") {
    state.modal.item.resourceId = null;
    state.modal.item.attachmentCategory = attachmentDefaultCategoryForResourceType(event.target.value);
    renderModal();
    return;
  }

  if (kind === "configItem") {
    syncConfigItemFields(form, event.target.name);
  }

  if (kind === "vehicleConfigValue" && event.target.name === "configItemId") {
    state.modal.item.configValueId = null;
    renderModal();
    return;
  }

  if (kind === "part") {
    syncPartDictionaryFields(form, event.target.name);
  }

  if (kind === "repair") {
    if (["repairFee", "repairExpense", "partsFee", "passThroughAmount"].includes(event.target.name)) {
      syncRepairTotalFee(form);
    }
    if (event.target.name === "repairPersonChoice") {
      if (!repairUsesExternal(state.modal.item)) {
        state.modal.item.repairExpense = "";
      }
      syncRepairTotalFee(form);
      renderModal();
      return;
    }
    if (event.target.name === "machineId") {
      syncRepairVehicleSelection(form, Number(event.target.value || 0));
    }
    if (event.target.name === "customerId") {
      syncCustomerAddress(form, Number(event.target.value || 0));
    }
    if (event.target.name === "usedPartIds") {
      syncRepairPartFee(form, event.target.value);
    }
  }

  if (kind === "rental" && event.target.name === "customerId") {
    syncRentalCustomerDestination(form, Number(event.target.value || 0));
  }

  if (kind === "rental" && event.target.name === "machineId") {
    syncRentalVehicleDefaults(form, Number(event.target.value || 0));
  }

  if (kind === "stocktaking" && event.target.name === "resourceType") {
    state.modal.item.resourceId = null;
    state.modal.item.warehouseId = null;
    state.modal.item.actualQuantity = 0;
    renderModal();
    return;
  }

  if (kind === "stocktaking" && event.target.name === "resourceId") {
    await syncStocktakingQuantity(form, Number(event.target.value || 0));
  }

  if (kind === "stocktaking" && event.target.name === "warehouseId") {
    await syncStocktakingQuantity(form, Number(form.elements.resourceId?.value || 0));
  }

  if (kind === "stockTransfer" && event.target.name === "resourceType") {
    state.modal.item.resourceId = null;
    state.modal.item.fromWarehouseId = null;
    state.modal.item.version = null;
    renderModal();
    return;
  }

  if (kind === "stockTransfer" && event.target.name === "resourceId") {
    syncStockTransferResource(form, Number(event.target.value || 0));
  }

  if (kind === "purchaseOrder" && ["quantity", "unitPrice", "totalAmount"].includes(event.target.name)) {
    syncPurchaseAmount(form, event.target.name);
  }

  if (kind === "purchaseOrder" && event.target.name === "resourceType") {
    applyPurchaseOrderResourceMode(form, event.target.value);
    if (purchaseOrderResourceType(state.modal.item) === "MACHINE") {
      ensureConfigSelections(state.modal.item);
      await prepareVehicleInboundTemplate(state.modal.item);
    }
    renderModal();
    return;
  }

  if (kind === "purchaseOrder" && event.target.name === "configItemId") {
    state.modal.item.configValueId = null;
    renderModal();
    return;
  }

  if (kind === "purchaseOrder" && event.target.name === "configValueId") {
    syncPurchaseResourceDefaults(form);
  }

  if (kind === "purchaseOrder" && event.target.name === "resourceId") {
    syncPurchaseSkuDefaults(form);
  }

  if (usesVehicleInboundConfigEditor(kind, state.modal?.item) && event.target.name === "machineType") {
    renderModal();
    return;
  }

  if (kind === "vehicleOutbound" && event.target.name === "customerMode") {
    if (event.target.value === "quickCreate") {
      state.modal.item.customerId = null;
    }
    renderModal();
    return;
  }

  if (kind === "vehicleOutbound" && event.target.name === "machineId") {
    syncVehicleOutboundDefaults(Number(event.target.value || 0));
    renderModal();
    return;
  }

  if (["vehicleOutbound", "partStock", "outboundOrder"].includes(kind) && isPaymentField(event.target.name)) {
    syncPaymentFields(form, event.target.name);
  }

  if (kind === "partStock" && event.target.name === "partCode") {
    if (state.modal?.item?.direction === "outbound") {
      syncPartOutboundDefaults(event.target.value);
    } else {
      syncPartAdjustmentDefaults(event.target.value);
    }
    renderModal();
    return;
  }

  if ((kind === "modificationOrder" || isVehicleConfigChangeMode("WORK_ORDER")) && event.target.name === "oldPartAction") {
    state.modal.item.newPartId = null;
    state.modal.item.newConfigValueId = null;
    renderModal();
    return;
  }

  if ((kind === "vehiclePartInstall" || isVehicleConfigChangeMode("INSTALL")) && event.target.name === "configItemId") {
    state.modal.item.configItemId = Number(event.target.value || 0) || null;
    state.modal.item.newPartId = null;
    syncComboOptionsForField(form, "newPartId", installPartOptions(), "");
    return;
  }

  if ((kind === "modificationOrder" || isVehicleConfigChangeMode("WORK_ORDER")) && event.target.name === "vehicleModelKey") {
    state.modal.item.machineId = null;
    clearPrefill(state.modal.item, "machineId");
    clearPrefill(state.modal.item, "machineConfigId");
    state.modal.item.newPartId = null;
    state.modal.item.newConfigValueId = null;
    state.modal.item.__placeholders = {};
    state.modal.context.machine = null;
    state.modal.context.machineConfigs = [];
    state.modal.context.compatibleParts = [];
    renderModal();
    return;
  }

  if (kind === "partReplace" || kind === "modificationOrder" || isVehicleConfigChangeMode("REPLACE") || isVehicleConfigChangeMode("WORK_ORDER")) {
    if (event.target.name === "machineId") {
      if (kind === "modificationOrder" || isVehicleConfigChangeMode("WORK_ORDER")) {
        state.modal.item.newConfigValueId = null;
      }
      await preparePartReplaceContext(Number(event.target.value || 0), null, { placeholderConfig: kind === "modificationOrder" || isVehicleConfigChangeMode("WORK_ORDER") });
      renderModal();
      return;
    }
    if (event.target.name === "machineConfigId") {
      updateCompatibleParts(Number(event.target.value || 0));
      if ((kind === "modificationOrder" || isVehicleConfigChangeMode("WORK_ORDER")) && isModificationDiscountMode()) {
        state.modal.item.newConfigValueId = null;
        renderModal();
      } else {
        syncComboOptionsForField(form, "newPartId", compatiblePartOptions(), "");
      }
      return;
    }
  }

  if (kind !== "replace") return;
}

async function handleModalSubmit(event) {
  event.preventDefault();
  const form = event.target;
  if (form.dataset.submitting === "true") return;
  const kind = form.dataset.kind;
  const item = state.modal?.item || {};
  const shouldContinue = event.submitter?.dataset?.submitMode === "continue" && canSaveAndContinue(kind, item);
  if (!validateCombos(form)) return;
  const activeModelKey = state.vehicleDetail?.modelKey || item.__modelKey || "";
  setModalSubmitting(form, true);
  try {
  if (kind === "attachmentUpload") {
    try {
      await uploadAttachmentsFromModal(form);
      closeModal();
      if (state.activeTab === "attachments") {
        await loadPagedTab("attachments", { force: true });
      }
      renderCurrentTab();
    } catch (error) {
      handleActionError(error);
    } finally {
      setModalSubmitting(form, false);
    }
    return;
  }
  if (kind === "invoiceUpload" || kind === "contractUpload") {
    if (!(await confirmDanger(modalDangerConfirmation(kind, item, {}) || {
      title: kind === "invoiceUpload" ? "确认上传发票" : "确认上传合同",
      target: entityDisplayName("outboundOrder", item),
      impact: "本次上传会作为当前订单文件，历史附件会保留在附件中心。"
    }))) {
      setModalSubmitting(form, false);
      return;
    }
    try {
      if (kind === "invoiceUpload") {
        await uploadInvoiceForOrder(item, form);
      } else {
        await uploadContractForOrder(item, form);
      }
      closeModal();
      markReferenceDataStale(referenceKindsForMutation(kind));
      await refreshAfterMutation(kind, { refreshDetail: false });
      renderCurrentTab();
    } catch (error) {
      handleActionError(error);
    } finally {
      setModalSubmitting(form, false);
    }
    return;
  }
  if (kind === "dataRestore") {
    if (!(await confirmDanger({
      title: "恢复数据备份",
      target: "数据库业务数据",
      impact: "恢复会清空当前应用表并用备份文件回填，请先确认已经下载了最新备份。"
    }))) {
      setModalSubmitting(form, false);
      return;
    }
    try {
      await restoreDataBackup(form);
      closeModal();
      markReferenceDataStale();
      await loadAllData({ force: true });
      renderCurrentTab();
      showToast("数据恢复完成", "success");
    } catch (error) {
      handleActionError(error);
    } finally {
      setModalSubmitting(form, false);
    }
    return;
  }
  const payload = serializeForm(kind, form);
  let mutationKind = kind;
  if (kind === "paymentRecord" || kind === "paymentReversal") {
    mutationKind = item.sourceKind || kind;
  }
  if (kind === "removedPartValuation") {
    mutationKind = "part";
  }
  if (kind === "vehicleConfigChange") {
    payload.changeMode = vehicleConfigChangeMode(payload);
    mutationKind = vehicleConfigChangeTargetKind(payload.changeMode);
  }
  if (kind === "purchaseOrder") {
    enrichPurchaseOrderPayload(payload, form, item);
  }
  attachVersion(payload, item);
  if (kind === "userJobTag" && normalizeJobTag(payload.jobTag, item.roles) === normalizeJobTag(item.jobTag, item.roles)) {
    closeModal();
    showToast("职务未变更", "info");
    return;
  }
  const confirmationKind = kind === "vehicleConfigChange" ? mutationKind : kind;
  const dangerConfirmation = modalDangerConfirmation(confirmationKind, item, payload);
  if (dangerConfirmation && !(await confirmDanger(dangerConfirmation))) {
    setModalSubmitting(form, false);
    return;
  }

  try {
    if (kind === "paymentRecord") {
      payload.requestId = item.requestId || createRequestId("payment");
      item.requestId = payload.requestId;
      await api(endpoints.payment.create, {
        method: "POST",
        body: payload
      });
      showContextSuccess(
        payload.direction === "PAYMENT" ? "付款已登记" : "收款已登记",
        item.sourceLabel,
        money(payload.amount)
      );
    } else if (kind === "paymentReversal") {
      const requestId = item.requestId || createRequestId("payment-reversal");
      item.requestId = requestId;
      await api(endpoints.payment.reverse(payload.paymentId), {
        method: "POST",
        body: {
          requestId,
          remark: payload.remark || ""
        }
      });
      showContextSuccess("收付款记录已冲销", item.sourceLabel, "原记录保留，已生成反向流水");
    } else if (kind === "removedPartValuation") {
      await api(endpoints.part.valuation(item.id), {
        method: "PUT",
        body: payload
      });
      showContextSuccess("旧件估值已确认", item.partLabel, `单位成本 ${money(payload.unitCost)}`);
    } else if (kind === "switchUser") {
      const data = await api("/api/auth/login", {
        method: "POST",
        body: payload,
        auth: false
      });
      state.token = data.token;
      state.user = normalizeUser(data);
      saveSession(state.token, state.user);
      closeModal();
      showToast("已切换用户", "success");
      await enterApp();
      return;
    }

    if (kind === "vehicleModel") {
      await api(endpoints.vehicle.create, {
        method: "POST",
        body: {
          ...payload,
          modelOnly: true,
          inventoryCount: 0,
          stockStatus: "PENDING_INBOUND"
        }
      });
      showContextSuccess("车型已新增", entityDisplayName("vehicle", payload), "可继续入库具体车号");
    } else if (kind === "vehicleInbound") {
      item.specificationModel = payload.specificationModel;
      await prepareVehicleInboundTemplate(item);
      const configs = buildInboundConfigs(item, payload.specificationModel);
      const savedVehicle = await api("/api/inventory/inbound", {
        method: "POST",
        body: {
          machineInventory: buildVehicleInboundPayload(payload, item),
          configs
        }
      });
      if (savedVehicle?.id) {
        state.selectedVehicleId = Number(savedVehicle.id);
      }
      showContextSuccess("车型入库成功", entityDisplayName("vehicle", { ...payload, id: savedVehicle?.id }), "车辆详情已更新");
    } else if (kind === "purchaseOrder" && payload.resourceType === "MACHINE") {
      if (item.id) {
        await api(endpoints.purchaseOrder.update(item.id), { method: "PUT", body: payload });
        showContextSuccess("整车入库订单已更新", payload.resourceCode || payload.vehicleProductNumber || payload.resourceName, "入库订单已刷新");
      } else {
        item.specificationModel = payload.specificationModel;
        await prepareVehicleInboundTemplate(item);
        const configs = buildInboundConfigs(item, payload.specificationModel);
        const workflowResult = await api(endpoints.workflow.machineInboundPurchase, {
          method: "POST",
          body: {
            inbound: {
              machineInventory: buildVehicleInboundPayload(payload, item),
              configs
            },
            purchaseOrder: payload
          }
        });
        const savedVehicle = workflowResult?.machine;
        if (savedVehicle?.id) {
          state.selectedVehicleId = Number(savedVehicle.id);
        }
        showContextSuccess("整车入库成功", entityDisplayName("vehicle", { ...payload, id: savedVehicle?.id }), "车辆库存和入库订单已刷新");
      }
    } else if (kind === "vehicleOutbound") {
      const machine = findEntity("vehicle", Number(payload.machineId || 0));
      const customerResult = await ensureVehicleOutboundCustomer(payload);
      if (!customerResult) {
        return;
      }
      const outboundOrder = buildVehicleOutboundOrderPayload(payload, machine, customerResult.customerId);
      if (customerResult.created) {
        await api(endpoints.workflow.vehicleOutboundWithCustomer, {
          method: "POST",
          body: {
            customer: customerResult.customer,
            outboundOrder
          }
        });
      } else {
        await api(endpoints.outboundOrder.vehicle, { method: "POST", body: outboundOrder });
      }
      showContextSuccess(customerResult.created ? "已新建客户并创建整车出库订单" : "整车出库订单已创建", entityDisplayName("vehicle", machine), "收款、报销售和发票跟进已生成");
    } else if (kind === "rental") {
      const machine = findEntity("vehicle", Number(payload.machineId || item.machineId || 0));
      const body = {
        machineId: payload.machineId || item.machineId,
        machineVersion: machine.version,
        customerId: payload.customerId,
        warehouseId: payload.warehouseId,
        destination: payload.destination,
        monthlyRentalPrice: payload.monthlyRentalPrice,
        startDate: payload.startDate,
        endDate: payload.endDate,
        returnDate: payload.returnDate,
        status: payload.status,
        operator: payload.operator,
        remark: payload.remark,
        version: payload.version
      };
      if (item.id) {
        await api(endpoints.rental.update(item.id), { method: "PUT", body });
        showContextSuccess("租赁记录已更新", entityDisplayName("vehicle", machine), body.destination || "租赁列表已刷新");
      } else {
        await api(endpoints.rental.create, { method: "POST", body });
        showContextSuccess("租赁记录已创建", entityDisplayName("vehicle", machine), body.destination || "租赁列表已刷新");
      }
    } else if (kind === "vehicleStock") {
      attachStockVersion(payload, "vehicle");
      const adjustmentDirection = item.direction === "adjustOutbound" ? "outbound" : item.direction;
      await api(`/api/inventory/${payload.machineId}/${adjustmentDirection}`, {
        method: "PUT",
        body: {
          quantity: payload.quantity,
          warehouseId: payload.warehouseId,
          businessDate: payload.businessDate,
          reason: payload.reason,
          operator: payload.operator,
          remark: payload.remark,
          version: payload.version
        }
      });
      showContextSuccess(item.direction === "inbound" ? "整车入库调整成功" : "整车库存减少成功", entityDisplayName("vehicle", findEntity("vehicle", Number(payload.machineId || 0))), `数量：${payload.quantity || 0}`);
    } else if (kind === "partStock") {
      attachStockVersion(payload, "part");
      if (item.direction === "outbound") {
        await api(endpoints.outboundOrder.part, {
          method: "POST",
          body: {
            partCode: payload.partCode,
            partVersion: payload.version,
            quantity: payload.quantity,
            customerId: payload.customerId,
            warehouseId: payload.warehouseId,
            unitSalePrice: payload.unitSalePrice,
            lineAmount: payload.lineAmount,
            receivedAmount: payload.receivedAmount,
            paymentDueDate: payload.paymentDueDate,
            lastPaymentDate: payload.lastPaymentDate,
            paymentSettled: payload.paymentSettled,
            paymentRemark: payload.paymentRemark,
            salesDate: payload.salesDate,
            operator: payload.operator,
            orderRemark: payload.orderRemark
          }
        });
        showContextSuccess("配件出库订单已创建", payload.partCode, `数量：${payload.quantity || 0}`);
      } else {
        const adjustmentDirection = item.direction === "adjustOutbound" ? "outbound" : item.direction;
        await api(`/api/parts/${adjustmentDirection}`, {
          method: "PUT",
          body: {
            partCode: payload.partCode,
            quantity: payload.quantity,
            warehouseId: payload.warehouseId,
            businessDate: payload.businessDate,
            reason: payload.reason,
            operator: payload.operator,
            remark: payload.remark,
            version: payload.version
          }
        });
        showContextSuccess(item.direction === "inbound" ? "配件入库调整成功" : "配件库存减少成功", payload.partCode, `数量：${payload.quantity || 0}`);
      }
    } else if (kind === "partReplace" || (kind === "vehicleConfigChange" && payload.changeMode === "REPLACE")) {
      enrichPartReplacePayload(payload);
      await api(endpoints.partReplace.create, { method: "POST", body: payload });
      showContextSuccess("配件替换成功", entityDisplayName("vehicle", findEntity("vehicle", Number(payload.machineId || 0))), "旧件已自动入库");
    } else if (kind === "vehiclePartInstall" || (kind === "vehicleConfigChange" && payload.changeMode === "INSTALL")) {
      enrichVehiclePartInstallPayload(payload);
      await api(endpoints.partReplace.install, { method: "POST", body: payload });
      showContextSuccess("配件装车成功", entityDisplayName("vehicle", findEntity("vehicle", Number(payload.machineId || 0))), `数量：${payload.quantity || 0}`);
    } else if (kind === "modificationOrder" || (kind === "vehicleConfigChange" && payload.changeMode === "WORK_ORDER")) {
      enrichPartReplacePayload(payload);
      await api(endpoints.modificationOrder.create, {
        method: "POST",
        body: buildModificationOrderPayload(payload)
      });
      showContextSuccess("改装工单已创建", entityDisplayName("vehicle", findEntity("vehicle", Number(payload.machineId || 0))), "待完成后同步库存和车辆配置");
    } else if (kind === "user") {
      await api(endpoints.user.create, { method: "POST", body: payload });
      showContextSuccess("用户创建成功", payload.username, "权限与职务已保存");
    } else if (kind === "userUsername") {
      await api(endpoints.user.updateUsername(item.id), { method: "PUT", body: payload });
      showContextSuccess("用户名修改成功", payload.username, "用户列表已刷新");
    } else if (kind === "userPassword") {
      await api(endpoints.user.updatePassword(item.id), { method: "PUT", body: payload });
      showContextSuccess("用户密码修改成功", entityDisplayName("user", item), "请使用新密码登录");
    } else if (kind === "userJobTag") {
      await api(endpoints.user.updateJobTag(item.id), { method: "PUT", body: payload });
      showContextSuccess("用户职务设置成功", entityDisplayName("user", item), jobTagLabel(payload.jobTag));
    } else if (kind === "outboundOrder") {
      await api(endpoints.outboundOrder.update(item.id), { method: "PUT", body: payload });
      showContextSuccess("订单状态已更新", entityDisplayName("outboundOrder", item), "收款、报销售和发票状态已保存");
    } else if (kind === "purchaseFreight") {
      const baseUrl = `${endpoints.purchaseOrder.freight(item.id)}?freightAmount=${encodeURIComponent(payload.freightAmount ?? 0)}`;
      await api(withVersion(baseUrl, item), { method: "PUT" });
      showContextSuccess("采购运费已更新", entityDisplayName("purchaseOrder", item), money(payload.freightAmount ?? 0));
    } else if (kind === "stockTransfer") {
      await api(endpoints.warehouse.transfer, { method: "POST", body: payload });
      showContextSuccess("库存调拨完成", stockTransferResourceName(payload), `数量：${payload.quantity || 0}`);
    } else if (item.id && endpoints[kind].update) {
      await api(endpoints[kind].update(item.id), { method: "PUT", body: payload });
      showContextSuccess(`${entityLabel(kind)}已保存`, entityDisplayName(kind, { ...item, ...payload }), "当前筛选和页码已保留");
    } else {
      await api(endpoints[kind].create, { method: "POST", body: payload });
      showContextSuccess(`${entityLabel(kind)}已新增`, entityDisplayName(kind, payload), "当前列表已刷新");
    }
    const nextEntity = shouldContinue ? nextEntityAfterContinue(kind, item, payload) : null;
    closeModal();
    resetPageAfterMutation(mutationKind);
    markReferenceDataStale(referenceKindsForMutation(mutationKind));
    await refreshAfterMutation(mutationKind, {
      activeModelKey,
      machineId: payload.machineId || item.machineId || item.sourceMachineId || state.selectedVehicleId
    });
    renderCurrentTab();
    if (nextEntity) {
      await openEntityModal(kind, nextEntity);
      focusFirstModalField();
    }
  } catch (error) {
    handleActionError(error);
  } finally {
    setModalSubmitting(form, false);
  }
  } catch (error) {
    handleActionError(error);
  } finally {
    setModalSubmitting(form, false);
  }
}

async function deleteEntity(kind, id) {
  if (!id || !endpoints[kind]?.delete) return;
  const item = findEntity(kind, id);
  if (!(await confirmDanger({
    title: `删除${entityLabel(kind)}`,
    target: entityDisplayName(kind, item),
    impact: "删除后将从当前列表移除；如该数据被业务单据引用，后端会阻止删除。"
  }))) return;
  await api(withVersion(endpoints[kind].delete(id), item), { method: "DELETE" });
  showContextSuccess(`${entityLabel(kind)}已删除`, entityDisplayName(kind, item), "相关列表已刷新");
  if (state.detailDrawer?.kind === kind && Number(state.detailDrawer.id) === Number(id)) {
    closeDetailDrawer();
  }
  toggleBatchSelection(kind, id, false);
  markReferenceDataStale(referenceKindsForMutation(kind));
  if (kind === "configValue") {
    await loadConfigValues(state.selectedConfigItemId);
  } else if (kind === "vehicleConfigValue") {
    await loadVehicleConfigValues(state.selectedVehicleConfigItemId);
  } else if (kind === "vehicleConfigItem") {
    if (Number(state.selectedVehicleConfigItemId) === Number(id)) {
      state.selectedVehicleConfigItemId = null;
    }
    await ensureConfigData(true);
    renderCurrentTab();
  } else {
    resetPageAfterMutation(kind);
    await refreshAfterMutation(kind, {
      machineId: item.machineId || state.selectedVehicleId
    });
    renderCurrentTab();
  }
}

async function completeModificationOrder(id) {
  const order = findModificationOrder(id);
  if (!order.id) {
    showToast("工单数据已刷新，请再点一次完成", "info");
    await loadAllData();
    renderCurrentTab();
    return;
  }
  if (!(await confirmDanger({
    title: "完成改装工单",
    target: entityDisplayName("modificationOrder", order),
    impact: "将扣减新配件库存、旧件入库，并更新车辆配置。"
  }))) return;
  const activeModelKey = state.vehicleDetail?.modelKey;
  const targetMachineId = order.machineId || state.selectedVehicleId;
  await api(endpoints.modificationOrder.complete(id), {
    method: "PUT",
    body: { version: order.version }
  });
  showContextSuccess("改装工单已完成", entityDisplayName("modificationOrder", order), "车辆配置与配件库存已同步");
  markReferenceDataStale(["vehicle", "part"]);
  resetPageAfterMutation("modificationOrder");
  await refreshAfterMutation("modificationOrder", { activeModelKey, machineId: targetMachineId });
  renderCurrentTab();
}

async function completeStocktaking(id) {
  const record = findEntity("stocktaking", id);
  if (!record.id) {
    showToast("盘点记录已刷新，请再试一次", "info");
    await loadAllData();
    renderCurrentTab();
    return;
  }
  if (!(await confirmDanger({
    title: "盘点入账",
    target: entityDisplayName("stocktaking", record),
    impact: "将按实盘数量同步库存，入账后不能作为草稿继续编辑。"
  }))) return;
  await api(withVersion(endpoints.stocktaking.complete(id), record), { method: "PUT" });
  showContextSuccess("盘点已入账", entityDisplayName("stocktaking", record), "库存数量已同步");
  markReferenceDataStale(["vehicle", "part"]);
  resetPageAfterMutation("stocktaking");
  await refreshAfterMutation("stocktaking", { machineId: record.resourceType === "MACHINE" ? record.resourceId : state.selectedVehicleId });
  renderCurrentTab();
}

async function completeStocktakingDirect(record) {
  await api(withVersion(endpoints.stocktaking.complete(record.id), record), { method: "PUT" });
}

async function togglePurchaseReceived(id) {
  const order = findEntity("purchaseOrder", id);
  if (!order.id) {
    showToast("入库订单已刷新，请再试一次", "info");
    await loadAllData();
    renderCurrentTab();
    return;
  }
  const nextReceived = order.status !== "RECEIVED";
  const url = withVersion(`${endpoints.purchaseOrder.received(id)}?received=${nextReceived ? "true" : "false"}`, order);
  const updatedOrder = await api(url, { method: "PUT" });
  const restoredStatus = purchaseStatusLabel(updatedOrder?.status);
  showToast(nextReceived ? "入库订单已收货" : `已撤销收货，状态恢复为“${restoredStatus}”`, "success");
  resetPageAfterMutation("purchaseOrder");
  await refreshAfterMutation("purchaseOrder", { refreshDetail: false });
  renderCurrentTab();
}

async function setPurchaseReceivedDirect(order, received) {
  const url = withVersion(`${endpoints.purchaseOrder.received(order.id)}?received=${received ? "true" : "false"}`, order);
  await api(url, { method: "PUT" });
}

async function openPaymentRecordModal(sourceType, sourceId, direction, sourceOverride = null) {
  const normalizedSourceType = String(sourceType || "").trim().toUpperCase();
  const normalizedDirection = String(direction || "").trim().toUpperCase();
  if (!normalizedSourceType || !sourceId || !["RECEIPT", "PAYMENT"].includes(normalizedDirection)) {
    throw new Error("收付款来源信息不完整");
  }
  const records = await api(endpoints.payment.list(normalizedSourceType, sourceId));
  const source = sourceOverride || paymentSourceContext(normalizedSourceType, sourceId);
  const target = paymentTargetAmount(normalizedSourceType, source, normalizedDirection);
  const posted = (records || [])
    .filter(record => record.direction === normalizedDirection)
    .reduce((total, record) => total + Number(record.amount || 0), 0);
  const remaining = Math.max(0, target - posted);
  await openEntityModal("paymentRecord", {
    sourceType: normalizedSourceType,
    sourceId,
    sourceKind: source.kind,
    sourceLabel: source.label,
    sourceMachineId: source.machineId,
    direction: normalizedDirection,
    amount: remaining > 0 ? formatDecimal(remaining) : "",
    paymentDate: todayInputDate(),
    requestId: createRequestId("payment"),
    paymentRecords: records || []
  });
}

async function openRentalPaymentModal(rentalId) {
  const rental = findEntity("rental", rentalId);
  let bills = await api(endpoints.rental.bills(rentalId));
  if (!bills?.length) {
    bills = await api(endpoints.rental.refreshBills(rentalId), { method: "POST" });
  }
  if (!bills?.length) {
    showToast("当前租赁尚未生成账单；办理归还后会按租期生成应收账单", "info");
    return;
  }
  const bill = bills.find(item => Number(item.outstandingAmount || 0) > 0);
  if (!bill) {
    showToast("当前租赁账单已全部收清", "success");
    return;
  }
  await openPaymentRecordModal("RENTAL_BILL", bill.id, "RECEIPT", {
    kind: "rental",
    item: rental,
    label: `${rental.rentalNo || `租赁 #${rental.id}`} · ${dateValue(bill.billPeriod) || "租金账单"}`,
    machineId: rental.machineId,
    targetAmount: amountValue(bill.amount)
  });
}

async function openRentalPaymentReversalModal(rentalId) {
  const rental = findEntity("rental", rentalId);
  const bills = await api(endpoints.rental.bills(rentalId));
  const recordGroups = await Promise.all((bills || []).map(async bill => {
    const records = await api(endpoints.payment.list("RENTAL_BILL", bill.id));
    return (records || []).map(record => ({
      ...record,
      sourceLabel: dateValue(bill.billPeriod) || `账单 #${bill.id}`
    }));
  }));
  const records = recordGroups.flat();
  const reversedIds = new Set(records.map(record => record.reversalOfPaymentId).filter(Boolean).map(String));
  const reversible = records.filter(record =>
    Number(record.amount || 0) > 0
    && !record.reversalOfPaymentId
    && !reversedIds.has(String(record.id))
  );
  if (!reversible.length) {
    showToast("当前租赁没有可冲销的收款记录", "info");
    return;
  }
  await openEntityModal("paymentReversal", {
    sourceType: "RENTAL_BILL",
    sourceId: reversible[reversible.length - 1].sourceId,
    sourceKind: "rental",
    sourceLabel: rental.rentalNo || `租赁 #${rental.id}`,
    sourceMachineId: rental.machineId,
    paymentId: reversible[reversible.length - 1].id,
    requestId: createRequestId("payment-reversal"),
    paymentRecords: records
  });
}

async function openPaymentReversalModal(sourceType, sourceId) {
  const normalizedSourceType = String(sourceType || "").trim().toUpperCase();
  if (!normalizedSourceType || !sourceId) {
    throw new Error("收付款来源信息不完整");
  }
  const records = await api(endpoints.payment.list(normalizedSourceType, sourceId));
  const reversedIds = new Set((records || [])
    .map(record => record.reversalOfPaymentId)
    .filter(Boolean)
    .map(String));
  const reversible = (records || []).filter(record =>
    Number(record.amount || 0) > 0
    && !record.reversalOfPaymentId
    && !reversedIds.has(String(record.id))
  );
  if (!reversible.length) {
    showToast("当前业务单据没有可冲销的收付款记录", "info");
    return;
  }
  const source = paymentSourceContext(normalizedSourceType, sourceId);
  await openEntityModal("paymentReversal", {
    sourceType: normalizedSourceType,
    sourceId,
    sourceKind: source.kind,
    sourceLabel: source.label,
    sourceMachineId: source.machineId,
    paymentId: reversible[reversible.length - 1].id,
    requestId: createRequestId("payment-reversal"),
    paymentRecords: records || []
  });
}

function paymentSourceContext(sourceType, sourceId) {
  const mapping = {
    OUTBOUND_ORDER: ["outboundOrder", findEntity("outboundOrder", sourceId)],
    PURCHASE_ORDER: ["purchaseOrder", findEntity("purchaseOrder", sourceId)],
    REPAIR: ["repair", findEntity("repair", sourceId)],
    MODIFICATION_WORK_ORDER: ["modificationOrder", findModificationOrder(sourceId)]
  };
  const [kind, item] = mapping[sourceType] || ["", {}];
  return {
    kind,
    item,
    label: kind ? entityDisplayName(kind, item) : `${sourceType} #${sourceId}`,
    machineId: item?.machineId || (item?.resourceType === "MACHINE" ? item.resourceId : null)
  };
}

function paymentTargetAmount(sourceType, source = {}, direction) {
  const item = source.item || {};
  if (sourceType === "OUTBOUND_ORDER" && direction === "RECEIPT") {
    return amountValue(item.receivableAmount ?? item.lineAmount);
  }
  if (sourceType === "PURCHASE_ORDER" && direction === "PAYMENT") {
    return amountValue(item.totalAmount) + amountValue(item.freightAmount);
  }
  if (sourceType === "REPAIR") {
    return direction === "PAYMENT"
      ? amountValue(item.repairExpense)
      : amountValue(item.receivableAmount ?? item.totalFee);
  }
  if (sourceType === "MODIFICATION_WORK_ORDER" && direction === "RECEIPT") {
    return (item.lines || []).reduce((total, line) => total + amountValue(line.chargeAmount), 0);
  }
  if (sourceType === "RENTAL_BILL" && direction === "RECEIPT") {
    return amountValue(source.targetAmount ?? item.amount);
  }
  return 0;
}

async function cancelModificationOrder(id) {
  const order = findModificationOrder(id);
  if (!order.id) {
    showToast("工单数据已刷新，请再点一次取消", "info");
    await loadAllData();
    renderCurrentTab();
    return;
  }
  if (!(await confirmDanger({
    title: "取消改装工单",
    target: entityDisplayName("modificationOrder", order),
    impact: "取消后该工单不会再进入完成流转。"
  }))) return;
  const activeModelKey = state.vehicleDetail?.modelKey;
  const targetMachineId = order.machineId || state.selectedVehicleId;
  await api(endpoints.modificationOrder.cancel(id), {
    method: "PUT",
    body: { version: order.version }
  });
  showContextSuccess("改装工单已取消", entityDisplayName("modificationOrder", order), "车辆和配件数据已刷新");
  markReferenceDataStale(["vehicle", "part"]);
  resetPageAfterMutation("modificationOrder");
  await refreshAfterMutation("modificationOrder", { activeModelKey, machineId: targetMachineId });
  renderCurrentTab();
}

function attachVersion(payload, item) {
  if (payload.version !== undefined && payload.version !== null) return;
  if (item?.version === undefined || item.version === null) return;
  payload.version = item.version;
}

function withVersion(url, item) {
  if (item?.version === undefined || item.version === null) return url;
  const separator = url.includes("?") ? "&" : "?";
  return `${url}${separator}version=${encodeURIComponent(item.version)}`;
}

function attachStockVersion(payload, type) {
  if (payload.version !== undefined && payload.version !== null) return;
  const source = type === "vehicle"
    ? findEntity("vehicle", Number(payload.machineId || 0))
    : state.data.parts.find(item => item.partCode === payload.partCode);
  attachVersion(payload, source || {});
}

function enrichPartReplacePayload(payload) {
  const context = state.modal?.context || {};
  const machine = context.machine || findEntity("vehicle", Number(payload.machineId || 0));
  const config = (context.machineConfigs || [])
    .find(item => String(item.id) === String(payload.machineConfigId));
  const part = state.data.parts.find(item => String(item.id) === String(payload.newPartId));
  const configValue = (state.data.configValueMap[config?.configItemId] || [])
    .find(item => String(item.id) === String(payload.newConfigValueId));

  if (payload.machineVersion === undefined && machine?.version !== undefined) {
    payload.machineVersion = machine.version;
  }
  if (payload.machineConfigVersion === undefined && config?.version !== undefined) {
    payload.machineConfigVersion = config.version;
  }
  if (payload.newPartVersion === undefined && part?.version !== undefined) {
    payload.newPartVersion = part.version;
  }
  if (payload.newConfigValueVersion === undefined && configValue?.version !== undefined) {
    payload.newConfigValueVersion = configValue.version;
  }
  if (!payload.warehouseId && part?.warehouseId) {
    payload.warehouseId = part.warehouseId;
  }
  if (!payload.oldPartWarehouseId && payload.oldPartDisposition !== "SCRAP" && payload.oldPartDisposition !== "DISCARD") {
    payload.oldPartWarehouseId = payload.warehouseId;
  }
  if (!payload.businessDate) {
    payload.businessDate = todayInputDate();
  }
}

function enrichVehiclePartInstallPayload(payload) {
  const machine = state.modal?.context?.machine || findEntity("vehicle", Number(payload.machineId || 0));
  const part = state.data.parts.find(item => String(item.id) === String(payload.newPartId));
  if (payload.machineVersion === undefined && machine?.version !== undefined) {
    payload.machineVersion = machine.version;
  }
  if (payload.newPartVersion === undefined && part?.version !== undefined) {
    payload.newPartVersion = part.version;
  }
  if (!payload.warehouseId && part?.warehouseId) {
    payload.warehouseId = part.warehouseId;
  }
  if (!payload.businessDate) {
    payload.businessDate = todayInputDate();
  }
}

function buildModificationOrderPayload(payload) {
  return {
    machineId: payload.machineId,
    machineVersion: payload.machineVersion,
    customerName: payload.customerName,
    salesOrderNo: payload.salesOrderNo,
    workOrderType: payload.workOrderType || "PRE_SALE",
    warehouseId: payload.warehouseId,
    businessDate: payload.businessDate || todayInputDate(),
    operator: payload.operator,
    remark: payload.remark,
    lines: [
      {
        machineConfigId: payload.machineConfigId,
        machineConfigVersion: payload.machineConfigVersion,
        newPartId: payload.newPartId,
        newPartVersion: payload.newPartVersion,
        newConfigValueId: payload.newConfigValueId,
        newConfigValueVersion: payload.newConfigValueVersion,
        quantity: payload.quantity || 1,
        oldPartAction: payload.oldPartAction || "STOCK_IN",
        priceDifference: payload.priceDifference || 0,
        warehouseId: payload.warehouseId,
        chargeUnitPrice: payload.chargeUnitPrice || 0,
        discountAmount: payload.discountAmount || 0,
        oldPartDisposition: payload.oldPartDisposition,
        oldPartWarehouseId: payload.oldPartWarehouseId,
        oldPartCondition: payload.oldPartCondition,
        oldPartValuationSource: payload.oldPartValuationSource,
        oldPartUnitCost: payload.oldPartUnitCost,
        remark: payload.remark
      }
    ]
  };
}

function buildInboundConfigs(item, specificationModel = effectiveFieldValue(item, "specificationModel")) {
  const matchedTemplate = vehicleConfigItemBySpecificationModel(specificationModel);
  const canUseTemplateRows = matchedTemplate
    && normalizeText(item.__vehicleConfigTemplateSpec) === normalizeText(matchedTemplate.specificationModel);
  const byConfigItem = new Map();
  for (const row of ensureConfigSelections(item)) {
    if (row.__fromVehicleTemplate && !canUseTemplateRows) continue;
    const configItemId = effectiveConfigSelectionValue(row, "configItemId");
    const configValueId = effectiveConfigSelectionValue(row, "configValueId");
    if (!configItemId || !configValueId) continue;
    const configItem = state.data.configItems.find(item => String(item.id) === String(configItemId));
    const configValue = (state.data.configValueMap[configItemId] || [])
      .find(value => String(value.id) === String(configValueId));
    byConfigItem.set(String(configItemId), {
      configItemId: Number(configItemId),
      configValueId: Number(configValueId),
      itemName: configItem?.itemName || "",
      selectedValue: configValue?.valueLabel || "",
      isStandard: true,
      configSource: row.__fromVehicleTemplate ? "VEHICLE_TEMPLATE" : "FACTORY_STANDARD"
    });
  }
  return [...byConfigItem.values()];
}

function buildVehicleInboundPayload(payload, item = {}) {
  const model = item.__modelIdentity || {};
  return {
    ...payload,
    name: model.name || payload.name,
    specificationModel: model.specificationModel || payload.specificationModel,
    machineType: model.machineType || payload.machineType,
    modelOnly: false
  };
}

async function ensureVehicleOutboundCustomer(payload) {
  if (payload.customerMode !== "quickCreate") {
    if (!payload.customerId) {
      showToast("请选择客户", "error");
      return null;
    }
    return { customerId: payload.customerId, created: false };
  }

  const companyName = String(payload.customerCompanyName || "").trim();
  if (!companyName) {
    showToast("请填写客户公司", "error");
    return null;
  }

  const existingCustomer = state.data.customers.find(item => normalizeText(item.companyName) === normalizeText(companyName));
  if (existingCustomer) {
    return { customerId: existingCustomer.id, created: false };
  }

  return {
    customerId: null,
    created: true,
    customer: {
      companyName,
      address: payload.customerAddress,
      contactName: payload.customerContactName || companyName,
      contactPhone: payload.customerContactPhone,
      taxOrIdNumber: payload.customerTaxOrIdNumber,
      remarks: payload.customerRemarks
    }
  };
}

async function uploadInvoiceForOrder(order, form) {
  if (!order?.id) {
    throw new Error("未找到订单");
  }
  const file = form.elements.invoiceFile?.files?.[0];
  if (!file) {
    throw new Error("请选择发票文件");
  }
  await uploadOrderManagedAttachment(order, file, "INVOICE", "发票");
  showContextSuccess("发票已上传", entityDisplayName("outboundOrder", order), file.name);
}

async function uploadContractForOrder(order, form) {
  if (!order?.id) {
    throw new Error("未找到订单");
  }
  const file = form.elements.contractFile?.files?.[0];
  if (!file) {
    throw new Error("请选择合同文件");
  }
  await uploadOrderManagedAttachment(order, file, "CONTRACT", "合同");
  showContextSuccess("合同已上传", entityDisplayName("outboundOrder", order), file.name);
}

async function restoreDataBackup(form) {
  const file = form.elements.backupFile?.files?.[0];
  const confirmation = String(form.elements.confirmation?.value || "").trim();
  if (!file) {
    throw new Error("请选择备份文件");
  }
  const formData = new FormData();
  formData.append("file", file);
  formData.append("confirmation", confirmation);
  await api(endpoints.admin.restore, {
    method: "POST",
    body: formData
  });
}

async function toggleOutboundOrderStatus(id, field) {
  const order = findEntity("outboundOrder", id);
  if (!order?.id || !field) return;
  const overrides = orderStatusToggleOverrides(order, field);
  if (!overrides) return;

  await api(endpoints.outboundOrder.update(order.id), {
    method: "PUT",
    body: buildOutboundOrderStatusPayload(order, overrides)
  });
  showToast("订单状态已更新；再次点击可切回", "success");
  markReferenceDataStale(["vehicle", "outboundOrder"]);
  await refreshAfterMutation("outboundOrder", {
    machineId: order.resourceType === "MACHINE" ? order.resourceId : state.selectedVehicleId
  });
  renderCurrentTab();
}

async function toggleRepairStatus(id) {
  const repair = findEntity("repair", id);
  if (!repair?.id || !hasPermission("repair:write")) return;
  const nextStatus = repair.status === "COMPLETED" ? "PENDING" : "COMPLETED";
  if (nextStatus === "PENDING") {
    const paymentRecords = await api(endpoints.payment.list("REPAIR", repair.id));
    const paymentTotals = (paymentRecords || []).reduce((totals, record) => {
      const direction = String(record.direction || "").toUpperCase();
      if (direction === "RECEIPT" || direction === "PAYMENT") {
        totals[direction] += Number(record.amount || 0);
      }
      return totals;
    }, { RECEIPT: 0, PAYMENT: 0 });
    if (Math.abs(paymentTotals.RECEIPT) > 0.005 || Math.abs(paymentTotals.PAYMENT) > 0.005) {
      showToast("该维修已有未冲销收付款，请先在详情中执行收付款冲销", "info");
      return;
    }
  }
  await api(endpoints.repair.updateStatus(repair.id), {
    method: "PUT",
    body: {
      version: repair.version,
      status: nextStatus
    }
  });
  showToast(nextStatus === "COMPLETED" ? "维修已标记完成；再次点击可撤回" : "维修已改回待处理", "success");
  markReferenceDataStale(["repair"]);
  await refreshAfterMutation("repair", { machineId: repair.machineId || state.selectedVehicleId });
  renderCurrentTab();
}

async function setRepairStatusDirect(repair, status) {
  await api(endpoints.repair.updateStatus(repair.id), {
    method: "PUT",
    body: {
      version: repair.version,
      status
    }
  });
}

async function batchCompleteRepairs() {
  const rows = selectedRows("repair").filter(row => row.status !== "COMPLETED");
  if (!rows.length) {
    showToast("没有可完成的维修记录", "info");
    return;
  }
  if (!(await confirmDanger({
    title: "批量完成维修",
    target: `${rows.length} 条维修记录`,
    impact: "所选记录会统一标记为已完成。"
  }))) return;
  await api(endpoints.repair.batchComplete, {
    method: "POST",
    body: versionedBatchPayload(rows)
  });
  showContextSuccess("维修记录已批量完成", `${rows.length} 条`, "列表已刷新");
  clearBatchSelection("repair");
  markReferenceDataStale(["repair"]);
  resetPageAfterMutation("repair");
  await refreshAfterMutation("repair", { refreshDetail: false });
  renderCurrentTab();
}

async function batchReceivePurchases() {
  const rows = selectedRows("purchaseOrder").filter(row => ["ORDERED", "PARTIAL", "ARRIVED"].includes(row.status));
  if (!rows.length) {
    showToast("没有可收货的入库订单", "info");
    return;
  }
  if (!(await confirmDanger({
    title: "批量收货",
    target: `${rows.length} 条入库订单`,
    impact: "所选订单会统一标记为已收货，运费默认为 0。"
  }))) return;
  await api(endpoints.purchaseOrder.batchReceive, {
    method: "POST",
    body: versionedBatchPayload(rows)
  });
  showContextSuccess("入库订单已批量收货", `${rows.length} 条`, "入库列表已刷新");
  clearBatchSelection("purchaseOrder");
  resetPageAfterMutation("purchaseOrder");
  await refreshAfterMutation("purchaseOrder", { refreshDetail: false });
  renderCurrentTab();
}

async function batchCompleteStocktakes() {
  const rows = selectedRows("stocktaking").filter(row => row.status !== "COMPLETED");
  if (!rows.length) {
    showToast("没有可入账的盘点记录", "info");
    return;
  }
  if (!(await confirmDanger({
    title: "批量盘点入账",
    target: `${rows.length} 条盘点记录`,
    impact: "所选盘点会同步库存数量，入账后不能作为草稿继续编辑。"
  }))) return;
  await api(endpoints.stocktaking.batchComplete, {
    method: "POST",
    body: versionedBatchPayload(rows)
  });
  showContextSuccess("盘点记录已批量入账", `${rows.length} 条`, "库存数量已同步");
  clearBatchSelection("stocktaking");
  markReferenceDataStale(["vehicle", "part"]);
  resetPageAfterMutation("stocktaking");
  await refreshAfterMutation("stocktaking", { refreshDetail: false });
  renderCurrentTab();
}

async function batchDeleteStocktakeDrafts() {
  const rows = selectedRows("stocktaking").filter(row => row.status !== "COMPLETED");
  if (!rows.length) {
    showToast("没有可删除的盘点草稿", "info");
    return;
  }
  if (!(await confirmDanger({
    title: "批量删除盘点草稿",
    target: `${rows.length} 条盘点草稿`,
    impact: "仅删除未入账草稿；删除后不可从列表恢复。"
  }))) return;
  await api(endpoints.stocktaking.batchDeleteDrafts, {
    method: "POST",
    body: versionedBatchPayload(rows)
  });
  showContextSuccess("盘点草稿已批量删除", `${rows.length} 条`, "盘点列表已刷新");
  clearBatchSelection("stocktaking");
  resetPageAfterMutation("stocktaking");
  await refreshAfterMutation("stocktaking", { refreshDetail: false });
  renderCurrentTab();
}

async function toggleOutboundOrderLock(id, locked) {
  const order = findEntity("outboundOrder", id);
  if (!order?.id || !canManageOrderLock()) return;
  if (!(await confirmDanger({
    title: locked ? "确认锁定订单" : "确认解锁订单",
    target: entityDisplayName("outboundOrder", order),
    impact: locked ? "锁定后关联记录仅管理员可见，请确认订单不再需要普通流程编辑。" : "解锁后关联记录会恢复普通流程可见性。"
  }))) return;
  const params = new URLSearchParams({ locked: String(locked) });
  if (order.version !== undefined && order.version !== null) {
    params.set("version", String(order.version));
  }
  await api(`${endpoints.outboundOrder.lock(order.id)}?${params.toString()}`, { method: "PUT" });
  showToast(locked ? "订单已锁定，关联记录仅管理员可见" : "订单已解锁", "success");
  markReferenceDataStale(["vehicle", "part", "outboundOrder"]);
  await refreshAfterMutation("outboundOrder", {
    machineId: order.resourceType === "MACHINE" ? order.resourceId : state.selectedVehicleId
  });
  renderCurrentTab();
}

async function goToTab(tab, data = {}) {
  if (!tabs[tab] || !canAccessTab(tab)) return;
  state.activeTab = tab; if (state.pages[tab]) resetPage(state.pages[tab]);
  if (tab === "vehicles") {
    state.filters.vehicles.stock = data.stock || "";
  }
  if (tab === "parts") {
    state.filters.parts = {
      ...(state.filters.parts || {}),
      stock: data.stock || ""
    };
  }
  if (tab === "outboundOrders") {
    state.filters.outboundOrders = {
      ...(state.filters.outboundOrders || {}),
      stage: data.stage || ""
    };
  }
  if (tab === "rentals") {
    state.filters.rentals = {
      ...(state.filters.rentals || {}),
      status: data.status || ""
    };
  }
  if (tab === "repairs") {
    state.filters.repairs = {
      ...(state.filters.repairs || {}),
      status: data.status || ""
    };
  }
  if (tab === "purchases") {
    state.filters.purchases = {
      ...(state.filters.purchases || {}),
      resourceType: data.resourceType || ""
    };
  }
  if (tab === "attachments") {
    state.filters.attachments = {
      ...(state.filters.attachments || {}),
      resourceType: data.resourceType || "",
      resourceId: data.resourceId || "",
      category: data.category || "",
      includeDeleted: data.includeDeleted || ""
    };
  }
  if (tab === "imports") {
    state.filters.imports = {
      ...(state.filters.imports || {}),
      importType: data.importType || ""
    };
  }
  els.content.innerHTML = renderLoading();
  await loadCurrentTab({ force: true });
  renderCurrentTab();
  scrollToWorkspaceTop();
}

function orderStatusToggleOverrides(order, field) {
  if (field === "paymentSettled") {
    return { paymentSettled: !Boolean(order.paymentSettled) };
  }
  if (field === "salesReported") {
    const next = !Boolean(order.salesReported);
    return {
      salesReported: next,
      salesReportDate: next && !order.salesReportDate ? todayInputDate() : order.salesReportDate
    };
  }
  if (field === "invoiceApplied") {
    const next = !Boolean(order.invoiceApplied);
    return {
      invoiceApplied: next,
      invoiceApplicationDate: next && !order.invoiceApplicationDate ? todayInputDate() : order.invoiceApplicationDate
    };
  }
  if (field === "registrationStatus") {
    return { registrationStatus: yesNoFromStatusText(order.registrationStatus) ? "未上牌" : "已上牌" };
  }
  if (field === "contractType") {
    return { contractType: yesNoFromStatusText(order.contractType) ? "无合同" : "有合同" };
  }
  return null;
}

function buildOutboundOrderStatusPayload(order, overrides) {
  return {
    version: order.version,
    unitSalePrice: order.unitSalePrice ?? order.settlementPrice,
    lineAmount: order.lineAmount ?? order.receivableAmount,
    salesDate: order.salesDate,
    salePrice: order.salePrice,
    receivedAmount: order.receivedAmount,
    paymentDueDate: order.paymentDueDate,
    lastPaymentDate: order.lastPaymentDate,
    paymentSettled: Boolean(order.paymentSettled),
    paymentRemark: order.paymentRemark,
    salesReported: Boolean(order.salesReported),
    invoiceApplied: Boolean(order.invoiceApplied),
    salesReportDate: order.salesReportDate,
    invoiceApplicationDate: order.invoiceApplicationDate,
    invoiceStatus: order.invoiceStatus,
    invoiceIssuedDate: order.invoiceIssuedDate,
    registrationStatus: order.registrationStatus,
    contractType: order.contractType,
    orderRemark: order.orderRemark,
    operator: order.operator,
    ...overrides
  };
}

async function openEntityModal(kind, item = {}) {
  await ensureModalDependencies(kind);
  if ((kind === "configValue" || kind === "vehiclePartInstall" || kind === "modificationOrder" || kind === "vehicleConfigChange" || kind === "vehicleInbound" || kind === "vehicleConfigValue" || kind === "purchaseOrder") && (!state.data.configItems.length || !state.data.vehicleConfigItems.length)) {
    await ensureConfigData();
  }

  state.modal = { kind, item: { ...item }, context: {} };
  if (kind === "purchaseOrder") {
    preparePurchaseOrderModalItem(state.modal.item);
  }
  if (kind === "repair") {
    prepareRepairModalItem(state.modal.item);
  }
  if (kind === "rental" && (state.modal.item.monthlyRentalPrice === undefined || state.modal.item.monthlyRentalPrice === null)) {
    state.modal.item.monthlyRentalPrice = state.modal.item.rentalPrice || "";
  }
  if (usesVehicleInboundConfigEditor(kind, state.modal.item)) {
    ensureConfigSelections(state.modal.item);
    await prepareVehicleInboundTemplate(state.modal.item);
  }
  if (kind === "partReplace" || kind === "modificationOrder") {
    await preparePartReplaceContext(
      effectiveFieldValue(state.modal.item, "machineId") || state.selectedVehicleId,
      effectiveFieldValue(state.modal.item, "machineConfigId"),
      { placeholderConfig: kind === "modificationOrder" }
    );
  }
  if (kind === "vehiclePartInstall") {
    prepareVehiclePartInstallContext(effectiveFieldValue(state.modal.item, "machineId") || state.selectedVehicleId);
  }
  if (kind === "vehicleConfigChange") {
    await prepareVehicleConfigChangeContext();
  }
  renderModal();
}

async function ensureModalDependencies(kind) {
  const needsVehicles = ["vehicleStock", "vehicleOutbound", "partReplace", "vehiclePartInstall", "modificationOrder", "vehicleConfigChange", "vehicleInbound", "rental", "repair", "stocktaking", "stockTransfer", "attachmentUpload"].includes(kind);
  const needsParts = ["part", "partStock", "partReplace", "vehiclePartInstall", "modificationOrder", "vehicleConfigChange", "repair", "stocktaking", "stockTransfer", "attachmentUpload"].includes(kind);
  const needsCustomers = ["vehicleOutbound", "partStock", "partOutbound", "customer", "rental", "repair", "attachmentUpload"].includes(kind);
  const needsSuppliers = kind === "purchaseOrder";
  const needsRentals = ["vehicleOutbound", "rental", "repair"].includes(kind);
  const needsRepairUsers = kind === "repair";
  const needsWarehouses = [
    "vehicle", "vehicleInbound", "vehicleStock", "vehicleOutbound",
    "part", "partStock", "rental", "repair", "purchaseOrder",
    "stocktaking", "stockTransfer", "partReplace", "vehiclePartInstall",
    "modificationOrder", "vehicleConfigChange", "warehouse"
  ].includes(kind);
  const needsRepairs = kind === "attachmentUpload";
  const needsOutboundOrders = kind === "attachmentUpload";
  const needsConfigItems = ["configValue", "vehicleInbound", "vehicleConfigValue", "vehicleConfigItem", "purchaseOrder"].includes(kind);
  const requests = [];
  if (needsVehicles && !state.reference.vehiclesLoaded) {
    requests.push(api(referencePageUrl(endpoints.vehicle.list)).then(payload => {
      state.data.vehicles = sortById(rowsFromPayload(payload));
      state.reference.vehiclesLoaded = true;
    }));
  }
  if (needsParts && !state.reference.partsLoaded) {
    requests.push(api(referencePageUrl(endpoints.part.list)).then(payload => {
      state.data.parts = sortById(rowsFromPayload(payload));
      state.reference.partsLoaded = true;
    }));
  }
  if (needsCustomers && !state.reference.customersLoaded && hasAnyPermission("stock:adjust", "vehicle:write", "repair:write")) {
    requests.push(api(referencePageUrl(endpoints.customer.list)).then(payload => {
      state.data.customers = sortById(rowsFromPayload(payload), false);
      state.reference.customersLoaded = true;
    }));
  }
  if (needsSuppliers && !state.reference.suppliersLoaded && hasPermission("stock:adjust")) {
    requests.push(api(referencePageUrl(endpoints.supplier.list)).then(payload => {
      state.data.suppliers = sortById(rowsFromPayload(payload), false);
      state.reference.suppliersLoaded = true;
    }));
  }
  if (needsRentals && !state.reference.rentalsLoaded && hasPermission("stock:adjust")) {
    requests.push(api(referencePageUrl(endpoints.rental.list)).then(payload => {
      state.data.rentals = sortById(rowsFromPayload(payload));
      state.reference.rentalsLoaded = true;
    }));
  }
  if (needsRepairUsers && hasPermission("repair:write")) {
    requests.push(api(endpoints.user.repairers).then(payload => {
      state.data.repairUsers = sortById(rowsFromPayload(payload));
      state.reference.repairUsersLoaded = true;
    }));
  }
  if (needsWarehouses && !state.reference.warehousesLoaded && hasPermission("stock:adjust")) {
    requests.push(api(referencePageUrl(endpoints.warehouse.list)).then(payload => {
      state.data.warehouses = sortById(rowsFromPayload(payload), false);
      state.reference.warehousesLoaded = true;
    }));
  }
  if (needsRepairs) {
    requests.push(api(referencePageUrl(endpoints.repair.list)).then(payload => {
      state.data.repairs = sortById(rowsFromPayload(payload));
    }));
  }
  if (needsOutboundOrders && hasPermission("stock:adjust")) {
    requests.push(api(referencePageUrl(endpoints.outboundOrder.list)).then(payload => {
      state.data.outboundOrders = sortById(rowsFromPayload(payload));
      state.reference.outboundOrdersLoaded = true;
    }));
  }
  if (needsConfigItems && (!state.data.configItems.length || !state.data.vehicleConfigItems.length)) {
    requests.push(ensureConfigData());
  }
  await Promise.all(requests);
}

async function preparePartReplaceContext(machineId, machineConfigId, options = {}) {
  const numericMachineId = Number(machineId || 0);
  if (!numericMachineId) {
    state.modal.context.machine = null;
    state.modal.context.machineConfigs = [];
    state.modal.context.compatibleParts = [];
    return;
  }
  const detail = await api(`/api/inventory/${numericMachineId}/detail`);
  state.modal.item.machineId = numericMachineId;
  state.modal.context.machine = detail.machine || findEntity("vehicle", numericMachineId);
  state.modal.context.machineConfigs = detail.configs || [];
  const selectedConfigId = machineConfigId || effectiveFieldValue(state.modal.item, "machineConfigId") || state.modal.context.machineConfigs[0]?.id || null;
  if (options.placeholderConfig) {
    const placeholderConfig = state.modal.context.machineConfigs
      .find(item => String(item.id) === String(selectedConfigId));
    clearPrefill(state.modal.item, "machineConfigId");
    state.modal.item.newPartId = null;
    state.modal.item.__placeholders = {
      ...(state.modal.item.__placeholders || {}),
      machineConfigId: placeholderConfig ? `预填：${machineConfigOptionLabel(placeholderConfig)}` : undefined
    };
    setPrefill(
      state.modal.item,
      "machineConfigId",
      placeholderConfig?.id,
      placeholderConfig ? `预填：${machineConfigOptionLabel(placeholderConfig)}` : undefined
    );
  } else {
    state.modal.item.machineConfigId = selectedConfigId;
  }
  updateCompatibleParts(selectedConfigId);
  if (options.placeholderConfig) {
    if (state.modal.item.__prefillValues?.machineId) {
      state.modal.item.machineId = null;
    }
    state.modal.item.machineConfigId = null;
  }
}

function updateCompatibleParts(machineConfigId) {
  const config = (state.modal?.context?.machineConfigs || [])
    .find(item => String(item.id) === String(machineConfigId));
  state.modal.item.machineConfigId = machineConfigId;
  const expectedTypes = configTypeCandidates(config);
  state.modal.context.compatibleParts = state.data.parts
    .filter(part => !part.isLocked)
    .filter(part => Number(part.quantity || 0) > 0)
    .filter(part => expectedTypes.includes(normalizeText(part.partCategory)));
}

function configTypeCandidates(config) {
  if (!config) return [];
  const item = state.data.configItems.find(configItem => String(configItem.id) === String(config.configItemId));
  return uniqueOptions([
    item?.subCategory,
    item?.itemName,
    config.itemName
  ]).map(option => normalizeText(option.value));
}

function prepareVehiclePartInstallContext(machineId) {
  const numericMachineId = Number(machineId || 0);
  state.modal.context.machine = numericMachineId ? findEntity("vehicle", numericMachineId) : null;
  if (numericMachineId) {
    state.modal.item.machineId = numericMachineId;
  }
  const firstCategory = installPartCategoryOptions()[0]?.value || null;
  if (!state.modal.item.configItemId && firstCategory) {
    state.modal.item.configItemId = firstCategory;
  }
}

async function prepareVehicleConfigChangeContext() {
  const mode = vehicleConfigChangeMode();
  if (mode === "INSTALL") {
    prepareVehiclePartInstallContext(effectiveFieldValue(state.modal.item, "machineId") || state.selectedVehicleId);
    return;
  }
  await preparePartReplaceContext(
    effectiveFieldValue(state.modal.item, "machineId") || state.selectedVehicleId,
    effectiveFieldValue(state.modal.item, "machineConfigId"),
    { placeholderConfig: mode === "WORK_ORDER" }
  );
}

function canSaveAndContinue(kind, item = {}) {
  if (item?.id) return false;
  if (kind === "partStock") return item.direction === "inbound";
  return ["vehicleInbound", "customer", "supplier", "purchaseOrder", "stocktaking", "repair"].includes(kind);
}

function nextEntityAfterContinue(kind, item = {}, payload = {}) {
  if (kind === "vehicleInbound") {
    return vehicleInboundDefaultsForModel({
      modelKey: item.__modelKey || "",
      name: payload.name,
      specificationModel: payload.specificationModel,
      configuration: payload.configuration,
      machineType: payload.machineType,
      supplier: payload.supplier,
      warehouseName: payload.warehouseName,
      purchasePrice: payload.purchasePrice,
      settlementPrice: payload.settlementPrice
    });
  }
  if (kind === "partStock") {
    const next = { direction: "inbound", quantity: 1, operator: payload.operator || "" };
    if (payload.partCode) setPrefill(next, "partCode", payload.partCode, `预填：${payload.partCode}`);
    return next;
  }
  if (kind === "purchaseOrder") {
    const resourceType = String(payload.resourceType || "PART").toUpperCase() === "MACHINE" ? "MACHINE" : "PART";
    const next = purchaseOrderDefaults({
      supplierId: payload.supplierId,
      configItemId: payload.configItemId,
      configValueId: payload.configValueId,
      resourceType,
      name: payload.name || payload.resourceName,
      specificationModel: payload.specificationModel,
      configuration: payload.configuration,
      machineType: payload.machineType,
      supplier: payload.supplier || payload.supplierName,
      warehouseName: payload.warehouseName,
      purchasePrice: payload.purchasePrice || payload.unitPrice,
      settlementPrice: payload.settlementPrice || payload.unitPrice
    });
    next.operator = payload.operator || "";
    next.unit = payload.unit || (resourceType === "MACHINE" ? "台" : "件");
    return next;
  }
  if (kind === "stocktaking") {
    return {
      resourceType: payload.resourceType || "PART",
      actualQuantity: 0,
      status: "DRAFT",
      stocktakingDate: todayInputDate(),
      operator: payload.operator || ""
    };
  }
  if (kind === "repair") {
    return repairDefaultsForMachine(findEntity("vehicle", Number(payload.machineId || 0)), { customerId: payload.customerId });
  }
  return defaultEntity(kind);
}

async function resetVehicleConfigChangeMode(mode) {
  const current = state.modal?.item || {};
  const machineId = effectiveFieldValue(current, "machineId") || state.selectedVehicleId;
  const machine = state.modal?.context?.machine || findEntity("vehicle", Number(machineId || 0));
  const configId = effectiveFieldValue(current, "machineConfigId");
  state.modal.item = vehicleConfigChangeDefaults(machine, mode, configId);
  await prepareVehicleConfigChangeContext();
}

function focusFirstModalField() {
  requestAnimationFrame(() => {
    const first = els.modalCard.querySelector("input:not([type='hidden']):not([readonly]), textarea:not([readonly]), select");
    first?.focus();
    if (typeof first?.select === "function") first.select();
  });
}

function renderCurrentTab() {
  syncShell();
  switch (state.activeTab) {
    case "vehicles":
      els.content.innerHTML = renderVehicles();
      break;
    case "parts":
      els.content.innerHTML = renderParts();
      break;
    case "modifications":
      els.content.innerHTML = renderModificationOrders();
      break;
    case "outboundOrders":
      els.content.innerHTML = renderOutboundOrders();
      break;
    case "rentals":
      els.content.innerHTML = renderRentals();
      break;
    case "customers":
      els.content.innerHTML = renderCustomers();
      break;
    case "suppliers":
      els.content.innerHTML = renderSuppliers();
      break;
    case "purchases":
      els.content.innerHTML = renderPurchaseOrders();
      break;
    case "stocktakes":
      els.content.innerHTML = renderStocktakes();
      break;
    case "warehouses":
      els.content.innerHTML = renderWarehouses();
      break;
    case "repairs":
      els.content.innerHTML = renderRepairs();
      break;
    case "stats":
      els.content.innerHTML = renderStatistics();
      break;
    case "attachments":
      els.content.innerHTML = renderAttachments();
      break;
    case "imports":
      els.content.innerHTML = renderImports();
      break;
    case "logs":
      els.content.innerHTML = renderOperationLogs();
      break;
    case "configs":
      els.content.innerHTML = renderConfigsV2();
      break;
    case "users":
      els.content.innerHTML = renderUsers();
      break;
    case "maintenance":
      els.content.innerHTML = renderMaintenance();
      break;
    default:
      els.content.innerHTML = renderOverview();
  }
  renderDetailDrawer();
  persistListState();
}

function scrollToWorkspaceTop() {
  window.scrollTo({ top: 0, behavior: "smooth" });
}

function detailTitle(kind, item) {
  return {
    part: item.partName || item.partCode,
    modificationOrder: item.workOrderNo || item.machineProductNumber,
    outboundOrder: item.orderNo || item.resourceCode,
    rental: item.rentalNo || item.vehicleNumber,
    customer: item.companyName || item.contactName,
    supplier: item.supplierName || item.contactName,
    purchaseOrder: item.purchaseNo || item.resourceName,
    stocktaking: item.stocktakingNo || item.resourceName,
    repair: item.customerName || item.vehicleNumber,
    attachment: item.originalName || item.attachmentLabel,
    importJob: item.originalFileName || item.templateName,
    user: item.username
  }[kind] || `ID ${item.id}`;
}

function detailFields(kind, item) {
  const fields = {
    part: [
      ["编码", item.partCode],
      ["名称", item.partName],
      ["品牌", item.partBrand],
      ["分类", item.partCategory],
      ["适配车型", item.applicableModels],
      ["库存", `${item.quantity ?? 0}${item.unit || ""}`],
      ["分仓余额", (item.warehouseBalances || []).map(balance =>
        `${balance.warehouseName || balance.warehouseCode || `仓库 ${balance.warehouseId}`}：可用 ${balance.availableQuantity || 0} / 预留 ${balance.reservedQuantity || 0} / 锁定 ${balance.lockedQuantity || 0}`
      ).join("；") || "-"],
      ["补货预警点", item.reorderPoint ?? 5],
      ["采购价", money(item.purchasePrice)],
      ["落地成本", money(item.landedUnitCost)],
      ["销售价", money(item.salePrice)],
      ["结算价", money(item.settlementPrice)],
      ["库存状态", item.isLocked ? "隔离待估值" : "可用"],
      ["来源", item.source],
      ["备注", item.remarks]
    ],
    modificationOrder: [
      ["工单号", item.workOrderNo],
      ["车辆", item.machineProductNumber],
      ["客户", item.customerName],
      ["销售单", item.salesOrderNo],
      ["状态", repairStatusText(item.status) || item.status],
      ["创建时间", dateTime(item.createdAt)],
      ["备注", item.remark]
    ],
    outboundOrder: [
      ["订单号", item.orderNo],
      ["出库类型", resourceTypeLabel(item.resourceType)],
      ["出库项", [item.resourceCode, item.resourceName].filter(Boolean).join(" / ")],
      ["客户", item.customerName],
      ["销售日期", dateValue(item.salesDate)],
      ["应收", money(item.receivableAmount ?? item.lineAmount)],
      ["已收", money(item.receivedAmount)],
      ["欠款", money(receivableOutstanding(item))],
      ["车款结清", yesNoText(item.paymentSettled)],
      ["报销售", yesNoText(item.salesReported)],
      ["发票申请", yesNoText(item.invoiceApplied)],
      ["合同", item.contractType],
      ["备注", item.orderRemark]
    ],
    rental: [
      ["租赁单", item.rentalNo],
      ["车号", item.vehicleNumber],
      ["车辆", [item.machineName, item.specificationModel].filter(Boolean).join(" / ")],
      ["客户", item.customerName],
      ["去向", item.destination],
      ["月租价", money(rentalMonthlyPrice(item))],
      ["开始日期", dateValue(item.startDate)],
      ["结束日期", dateValue(item.endDate)],
      ["状态", item.status === "RETURNED" ? "已归还" : "租赁中"],
      ["备注", item.remark]
    ],
    customer: [
      ["公司名称", item.companyName],
      ["地址", item.address],
      ["联系人", item.contactName],
      ["电话", item.contactPhone],
      ["税号/身份证号", item.taxOrIdNumber],
      ["备注", item.remarks]
    ],
    supplier: [
      ["供应商", item.supplierName],
      ["类型", item.supplierType],
      ["联系人", item.contactName],
      ["电话", item.contactPhone],
      ["地址", item.address],
      ["税号", item.taxNumber],
      ["备注", item.remarks]
    ],
    purchaseOrder: [
      ["采购单", item.purchaseNo],
      ["供应商", item.supplierName],
      ["采购内容", [item.resourceCode, item.resourceName].filter(Boolean).join(" / ")],
      ["规格", item.specificationModel],
      ["数量", `${item.quantity ?? 0}${item.unit || ""}`],
      ["金额", money(item.totalAmount)],
      ["运费", money(item.freightAmount)],
      ["状态", item.status],
      ["备注", item.remark]
    ],
    stocktaking: [
      ["盘点单", item.stocktakingNo],
      ["对象类型", purchaseResourceLabel(item.resourceType)],
      ["盘点对象", [item.resourceCode, item.resourceName].filter(Boolean).join(" / ")],
      ["账面数量", stockText(item.bookQuantity)],
      ["实盘数量", stockText(item.actualQuantity)],
      ["差异", stockText(item.differenceQuantity)],
      ["状态", item.status === "COMPLETED" ? "已入账" : "草稿"],
      ["备注", item.remark]
    ],
    repair: [
      ["维修时间", dateTime(item.repairDate)],
      ["车号", item.vehicleNumber],
      ["客户", item.customerName],
      ["地址", item.customerAddress],
      ["维修人", item.repairPerson],
      ["故障描述", item.faultDescription],
      ["维修收入", money(item.repairFee)],
      ...(item.repairExternal ? [["维修支出", money(item.repairExpense)]] : []),
      ["配件费", money(item.partsFee)],
      ["配件成本", money(item.partsCost)],
      ["客户应收", money(item.totalFee)],
      ["状态", repairStatusText(item.status)]
    ],
    attachment: [
      ["业务对象", attachmentResourceTypeLabel(item.resourceType)],
      ["对象编号", item.resourceCode || item.resourceId],
      ["对象名称", item.resourceName],
      ["附件类型", attachmentCategoryLabel(item.attachmentCategory)],
      ["文件名", item.originalName || item.attachmentLabel],
      ["文件大小", fileSize(item.fileSize)],
      ["上传人", item.uploadedBy],
      ["上传时间", dateTime(item.uploadedAt)],
      ["状态", item.deleted ? "已删除" : "有效"],
      ["备注", item.uploadNote]
    ],
    importJob: [
      ["导入类型", importTypeLabel(item.importType)],
      ["模板", item.templateName],
      ["文件名", item.originalFileName],
      ["状态", item.status],
      ["总行数", item.totalRows],
      ["有效行", item.validRows],
      ["错误行", item.errorRows],
      ["已导入", item.importedRows],
      ["提交人", item.createdBy],
      ["摘要", item.summary]
    ],
    user: [
      ["用户名", item.username],
      ["角色", (item.roles || []).join(" / ")],
      ["职务", jobTagLabel(item.jobTag)],
      ["状态", item.enabled ? "启用" : "停用"],
      ["创建时间", dateTime(item.createdAt)]
    ]
  }[kind] || Object.entries(item).slice(0, 8).map(([key, value]) => [key, value]);
  return fields.map(([label, value]) => ({ label, value }));
}

function focusPrimarySearch() {
  const input = els.content.querySelector("[data-search-for]");
  if (!input) {
    showToast("当前页面没有搜索框", "info");
    return;
  }
  input.focus();
  input.select();
}

async function syncShell() {
  if (!canAccessTab(state.activeTab)) {
    state.activeTab = "overview";
  }
  const tab = tabs[state.activeTab] || tabs.overview;
  els.content.dataset.activeTab = state.activeTab;
  els.pageTitle.textContent = tab.title;
  els.pageSubtitle.textContent = tab.subtitle;
  syncUserShell(els, state.user);
  els.mainNav.querySelectorAll(".nav-item").forEach(button => {
    const requiredRoles = (button.dataset.roles || button.dataset.role || "").split(",").map(role => role.trim()).filter(Boolean);
    const requiredPermissions = (button.dataset.permissions || button.dataset.permission || "").split(",").map(permission => permission.trim()).filter(Boolean);
    const allowed = (!requiredRoles.length || hasAnyRole(...requiredRoles))
      && (!requiredPermissions.length || hasAnyPermission(...requiredPermissions));
    button.classList.toggle("is-hidden", !allowed);
    button.classList.toggle("is-active", Boolean(button.dataset.tab) && button.dataset.tab === state.activeTab);
    if (button.dataset.tab === state.activeTab) {
      button.setAttribute("aria-current", "page");
    } else {
      button.removeAttribute("aria-current");
    }
  });
  els.newBusinessMenu?.querySelectorAll("[data-permissions], [data-roles]").forEach(button => {
    const requiredRoles = (button.dataset.roles || "").split(",").map(role => role.trim()).filter(Boolean);
    const requiredPermissions = (button.dataset.permissions || "").split(",").map(permission => permission.trim()).filter(Boolean);
    const allowed = (!requiredRoles.length || hasAnyRole(...requiredRoles))
      && (!requiredPermissions.length || hasAnyPermission(...requiredPermissions));
    button.classList.toggle("is-hidden", !allowed);
  });
  const hasBusinessAction = [...(els.newBusinessMenu?.querySelectorAll("button") || [])]
    .some(button => !button.classList.contains("is-hidden"));
  els.newBusinessBtn?.classList.toggle("is-hidden", !hasBusinessAction);
  els.mainNav.querySelectorAll("[data-nav-group]").forEach(group => {
    const hasVisibleItem = [...group.querySelectorAll(".nav-item")]
      .some(button => !button.classList.contains("is-hidden"));
    group.classList.toggle("is-hidden", !hasVisibleItem);
  });
}

function renderOverview() {
  return dashboardView.renderOverview();
}

function pageTotal(tab, fallback) {
  return state.pages[tab]?.loaded ? state.pages[tab].totalElements : fallback;
}

function paginateClientRows(tab, rows) {
  const page = state.pages[tab];
  const size = Math.max(1, Number(page?.size || LIST_PAGE_SIZE));
  const totalElements = rows.length;
  const totalPages = Math.ceil(totalElements / size);
  const maxPage = Math.max(0, totalPages - 1);
  page.page = Math.min(Math.max(0, Number(page.page || 0)), maxPage);
  page.size = size;
  page.totalElements = totalElements;
  page.totalPages = totalPages;
  page.first = page.page <= 0;
  page.last = totalPages === 0 || page.page >= maxPage;
  page.loaded = true;
  page.loading = false;
  const start = page.page * size;
  return rows.slice(start, start + size);
}

function latestVehicleOutboundOrder(machineId) {
  return state.data.outboundOrders.find(item => item.resourceType === "MACHINE" && Number(item.resourceId) === Number(machineId)) || null;
}

function activeRentalForMachine(machineId) {
  return state.data.rentals.find(item => item.status === "ACTIVE" && Number(item.machineId) === Number(machineId)) || null;
}

function yesNoFromText(value) {
  const normalized = String(value || "").trim();
  if (!normalized) return false;
  if (normalized.startsWith("否") || normalized.startsWith("不") || normalized === "/") return false;
  return true;
}

function yesNoText(value) {
  return value ? "是" : "否";
}

function vehicleConfigChangeModeOptions() {
  return [
    { value: "INSTALL", label: "新增装车" },
    { value: "REPLACE", label: "替换配件" },
    { value: "WORK_ORDER", label: "创建工单" }
  ];
}

function renderDetailGrid(items) {
  return `
    <div class="detail-grid detail-grid-compact">
      ${items.map(item => detailItem(item.label, item.value)).join("")}
    </div>
  `;
}

function renderToolbar(moduleKey, placeholder, kind, createLabel, createPermission, options = {}) {
  const searches = options.searches || (moduleKey && placeholder ? [{ key: moduleKey, placeholder }] : []);
  const mainItems = [
    ...searches.map(item => searchBox(item.key, item.placeholder)),
    ...(options.main || [])
  ].filter(Boolean);
  const canCreate = options.create !== false
    && kind
    && createLabel
    && (!createPermission || hasPermission(createPermission));
  const actions = [
    canCreate ? `<button class="btn btn-primary" type="button" data-action="create" data-kind="${escapeAttr(kind)}">${icon("plus")}${escapeHtml(createLabel)}</button>` : "",
    ...(options.actions || []),
    options.exportType === false ? "" : exportButton(options.exportType || moduleKey),
    options.refresh === false ? "" : `<button class="btn btn-ghost" type="button" data-action="refresh">${icon("refresh")}刷新</button>`
  ].filter(Boolean);
  const className = ["toolbar", options.className || ""].filter(Boolean).join(" ");
  return `
    <div class="${escapeAttr(className)}">
      <div class="toolbar-main">${mainItems.join("")}</div>
      <div class="toolbar-actions">${actions.join("")}</div>
    </div>
  `;
}

function searchBox(key, placeholder) {
  const value = state.search[key] || "";
  return `
    <label class="search-box">
      <span class="search-icon">${icons.search}</span>
      <input class="input" type="search" data-search-for="${escapeAttr(key)}" value="${escapeAttr(value)}" placeholder="${escapeAttr(placeholder)}" aria-label="${escapeAttr(placeholder)}" autocomplete="off" spellcheck="false">
      ${value ? `<button class="search-clear" type="button" data-action="clear-search" data-search-for="${escapeAttr(key)}" aria-label="清空搜索">${icons.close}</button>` : ""}
    </label>
  `;
}

function partFilterControls() {
  const filters = state.filters.parts || {};
  return `
    <div class="filter-row">
      ${filterButtonGroup("parts", "stock", filters.stock || "", "库存", "全部库存", [
        { value: "available", label: "有库存" },
        { value: "low", label: "库存预警" }
      ])}
    </div>
  `;
}

function outboundOrderFilterControls() {
  const filters = state.filters.outboundOrders || {};
  return `
    <div class="filter-row">
      ${filterButtonGroup("outboundOrders", "stage", filters.stage || "", "跟进", "全部跟进", [
        { value: "payment", label: "待收款" },
        { value: "overdue", label: "逾期收款" },
        { value: "salesReport", label: "待报销售" },
        { value: "invoiceApplication", label: "待申请发票" },
        { value: "invoiceFile", label: "待上传发票" },
        { value: "contractFile", label: "待上传合同" },
        { value: "closed", label: "闭环完成" }
      ])}
    </div>
  `;
}

function rentalFilterControls() {
  const filters = state.filters.rentals || {};
  return `
    <div class="filter-row">
      ${filterButtonGroup("rentals", "status", filters.status || "", "状态", "全部状态", [
        { value: "active", label: "租赁中" },
        { value: "dueSoon", label: "7天内到期" },
        { value: "overdue", label: "已逾期" },
        { value: "returned", label: "已归还" }
      ])}
    </div>
  `;
}

function repairFilterControls() {
  const filters = state.filters.repairs || {};
  return `
    <div class="filter-row">
      ${filterButtonGroup("repairs", "status", filters.status || "", "状态", "全部状态", [
        { value: "pending", label: "待处理" },
        { value: "completed", label: "已完成" }
      ])}
    </div>
  `;
}

function purchaseOrderFilterControls() {
  const filters = state.filters.purchases || {};
  return `
    <div class="filter-row">
      ${filterButtonGroup("purchases", "resourceType", filters.resourceType || "", "入库类型", "全部订单", purchaseResourceTypeOptions())}
    </div>
  `;
}

function filterButtonGroup(key, name, value, label, placeholder, options) {
  const normalizedOptions = [{ value: "", label: placeholder }, ...(options || [])];
  return `
    <div class="filter-group" role="group" aria-label="${escapeAttr(label)}">
      <span class="filter-label">${escapeHtml(label)}</span>
      ${normalizedOptions.map(option => {
        const active = String(option.value) === String(value || "");
        return `<button class="filter-chip${active ? " is-active" : ""}" type="button" data-action="apply-filter" data-filter-for="${escapeAttr(key)}" data-filter-name="${escapeAttr(name)}" data-filter-value="${escapeAttr(option.value)}" aria-pressed="${active ? "true" : "false"}">${escapeHtml(option.label)}</button>`;
      }).join("")}
    </div>
  `;
}

function renderSurface(title, body, actions = "") {
  return `
    <section class="surface">
      <div class="surface-head">
        <h2 class="surface-title">${title}</h2>
        ${actions ? `<div class="section-actions">${actions}</div>` : ""}
      </div>
      <div class="surface-body">${body}</div>
    </section>
  `;
}

function renderYearOptions(selectedYear, yearlyRows) {
  const currentYear = new Date().getFullYear();
  const years = new Set([selectedYear || currentYear, currentYear]);
  for (const row of yearlyRows || []) {
    if (row.period) years.add(Number(row.period));
  }
  return [...years]
    .filter(year => !Number.isNaN(year))
    .sort((a, b) => b - a)
    .map(year => `<option value="${escapeAttr(year)}"${Number(year) === Number(selectedYear) ? " selected" : ""}>${escapeHtml(year)} 年</option>`)
    .join("");
}

function renderFinanceTrend(rows) {
  return `
    <div class="finance-trend-layout">
      ${renderFinanceBars(rows)}
      <section class="finance-inline-report" aria-label="月度财报">
        <h3>月度财报</h3>
        ${renderMonthlyFinanceTable(rows)}
      </section>
    </div>
  `;
}

function renderMonthlyFinanceTable(rows) {
  return renderTable([
    { label: "月份", key: "period" },
    { label: "入库成本", key: "inboundCost", formatter: money },
    { label: "出库收入", key: "outboundRevenue", formatter: money },
    { label: "出库成本", key: "outboundCost", formatter: money },
    { label: "维修收入", key: "repairIncome", formatter: money },
    { label: "维修应收", key: "repairReceivable", formatter: money },
    { label: "维修支出", key: "repairExpense", formatter: money },
    { label: "配件成本", key: "repairPartsCost", formatter: money },
    { label: "租赁收入", key: "rentalIncome", formatter: money },
    { label: "折价收入", key: "modificationIncome", formatter: money },
    { label: "折价支出", key: "modificationExpense", formatter: money },
    { label: "盘盈收益", key: "inventoryGain", formatter: money },
    { label: "盘亏损失", key: "inventoryLoss", formatter: money },
    { label: "成本/支出", key: "totalExpense", formatter: money },
    { label: "经营毛利", key: "netProfit", formatter: money },
    { label: "净现金流", key: "netCashflow", formatter: money }
  ], rows);
}

function renderFinanceBars(rows) {
  const chartRows = (rows || []).map(row => ({
    ...row,
    periodLabel: row.period?.slice(5) || row.period || "-",
    totalIncome: financeNumber(row.totalIncome),
    inboundCost: financeNumber(row.inboundCost),
    outboundRevenue: financeNumber(row.outboundRevenue),
    outboundCost: financeNumber(row.outboundCost),
    repairIncome: financeNumber(row.repairIncome),
    repairReceivable: financeNumber(row.repairReceivable),
    repairExpense: financeNumber(row.repairExpense),
    repairPartsCost: financeNumber(row.repairPartsCost),
    rentalIncome: financeNumber(row.rentalIncome),
    modificationIncome: financeNumber(row.modificationIncome),
    modificationExpense: financeNumber(row.modificationExpense),
    inventoryGain: financeNumber(row.inventoryGain),
    inventoryLoss: financeNumber(row.inventoryLoss),
    totalExpense: financeNumber(row.totalExpense),
    grossProfit: financeNumber(row.grossProfit),
    netProfit: financeNumber(row.netProfit),
    netCashflow: financeNumber(row.netCashflow)
  }));

  if (!chartRows.length) {
    return emptyState("暂无月度收支走势数据");
  }

  const totalIncome = chartRows.reduce((sum, row) => sum + row.totalIncome, 0);
  const totalExpense = chartRows.reduce((sum, row) => sum + row.totalExpense, 0);
  const totalCashflow = chartRows.reduce((sum, row) => sum + row.netCashflow, 0);
  const rawMax = Math.max(1, ...chartRows.flatMap(row => [row.totalIncome, row.totalExpense, row.netCashflow]));
  const rawMin = Math.min(0, ...chartRows.map(row => row.netCashflow));
  const max = niceFinanceMax(rawMax);
  const min = rawMin < 0 ? -niceFinanceMax(Math.abs(rawMin)) : 0;
  const chart = { width: 720, height: 300, left: 58, right: 24, top: 24, bottom: 42 };
  const plotWidth = chart.width - chart.left - chart.right;
  const plotHeight = chart.height - chart.top - chart.bottom;
  const valueRange = Math.max(1, max - min);
  const xFor = index => chartRows.length === 1
    ? chart.left + plotWidth / 2
    : chart.left + (index / (chartRows.length - 1)) * plotWidth;
  const yFor = value => chart.top + ((max - value) / valueRange) * plotHeight;
  const baseY = yFor(0);
  const incomePath = financeLinePath(chartRows, "totalIncome", xFor, yFor);
  const expensePath = financeLinePath(chartRows, "totalExpense", xFor, yFor);
  const cashflowPath = financeLinePath(chartRows, "netCashflow", xFor, yFor);
  const incomeArea = financeAreaPath(chartRows, "totalIncome", xFor, yFor, baseY);
  const expenseArea = financeAreaPath(chartRows, "totalExpense", xFor, yFor, baseY);
  const ticks = min < 0
    ? [min, min / 2, 0, max / 2, max]
    : [0, 0.25, 0.5, 0.75, 1].map(ratio => max * ratio);
  const labelEvery = Math.max(1, Math.ceil(chartRows.length / 8));

  return `
    <div class="finance-chart-card">
      <div class="finance-chart-stats" aria-label="收支汇总">
        <span class="finance-chart-stat primary"><small>收入合计</small><strong>${escapeHtml(formatChartMoney(totalIncome))}</strong></span>
        <span class="finance-chart-stat warn"><small>成本/支出</small><strong>${escapeHtml(formatChartMoney(totalExpense))}</strong></span>
        <span class="finance-chart-stat teal"><small>净现金流</small><strong>${escapeHtml(formatChartMoney(totalCashflow))}</strong></span>
      </div>
      <div class="finance-chart" role="img" aria-label="月度收入、支出与净现金流折线图">
        <svg class="finance-chart-svg" viewBox="0 0 ${chart.width} ${chart.height}" aria-hidden="true">
          ${ticks.map(value => {
            const y = yFor(value);
            return `
              <g>
                <line class="finance-grid-line" x1="${chart.left}" y1="${roundChart(y)}" x2="${chart.width - chart.right}" y2="${roundChart(y)}"></line>
                <text class="finance-axis-label" x="${chart.left - 10}" y="${roundChart(y + 4)}" text-anchor="end">${escapeHtml(formatAxisMoney(value))}</text>
              </g>
            `;
          }).join("")}
          <line class="finance-axis-line" x1="${chart.left}" y1="${baseY}" x2="${chart.width - chart.right}" y2="${baseY}"></line>
          <path class="finance-area income" d="${escapeAttr(incomeArea)}"></path>
          <path class="finance-area cost" d="${escapeAttr(expenseArea)}"></path>
          <path class="finance-line income" d="${escapeAttr(incomePath)}"></path>
          <path class="finance-line cost" d="${escapeAttr(expensePath)}"></path>
          <path class="finance-line net" d="${escapeAttr(cashflowPath)}"></path>
          ${chartRows.map((row, index) => {
            const x = xFor(index);
            return `
              <circle class="finance-point income" data-finance-point-index="${escapeAttr(index)}" cx="${roundChart(x)}" cy="${roundChart(yFor(row.totalIncome))}" r="4"></circle>
              <circle class="finance-point cost" data-finance-point-index="${escapeAttr(index)}" cx="${roundChart(x)}" cy="${roundChart(yFor(row.totalExpense))}" r="4"></circle>
              <circle class="finance-point net" data-finance-point-index="${escapeAttr(index)}" cx="${roundChart(x)}" cy="${roundChart(yFor(row.netCashflow))}" r="4"></circle>
              ${(index % labelEvery === 0 || index === chartRows.length - 1) ? `<text class="finance-x-label" x="${roundChart(x)}" y="${chart.height - 14}" text-anchor="middle">${escapeHtml(row.periodLabel)}</text>` : ""}
            `;
          }).join("")}
        </svg>
        ${chartRows.map((row, index) => {
          const x = xFor(index);
          const leftBoundary = index === 0 ? chart.left : (xFor(index - 1) + x) / 2;
          const rightBoundary = index === chartRows.length - 1 ? chart.width - chart.right : (x + xFor(index + 1)) / 2;
          const left = (leftBoundary / chart.width) * 100;
          const width = ((rightBoundary - leftBoundary) / chart.width) * 100;
          const guideLeft = ((x - leftBoundary) / Math.max(1, rightBoundary - leftBoundary)) * 100;
          const edgeClass = index === 0 ? " is-start" : index === chartRows.length - 1 ? " is-end" : "";
          return `
            <div class="finance-chart-column${edgeClass}" data-finance-column-index="${escapeAttr(index)}" tabindex="0" style="left:${roundChart(left)}%;width:${roundChart(width)}%;--finance-guide-left:${roundChart(guideLeft)}%" aria-label="${escapeAttr(row.periodLabel)} 收支详情">
              <span class="finance-guide"></span>
              <span class="finance-tooltip">
                <strong>${escapeHtml(row.periodLabel)}</strong>
                ${financeTooltipRow("总收入", row.totalIncome, "primary")}
                ${financeTooltipRow("入库成本", row.inboundCost, "warn")}
                ${financeTooltipRow("成本/支出", row.totalExpense, "warn")}
                ${financeTooltipRow("出库收入", row.outboundRevenue)}
                ${financeTooltipRow("出库成本", row.outboundCost)}
                ${financeTooltipRow("维修收入", row.repairIncome)}
                ${financeTooltipRow("维修应收", row.repairReceivable)}
                ${financeTooltipRow("维修支出", row.repairExpense)}
                ${financeTooltipRow("配件成本", row.repairPartsCost)}
                ${financeTooltipRow("租赁收入", row.rentalIncome)}
                ${financeTooltipRow("折价收入", row.modificationIncome)}
                ${financeTooltipRow("折价支出", row.modificationExpense)}
                ${financeTooltipRow("盘盈收益", row.inventoryGain)}
                ${financeTooltipRow("盘亏损失", row.inventoryLoss)}
                ${financeTooltipRow("经营毛利", row.netProfit, "teal")}
                ${financeTooltipRow("净现金流", row.netCashflow, "teal")}
              </span>
            </div>
          `;
        }).join("")}
      </div>
      <div class="finance-legend">
        <span><i class="legend-dot income"></i>收入</span>
        <span><i class="legend-dot cost"></i>成本/支出</span>
        <span><i class="legend-dot net"></i>净现金流</span>
      </div>
    </div>
  `;
}

function financeNumber(value) {
  const numeric = Number(value || 0);
  return Number.isFinite(numeric) ? numeric : 0;
}

function niceFinanceMax(value) {
  const numeric = Math.max(1, Number(value || 0));
  const exponent = Math.floor(Math.log10(numeric));
  const base = 10 ** exponent;
  const scaled = numeric / base;
  const niceScaled = scaled <= 1 ? 1 : scaled <= 2 ? 2 : scaled <= 5 ? 5 : 10;
  return niceScaled * base;
}

function financeLinePath(rows, key, xFor, yFor) {
  if (rows.length === 1) {
    const x = xFor(0);
    const y = yFor(rows[0][key]);
    return `M ${roundChart(x - 18)} ${roundChart(y)} L ${roundChart(x + 18)} ${roundChart(y)}`;
  }
  return rows.map((row, index) => `${index ? "L" : "M"} ${roundChart(xFor(index))} ${roundChart(yFor(row[key]))}`).join(" ");
}

function financeAreaPath(rows, key, xFor, yFor, baseY) {
  if (rows.length === 1) {
    const x = xFor(0);
    const y = yFor(rows[0][key]);
    return `M ${roundChart(x - 18)} ${roundChart(baseY)} L ${roundChart(x - 18)} ${roundChart(y)} L ${roundChart(x + 18)} ${roundChart(y)} L ${roundChart(x + 18)} ${roundChart(baseY)} Z`;
  }
  const line = rows.map((row, index) => `${index ? "L" : "M"} ${roundChart(xFor(index))} ${roundChart(yFor(row[key]))}`).join(" ");
  return `${line} L ${roundChart(xFor(rows.length - 1))} ${roundChart(baseY)} L ${roundChart(xFor(0))} ${roundChart(baseY)} Z`;
}

function financeTooltipRow(label, value, tone = "") {
  return `<span class="finance-tooltip-row ${escapeAttr(tone)}"><em>${escapeHtml(label)}</em><b>${escapeHtml(money(value) || "¥0.00")}</b></span>`;
}

function formatChartMoney(value) {
  const numeric = financeNumber(value);
  const abs = Math.abs(numeric);
  if (abs >= 100000000) return `¥${trimChartNumber(numeric / 100000000)}亿`;
  if (abs >= 10000) return `¥${trimChartNumber(numeric / 10000)}万`;
  return money(numeric) || "¥0.00";
}

function formatAxisMoney(value) {
  const numeric = financeNumber(value);
  const abs = Math.abs(numeric);
  if (abs >= 100000000) return `${trimChartNumber(numeric / 100000000)}亿`;
  if (abs >= 10000) return `${trimChartNumber(numeric / 10000)}万`;
  return trimChartNumber(numeric);
}

function trimChartNumber(value) {
  return Number(value).toFixed(1).replace(/\.0$/, "");
}

function roundChart(value) {
  return Math.round(Number(value || 0) * 100) / 100;
}

function renderBackendSummary(tab, fallbackFactory) {
  const cards = state.data.summaries?.[tab]?.cards;
  if (Array.isArray(cards) && cards.length) {
    return `<section class="summary-grid">${cards.map(card => summaryCard(card.label, summaryValue(card), card.foot || "")).join("")}</section>`;
  }
  return typeof fallbackFactory === "function" ? fallbackFactory() : "";
}

function summaryValue(card = {}) {
  return card.format === "money" ? money(card.value) : display(card.value);
}

function listEmptyStateMeta(kind) {
  return {
    part: {
      title: "暂无配件",
      message: "先新增配件，后续入库、出库和采购都可直接引用。",
      actionKind: "part",
      actionLabel: "新增配件",
      permission: "part:write"
    },
    modificationOrder: {
      title: "暂无改装工单",
      message: "新建改装工单后，可以跟踪替换明细和完成入账。",
      actionKind: "modificationOrder",
      actionLabel: "新建改装工单",
      permission: "replace:write"
    },
    outboundOrder: {
      title: "暂无出库订单",
      message: "完成车辆或配件出库后，这里会生成销售与发票跟进记录。"
    },
    rental: {
      title: "暂无租赁记录",
      message: "新增租赁后，可以记录车辆去向、租期和月租。",
      actionKind: "rental",
      actionLabel: "新增租赁",
      permission: "stock:adjust"
    },
    customer: {
      title: "暂无客户",
      message: "先新增客户，后续销售、维修和租赁可直接带出资料。",
      actionKind: "customer",
      actionLabel: "新增客户",
      permission: "vehicle:write"
    },
    supplier: {
      title: "暂无供应商",
      message: "先新增供应商，后续采购单可直接引用。",
      actionKind: "supplier",
      actionLabel: "新增供应商",
      permission: "stock:adjust"
    },
    purchaseOrder: {
      title: "暂无入库订单",
      message: "新增采购后，可以同步跟踪收货状态和采购成本。",
      actionKind: "purchaseOrder",
      actionLabel: "新增入库订单",
      permission: "stock:adjust"
    },
    stocktaking: {
      title: "暂无盘点记录",
      message: "新增盘点后，可以核对账面和实盘数量并入账。",
      actionKind: "stocktaking",
      actionLabel: "新增盘点",
      permission: "stock:adjust"
    },
    repair: {
      title: "暂无维修记录",
      message: "新增维修后，可以跟踪故障、用料和结算状态。",
      actionKind: "repair",
      actionLabel: "新增维修",
      permission: "repair:write"
    },
    user: {
      title: "暂无用户",
      message: "新建用户后，可以分配角色、职务和账号状态。",
      actionKind: "user",
      actionLabel: "新建用户",
      permission: "user:write"
    }
  }[kind] || {
    title: "暂无数据",
    message: `暂无${entityLabel(kind)}`
  };
}

function createListEmptyState(kind) {
  const meta = listEmptyStateMeta(kind);
  const canCreate = meta.actionKind && (!meta.permission || hasPermission(meta.permission));
  return `
    <div class="empty-state empty-state-actionable">
      <div class="empty-state-main">
        <strong>${escapeHtml(meta.title)}</strong>
        ${canCreate ? `<button class="btn btn-primary" type="button" data-action="create" data-kind="${escapeAttr(meta.actionKind)}">${icon("plus")}${escapeHtml(meta.actionLabel)}</button>` : ""}
      </div>
      <span>${escapeHtml(meta.message)}</span>
    </div>
  `;
}

function renderExportableSurface(title, exportType, body) {
  return `
    <section class="surface">
      <div class="surface-head">
        <h2 class="surface-title">${title}</h2>
      </div>
      <div class="surface-body">${body}</div>
    </section>
  `;
}

function exportButton(type) {
  if (!endpoints.export?.[type]) return "";
  return `<button class="btn" type="button" data-action="export-excel" data-export-type="${escapeAttr(type)}">${icon("download")}导出 Excel</button>`;
}

function renderPagination(tab) {
  const page = state.pages[tab];
  if (!page?.loaded) return "";
  const current = page.totalPages ? page.page + 1 : 0;
  const totalPages = page.totalPages || 0;
  return `
    <div class="pagination-bar">
      <div class="pagination-meta">共 ${escapeHtml(page.totalElements)} 条 · 第 ${escapeHtml(current)} / ${escapeHtml(totalPages)} 页</div>
      <div class="pagination-actions">
        <button class="btn btn-sm" type="button" data-action="page-list" data-page-tab="${escapeAttr(tab)}" data-page="0"${page.first ? " disabled" : ""}>首页</button>
        <button class="btn btn-sm" type="button" data-action="page-list" data-page-tab="${escapeAttr(tab)}" data-page="${escapeAttr(Math.max(0, page.page - 1))}"${page.first ? " disabled" : ""}>上一页</button>
        <button class="btn btn-sm" type="button" data-action="page-list" data-page-tab="${escapeAttr(tab)}" data-page="${escapeAttr(page.page + 1)}"${page.last ? " disabled" : ""}>下一页</button>
        <button class="btn btn-sm" type="button" data-action="page-list" data-page-tab="${escapeAttr(tab)}" data-page="${escapeAttr(Math.max(0, totalPages - 1))}"${page.last ? " disabled" : ""}>末页</button>
      </div>
    </div>
  `;
}

function compactTable(columns, rows) {
  return `<div class="compact-table">${renderTable(columns, rows)}</div>`;
}

function toDataAttrName(key) {
  return String(key).replace(/([A-Z])/g, "-$1").toLowerCase();
}

function rowActions(kind, row, actions) {
  return entityActionRegistry.render(kind, row, actions);
}

function renderBatchToolbar(batch, rows) {
  const selected = selectedRows(batch.kind);
  if (!selected.length) return "";
  return `
    <div class="batch-toolbar">
      <div class="batch-meta">已选择 ${escapeHtml(selected.length)} 条</div>
      <div class="batch-actions">
        ${(batch.actions || []).map(action => `
          <button class="btn btn-sm${action.danger ? " btn-danger" : ""}" type="button" data-action="${escapeAttr(action.action)}" data-batch-kind="${escapeAttr(batch.kind)}">${icon(action.icon || "swap")}${escapeHtml(action.label)}</button>
        `).join("")}
        <button class="btn btn-sm btn-ghost" type="button" data-action="clear-batch-selection" data-batch-kind="${escapeAttr(batch.kind)}">清空选择</button>
      </div>
    </div>
  `;
}

function selectedIdSet(kind) {
  return new Set((state.batchSelections?.[kind] || []).map(String));
}

function isBatchMode(kind) {
  return Boolean(state.batchModes?.[kind]);
}

function toggleBatchMode(kind) {
  if (!kind) return;
  const active = !isBatchMode(kind);
  state.batchModes = {
    ...(state.batchModes || {}),
    [kind]: active
  };
  if (!active) clearBatchSelection(kind);
}

function selectedRows(kind) {
  const ids = selectedIdSet(kind);
  return entityRows(kind).filter(row => ids.has(String(row.id)));
}

function toggleBatchSelection(kind, id, selected) {
  if (!kind || !id) return;
  const ids = selectedIdSet(kind);
  if (selected) {
    ids.add(String(id));
  } else {
    ids.delete(String(id));
  }
  state.batchSelections = {
    ...(state.batchSelections || {}),
    [kind]: [...ids]
  };
}

function toggleVisibleBatchSelection(kind, visibleIds, selected) {
  if (!kind) return;
  const ids = selectedIdSet(kind);
  visibleIds.forEach(id => {
    if (selected) ids.add(String(id));
    else ids.delete(String(id));
  });
  state.batchSelections = {
    ...(state.batchSelections || {}),
    [kind]: [...ids]
  };
}

function clearBatchSelection(kind) {
  if (!kind) return;
  state.batchSelections = {
    ...(state.batchSelections || {}),
    [kind]: []
  };
}

function batchActionsForKind(kind) {
  if (kind === "repair" && hasPermission("repair:write")) {
    return [{ action: "batch-complete-repairs", label: "标记完成", icon: "swap" }];
  }
  if (kind === "purchaseOrder" && hasPermission("stock:adjust")) {
    return [{ action: "batch-receive-purchases", label: "标记收货", icon: "download" }];
  }
  if (kind === "stocktaking" && hasPermission("stock:adjust")) {
    return [
      { action: "batch-complete-stocktakes", label: "批量入账", icon: "swap" },
      { action: "batch-delete-stocktake-drafts", label: "删除草稿", icon: "trash", danger: true }
    ];
  }
  return [];
}

function purchaseOrderSummary(row = {}) {
  return `
    <div class="cell-stack">
      <strong>${escapeHtml(row.purchaseNo || "-")}</strong>
      <span class="helper-inline">${escapeHtml(dateValue(row.orderDate) || "-")}${row.expectedArrivalDate ? ` / 预计 ${escapeHtml(dateValue(row.expectedArrivalDate))}` : ""}</span>
    </div>
  `;
}

function purchaseResourceSummary(row = {}) {
  return `
    <div class="cell-stack">
      <strong>${escapeHtml(row.resourceCode || row.resourceName || "-")}</strong>
      <span class="helper-inline">${escapeHtml(purchaseResourceLabel(row.resourceType))}${row.resourceName && row.resourceCode ? ` / ${escapeHtml(row.resourceName)}` : ""}${row.specificationModel ? ` / ${escapeHtml(row.specificationModel)}` : ""}</span>
    </div>
  `;
}

function stocktakingSummary(row = {}) {
  return `
    <div class="cell-stack">
      <strong>${escapeHtml(row.stocktakingNo || "-")}</strong>
      <span class="helper-inline">${escapeHtml(dateValue(row.stocktakingDate) || "-")}${row.operator ? ` / ${escapeHtml(row.operator)}` : ""}</span>
    </div>
  `;
}

function stocktakingDifference(row = {}) {
  const value = Number(row.differenceQuantity || 0);
  return badge(value > 0 ? `+${value}` : String(value), value === 0 ? "teal" : value > 0 ? "warn" : "danger");
}

function stocktakingActions(row = {}) {
  if (!hasPermission("stock:adjust")) return "";
  const completed = row.status === "COMPLETED";
  return `
    <div class="action-row">
      ${!completed ? `<button class="btn btn-sm btn-primary" type="button" data-action="complete-stocktaking" data-id="${escapeAttr(row.id)}">${icon("swap")}入账</button>` : ""}
      ${!completed ? `<button class="btn btn-sm" type="button" data-action="edit" data-kind="stocktaking" data-id="${escapeAttr(row.id)}">${icon("edit")}编辑</button>` : ""}
      ${!completed ? `<button class="btn btn-sm btn-danger" type="button" data-action="delete" data-kind="stocktaking" data-id="${escapeAttr(row.id)}">${icon("trash")}删除</button>` : ""}
    </div>
  `;
}

function purchaseStatusControl(row = {}) {
  const received = row.status === "RECEIVED";
  return `<div class="cell-stack">${purchaseStatusBadge(row.status)}${received ? `<span class="helper-inline">运费 ${escapeHtml(money(row.freightAmount ?? 0))}</span>` : ""}</div>`;
}
function stocktakingStatusBadge(status) {
  const map = {
    DRAFT: ["草稿", "primary"],
    COMPLETED: ["已入账", "teal"]
  };
  const [label, type] = map[status] || [status || "未设置", "primary"];
  return badge(label, type);
}

function purchaseResourceLabel(value) {
  const labels = {
    MACHINE: "整车",
    PART: "配件"
  };
  return labels[value] || value || "-";
}

function uniqueSupplierCount() {
  return new Set((state.data.purchaseOrders || [])
    .map(row => row.supplierId || row.supplierName)
    .filter(Boolean)).size;
}

function renderModalFields(modalFields, item, options = {}) {
  if (Array.isArray(options.sections) && options.sections.length) {
    const hiddenFields = modalFields.filter(field => field.type === "hidden");
    return `
      ${hiddenFields.map(field => renderField(field, item)).join("")}
      ${options.sections.map(section => `
        <section class="form-section" id="${escapeAttr(section.key)}" data-form-section>
          <div class="form-section-head">
            <div>
              <span class="form-section-index">${escapeHtml(String(options.sections.indexOf(section) + 1).padStart(2, "0"))}</span>
              <h3>${escapeHtml(section.title)}</h3>
            </div>
            <span>${escapeHtml(section.fields.length)} 项</span>
          </div>
          <div class="modal-grid">
            ${section.fields.map(field => renderField(field, item)).join("")}
          </div>
        </section>
      `).join("")}
    `;
  }
  let activeSection = null;
  return modalFields.map(field => {
    const section = field.type === "hidden" ? activeSection : (field.section || "");
    const sectionTitle = section && section !== activeSection
      ? `<div class="form-section-title">${escapeHtml(section)}</div>`
      : "";
    if (field.type !== "hidden") {
      activeSection = section;
    }
    return `${sectionTitle}${renderField(field, item)}`;
  }).join("");
}

function renderField(field, data) {
  const rawValue = Object.prototype.hasOwnProperty.call(data || {}, field.name) ? data[field.name] : "";
  const prefillValue = fieldPrefillValue(field, data);
  const value = toInputValue(rawValue, field);
  const span = field.span ? ` data-span="${field.span}"` : "";
  const required = field.required && prefillValue === undefined ? " required" : "";
  const prefillHint = fieldPrefillHint(field, data);
  const fieldHint = prefillHint
    ? `<span class="field-source" data-field-source>${escapeHtml(prefillHint)}</span>`
    : "";
  const coerce = field.coerce || field.type || "string";

  if (field.type === "hidden") {
    return `<input name="${escapeAttr(field.name)}" type="hidden" data-coerce="${escapeAttr(coerce)}" value="${escapeAttr(value)}"${renderPrefillAttrs(field, prefillValue)}>`;
  }

  if (field.type === "repairPartUsages") {
    return renderRepairPartUsageEditor(data, span, field.label, fieldHint);
  }

  if (field.type === "checkbox") {
    return `
      <label class="field checkbox-field"${span}>
        <input name="${escapeAttr(field.name)}" type="checkbox" data-coerce="${escapeAttr(coerce)}"${rawValue ? " checked" : ""}>
        <span>${escapeHtml(field.label)}${fieldHint}</span>
      </label>
    `;
  }

  if (field.type === "select") {
    const options = resolveOptions(field);
    const selected = findOptionByValue(options, value);
    const displayValue = selected ? selected.label : (field.allowCustom ? value : "");
    const hiddenValue = selected ? selected.value : (field.allowCustom ? value : "");
    const placeholder = fieldPlaceholder(field, options, data);
    const prefillAttrs = renderPrefillAttrs(field, prefillValue, options);
    const remoteSource = remoteComboSourceForField(state.modal?.kind, field.name);
    const remoteAttrs = remoteSource ? ` data-remote-source="${escapeAttr(remoteSource)}"` : "";
    return `
      <label class="field"${span}>
        <span>${escapeHtml(field.label)}${fieldHint}</span>
        <div class="combo${options.length ? "" : " is-empty"}" data-combo data-name="${escapeAttr(field.name)}" data-allow-custom="${field.allowCustom ? "true" : "false"}" data-required="${field.required ? "true" : "false"}"${remoteAttrs}>
          <input class="combo-input" data-combo-input type="text" value="${escapeAttr(displayValue)}" placeholder="${escapeAttr(placeholder)}" autocomplete="off"${required}>
          <button class="combo-toggle" type="button" data-combo-toggle aria-label="展开选项"></button>
          <input name="${escapeAttr(field.name)}" type="hidden" data-coerce="${escapeAttr(coerce)}" value="${escapeAttr(hiddenValue)}" data-selected-label="${escapeAttr(displayValue)}" data-selected-meta="${selected?.meta ? escapeAttr(JSON.stringify(selected.meta)) : ""}"${prefillAttrs}>
          <div class="combo-menu" data-combo-menu>
            ${renderComboOptions(options, hiddenValue)}
          </div>
        </div>
      </label>
    `;
  }

  if (field.type === "textarea") {
    return `
      <label class="field"${span}>
        <span>${escapeHtml(field.label)}${fieldHint}</span>
        <textarea name="${escapeAttr(field.name)}" data-coerce="${escapeAttr(coerce)}" placeholder="${escapeAttr(fieldPlaceholder(field, [], data))}"${renderPrefillAttrs(field, prefillValue)}${required}>${escapeHtml(value)}</textarea>
      </label>
    `;
  }

  if (field.type === "toggle") {
    const options = resolveOptions(field);
    const selectedValue = value || prefillValue || field.defaultValue || options[0]?.value || "";
    return `
      <label class="field"${span}>
        <span>${escapeHtml(field.label)}${fieldHint}</span>
        <div class="status-toggle-group" data-toggle-group="${escapeAttr(field.name)}">
          ${options.map(option => {
            const active = String(option.value) === String(selectedValue);
            return `<button class="status-toggle ${active ? "teal" : "primary"}" type="button" data-toggle-option data-name="${escapeAttr(field.name)}" data-value="${escapeAttr(option.value)}" aria-pressed="${active ? "true" : "false"}">${escapeHtml(option.label)}</button>`;
          }).join("")}
          <input name="${escapeAttr(field.name)}" type="hidden" data-coerce="${escapeAttr(coerce)}" value="${escapeAttr(selectedValue)}"${renderPrefillAttrs(field, prefillValue)}>
        </div>
      </label>
    `;
  }

  if (field.type === "file") {
    return `
      <label class="field"${span}>
        <span>${escapeHtml(field.label)}${fieldHint}</span>
        <input name="${escapeAttr(field.name)}" type="file"${field.accept ? ` accept="${escapeAttr(field.accept)}"` : ""}${field.multiple ? " multiple" : ""}${required}>
      </label>
    `;
  }

  return `
    <label class="field"${span}>
      <span>${escapeHtml(field.label)}${fieldHint}</span>
      <input name="${escapeAttr(field.name)}" type="${escapeAttr(field.type || "text")}" data-coerce="${escapeAttr(coerce)}" value="${escapeAttr(value)}" placeholder="${escapeAttr(fieldPlaceholder(field, [], data))}"${renderPrefillAttrs(field, prefillValue)}${field.step ? ` step="${escapeAttr(field.step)}"` : ""}${field.readOnly ? " readonly" : ""}${required}>
    </label>
  `;
}

function renderRepairPartUsageEditor(item = {}, span = "", label = "配件明细", hint = "") {
  const rows = ensureRepairPartUsageRows(item);
  const legacyUntracked = item.partUsageTrackingStatus === "LEGACY_UNTRACKED";
  return `
    <section class="field repair-part-usage-field"${span}>
      <div class="repair-part-usage-head">
        <span>${escapeHtml(label)}${hint}</span>
        ${legacyUntracked ? "" : `<button class="btn btn-sm" type="button" data-repair-part-action="add">${icon("plus")}添加配件</button>`}
      </div>
      <p class="helper-inline">${legacyUntracked
        ? "此历史维修单缺少可追溯库存流水/FIFO 明细，配件行已锁定；请通过历史纠偏或显式库存调整处理。"
        : "每行独立选择仓库、数量、收费单价和折扣；后端会按该仓库 FIFO 计算成本。"
      }</p>
      <div class="repair-part-usage-list">
        ${rows.length ? rows.map((row, index) => renderRepairPartUsageRow(row, index, legacyUntracked)).join("") : `
          <div class="repair-part-usage-empty">未领用配件；如需收费或扣库存，请添加一条配件明细。</div>
        `}
      </div>
    </section>
  `;
}

function renderRepairPartUsageRow(row = {}, index, readOnly = false) {
  const partOptions = repairPartOptions();
  const warehouseOptionsList = warehouseOptions();
  const partId = Number(row.partId || 0);
  const warehouseId = Number(row.warehouseId || 0);
  const part = state.data.parts.find(item => Number(item.id) === partId);
  const unitPrice = row.chargeUnitPrice ?? part?.salePrice ?? part?.settlementPrice ?? "";
  const quantity = Number(row.quantity || 1);
  const discount = row.discountAmount ?? "";
  const lineAmount = repairPartUsageLineAmount(unitPrice, quantity, discount);
  const disabled = readOnly ? " disabled" : "";
  return `
    <div class="repair-part-usage-row" data-repair-part-row data-index="${escapeAttr(index)}">
      <input type="hidden" data-repair-part-field="id" value="${escapeAttr(row.id ?? "")}">
      <label>
        <span>配件</span>
        <select data-repair-part-field="partId"${disabled}>
          <option value="">请选择配件</option>
          ${partOptions.map(option => `<option value="${escapeAttr(option.value)}"${Number(option.value) === partId ? " selected" : ""}>${escapeHtml(option.label)}</option>`).join("")}
        </select>
      </label>
      <label>
        <span>领料仓库</span>
        <select data-repair-part-field="warehouseId"${disabled}>
          <option value="">按默认/单仓规则</option>
          ${warehouseOptionsList.map(option => `<option value="${escapeAttr(option.value)}"${Number(option.value) === warehouseId ? " selected" : ""}>${escapeHtml(option.label)}</option>`).join("")}
        </select>
      </label>
      <label>
        <span>数量</span>
        <input type="number" min="1" step="1" data-repair-part-field="quantity" value="${escapeAttr(quantity)}"${disabled}>
      </label>
      <label>
        <span>收费单价</span>
        <input type="number" min="0" step="0.01" data-repair-part-field="chargeUnitPrice" value="${escapeAttr(unitPrice)}"${disabled}>
      </label>
      <label>
        <span>折扣额</span>
        <input type="number" min="0" step="0.01" data-repair-part-field="discountAmount" value="${escapeAttr(discount)}"${disabled}>
      </label>
      <label>
        <span>收费金额</span>
        <output data-repair-part-line-total>${escapeHtml(formatDecimal(lineAmount))}</output>
      </label>
      <label class="repair-part-usage-remark">
        <span>备注</span>
        <input type="text" data-repair-part-field="remark" value="${escapeAttr(row.remark ?? "")}"${disabled}>
      </label>
      ${readOnly ? "" : `<button class="btn btn-sm btn-danger" type="button" data-repair-part-action="remove" data-index="${escapeAttr(index)}">删除</button>`}
    </div>
  `;
}

function renderConfigSelectionEditor(item) {
  const rows = ensureConfigSelections(item);
  return `
    <section class="config-selection-editor">
      <div class="config-editor-head">
        <div class="config-editor-copy">
          <strong>结构化配置（选填）</strong>
          <span class="helper-inline">入库时可先只填“配置”文本；需要拆解到配置字典时再补充这里。</span>
        </div>
        <button class="btn btn-sm" type="button" data-config-action="add">${icon("plus")}添加配置</button>
      </div>
      <div class="config-selection-list">
        ${rows.map((row, index) => renderConfigSelectionRow(row, index)).join("")}
      </div>
    </section>
  `;
}

function renderConfigSelectionRow(row, index) {
  const itemOptions = configItemOptions();
  const effectiveConfigItemId = effectiveConfigSelectionValue(row, "configItemId");
  const valueOptions = effectiveConfigItemId ? configValueOptionsForItem(effectiveConfigItemId) : [];
  return `
    <div class="config-selection-row" data-config-index="${index}">
      ${renderConfigCombo("配置类型", `configItemId-${index}`, itemOptions, row.configItemId, "configItemId", index, row)}
      ${renderConfigCombo("具体配置", `configValueId-${index}`, valueOptions, row.configValueId, "configValueId", index, row)}
      <button class="btn btn-sm btn-danger" type="button" data-config-action="remove" data-config-index="${index}">${icon("trash")}移除</button>
    </div>
  `;
}

function renderConfigCombo(label, name, options, selectedValue, fieldName, index, row = {}) {
  const selected = findOptionByValue(options, selectedValue);
  const displayValue = selected?.label || "";
  const hiddenValue = selected?.value || "";
  const prefillValue = row.__prefillValues?.[fieldName];
  const prefillAttrs = renderConfigSelectionPrefillAttrs(prefillValue, options);
  const placeholder = row.__placeholders?.[fieldName] || (options.length ? "请选择或输入筛选" : "请先选择配置类型");
  return `
    <label class="field">
      <span>${escapeHtml(label)}</span>
      <div class="combo${options.length ? "" : " is-empty"}" data-combo data-name="${escapeAttr(name)}" data-required="false">
        <input class="combo-input" data-combo-input type="text" value="${escapeAttr(displayValue)}" placeholder="${escapeAttr(placeholder)}" autocomplete="off">
        <button class="combo-toggle" type="button" data-combo-toggle aria-label="展开选项"></button>
        <input name="${escapeAttr(name)}" type="hidden" data-config-field="${escapeAttr(fieldName)}" data-config-index="${index}" value="${escapeAttr(hiddenValue)}" data-selected-label="${escapeAttr(displayValue)}"${prefillAttrs}>
        <div class="combo-menu" data-combo-menu>
          ${renderComboOptions(options, hiddenValue)}
        </div>
      </div>
    </label>
  `;
}

function renderConfigSelectionPrefillAttrs(value, options = []) {
  if (value === undefined || value === null || value === "") return "";
  const option = findOptionByValue(options, value);
  const label = option?.label ?? value;
  return ` data-prefill-value="${escapeAttr(value)}" data-prefill-label="${escapeAttr(label)}"`;
}

function renderOptions(options, selectedValue) {
  const optionList = [{ label: "请选择", value: "" }, ...options];
  return optionList.map(option => {
    const selected = String(option.value) === String(selectedValue ?? "") ? " selected" : "";
    return `<option value="${escapeAttr(option.value)}"${selected}>${escapeHtml(option.label)}</option>`;
  }).join("");
}

function renderComboOptions(options, selectedValue) {
  if (!options.length) {
    return `<div class="combo-empty">暂无可选项</div>`;
  }
  return options.map(option => {
    const selected = String(option.value) === String(selectedValue ?? "") ? " is-selected" : "";
    const meta = option.meta ? JSON.stringify(option.meta) : "";
    return `
      <button class="combo-option${selected}" type="button" data-combo-option data-value="${escapeAttr(option.value)}" data-label="${escapeAttr(option.label)}" data-meta="${escapeAttr(meta)}">
        ${escapeHtml(option.label)}
      </button>
    `;
  }).join("") + `<div class="combo-empty">无匹配选项</div>`;
}

function findOptionByValue(options, value) {
  return (options || []).find(option => String(option.value) === String(value ?? ""));
}

function findComboOption(combo, query) {
  const normalized = normalizeText(query);
  if (!normalized) return null;
  return [...combo.querySelectorAll("[data-combo-option]")]
    .find(option => normalizeText(option.dataset.label) === normalized || normalizeText(option.dataset.value) === normalized);
}

function selectComboOption(option) {
  const combo = option.closest("[data-combo]");
  setComboValue(combo, option.dataset.value, option.dataset.label, option.dataset.meta, true);
  closeAllCombos();
}

function setToggleFieldValue(button) {
  const group = button.closest("[data-toggle-group]");
  const hidden = group?.querySelector(`input[name="${button.dataset.name}"]`);
  if (!group || !hidden) return;
  hidden.value = button.dataset.value || "";
  group.querySelectorAll("[data-toggle-option]").forEach(option => {
    const active = option === button;
    option.classList.toggle("teal", active);
    option.classList.toggle("primary", !active);
    option.setAttribute("aria-pressed", active ? "true" : "false");
  });
  hidden.dispatchEvent(new Event("change", { bubbles: true }));
}

function setComboValue(combo, value, label, meta, shouldNotify) {
  const input = combo.querySelector("[data-combo-input]");
  const hidden = combo.querySelector("input[type='hidden']");
  const previous = hidden.value;
  input.value = label || "";
  delete input.dataset.userEdited;
  hidden.value = value || "";
  hidden.dataset.selectedLabel = label || "";
  hidden.dataset.selectedMeta = meta || "";
  combo.querySelectorAll("[data-combo-option]").forEach(option => {
    option.classList.toggle("is-selected", String(option.dataset.value) === String(hidden.value));
    option.hidden = false;
  });
  if (shouldNotify || previous !== hidden.value) {
    hidden.dispatchEvent(new Event("change", { bubbles: true }));
  }
}

function filterComboOptions(combo, query) {
  const normalized = normalizeText(query);
  let visibleCount = 0;
  combo.querySelectorAll("[data-combo-option]").forEach(option => {
    const haystack = normalizeText(`${option.dataset.label || ""} ${option.dataset.value || ""}`);
    const visible = !normalized || haystack.includes(normalized);
    option.hidden = !visible;
    if (visible) visibleCount += 1;
  });
  combo.classList.toggle("is-empty", visibleCount === 0);
}

function openCombo(combo, query = "") {
  if (!combo) return;
  closeAllCombos(combo);
  filterComboOptions(combo, query);
  combo.classList.add("is-open");
}

function toggleCombo(combo) {
  if (!combo) return;
  if (combo.classList.contains("is-open")) {
    combo.classList.remove("is-open");
    return;
  }
  const input = combo.querySelector("[data-combo-input]");
  openCombo(combo, input?.value.trim() || "");
  scheduleRemoteComboSearch(combo, input?.value.trim() || "", { immediate: combo.dataset.remoteLoaded !== "true" });
  input?.focus();
}

function closeAllCombos(except = null) {
  els.modalCard.querySelectorAll("[data-combo].is-open").forEach(combo => {
    if (combo !== except) combo.classList.remove("is-open");
  });
}

function syncComboOptionsForField(form, name, options, selectedValue) {
  const combo = form.querySelector(`[data-combo][data-name="${name}"]`);
  if (!combo) return;
  const menu = combo.querySelector("[data-combo-menu]");
  const hidden = combo.querySelector(`input[name="${name}"]`);
  const input = combo.querySelector("[data-combo-input]");
  const selected = findOptionByValue(options, selectedValue);
  menu.innerHTML = renderComboOptions(options, selected?.value ?? "");
  hidden.value = selected?.value ?? "";
  hidden.dataset.selectedLabel = selected?.label ?? "";
  hidden.dataset.selectedMeta = selected?.meta ? JSON.stringify(selected.meta) : "";
  input.value = selected?.label ?? "";
  filterComboOptions(combo, "");
}

function remoteComboSourceForField(kind, fieldName) {
  if (!kind || !fieldName) return "";
  if (fieldName === "customerId" && ["vehicleOutbound", "partStock", "partOutbound", "rental", "repair"].includes(kind)) return "customers";
  if (fieldName === "supplierId" && kind === "purchaseOrder") return "suppliers";
  if (["fromWarehouseId", "toWarehouseId"].includes(fieldName) && kind === "stockTransfer") return "warehouses";
  if (fieldName === "partCode" && ["partStock", "partOutbound"].includes(kind)) return "partCodes";
  if (fieldName === "usedPartIds" && kind === "repair") return "repairParts";
  if (fieldName === "machineId") {
    if (kind === "vehicleOutbound") return "vehicleOutbound";
    if (kind === "rental") return "vehicleRental";
    if (["vehicleStock", "partReplace", "vehiclePartInstall", "repair"].includes(kind)) return "vehicles";
  }
  if (fieldName === "resourceId" && kind === "stocktaking") {
    return resourceTypeForModal() === "MACHINE" ? "stocktakingVehicles" : "stocktakingParts";
  }
  if (fieldName === "resourceId" && kind === "stockTransfer") {
    return resourceTypeForModal() === "MACHINE" ? "stockTransferVehicles" : "stockTransferParts";
  }
  if (fieldName === "newPartId" && kind === "vehiclePartInstall") return "installParts";
  return "";
}

function resourceTypeForModal() {
  return String(effectiveFieldValue(state.modal?.item || {}, "resourceType") || "PART").toUpperCase();
}

function scheduleRemoteComboSearch(combo, query = "", options = {}) {
  if (!combo?.dataset.remoteSource) return;
  window.clearTimeout(remoteComboSearchTimer);
  const run = () => searchRemoteComboOptions(combo, query);
  if (options.immediate) {
    run();
    return;
  }
  remoteComboSearchTimer = window.setTimeout(run, REMOTE_COMBO_DEBOUNCE_MS);
}

async function searchRemoteComboOptions(combo, query = "") {
  const source = combo?.dataset.remoteSource;
  if (!source || !combo.isConnected) return;
  const requestId = String(++remoteComboRequestSequence);
  combo.dataset.remoteRequestId = requestId;
  setComboRemoteStatus(combo, "loading");
  try {
    const options = await fetchRemoteComboOptions(source, query);
    if (!combo.isConnected || combo.dataset.remoteRequestId !== requestId) return;
    updateComboMenuOptions(combo, options, query);
    combo.dataset.remoteLoaded = "true";
    combo.dataset.remoteQuery = query;
  } catch (error) {
    if (!combo.isConnected || combo.dataset.remoteRequestId !== requestId) return;
    console.warn("Remote combo search failed", error);
    setComboRemoteStatus(combo, "error");
  }
}

function setComboRemoteStatus(combo, status) {
  const menu = combo.querySelector("[data-combo-menu]");
  if (!menu) return;
  const text = status === "error" ? "搜索失败，请重试" : "正在搜索...";
  menu.innerHTML = `<div class="combo-empty">${escapeHtml(text)}</div>`;
  combo.classList.add("is-open", "is-empty");
}

function updateComboMenuOptions(combo, options, query = "") {
  const menu = combo.querySelector("[data-combo-menu]");
  const hidden = combo.querySelector("input[type='hidden']");
  const input = combo.querySelector("[data-combo-input]");
  if (!menu || !hidden) return;
  menu.innerHTML = renderComboOptions(options, hidden.value);
  filterComboOptions(combo, query);
  combo.classList.add("is-open");
  const typed = String(input?.value || "").trim();
  if (typed && !hidden.value) {
    const exact = findComboOption(combo, typed);
    if (exact) {
      setComboValue(combo, exact.dataset.value, exact.dataset.label, exact.dataset.meta, false);
    }
  }
}

async function fetchRemoteComboOptions(source, query = "") {
  const meta = remoteComboMeta(source);
  if (!meta) return [];
  const payload = await api(referencePageUrl(meta.endpoint, query, REMOTE_COMBO_PAGE_SIZE));
  const rows = rowsFromPayload(payload);
  mergeReferenceRows(meta.dataKey, rows);
  return optionsForRemoteComboSource(source);
}

function remoteComboMeta(source) {
  if (["vehicles", "vehicleOutbound", "vehicleRental", "stocktakingVehicles", "stockTransferVehicles"].includes(source)) {
    return { endpoint: endpoints.vehicle.list, dataKey: "vehicles" };
  }
  if (["partCodes", "repairParts", "stocktakingParts", "stockTransferParts", "installParts"].includes(source)) {
    return { endpoint: endpoints.part.list, dataKey: "parts" };
  }
  if (source === "customers") return { endpoint: endpoints.customer.list, dataKey: "customers" };
  if (source === "suppliers") return { endpoint: endpoints.supplier.list, dataKey: "suppliers" };
  if (source === "warehouses") return { endpoint: endpoints.warehouse.list, dataKey: "warehouses" };
  return null;
}

function mergeReferenceRows(dataKey, rows = []) {
  if (!dataKey || !Array.isArray(rows) || !rows.length) return;
  const current = Array.isArray(state.data[dataKey]) ? state.data[dataKey] : [];
  const byId = new Map(current.map(row => [String(row.id ?? ""), row]));
  for (const row of rows) {
    if (row?.id === undefined || row?.id === null) continue;
    byId.set(String(row.id), { ...(byId.get(String(row.id)) || {}), ...row });
  }
  const asc = ["customers", "suppliers", "warehouses"].includes(dataKey);
  state.data[dataKey] = sortById([...byId.values()], !asc);
}

function optionsForRemoteComboSource(source) {
  return {
    vehicles: vehicleOptions,
    vehicleOutbound: vehicleOutboundOptions,
    vehicleRental: vehicleRentalOptions,
    stocktakingVehicles: stocktakingResourceOptions,
    stocktakingParts: stocktakingResourceOptions,
    stockTransferVehicles: stockTransferResourceOptions,
    stockTransferParts: stockTransferResourceOptions,
    partCodes: partCodeOptions,
    repairParts: repairPartOptions,
    installParts: installPartOptions,
    customers: customerOptions,
    suppliers: supplierOptions,
    warehouses: warehouseOptions
  }[source]?.() || [];
}

function validateCombos(form) {
  const invalid = [...form.querySelectorAll("[data-combo][data-required='true']")]
    .find(combo => {
      const hidden = combo.querySelector("input[type='hidden']");
      return !comboHasSubmittableValue(combo, hidden);
    });
  if (!invalid) return true;
  const input = invalid.querySelector("[data-combo-input]");
  invalid.classList.add("is-open");
  input.focus();
  showToast("请从下拉列表中选择有效选项", "error");
  return false;
}

function comboHasSubmittableValue(combo, hidden = combo?.querySelector("input[type='hidden']")) {
  if (!combo || !hidden) return false;
  if (String(hidden.value || "").trim()) return true;
  const input = combo.querySelector("[data-combo-input]");
  const typedValue = String(input?.value || "").trim();
  const prefillLabel = String(hidden.dataset.prefillLabel || "").trim();
  return hidden.dataset.prefillValue !== undefined && (typedValue === "" || typedValue === prefillLabel);
}

function resolveOptions(field) {
  if (typeof field.options === "function") return field.options();
  return field.options || [];
}

function renderPrefillAttrs(field, value, options = []) {
  if (value === undefined || value === null || value === "") return "";
  const inputValue = toInputValue(value, field);
  const option = findOptionByValue(options, inputValue);
  const label = option?.label ?? inputValue;
  return ` data-prefill-value="${escapeAttr(inputValue)}" data-prefill-label="${escapeAttr(label)}"`;
}

function fieldPrefillValue(field, data = {}) {
  if (Object.prototype.hasOwnProperty.call(data?.__prefillValues || {}, field.name)) {
    return data.__prefillValues[field.name];
  }
  if (field.placeholderValue !== undefined) {
    return typeof field.placeholderValue === "function" ? field.placeholderValue() : field.placeholderValue;
  }
  if (field.defaultValue !== undefined) {
    return typeof field.defaultValue === "function" ? field.defaultValue() : field.defaultValue;
  }
  return undefined;
}

function fieldPrefillHint(field, data = {}) {
  if (!Object.prototype.hasOwnProperty.call(data?.__prefillValues || {}, field.name)) return "";
  const placeholder = String(data?.__placeholders?.[field.name] || "").trim();
  if (placeholder.startsWith("默认：")) return placeholder;
  if (placeholder.startsWith("预填：")) return `自动带入 · ${placeholder.slice(3)}`;
  return "自动带入";
}

function fieldPlaceholder(field, options = [], data = {}) {
  const placeholder = data?.__placeholders?.[field.name];
  if (placeholder) return placeholder;
  if (field.placeholder) return typeof field.placeholder === "function" ? field.placeholder() : field.placeholder;
  const placeholderValue = fieldPrefillValue(field, data);
  if (placeholderValue !== undefined) {
    const option = findOptionByValue(options, placeholderValue);
    return `默认：${option?.label ?? placeholderValue}`;
  }
  return field.type === "select" ? "请选择或输入筛选" : "";
}

function serializeForm(kind, form) {
  const payload = {};
  for (const field of getFields(kind)) {
    const input = form.elements[field.name];
    if (!input) continue;
    const coerce = input.dataset.coerce || "string";
    let raw = input.type === "checkbox" ? input.checked : String(input.value || "").trim();
    const combo = input.closest?.("[data-combo]");
    const comboTypedValue = combo ? String(combo.querySelector("[data-combo-input]")?.value || "").trim() : "";
    const prefillLabel = String(input.dataset.prefillLabel || "").trim();
    if (raw === "" && input.dataset.prefillValue !== undefined && (!combo || comboTypedValue === "" || comboTypedValue === prefillLabel)) {
      raw = input.dataset.prefillValue;
    }
    const value = coerceValue(raw, coerce, input.type);
    if (value !== null || field.required || input.type === "checkbox") {
      payload[field.name] = value;
    }
  }
  if (kind === "user") {
    payload.roles = payload.role ? [payload.role] : ["USER"];
    delete payload.role;
  }
  if (kind === "repair") {
    payload.partUsages = collectRepairPartUsages(form);
    const legacyUntracked = state.modal?.item?.partUsageTrackingStatus === "LEGACY_UNTRACKED";
    const legacyPartIds = Array.isArray(state.modal?.item?.usedPartIds)
      ? state.modal.item.usedPartIds
      : String(state.modal?.item?.usedPartIds || "").split(",").map(value => value.trim()).filter(Boolean);
    payload.usedPartIds = legacyUntracked
      ? legacyPartIds.map(value => Number(value)).filter(Number.isFinite)
      : payload.partUsages.flatMap(row =>
        Array.from({ length: Math.max(0, Number(row.quantity || 0)) }, () => row.partId)
      );
    if (!legacyUntracked) {
      payload.partsFee = formatDecimal(payload.partUsages.reduce(
        (total, row) => total + repairPartUsageLineAmount(row.chargeUnitPrice, row.quantity, row.discountAmount),
        0
      ));
    }
  }
  return payload;
}

function coerceValue(value, coerce, inputType) {
  if (inputType === "checkbox") return Boolean(value);
  if (value === "") return null;
  if (coerce === "int") return Number.parseInt(value, 10);
  if (coerce === "intList") return String(value).split(",").map(item => Number.parseInt(item.trim(), 10)).filter(Number.isFinite);
  if (coerce === "datetime") return value.length === 16 ? `${value}:00` : value;
  if (coerce === "decimal") return value;
  return value;
}

function defaultEntity(kind, context = {}) {
  if (kind === "vehicleOutbound") {
    const machine = findEntity("vehicle", Number(context.machineId || 0));
    return vehicleOutboundDefaultsForMachine(machine, null, context);
  }
  if (kind === "rental") {
    const machine = findEntity("vehicle", Number(context.machineId || 0));
    return rentalDefaultsForMachine(machine, context);
  }
  if (kind === "repair") {
    const machine = findEntity("vehicle", Number(context.machineId || 0));
    return repairDefaultsForMachine(machine, context);
  }
  if (kind === "purchaseOrder") {
    return purchaseOrderDefaults(context);
  }
  if (kind === "stocktaking") {
    return { resourceType: "PART", actualQuantity: 0, status: "DRAFT", stocktakingDate: todayInputDate() };
  }
  if (kind === "warehouse") {
    return { warehouseType: "MAIN" };
  }
  if (kind === "stockTransfer") {
    return { resourceType: "PART", quantity: 1 };
  }
  if (kind === "dataRestore") {
    return { confirmation: "RESTORE-DATA-BACKUP" };
  }
  if (kind === "attachmentUpload") {
    const filters = state.filters.attachments || {};
    const resourceType = filters.resourceType || "MACHINE";
    const entity = {
      resourceType,
      attachmentCategory: filters.category || attachmentDefaultCategoryForResourceType(resourceType)
    };
    if (filters.resourceId) {
      setPrefill(entity, "resourceId", filters.resourceId, `预填：${filters.resourceId}`);
    }
    return entity;
  }
  const entity = {};
  if (kind === "configValue" && state.selectedConfigItemId) {
    const selectedItem = state.data.configItems.find(item => Number(item.id) === Number(state.selectedConfigItemId));
    setPrefill(entity, "configItemId", state.selectedConfigItemId, selectedItem ? `预填：${configItemLabel(selectedItem)}` : undefined);
  }
  if (kind === "vehicleConfigValue" && state.selectedVehicleConfigItemId) {
    const selectedItem = state.data.vehicleConfigItems.find(item => Number(item.id) === Number(state.selectedVehicleConfigItemId));
    setPrefill(entity, "vehicleConfigItemId", state.selectedVehicleConfigItemId, selectedItem ? `预填：${selectedItem.specificationModel || ""}` : undefined);
  }
  return entity;
}

function purchaseOrderDefaults(context = {}) {
  const resourceType = String(context.resourceType || "PART").toUpperCase() === "MACHINE" ? "MACHINE" : "PART";
  const entity = resourceType === "MACHINE"
    ? vehicleInboundDefaultsForModel(context)
    : { quantity: 1, unit: "件", status: "ORDERED", orderDate: todayInputDate() };
  if (resourceType === "MACHINE") {
    entity.status = "RECEIVED";
    entity.orderDate = todayInputDate();
    entity.freightAmount = 0;
  }
  setPrefill(entity, "resourceType", resourceType, `默认：${resourceType === "MACHINE" ? "整车入库" : "配件入库"}`);
  if (context.supplierId) {
    const supplier = findEntity("supplier", Number(context.supplierId || 0));
    setPrefill(entity, "supplierId", context.supplierId, supplier?.id ? `预填：${entityDisplayName("supplier", supplier)}` : undefined);
  }
  if (resourceType === "PART") {
    if (context.configItemId) setPrefill(entity, "configItemId", context.configItemId);
    if (context.configValueId) setPrefill(entity, "configValueId", context.configValueId);
  }
  return entity;
}

function preparePurchaseOrderModalItem(item = {}) {
  if (!item.resourceType && !Object.prototype.hasOwnProperty.call(item.__prefillValues || {}, "resourceType")) {
    setPrefill(item, "resourceType", "PART", "默认：配件入库");
  }
  if (purchaseOrderResourceType(item) !== "MACHINE") return item;
  item.vehicleProductNumber ??= item.resourceCode || "";
  item.name ??= item.resourceName || "";
  item.supplier ??= item.supplierName || "";
  item.inventoryCount ??= item.quantity || 1;
  item.remarks ??= item.remark || "";
  if (!item.inboundDate && item.orderDate) {
    item.inboundDate = `${item.orderDate}T00:00`;
  }
  clearPrefill(item, "salePrice");
  clearPrefill(item, "purchasePrice");
  const existingSettlement = item.settlementPrice ?? item.unitPrice ?? (item.totalAmount && item.quantity ? Number(item.totalAmount) / Number(item.quantity) : "");
  setPrefill(item, "settlementPrice", existingSettlement, existingSettlement !== "" ? `预填：${money(existingSettlement)}` : undefined);
  if (item.purchasePrice) {
    setPrefill(item, "purchasePrice", item.purchasePrice, `预填：${money(item.purchasePrice)}`);
  }
  item.__placeholders = {
    ...vehicleInboundDefaultsForModel(item).__placeholders,
    ...(item.__placeholders || {})
  };
  ensureConfigSelections(item);
  return item;
}

function setPrefill(entity, name, value, placeholder) {
  if (value === undefined || value === null || value === "") return entity;
  entity.__prefillValues = {
    ...(entity.__prefillValues || {}),
    [name]: value
  };
  if (placeholder) {
    entity.__placeholders = {
      ...(entity.__placeholders || {}),
      [name]: placeholder
    };
  }
  return entity;
}

function clearPrefill(entity, name) {
  if (!entity) return entity;
  if (entity.__prefillValues) {
    delete entity.__prefillValues[name];
  }
  if (entity.__placeholders) {
    delete entity.__placeholders[name];
  }
  return entity;
}

function ensureConfigSelections(item = state.modal?.item || {}) {
  if (!Array.isArray(item.configSelections) || !item.configSelections.length) {
    item.configSelections = [{ configItemId: "", configValueId: "" }];
  }
  return item.configSelections;
}

function effectiveConfigSelectionValue(row = {}, name) {
  const value = row?.[name];
  if (value !== undefined && value !== null && value !== "") return value;
  if (Object.prototype.hasOwnProperty.call(row?.__prefillValues || {}, name)) {
    return row.__prefillValues[name];
  }
  return value;
}

function clearConfigSelectionPrefill(row = {}, name) {
  if (row.__prefillValues) {
    delete row.__prefillValues[name];
  }
  if (row.__placeholders) {
    delete row.__placeholders[name];
  }
  row.__fromVehicleTemplate = false;
  return row;
}

async function prepareVehicleInboundTemplate(item = state.modal?.item || {}) {
  const specificationModel = String(effectiveFieldValue(item, "specificationModel") || "").trim();
  if (!specificationModel) {
    clearVehicleTemplateSelections(item);
    return;
  }
  const matchedTemplate = vehicleConfigItemBySpecificationModel(specificationModel);
  if (!matchedTemplate) {
    clearVehicleTemplateSelections(item);
    return;
  }
  const canonicalSpecificationModel = String(matchedTemplate.specificationModel || specificationModel).trim();
  const normalized = normalizeText(canonicalSpecificationModel);
  if (item.__vehicleConfigTemplateSpec === normalized) return;
  const params = new URLSearchParams({ specificationModel: canonicalSpecificationModel });
  const template = await api(`${endpoints.vehicleConfigItem.templateBySpecification}?${params.toString()}`);
  item.__vehicleConfigTemplateSpec = normalized;
  item.__vehicleConfigTemplate = template;
  applyVehicleConfigTemplateSelections(item, template?.values || []);
}

function vehicleConfigItemBySpecificationModel(specificationModel) {
  const normalized = normalizeText(specificationModel);
  if (!normalized) return null;
  return state.data.vehicleConfigItems.find(item => normalizeText(item.specificationModel) === normalized) || null;
}

function clearVehicleTemplateSelections(item = {}) {
  if (!item.__vehicleConfigTemplateSpec && !Array.isArray(item.configSelections)) return;
  item.configSelections = (item.configSelections || []).filter(row => !row.__fromVehicleTemplate);
  item.__vehicleConfigTemplateSpec = "";
  item.__vehicleConfigTemplate = null;
  if (!item.configSelections.length) {
    item.configSelections = [{ configItemId: "", configValueId: "" }];
  }
}

function applyVehicleConfigTemplateSelections(item = {}, values = []) {
  const existing = Array.isArray(item.configSelections) ? item.configSelections : [];
  const manualRows = existing.filter(row => {
    if (row.__fromVehicleTemplate) return false;
    return Boolean(row.configItemId || row.configValueId);
  });
  const templateRows = values.map(value => ({
    configItemId: "",
    configValueId: "",
    __fromVehicleTemplate: true,
    __prefillValues: {
      configItemId: value.configItemId,
      configValueId: value.configValueId
    },
    __placeholders: {
      configItemId: `默认：${value.configItemLabel || value.configItemName || value.configItemId}`,
      configValueId: `默认：${value.configValueLabel || value.configValueId}`
    }
  }));
  item.configSelections = [...templateRows, ...manualRows];
  if (!item.configSelections.length) {
    item.configSelections = [{ configItemId: "", configValueId: "" }];
  }
}

function vehicleModelDefaults(powerType) {
  const entity = {};
  setPrefill(entity, "machineType", normalizePowerType(powerType), `预填：${normalizePowerType(powerType)}`);
  return entity;
}

function vehicleInboundDefaultsForModel(group = {}) {
  const entity = {
    __modelKey: group.modelKey || "",
    __modelIdentity: {
      name: group.name || "",
      specificationModel: group.specificationModel || "",
      machineType: group.machineType || ""
    },
    configSelections: [{ configItemId: "", configValueId: "" }],
    __placeholders: {
      vehicleProductNumber: "请输入此辆整车唯一车号",
      engineNumber: "请输入发动机号",
      frameNumber: "请输入车架号",
      warrantyCardNumber: "请输入保修卡号",
      applicationNumber: "可填写样机申请单号",
      materialNumber: "可填写物料号",
      remarks: "可记录仓储车季返、特殊说明或原表备注"
    }
  };
  setPrefill(entity, "name", group.name || "", group.name ? `预填：${group.name}` : undefined);
  setPrefill(entity, "specificationModel", group.specificationModel || "", group.specificationModel ? `预填：${group.specificationModel}` : undefined);
  setPrefill(entity, "configuration", group.configuration || "", group.configuration ? `预填：${group.configuration}` : undefined);
  setPrefill(entity, "machineType", group.machineType || "", group.machineType ? `预填：${group.machineType}` : undefined);
  setPrefill(entity, "supplier", group.supplier || "", group.supplier ? `预填：${group.supplier}` : undefined);
  setPrefill(entity, "warehouseName", group.warehouseName || "", group.warehouseName ? `预填：${group.warehouseName}` : undefined);
  setPrefill(entity, "purchasePrice", group.purchasePrice || "", group.purchasePrice ? `预填：${money(group.purchasePrice)}` : undefined);
  setPrefill(entity, "settlementPrice", group.settlementPrice || "", group.settlementPrice ? `预填：${money(group.settlementPrice)}` : undefined);
  if (isManualForklift(group.machineType)) {
    entity.__placeholders = {
      ...entity.__placeholders,
      name: group.name ? `预填：${group.name}` : "请输入车型名称",
      specificationModel: group.specificationModel ? `预填：${group.specificationModel}` : "请输入规格型号",
      settlementPrice: "请输入此辆整车结算价"
    };
  }
  return entity;
}

function vehicleOutboundDefaultsForMachine(machine = {}, modelKey = null, context = {}) {
  const entity = {
    __modelKey: modelKey || (machine?.id ? vehicleModelKey(machine) : ""),
    customerMode: "existing",
    salesDate: todayInputDate(),
    paymentSettled: false,
    salesReported: false,
    invoiceApplied: false,
    __placeholders: {
      customerId: "请选择客户列表中的公司",
      paymentRemark: "例如：已收订金 / 尾款待结",
      invoiceStatus: "例如：含税已开票 / 不含税",
      registrationStatus: "例如：包上牌 / 已上牌",
      contractType: "例如：纸质合同 / 电子合同"
    }
  };
  if (machine?.id) {
    setPrefill(entity, "machineId", machine.id, `预填：${vehicleNumberLabel(machine)}`);
    const unitSalePrice = machine.salePrice || machine.settlementPrice || "";
    setPrefill(entity, "unitSalePrice", unitSalePrice, unitSalePrice ? `预填：${money(unitSalePrice)}` : undefined);
    setPrefill(entity, "lineAmount", unitSalePrice, unitSalePrice ? `预填：${money(unitSalePrice)}` : undefined);
    if (machine.warehouseId) {
      setPrefill(entity, "warehouseId", machine.warehouseId, `预填：${warehouseNameById(machine.warehouseId)}`);
    }
  } else {
    entity.__placeholders = {
      ...entity.__placeholders,
      machineId: "请选择准确出库车辆"
    };
  }
  applyCustomerPrefill(entity, context.customerId || customerIdForMachine(machine));
  return entity;
}

function vehicleOutboundDefaultsForModel(group = {}) {
  const availableVehicles = vehiclesForModelKey(group.modelKey).filter(canOutboundVehicle);
  if (availableVehicles.length === 1) {
    return vehicleOutboundDefaultsForMachine(availableVehicles[0], group.modelKey);
  }
  return {
    __modelKey: group.modelKey,
    customerMode: "existing",
    salesDate: todayInputDate(),
    paymentSettled: false,
    salesReported: false,
    invoiceApplied: false,
    __placeholders: {
      machineId: "请选择此车型库存车号",
      customerId: "请选择客户列表中的公司",
      paymentRemark: "例如：已收订金 / 尾款待结",
      invoiceStatus: "例如：含税已开票 / 不含税",
      registrationStatus: "例如：包上牌 / 已上牌",
      contractType: "例如：纸质合同 / 电子合同"
    }
  };
}

function syncVehicleOutboundDefaults(machineId) {
  const machine = findEntity("vehicle", Number(machineId || 0));
  clearPrefill(state.modal.item, "unitSalePrice");
  clearPrefill(state.modal.item, "lineAmount");
  clearPrefill(state.modal.item, "warehouseId");
  const unitSalePrice = machine.salePrice || machine.settlementPrice || "";
  setPrefill(state.modal.item, "unitSalePrice", unitSalePrice, unitSalePrice ? `预填：${money(unitSalePrice)}` : undefined);
  setPrefill(state.modal.item, "lineAmount", unitSalePrice, unitSalePrice ? `预填：${money(unitSalePrice)}` : undefined);
  if (machine.warehouseId) {
    setPrefill(state.modal.item, "warehouseId", machine.warehouseId, `预填：${warehouseNameById(machine.warehouseId)}`);
  }
}

function syncPartOutboundDefaults(partCode) {
  const part = state.data.parts.find(item => String(item.partCode) === String(partCode)) || {};
  if (part.version !== undefined) {
    state.modal.item.version = part.version;
  }
  clearPrefill(state.modal.item, "unitSalePrice");
  clearPrefill(state.modal.item, "lineAmount");
  const price = part.salePrice || part.settlementPrice || "";
  const quantity = Math.max(1, Number(state.modal.item.quantity || 1));
  setPrefill(state.modal.item, "unitSalePrice", price, price ? `预填：${money(price)}` : undefined);
  setPrefill(state.modal.item, "lineAmount", price ? formatDecimal(Number(price) * quantity) : "", price ? `预填：${money(Number(price) * quantity)}` : undefined);
  if (part.warehouseId) {
    setPrefill(state.modal.item, "warehouseId", part.warehouseId, `预填：${warehouseNameById(part.warehouseId)}`);
  }
}

function syncPartAdjustmentDefaults(partCode) {
  const part = state.data.parts.find(item => String(item.partCode) === String(partCode)) || {};
  if (part.version !== undefined) {
    state.modal.item.version = part.version;
  }
  clearPrefill(state.modal.item, "warehouseId");
  if (part.warehouseId) {
    setPrefill(state.modal.item, "warehouseId", part.warehouseId, `预填：${warehouseNameById(part.warehouseId)}`);
  }
}

function modificationOrderDefaults(machine = {}, configId = null) {
  const entity = {};
  if (!machine?.id) return entity;
  const modelKey = vehicleModelKey(machine);
  const detailConfigs = state.vehicleDetail?.selectedDetail?.machine?.id === machine.id
    ? (state.vehicleDetail.selectedDetail.configs || [])
    : (state.vehicleDetail?.machine?.id === machine.id ? (state.vehicleDetail.configs || []) : []);
  const config = detailConfigs.find(item => String(item.id) === String(configId))
    || detailConfigs[0]
    || null;
  setPrefill(entity, "vehicleModelKey", modelKey, `预填：${vehicleModelLabel(machine)}`);
  setPrefill(entity, "machineId", machine.id, `预填：${machine.vehicleProductNumber || machine.id}`);
  if (config) {
    setPrefill(entity, "machineConfigId", config.id, `预填：${machineConfigOptionLabel(config)}`);
  }
  if (machine.warehouseId) {
    setPrefill(entity, "warehouseId", machine.warehouseId, `预填：${warehouseNameById(machine.warehouseId)}`);
  }
  setPrefill(entity, "workOrderType", "PRE_SALE", "默认：售前改装");
  entity.__placeholders = {
    ...(entity.__placeholders || {}),
    quantity: "默认：1",
    oldPartAction: "默认：拆下件入库"
  };
  return entity;
}

function vehicleConfigChangeDefaults(machine = {}, mode = "INSTALL", configId = null) {
  const changeMode = normalizeVehicleConfigChangeMode(mode);
  if (changeMode === "WORK_ORDER") {
    return {
      ...modificationOrderDefaults(machine, configId),
      changeMode
    };
  }
  if (changeMode === "REPLACE") {
    return {
      changeMode,
      machineId: machine?.id || null,
      machineVersion: machine?.version,
      machineConfigId: configId || null
    };
  }
  return {
    ...vehiclePartInstallDefaultsForMachine(machine),
    changeMode
  };
}

function rentalDefaultsForMachine(machine = {}, context = {}) {
  const entity = {
    status: "ACTIVE",
    startDate: todayInputDate(),
    __placeholders: {
      machineId: "请选择具体在库车号",
      customerId: "请选择客户列表中的租赁去向",
      destination: "可填写具体工地、收货地址或临时周转说明",
      monthlyRentalPrice: "请输入每月租赁价格",
      endDate: "未确定可先留空"
    }
  };
  if (machine?.id) {
    setPrefill(entity, "machineId", machine.id, `预填：${vehicleNumberLabel(machine)}`);
  }
  applyCustomerPrefill(entity, context.customerId || customerIdForMachine(machine));
  return entity;
}

function repairDefaults() {
  const entity = {
    status: "PENDING",
    repairDate: nowInputDateTime(),
    __placeholders: {
      machineId: "请从车辆库存中选择车号",
      customerId: "请从客户列表中选择客户",
      partUsages: "按配件明细选择实际领料仓库和收费",
      totalFee: "客户应收 = 服务费 + 配件收费 + 可转嫁金额；外协成本仅计入内部成本"
    }
  };
  setPrefill(entity, "repairPersonChoice", "OTHER", "预填：其他");
  return entity;
}

function repairDefaultsForMachine(machine = {}, context = {}) {
  const entity = repairDefaults();
  if (machine?.id) {
    setPrefill(entity, "machineId", machine.id, `预填：${vehicleNumberLabel(machine)}`);
  }
  applyCustomerPrefill(entity, context.customerId || customerIdForMachine(machine));
  return entity;
}

function applyCustomerPrefill(entity, customerId) {
  const numericId = Number(customerId || 0);
  if (!numericId) return entity;
  const customer = findEntity("customer", numericId);
  setPrefill(entity, "customerId", numericId, customer?.id ? `预填：${entityDisplayName("customer", customer)}` : undefined);
  return entity;
}

function customerIdForMachine(machine = {}) {
  if (!machine?.id) return null;
  const rental = activeRentalForMachine(machine.id);
  if (rental?.customerId) return rental.customerId;
  const order = latestVehicleOutboundOrder(machine.id);
  if (order?.customerId) return order.customerId;
  return null;
}

function prepareRepairModalItem(item = {}) {
  if (!item.repairPersonChoice) {
    item.repairPersonChoice = item.repairExternal ? "OTHER" : (item.repairPersonUserId || "");
  }
  if (item.usedPartIds && typeof item.usedPartIds === "string") {
    item.usedPartIds = item.usedPartIds.split(",").map(value => value.trim()).filter(Boolean);
  }
  ensureRepairPartUsageRows(item);
  if (item.totalFee === undefined || item.totalFee === null || item.totalFee === "") {
    item.totalFee = repairTotalFee(item);
  }
  return item;
}

function ensureRepairPartUsageRows(item = {}) {
  if (Array.isArray(item.partUsages)) {
    item.partUsages = item.partUsages.map(row => ({ ...row, quantity: Number(row.quantity || 1) }));
    return item.partUsages;
  }
  const legacyIds = Array.isArray(item.usedPartIds)
    ? item.usedPartIds
    : String(item.usedPartIds || "").split(",").map(value => value.trim()).filter(Boolean);
  item.partUsages = legacyIds.map(partId => ({
    partId: Number(partId),
    quantity: 1,
    warehouseId: null,
    chargeUnitPrice: null,
    discountAmount: null,
    remark: ""
  }));
  return item.partUsages;
}

function collectRepairPartUsages(form) {
  if (!form) return [];
  return [...form.querySelectorAll("[data-repair-part-row]")].map(row => {
    const value = name => row.querySelector(`[data-repair-part-field="${name}"]`)?.value ?? "";
    const id = Number.parseInt(value("id"), 10);
    const partId = Number.parseInt(value("partId"), 10);
    const warehouseId = Number.parseInt(value("warehouseId"), 10);
    const quantity = Number.parseInt(value("quantity"), 10);
    const chargeUnitPrice = value("chargeUnitPrice");
    const discountAmount = value("discountAmount");
    return {
      ...(Number.isFinite(id) ? { id } : {}),
      partId: Number.isFinite(partId) ? partId : null,
      warehouseId: Number.isFinite(warehouseId) ? warehouseId : null,
      quantity: Number.isFinite(quantity) ? quantity : 0,
      chargeUnitPrice: chargeUnitPrice === "" ? null : chargeUnitPrice,
      discountAmount: discountAmount === "" ? null : discountAmount,
      remark: String(value("remark") || "").trim() || null
    };
  }).filter(row => row.partId || row.quantity || row.chargeUnitPrice || row.discountAmount || row.remark);
}

function syncRepairPartUsageEditor(form, changedRow = null) {
  if (!form) return;
  if (changedRow?.dataset?.repairPartRow !== undefined) {
    const partId = Number(changedRow.querySelector('[data-repair-part-field="partId"]')?.value || 0);
    const part = state.data.parts.find(item => Number(item.id) === partId);
    if (part) {
      const warehouse = changedRow.querySelector('[data-repair-part-field="warehouseId"]');
      const unitPrice = changedRow.querySelector('[data-repair-part-field="chargeUnitPrice"]');
      if (warehouse && !warehouse.value && part.warehouseId) warehouse.value = String(part.warehouseId);
      if (unitPrice && !unitPrice.value) {
        const defaultPrice = firstAmount(part.salePrice, part.settlementPrice, part.purchasePrice);
        if (defaultPrice !== null) unitPrice.value = formatDecimal(defaultPrice);
      }
    }
  }
  const usages = collectRepairPartUsages(form);
  state.modal.item.partUsages = usages;
  const partsFee = usages.reduce(
    (total, row) => total + repairPartUsageLineAmount(row.chargeUnitPrice, row.quantity, row.discountAmount),
    0
  );
  setFormFieldValue(form, "partsFee", formatDecimal(partsFee), false);
  for (const row of form.querySelectorAll("[data-repair-part-row]")) {
    const lineTotal = repairPartUsageLineAmount(
      row.querySelector('[data-repair-part-field="chargeUnitPrice"]')?.value,
      row.querySelector('[data-repair-part-field="quantity"]')?.value,
      row.querySelector('[data-repair-part-field="discountAmount"]')?.value
    );
    const output = row.querySelector("[data-repair-part-line-total]");
    if (output) output.textContent = formatDecimal(lineTotal);
  }
  syncRepairTotalFee(form);
}

function repairPartUsageLineAmount(unitPrice, quantity, discountAmount) {
  const total = amountValue(unitPrice) * Math.max(0, Number.parseInt(quantity, 10) || 0)
    - amountValue(discountAmount);
  return Math.max(0, total);
}

function repairUsesExternal(item = {}) {
  const rawChoice = fieldOrPrefillValue(item, "repairPersonChoice");
  if (rawChoice !== undefined && rawChoice !== null && String(rawChoice).trim() !== "") {
    return String(rawChoice).trim().toUpperCase() === "OTHER";
  }
  return item.repairExternal === true;
}

function effectiveFieldValue(entity = {}, name) {
  const value = entity?.[name];
  if (value !== undefined && value !== null && value !== "") return value;
  if (Object.prototype.hasOwnProperty.call(entity?.__prefillValues || {}, name)) {
    return entity.__prefillValues[name];
  }
  return value;
}

function syncConfigItemFields(form, changedName) {
  if (changedName === "subCategory" && !form.elements.itemName?.value) {
    setFormFieldValue(form, "itemName", form.elements.subCategory.value);
  }
  if (!form.elements.itemCode?.value) {
    setFormFieldValue(form, "itemCode", nextConfigItemCode());
  }
}

function syncPartDictionaryFields(form, changedName) {
  if (changedName === "partCategory") {
    const selectedPartName = form.elements.partName?.value || "";
    const options = configValueOptions();
    syncComboOptionsForField(form, "partName", options, findOptionByValue(options, selectedPartName) ? selectedPartName : "");
    return;
  }
  if (changedName !== "partName") return;
  const meta = selectedComboMeta(form, "partName");
  if (meta?.partCategory && !form.elements.partCategory?.value) {
    setFormFieldValue(form, "partCategory", meta.partCategory, false);
  }
}

function syncRepairTotalFee(form) {
  setFormFieldValue(form, "totalFee", repairTotalFee({
    repairPersonChoice: form.elements.repairPersonChoice?.value,
    repairExternal: state.modal?.item?.repairExternal,
    repairFee: form.elements.repairFee?.value,
    partsFee: form.elements.partsFee?.value,
    passThroughAmount: form.elements.passThroughAmount?.value
  }), false);
}

function repairTotalFee(item = {}) {
  return decimalSum(item.repairFee, item.partsFee, item.passThroughAmount);
}

function decimalSum(...values) {
  const total = values.reduce((sum, value) => sum + Number(value || 0), 0);
  return total ? total.toFixed(2) : "0.00";
}

function syncRepairVehicleSelection(form, machineId) {
  const machine = state.data.vehicles.find(item => Number(item.id) === Number(machineId));
  if (!machine) return;
  state.modal.item.vehicleNumber = machine.vehicleProductNumber || "";
}

function syncCustomerAddress(form, customerId) {
  const customer = state.data.customers.find(item => Number(item.id) === Number(customerId));
  if (!customer) return;
  state.modal.item.customerName = customer.companyName || "";
  state.modal.item.customerAddress = customer.address || "";
}

function syncRentalCustomerDestination(form, customerId) {
  const customer = state.data.customers.find(item => Number(item.id) === Number(customerId));
  if (!customer) return;
  if (!String(form.elements.destination?.value || "").trim()) {
    setFormFieldValue(form, "destination", customer.address || customer.companyName || "", false);
  }
}

function syncRentalVehicleDefaults(form, machineId) {
  const machine = state.data.vehicles.find(item => Number(item.id) === Number(machineId));
  if (!machine) return;
  if (!String(form.elements.destination?.value || "").trim() && machine.destination1) {
    setFormFieldValue(form, "destination", machine.destination1, false);
  }
}

function syncRepairPartFee(form, partId) {
  const part = state.data.parts.find(item => Number(item.id) === Number(partId));
  if (!part) return;
  const price = firstAmount(part.settlementPrice, part.salePrice, part.purchasePrice);
  if (price !== null && !Number(form.elements.partsFee?.value || 0)) {
    setFormFieldValue(form, "partsFee", formatDecimal(price), false);
    syncRepairTotalFee(form);
  }
}

async function syncStocktakingQuantity(form, resourceId) {
  const type = String(form.elements.resourceType?.value || "PART").toUpperCase();
  const rows = type === "MACHINE" ? state.data.vehicles : state.data.parts;
  const resource = rows.find(item => Number(item.id) === Number(resourceId));
  if (!resource) return;
  let warehouseId = Number(form.elements.warehouseId?.value || 0);
  if (!warehouseId && resource.warehouseId) {
    warehouseId = Number(resource.warehouseId);
    setFormFieldValue(form, "warehouseId", warehouseId, false);
    state.modal.item.warehouseId = warehouseId;
  }
  if (!warehouseId) return;
  const params = new URLSearchParams({
    resourceType: type,
    resourceId: String(resourceId),
    warehouseId: String(warehouseId)
  });
  const quantity = await api(`${endpoints.warehouse.balance}?${params.toString()}`);
  setFormFieldValue(form, "actualQuantity", quantity ?? 0, false);
  state.modal.item.actualQuantity = Number(quantity || 0);
}

function syncStockTransferResource(form, resourceId) {
  const type = String(form.elements.resourceType?.value || "PART").toUpperCase();
  const rows = type === "MACHINE" ? state.data.vehicles : state.data.parts;
  const resource = rows.find(item => Number(item.id) === Number(resourceId));
  if (!resource) return;
  if (resource.warehouseId) {
    setFormFieldValue(form, "fromWarehouseId", resource.warehouseId, false);
  }
  state.modal.item.version = resource.version;
  if (type === "MACHINE") {
    setFormFieldValue(form, "quantity", 1, false);
  }
}

function syncPurchaseResourceDefaults(form) {
  if (purchaseOrderResourceType() !== "PART") return;
  const meta = selectedComboMeta(form, "configValueId");
  if (!meta) return;
  if (meta.unit && !String(form.elements.unit?.value || "").trim()) {
    setFormFieldValue(form, "unit", meta.unit, false);
  }
}

function syncPurchaseSkuDefaults(form) {
  if (purchaseOrderResourceType() !== "PART") return;
  const meta = selectedComboMeta(form, "resourceId");
  if (!meta) return;
  if (meta.unit && !String(form.elements.unit?.value || "").trim()) {
    setFormFieldValue(form, "unit", meta.unit, false);
  }
  if (meta.warehouseId && !String(form.elements.warehouseId?.value || "").trim()) {
    setFormFieldValue(form, "warehouseId", meta.warehouseId, false);
  }
}

function applyPurchaseOrderResourceMode(form, value) {
  const type = String(value || "PART").toUpperCase() === "MACHINE" ? "MACHINE" : "PART";
  state.modal.item.resourceType = type;
  clearPrefill(state.modal.item, "resourceType");
  if (type === "MACHINE") {
    state.modal.item.configItemId = null;
    state.modal.item.configValueId = null;
    state.modal.item.purchasePrice = null;
    state.modal.item.salePrice = null;
    state.modal.item.settlementPrice = null;
    state.modal.item.unitPrice = null;
    state.modal.item.totalAmount = null;
    state.modal.item.status = "RECEIVED";
    state.modal.item.orderDate ||= todayInputDate();
    state.modal.item.freightAmount ??= 0;
    state.modal.item.__placeholders = {
      ...vehicleInboundDefaultsForModel(state.modal.item).__placeholders,
      ...(state.modal.item.__placeholders || {})
    };
    ensureConfigSelections(state.modal.item);
    return;
  }
  state.modal.item.resourceCode = null;
  state.modal.item.resourceName = null;
  state.modal.item.specificationModel = null;
  state.modal.item.status = "ORDERED";
  if (!state.modal.item.unit || state.modal.item.unit === "台") {
    state.modal.item.unit = "件";
  }
}

function syncPurchaseAmount(form, changedName) {
  const quantity = amountValue(form.elements.quantity?.value);
  const unitPrice = amountValue(form.elements.unitPrice?.value);
  const totalAmount = amountValue(form.elements.totalAmount?.value);
  if (changedName === "totalAmount" && quantity > 0 && totalAmount > 0) {
    setFormFieldValue(form, "unitPrice", formatDecimal(totalAmount / quantity), false);
    return;
  }
  if (quantity > 0 && unitPrice > 0) {
    setFormFieldValue(form, "totalAmount", formatDecimal(quantity * unitPrice), false);
  }
}

function isPaymentField(name) {
  return ["unitSalePrice", "lineAmount", "receivedAmount", "paymentSettled", "lastPaymentDate", "quantity"].includes(name);
}

function syncPaymentFields(form, changedName) {
  if (!form) return;
  const quantity = Math.max(1, amountValue(form.elements.quantity?.value || 1));
  const unitSalePrice = amountValue(form.elements.unitSalePrice?.value);
  if (["unitSalePrice", "quantity"].includes(changedName) && unitSalePrice > 0) {
    const nextLineAmount = unitSalePrice * quantity;
    setFormFieldValue(form, "lineAmount", formatDecimal(nextLineAmount), false);
  }
  const receivable = amountValue(form.elements.lineAmount?.value);
  if (changedName === "paymentSettled" && form.elements.paymentSettled?.checked && receivable > 0) {
    setFormFieldValue(form, "receivedAmount", formatDecimal(receivable), false);
  }
  const received = amountValue(form.elements.receivedAmount?.value);
  if (received > 0 && !String(form.elements.lastPaymentDate?.value || "").trim()) {
    setFormFieldValue(form, "lastPaymentDate", todayInputDate(), false);
  }
  if (changedName !== "paymentSettled") {
    if (receivable > 0 && received >= receivable) {
      setFormFieldValue(form, "paymentSettled", true, false);
    } else if (changedName === "receivedAmount" && received > 0 && receivable > received) {
      setFormFieldValue(form, "paymentSettled", false, false);
    }
  }
}

function firstAmount(...values) {
  for (const value of values) {
    const amount = amountValue(value);
    if (amount > 0) return amount;
  }
  return null;
}

function amountValue(value) {
  const amount = Number(value || 0);
  return Number.isFinite(amount) ? amount : 0;
}

function formatDecimal(value) {
  const amount = Number(value || 0);
  if (!Number.isFinite(amount)) return "";
  return amount ? amount.toFixed(2) : "0.00";
}

function datePart(value) {
  const text = String(value || "").trim();
  return text.length >= 10 ? text.slice(0, 10) : null;
}

function enrichPurchaseOrderPayload(payload, form, item = {}) {
  const type = String(payload.resourceType || "PART").toUpperCase() === "MACHINE" ? "MACHINE" : "PART";
  payload.resourceType = type;
  if (type === "PART") {
    const sku = selectedComboMeta(form, "resourceId");
    if (sku?.unit && !payload.unit) {
      payload.unit = sku.unit;
    }
    return;
  }
  payload.configItemId = null;
  payload.configValueId = null;
  payload.resourceCode = payload.vehicleProductNumber || payload.resourceCode || null;
  payload.resourceName = payload.name || payload.resourceName || null;
  payload.supplierName = payload.supplier || payload.supplierName || null;
  payload.quantity = Number(payload.inventoryCount || payload.quantity || 1);
  payload.unit = "台";
  const machineSettlementPrice = payload.settlementPrice || payload.purchasePrice || payload.unitPrice || null;
  payload.unitPrice = machineSettlementPrice;
  if (!payload.totalAmount && machineSettlementPrice) {
    payload.totalAmount = formatDecimal(amountValue(machineSettlementPrice) * Math.max(1, Number(payload.quantity || 1)));
  }
  payload.orderDate = payload.orderDate || datePart(payload.inboundDate) || todayInputDate();
  payload.status = payload.status || item.status || "RECEIVED";
  payload.remark = payload.remarks || payload.remark || null;
}

function selectedComboMeta(form, name) {
  const input = form.elements[name];
  if (!input?.dataset?.selectedMeta) return null;
  try {
    return JSON.parse(input.dataset.selectedMeta);
  } catch (error) {
    return null;
  }
}

function setFormFieldValue(form, name, value, shouldNotify = true) {
  const combo = form.querySelector(`[data-combo][data-name="${name}"]`);
  if (combo) {
    const options = [...combo.querySelectorAll("[data-combo-option]")].map(option => ({
      value: option.dataset.value,
      label: option.dataset.label,
      meta: option.dataset.meta ? JSON.parse(option.dataset.meta) : null
    }));
    const selected = findOptionByValue(options, value);
    if (selected) {
      setComboValue(combo, selected.value, selected.label, selected.meta ? JSON.stringify(selected.meta) : "", shouldNotify);
    } else {
      const input = combo.querySelector("[data-combo-input]");
      const hidden = combo.querySelector(`input[name="${name}"]`);
      input.value = value || "";
      hidden.value = value || "";
      hidden.dataset.selectedLabel = value || "";
      hidden.dataset.selectedMeta = "";
      if (shouldNotify) {
        hidden.dispatchEvent(new Event("change", { bubbles: true }));
      }
    }
    return;
  }
  if (form.elements[name]) {
    if (form.elements[name].type === "checkbox") {
      form.elements[name].checked = Boolean(value);
    } else {
      form.elements[name].value = value ?? "";
    }
    if (state.modal?.item) {
      state.modal.item[name] = form.elements[name].type === "checkbox" ? form.elements[name].checked : form.elements[name].value;
    }
    if (shouldNotify) {
      form.elements[name].dispatchEvent(new Event("change", { bubbles: true }));
    }
  }
}

function defaultValue(field) {
  if (typeof field.defaultValue === "function") return field.defaultValue();
  if (field.defaultValue !== undefined) return field.defaultValue;
  if (field.type === "checkbox") return false;
  return "";
}

function isModificationDiscountMode(item = state.modal?.item || {}) {
  return fieldOrPrefillValue(item, "oldPartAction") === "DISCOUNT";
}

function normalizeVehicleConfigChangeMode(mode) {
  const normalized = String(mode || "").toUpperCase();
  if (normalized === "REPLACE" || normalized === "WORK_ORDER" || normalized === "INSTALL") {
    return normalized;
  }
  return "INSTALL";
}

function vehicleConfigChangeTargetKind(mode) {
  return {
    INSTALL: "vehiclePartInstall",
    REPLACE: "partReplace",
    WORK_ORDER: "modificationOrder"
  }[normalizeVehicleConfigChangeMode(mode)] || "vehiclePartInstall";
}

function vehicleConfigChangeMode(item = state.modal?.item || {}) {
  return normalizeVehicleConfigChangeMode(fieldOrPrefillValue(item, "changeMode"));
}

function isVehicleConfigChangeMode(mode) {
  return state.modal?.kind === "vehicleConfigChange" && vehicleConfigChangeMode() === normalizeVehicleConfigChangeMode(mode);
}

function vehicleConfigChangeModeLabel(mode) {
  return {
    INSTALL: "新增装车",
    REPLACE: "替换配件",
    WORK_ORDER: "创建工单"
  }[normalizeVehicleConfigChangeMode(mode)] || "车辆配置变更";
}

function purchaseOrderResourceType(item = state.modal?.item || {}) {
  const normalized = String(fieldOrPrefillValue(item, "resourceType") || "PART").toUpperCase();
  return normalized === "MACHINE" ? "MACHINE" : "PART";
}

function usesVehicleInboundConfigEditor(kind, item = state.modal?.item || {}) {
  return kind === "vehicleInbound" || (kind === "purchaseOrder" && purchaseOrderResourceType(item) === "MACHINE");
}

function vehicleInboundFormFields(item = state.modal?.item || {}) {
  const baseFields = fields.vehicleInbound
    .filter(field => field.name !== "salePrice")
    .map(field => field.name === "stockStatus"
      ? { ...field, type: "hidden", defaultValue: "IN_STOCK", required: false }
      : field);
  if (!isManualForklift(fieldOrPrefillValue(item, "machineType"))) {
    return baseFields;
  }
  const hiddenNames = new Set(["vehicleProductNumber", "engineNumber", "frameNumber", "warrantyCardNumber", "machineType"]);
  return [
    ...baseFields.filter(field => !hiddenNames.has(field.name)),
    { name: "machineType", type: "hidden" }
  ];
}

function inventoryMasterFormFields(kind, item = {}) {
  const source = fields[kind] || [];
  if (!item?.id || Boolean(item.modelOnly)) return source;
  const hiddenNames = new Set(["warehouseId", ...(kind === "vehicle" ? ["stockStatus"] : [])]);
  const readOnlyNames = new Set([
    kind === "vehicle" ? "inventoryCount" : "quantity",
    "purchasePrice",
    "landedUnitCost"
  ]);
  return source.map(field => {
    if (hiddenNames.has(field.name)) {
      return { ...field, type: "hidden", required: false };
    }
    if (readOnlyNames.has(field.name)) {
      return { ...field, readOnly: true };
    }
    return field;
  });
}

function modificationOrderFormFields(item = state.modal?.item || {}) {
  if (!isModificationDiscountMode(item)) return fields.modificationOrder;
  return fields.modificationOrder.flatMap(field => {
    if (field.name === "newPartId") {
      return [{
        name: "newConfigValueId",
        label: "目标新配件",
        type: "select",
        coerce: "int",
        required: true,
        options: discountConfigValueOptions
      }];
    }
    if (field.name === "quantity") {
      return [{ name: "quantity", type: "hidden", coerce: "int", defaultValue: 1 }];
    }
    return [field];
  });
}

function getFields(kind, item = state.modal?.item || {}) {
  if (kind === "vehicle" || kind === "part") {
    return inventoryMasterFormFields(kind, item);
  }
  if (kind === "purchaseOrder") {
    const type = purchaseOrderResourceType(item);
    if (type === "MACHINE") {
      const resourceTypeField = fields.purchaseOrder.find(field => field.name === "resourceType");
      const specificationField = fields.purchaseOrder.find(field => field.name === "specificationModel");
      const purchaseOnlyNames = new Set([
        "freightAmount",
        "orderDate",
        "expectedArrivalDate",
        "receivedDate",
        "status",
        "operator"
      ]);
      return [
        ...(resourceTypeField ? [resourceTypeField] : []),
        ...(specificationField ? [specificationField] : []),
        ...vehicleInboundFormFields(item).filter(field => field.name !== "specificationModel"),
        ...fields.purchaseOrder.filter(field => purchaseOnlyNames.has(field.name))
      ];
    }
    const partFields = new Set(["configItemId", "configValueId"]);
    const machineFields = new Set(["resourceCode", "resourceName", "specificationModel"]);
    return fields.purchaseOrder.filter(field => {
      if (partFields.has(field.name)) return type === "PART";
      if (machineFields.has(field.name)) return type === "MACHINE";
      return true;
    });
  }
  if (kind === "vehicleInbound") {
    return vehicleInboundFormFields(item);
  }
  if (kind === "vehicleOutbound") {
    const customerMode = fieldOrPrefillValue(item, "customerMode") === "quickCreate" ? "quickCreate" : "existing";
    if (customerMode === "quickCreate") {
      const baseFields = fields.vehicleOutbound.filter(field => field.name !== "customerId");
      const insertIndex = baseFields.findIndex(field => field.name === "customerMode") + 1;
      return [
        ...baseFields.slice(0, insertIndex),
        ...fields.vehicleOutboundQuickCustomer,
        ...baseFields.slice(insertIndex)
      ];
    }
  }
  if (kind === "repair") {
    return fields.repair.filter(field => field.name !== "repairExpense" || repairUsesExternal(item));
  }
  if (kind === "rental" && !item?.id) {
    return fields.rental.flatMap(field => {
      if (field.name === "returnDate") return [];
      if (field.name === "status") {
        return [{ name: "status", type: "hidden", defaultValue: "ACTIVE" }];
      }
      return [field];
    });
  }
  if (kind === "vehicleConfigChange") {
    const mode = vehicleConfigChangeMode(item);
    if (mode === "INSTALL") {
      return [
        ...fields.vehicleConfigChange,
        ...fields.vehiclePartInstall
      ];
    }
    if (mode === "REPLACE") {
      return [
        ...fields.vehicleConfigChange,
        ...fields.partReplace
      ];
    }
    return [
      ...fields.vehicleConfigChange,
      ...modificationOrderFormFields(item)
    ];
  }
  if (kind === "partStock" && item.direction === "outbound") {
    return fields.partOutbound;
  }
  if (kind === "modificationOrder") {
    return modificationOrderFormFields(item);
  }
  return fields[kind] || [];
}

function fieldOrPrefillValue(item, name) {
  if (Object.prototype.hasOwnProperty.call(item || {}, name)) return item[name];
  return item?.__prefillValues?.[name];
}

function nextConfigItemCode() {
  const max = state.data.configItems
    .map(item => String(item.itemCode || ""))
    .map(code => /^CFG-(\d+)$/.exec(code))
    .filter(Boolean)
    .map(match => Number.parseInt(match[1], 10))
    .filter(Number.isFinite)
    .reduce((currentMax, value) => Math.max(currentMax, value), 0);
  return `CFG-${String(max + 1).padStart(4, "0")}`;
}

function findEntity(kind, id) {
  const detail = state.vehicleDetail || {};
  const contextualRows = kind === "vehicle" ? [
    detail.machine,
    detail.selectedDetail?.machine,
    ...(detail.vehicles || [])
  ].filter(Boolean) : [];
  return [...entityRows(kind), ...contextualRows]
    .find(item => item?.id !== null && item?.id !== undefined && id !== null && id !== undefined && String(item.id) === String(id)) || {};
}

function entityRows(kind) {
  const key = {
    vehicle: "vehicles",
    part: "parts",
    modificationOrder: "modificationOrders",
    outboundOrder: "outboundOrders",
    rental: "rentals",
    customer: "customers",
    supplier: "suppliers",
    purchaseOrder: "purchaseOrders",
    stocktaking: "stocktakingRecords",
    warehouse: "warehouses",
    stockMovement: "stockMovements",
    repair: "repairs",
    configItem: "configItems",
    configValue: "configValues",
    vehicleConfigItem: "vehicleConfigItems",
    vehicleConfigValue: "vehicleConfigValues",
    attachment: "attachments",
    importJob: "importJobs",
    user: "users"
  }[kind];
  return state.data[key] || [];
}

function entityLabel(kind) {
  if (kind === "attachment") return "附件";
  if (kind === "importJob") return "导入记录";
  return {
    vehicle: "车辆",
    part: "配件",
    modificationOrder: "改装工单",
    outboundOrder: "出库订单",
    rental: "租赁记录",
    customer: "客户",
    supplier: "供应商",
    purchaseOrder: "入库订单",
    stocktaking: "盘点记录",
    warehouse: "仓库",
    stockTransfer: "库存调拨",
    stockMovement: "库存流水",
    repair: "维修记录",
    configItem: "配置项",
    configValue: "配置值",
    vehicleConfigItem: "整车配置项",
    vehicleConfigValue: "整车配置值",
    user: "用户"
  }[kind] || "数据";
}

function entityDisplayName(kind, item = {}) {
  if (kind === "attachment") return display(item.originalName || item.attachmentLabel || item.id);
  if (kind === "importJob") return display(item.originalFileName || item.templateName || item.id);
  const value = {
    vehicle: item.vehicleProductNumber || item.name || item.id,
    part: [item.partCode, item.partName].filter(Boolean).join(" / "),
    modificationOrder: item.workOrderNo || item.machineProductNumber || item.id,
    outboundOrder: item.orderNo || item.resourceCode || item.id,
    rental: item.rentalNo || item.vehicleNumber || item.id,
    customer: item.companyName || item.contactName || item.id,
    supplier: item.supplierName || item.contactName || item.id,
    purchaseOrder: item.purchaseNo || item.resourceName || item.id,
    stocktaking: item.stocktakingNo || item.resourceName || item.id,
    warehouse: item.warehouseName || item.warehouseCode || item.id,
    stockMovement: item.movementNo || item.resourceCode || item.id,
    repair: item.vehicleNumber || item.customerName || item.id,
    configItem: item.itemName || item.itemCode || item.id,
    configValue: item.valueLabel || item.valueCode || item.id,
    vehicleConfigItem: item.specificationModel || item.id,
    vehicleConfigValue: item.configValueLabel || item.configItemLabel || item.id,
    user: item.username || item.id
  }[kind];
  return display(value || item.id || "-");
}

function findModificationOrder(id) {
  const numericId = Number(id || 0);
  const candidates = [
    ...(state.data.modificationOrders || []),
    ...(state.vehicleDetail?.workOrders || []),
    ...(state.vehicleDetail?.selectedDetail?.workOrders || [])
  ];
  return candidates.find(item => Number(item.id) === numericId) || {};
}

function modalTitle(kind, item) {
  if (kind === "attachmentUpload") {
    return item?.resourceType ? `附件上传：${attachmentResourceTypeLabel(item.resourceType)}` : "附件上传";
  }
  if (kind === "vehicleConfigChange") {
    return `车辆配置变更：${vehicleConfigChangeModeLabel(vehicleConfigChangeMode(item))}`;
  }
  if (kind === "paymentRecord") {
    return `${item?.direction === "PAYMENT" ? "登记付款" : "登记收款"}：${item?.sourceLabel || "业务单据"}`;
  }
  if (kind === "paymentReversal") {
    return `冲销收付款：${item?.sourceLabel || "业务单据"}`;
  }
  if (kind === "removedPartValuation") {
    return `确认旧件估值：${item?.partLabel || "拆下件"}`;
  }
  const names = {
    vehicle: "整车档案",
    vehicleModel: "车型",
    vehicleInbound: "车型入库",
    vehicleOutbound: "销售跟进",
    part: "配件",
    customer: "客户",
    rental: "租赁记录",
    outboundOrder: "出库订单",
    supplier: "采购供应商",
    purchaseOrder: "入库订单",
    purchaseFreight: "修改运费",
    paymentRecord: "收付款记录",
    paymentReversal: "收付款冲销",
    removedPartValuation: "旧件估值",
    stocktaking: "库存盘点",
    warehouse: "仓库",
    stockTransfer: "库存调拨",
    dataRestore: "恢复数据备份",
    invoiceUpload: "上传发票",
    contractUpload: "上传合同",
    vehicleConfigChange: "车辆配置变更",
    repair: "维修记录",
    configItem: "配置项",
    configValue: "配置值",
    vehicleConfigItem: "整车配置项",
    vehicleConfigValue: "整车配置值",
    vehicleStock: item?.direction === "inbound" ? "整车入库调整" : "整车库存减少",
    partStock: item?.direction === "outbound"
      ? "配件销售出库"
      : item?.direction === "adjustOutbound" ? "配件库存减少" : "配件入库调整",
    partReplace: "配件替换",
    vehiclePartInstall: "新增装车配件",
    modificationOrder: "改装工单",
    user: "用户",
    userUsername: "修改用户名",
    userPassword: "修改密码",
    userJobTag: "设置用户职务",
    switchUser: "切换用户"
  };
  if (kind === "switchUser" || kind === "vehicleStock" || kind === "vehicleInbound" || kind === "vehicleOutbound" || kind === "rental" || kind === "partStock" || kind === "partReplace" || kind === "vehiclePartInstall" || kind === "modificationOrder" || kind === "vehicleConfigChange" || kind === "stockTransfer" || kind === "dataRestore" || kind === "userUsername" || kind === "userPassword" || kind === "userJobTag" || kind === "invoiceUpload" || kind === "contractUpload" || kind === "paymentRecord" || kind === "paymentReversal" || kind === "removedPartValuation") {
    return (kind === "invoiceUpload" || kind === "contractUpload") && item?.orderNo ? `${names[kind]}：${item.orderNo}` : names[kind];
  }
  if (kind === "configValue" || kind === "user" || kind === "vehicleModel") return `新增${names[kind]}`;
  return item?.id ? `编辑${names[kind]}` : `新增${names[kind]}`;
}

function modalSubtitle(kind) {
  if (kind === "attachmentUpload") {
    return "选择业务对象后可一次上传多份图片、文档或其他附件。";
  }
  if (kind === "vehicleConfigChange") {
    return "先选模式，再按需要填写装车、替换或改装工单所需字段。";
  }
  const subtitles = {
    vehicle: "按进出库明细表直接录入单台整机的入库、库存、去向和后续跟进字段。",
    vehicleModel: "只维护车型、型号和动力信息；具体配置在入库时选择。",
    vehicleInbound: "结构化配置现在改为选填；可先录文本配置，后续再补配置字典也可以。",
    vehicleOutbound: "可直接选择现有客户，也可以在当前弹窗里按销售表字段临时录入并新建客户。",
    part: "维护配件编码、库存和价格。",
    customer: "维护出库时可直接下拉选择的客户公司信息。",
    rental: "选择具体在库车号，记录租赁去向、租赁价格和归还状态。",
    outboundOrder: "维护车款结清、报销售、发票申请和订单备注。",
    supplier: "维护采购供应商、联系人、税号、账号和备注。",
    purchaseOrder: "配件入库可从配置字典带入名称、编码与规格；整车入库会创建库存车辆并按规格型号带入结构化配置。",
    purchaseFreight: "运费默认为 0；只在实际产生运费时修改。",
    paymentRecord: "本次金额会生成不可变收付款流水，并同步实际现金收支；如录入错误请使用冲销。",
    paymentReversal: "冲销不会删除原记录，而是生成等额反向流水以保留完整审计链。",
    removedPartValuation: "确认后会更新旧件 FIFO 单价、解除隔离锁定，并把新增库存价值计入库存收益。",
    stocktaking: "先创建盘点草稿；确认入账后才会同步库存数量。",
    warehouse: "维护仓库编码、名称、类型和默认仓设置。",
    stockTransfer: "选择整车或配件，从一个仓库调拨到另一个仓库并生成库存流水。",
    dataRestore: "上传 JSON 备份文件并输入确认码后恢复数据库业务数据。",
    invoiceUpload: "已申请发票或已开票的订单可上传；最新文件会回写为当前发票。",
    contractUpload: "标记为有合同的订单可上传；最新文件会回写为当前合同。",
    vehicleConfigChange: "新增装车、替换配件和创建改装工单现在都从这里进入。",
    repair: "记录维修过程、费用与处理状态。",
    configItem: "定义可维护的车辆配置项。",
    configValue: "为当前配置项添加可选值。",
    vehicleConfigItem: "维护整车规格型号，用于整车采购和车型入库时拉取默认配置。",
    vehicleConfigValue: "从配件配置项和值中选择车辆各部分默认配置。",
    vehicleStock: "仅用于盘盈、盘亏或历史纠偏；正常销售请使用整车销售出库。",
    partStock: "销售出库会生成客户订单；入库调整和库存减少仅用于盘盈、盘亏或历史纠偏。",
    partReplace: "选择车辆上的旧配件，并用同类型库存配件替换；拆下件会自动入库。",
    vehiclePartInstall: "从配件仓库领料装到当前整车，只需选择分类、库存配件和数量。",
    modificationOrder: "只填写这次客户要求替换的配置；完成工单时才会生成库存流水并更新车辆配置。",
    user: "由超级管理员创建管理员或普通用户。",
    userUsername: "仅超级管理员可修改管理员或普通用户的登录名。",
    userPassword: "仅超级管理员可重置管理员或普通用户的登录密码。",
    userJobTag: "选择管理、文员或维修；职务会影响维修人员选择和业务分配。",
    switchUser: "输入另一个账号后立即进入对应权限。"
  };
  return subtitles[kind] || "";
}

function detailItem(label, value) {
  return `
    <div class="detail-item">
      <div class="label">${escapeHtml(label)}</div>
      <div class="value">${escapeHtml(display(value))}</div>
    </div>
  `;
}

function icon(name) {
  return `<span class="btn-icon" aria-hidden="true">${icons[name] || ""}</span>`;
}

function renderLoading() {
  return `<div class="surface"><div class="surface-body"><div class="loading-state"><span class="loading-spinner" aria-hidden="true"></span><span>正在加载数据...</span></div></div></div>`;
}

function renderLoadError(error) {
  return `
    <div class="surface">
      <div class="surface-body">
        <div class="empty-state empty-state-error">
          <span class="empty-state-visual" aria-hidden="true">${icons.warning}</span>
          <strong>数据加载失败</strong>
          <span>${escapeHtml(error?.message || "请刷新后重试")}</span>
          <button class="btn btn-primary" type="button" data-action="refresh">${icon("refresh")}重新加载</button>
        </div>
      </div>
    </div>
  `;
}

function showToast(message, type = "info") {
  const toast = document.createElement("div");
  toast.className = `toast ${type}`;
  toast.textContent = message;
  els.toastHost.appendChild(toast);
  window.setTimeout(() => toast.remove(), 3600);
}

function showContextSuccess(message, target = "", detail = "") {
  const parts = [message, target && `对象：${target}`, detail].filter(Boolean);
  showToast(parts.join(" · "), "success");
}

function modalDangerConfirmation(kind, item = {}, payload = {}) {
  if (kind === "vehicleOutbound") {
    return {
      title: "确认整车出库",
      target: entityDisplayName("vehicle", findEntity("vehicle", Number(payload.machineId || 0))),
      impact: "将创建整车出库订单并进入收款、报销售和发票跟进流程。"
    };
  }
  if (kind === "vehicleStock") {
    const outbound = item.direction !== "inbound";
    return {
      title: outbound ? "确认整车出库调整" : "确认整车入库调整",
      target: entityDisplayName("vehicle", findEntity("vehicle", Number(payload.machineId || 0))),
      impact: `库存数量将${outbound ? "减少" : "增加"} ${payload.quantity || 0}，请确认车号和数量无误。`
    };
  }
  if (kind === "partStock") {
    const saleOutbound = item.direction === "outbound";
    const adjustmentOutbound = item.direction === "adjustOutbound";
    return {
      title: saleOutbound ? "确认配件销售出库" : adjustmentOutbound ? "确认配件库存减少" : "确认配件入库调整",
      target: payload.partCode || entityDisplayName("part", item),
      impact: saleOutbound
        ? "将创建配件出库订单、确认应收并扣减 FIFO 库存。"
        : `配件库存将${adjustmentOutbound ? "减少" : "增加"} ${payload.quantity || 0}，并按库存调整计入损益。`
    };
  }
  if (kind === "partReplace") {
    return {
      title: "确认配件替换",
      target: entityDisplayName("vehicle", findEntity("vehicle", Number(payload.machineId || 0))),
      impact: "将扣减新配件库存，旧件会按规则处理，并更新车辆配置记录。"
    };
  }
  if (kind === "vehiclePartInstall") {
    return {
      title: "确认配件装车",
      target: entityDisplayName("vehicle", findEntity("vehicle", Number(payload.machineId || 0))),
      impact: "将从配件库存领料并关联到当前车辆。"
    };
  }
  if (kind === "outboundOrder") {
    return {
      title: "确认更新出库订单",
      target: entityDisplayName("outboundOrder", item),
      impact: "会更新收款、报销售、发票或合同等关键跟进字段。"
    };
  }
  if (kind === "purchaseFreight") {
    return {
      title: "确认修改采购运费",
      target: entityDisplayName("purchaseOrder", item),
      impact: "运费会影响采购成本统计，请确认金额无误。"
    };
  }
  if (kind === "paymentRecord") {
    return {
      title: item.direction === "PAYMENT" ? "确认登记付款" : "确认登记收款",
      target: item.sourceLabel || "业务单据",
      impact: "将生成现金流水并参与收支统计；保存后不能直接修改，只能冲销。"
    };
  }
  if (kind === "paymentReversal") {
    return {
      title: "确认冲销收付款",
      target: item.sourceLabel || "业务单据",
      impact: "将生成等额反向流水，原记录和审计历史都会保留。"
    };
  }
  if (kind === "removedPartValuation") {
    return {
      title: "确认旧件估值",
      target: item.partLabel || "拆下件",
      impact: "将更新 FIFO 库存价值、解除隔离锁定并生成库存收益记录；确认后不可直接改价。"
    };
  }
  if (kind === "userUsername") {
    return {
      title: "确认修改用户名",
      target: entityDisplayName("user", item),
      impact: "用户名修改后，用户需要使用新用户名登录。"
    };
  }
  if (kind === "userPassword") {
    return {
      title: "确认修改用户密码",
      target: entityDisplayName("user", item),
      impact: "密码修改后，旧密码将无法继续登录。"
    };
  }
  if (kind === "invoiceUpload") {
    return {
      title: "确认上传发票",
      target: entityDisplayName("outboundOrder", item),
      impact: item.invoiceFileAvailable ? "本次上传会替换已有发票文件。" : "发票文件会绑定到当前出库订单。"
    };
  }
  if (kind === "contractUpload") {
    return {
      title: "确认上传合同",
      target: entityDisplayName("outboundOrder", item),
      impact: item.contractFileAvailable ? "本次上传会替换已有合同文件。" : "合同文件会绑定到当前出库订单。"
    };
  }
  return null;
}

function handleActionError(error) {
  if (isAuthExpiredError(error)) {
    logout("登录已过期，请重新登录");
    return;
  }
  if (isConflictError(error)) {
    showToast(error.message || "数据已被其他用户更新，请刷新后重试", "error");
    void refreshAfterConflict();
    return;
  }
  if (state.modal && els?.modalCard?.querySelector("form")) {
    showModalValidationSummary(els.modalCard.querySelector("form"), error.message || "保存失败，请检查表单后重试");
  }
  showToast(error.message || "操作失败", "error");
}

async function refreshAfterConflict() {
  if (state.modal) {
    closeModal();
  }
  try {
    await loadAllData();
    renderCurrentTab();
    showToast("已刷新最新数据，请重新打开编辑界面后再保存", "info");
  } catch (error) {
    if (isAuthExpiredError(error)) {
      logout("登录已过期，请重新登录");
    }
  }
}

function isTypingTarget(target) {
  return Boolean(target?.closest?.("input, select, textarea, [contenteditable='true']"));
}

function isAuthExpiredError(error) {
  return Boolean(error?.authExpired);
}

function isConflictError(error) {
  return Number(error?.status) === 409 || Number(error?.code) === 409;
}
