export const VEHICLE_DETAIL_TABS = Object.freeze([
  { key: "archive", label: "档案" },
  { key: "config", label: "配置" },
  { key: "outbound", label: "出库" },
  { key: "rental", label: "租赁" },
  { key: "repair", label: "维修" },
  { key: "modification", label: "改装" },
  { key: "attachments", label: "附件" }
]);

export function ensureVehicleDetailTab(state) {
  const keys = VEHICLE_DETAIL_TABS.map(item => item.key);
  if (!keys.includes(state.vehicleDetailTab)) {
    state.vehicleDetailTab = "archive";
  }
  return state.vehicleDetailTab;
}
