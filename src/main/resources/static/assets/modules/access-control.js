export function normalizeUser(data) {
  if (!data) return null;
  const roles = data.roles || [];
  return {
    username: data.username,
    jobTag: data.jobTag,
    roles,
    permissions: data.permissions || defaultPermissionsForRoles(roles)
  };
}


export function defaultPermissionsForRoles(roles = []) {
  const permissionMap = {
    SUPER_ADMIN: [
      "vehicle:write",
      "part:write",
      "repair:write",
      "config:write",
      "replace:write",
      "stock:adjust",
      "log:read",
      "user:read",
      "user:write",
      "user:admin"
    ],
    ADMIN: [
      "vehicle:write",
      "part:write",
      "repair:write",
      "config:write",
      "replace:write",
      "stock:adjust",
      "log:read"
    ],
    USER: [
      "vehicle:write",
      "part:write",
      "repair:write",
      "config:write",
      "replace:write",
      "stock:adjust"
    ]
  };
  return [...new Set(roles.flatMap(role => permissionMap[role] || []))];
}


export function createAccessControl({ state }) {
function hasRole(role) {
  return Boolean(state.user?.roles?.includes(role));
}

function hasAnyRole(...roles) {
  return roles.some(role => hasRole(role));
}


function hasPermission(permission) {
  return hasRole("SUPER_ADMIN") || Boolean(state.user?.permissions?.includes(permission));
}

function hasAnyPermission(...permissions) {
  return permissions.some(permission => hasPermission(permission));
}


function canAccessTab(tab) {
  if (tab === "stockMovements") return false;
  const rolesByTab = {
    imports: ["SUPER_ADMIN"],
    maintenance: ["SUPER_ADMIN"],
    users: ["SUPER_ADMIN"]
  };
  const permissionsByTab = {
    modifications: "replace:write",
    outboundOrders: "stock:adjust",
    rentals: "stock:adjust",
    customers: "vehicle:write",
    suppliers: "stock:adjust",
    purchases: "stock:adjust",
    stocktakes: "stock:adjust",
    warehouses: "stock:adjust",
    stats: "log:read",
    logs: "log:read",
    imports: "stock:adjust",
    users: "user:read",
  };
  if (rolesByTab[tab] && !hasAnyRole(...rolesByTab[tab])) return false;
  return !permissionsByTab[tab] || hasPermission(permissionsByTab[tab]);
}

function canWriteEntity(kind) {
  const permissionsByKind = {
    vehicle: "vehicle:write",
    part: "part:write",
    repair: "repair:write",
    rental: "stock:adjust",
    supplier: "stock:adjust",
    warehouse: "stock:adjust",
    purchaseOrder: "stock:adjust",
    stocktaking: "stock:adjust",
    configItem: "config:write",
    configValue: "config:write",
    vehicleConfigItem: "config:write",
    vehicleConfigValue: "config:write",
    customer: "vehicle:write"
  };
  return !permissionsByKind[kind] || hasPermission(permissionsByKind[kind]);
}


  return {
    hasRole, hasAnyRole, hasPermission, hasAnyPermission, canAccessTab, canWriteEntity
  };
}
