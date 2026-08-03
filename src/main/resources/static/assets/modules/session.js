import { createRequestId } from "./request-id.js";

const TOKEN_KEY = "forklift_erp_token";
const USER_KEY = "forklift_erp_user";
const API_CACHE_PREFIX = "forklift_erp_api_cache:";
const API_CACHE_MAX_AGE = 5 * 60 * 1000;
const PAGE_CACHE_MAX_AGE = 45 * 1000;

export function readStoredToken() {
  return localStorage.getItem(TOKEN_KEY) || "";
}

export function readStoredUser() {
  try {
    return JSON.parse(localStorage.getItem(USER_KEY) || "null");
  } catch {
    return null;
  }
}

export function saveSession(token, user) {
  localStorage.setItem(TOKEN_KEY, token || "");
  localStorage.setItem(USER_KEY, JSON.stringify(user || null));
  clearApiCache();
}

export function clearSession() {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(USER_KEY);
  clearApiCache();
}

export function createApiClient(getToken) {
  return async function api(path, options = {}) {
    const method = (options.method || "GET").toUpperCase();
    const isFormData = options.body instanceof FormData;
    const headers = { ...(options.headers || {}) };
    if (!isFormData && !headers["Content-Type"]) {
      headers["Content-Type"] = "application/json";
    }
    const requestId = options.requestId || createRequestId(`api-${method.toLowerCase()}`);
    headers["X-Request-ID"] = requestId;
    const token = getToken();
    if (options.auth !== false && token) {
      headers.Authorization = `Bearer ${token}`;
    }
    const cacheable = method === "GET" && options.cache !== false && isCacheableGet(path);
    const cached = cacheable ? readApiCache(path) : null;
    if (cached?.etag) {
      headers["If-None-Match"] = cached.etag;
    }

    const response = await fetch(path, {
      method,
      headers,
      body: options.body === undefined ? undefined : (isFormData ? options.body : JSON.stringify(options.body))
    });

    if (response.status === 304 && cached) {
      return cached.payload;
    }
    const payload = await response.json().catch(() => null);
    if (response.status === 401 || payload?.code === 401) {
      const error = new Error("未登录或登录已过期");
      error.authExpired = true;
      error.status = response.status;
      error.code = payload?.code;
      error.requestId = payload?.requestId || response.headers.get("X-Request-ID") || requestId;
      throw error;
    }
    if (!response.ok) {
      const error = new Error(payload?.message || `请求失败：${response.status}`);
      error.status = response.status;
      error.code = payload?.code;
      error.requestId = payload?.requestId || response.headers.get("X-Request-ID") || requestId;
      throw error;
    }
    if (payload && Object.prototype.hasOwnProperty.call(payload, "code")) {
      if (payload.code !== 200) {
        const error = new Error(payload.message || "请求失败");
        error.status = response.status;
        error.code = payload.code;
        error.requestId = payload.requestId || response.headers.get("X-Request-ID") || requestId;
        throw error;
      }
      if (cacheable) {
        writeApiCache(path, payload.data, response.headers.get("ETag"));
      } else if (method !== "GET") {
        invalidateApiCache(path);
      }
      return payload.data;
    }
    if (cacheable) {
      writeApiCache(path, payload, response.headers.get("ETag"));
    } else if (method !== "GET") {
      invalidateApiCache(path);
    }
    return payload;
  };
}

function isCacheableGet(path) {
  const value = String(path || "");
  if (!value.startsWith("/api/")) return false;
  if (value.includes("paged=true")) return true;
  return [
    "/api/config/",
    "/api/auth/users",
    "/api/warehouses",
    "/api/suppliers",
    "/api/customers"
  ].some(prefix => value.startsWith(prefix));
}

function readApiCache(path) {
  try {
    const key = cacheKey(path);
    const entry = JSON.parse(sessionStorage.getItem(key) || "null");
    if (!entry) return null;
    const maxAge = String(path).includes("paged=true") ? PAGE_CACHE_MAX_AGE : API_CACHE_MAX_AGE;
    if (Date.now() - Number(entry.savedAt || 0) > maxAge) {
      sessionStorage.removeItem(key);
      return null;
    }
    return entry;
  } catch {
    return null;
  }
}

function writeApiCache(path, payload, etag) {
  try {
    sessionStorage.setItem(cacheKey(path), JSON.stringify({
      etag: etag || "",
      payload,
      savedAt: Date.now()
    }));
  } catch {
    clearApiCache();
  }
}

function invalidateApiCache(path) {
  const groups = mutationGroups(String(path || ""));
  for (const key of apiCacheKeys()) {
    const cachedPath = key.slice(API_CACHE_PREFIX.length);
    if (groups.some(group => cachedPath.includes(group))) {
      sessionStorage.removeItem(key);
    }
  }
}

function clearApiCache() {
  for (const key of apiCacheKeys()) {
    sessionStorage.removeItem(key);
  }
}

function apiCacheKeys() {
  try {
    return Array.from({ length: sessionStorage.length }, (_, index) => sessionStorage.key(index))
      .filter(key => key?.startsWith(API_CACHE_PREFIX));
  } catch {
    return [];
  }
}

function cacheKey(path) {
  return `${API_CACHE_PREFIX}${path}`;
}

function mutationGroups(path) {
  const groups = ["/api/logs", "/api/todos", "/api/statistics"];
  const rules = [
    ["/api/inventory", ["/api/inventory", "type=vehicles", "/api/warehouses"]],
    ["/api/parts", ["/api/parts", "type=parts", "/api/warehouses"]],
    ["/api/repairs", ["/api/repairs", "type=repairs"]],
    ["/api/config", ["/api/config/"]],
    ["/api/rentals", ["/api/rentals", "type=rentals", "/api/inventory"]],
    ["/api/customers", ["/api/customers", "/api/rentals", "/api/outbound-orders", "type=rentals", "type=outboundOrders"]],
    ["/api/suppliers", ["/api/suppliers", "/api/purchase-orders", "type=suppliers", "type=purchases"]],
    ["/api/purchase-orders", ["/api/purchase-orders", "type=purchases", "type=suppliers"]],
    ["/api/stocktaking-records", ["/api/stocktaking-records", "type=stocktakes"]],
    ["/api/warehouses", ["/api/warehouses", "/api/inventory", "/api/parts"]],
    ["/api/attachments", ["/api/attachments", "/api/outbound-orders", "type=outboundOrders"]],
    ["/api/imports", ["/api/imports", "/api/inventory", "/api/parts", "/api/customers", "/api/outbound-orders", "type=vehicles", "type=parts", "type=outboundOrders"]],
    ["/api/auth/users", ["/api/auth/users"]]
  ];
  for (const [prefix, affected] of rules) {
    if (path.startsWith(prefix)) {
      groups.push(...affected);
    }
  }
  return [...new Set(groups)];
}
