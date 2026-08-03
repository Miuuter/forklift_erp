const CACHE_NAME = "forklift-erp-client-20260802-maintenance-v3";
const SHELL_ASSETS = [
  "/",
  "/index.html",
  "/assets/app.css",
  "/assets/app.js",
  "/assets/modules/app-state.js",
  "/assets/modules/data-loader.js",
  "/assets/modules/dashboard-view.js",
  "/assets/modules/detail-drawer.js",
  "/assets/modules/display-utils.js",
  "/assets/modules/downloads.js",
  "/assets/modules/field-config.js",
  "/assets/modules/list-config.js",
  "/assets/modules/command-palette.js",
  "/assets/modules/modal-renderer.js",
  "/assets/modules/mutation-refresh.js",
  "/assets/modules/paging.js",
  "/assets/modules/routes.js",
  "/assets/modules/session.js",
  "/assets/modules/status-ui.js",
  "/assets/modules/ui-config.js",
  "/assets/modules/ui/confirm-dialog.js",
  "/assets/modules/ui/contracts.js",
  "/assets/modules/ui/action-registry.js",
  "/assets/modules/ui/data-table.js",
  "/assets/modules/ui/drawer-actions.js",
  "/assets/modules/ui/form-workspace.js",
  "/assets/modules/ui/overlay-manager.js",
  "/assets/modules/ui/shell-decorator.js",
  "/assets/modules/ui/state-utils.js",
  "/assets/modules/ui/summary-card.js",
  "/assets/modules/workflows/attachments-workflow.js",
  "/assets/modules/workflows/configs-workflow.js",
  "/assets/modules/workflows/imports-workflow.js",
  "/assets/modules/workflows/operations-workflow.js",
  "/assets/modules/workflows/outbound-workflow.js",
  "/assets/modules/workflows/parts-workflow.js",
  "/assets/modules/workflows/rentals-workflow.js",
  "/assets/modules/workflows/repairs-workflow.js",
  "/assets/modules/workflows/statistics-workflow.js",
  "/assets/modules/workflows/users-workflow.js",
  "/assets/modules/workflows/vehicle-workflow.js",
  "/assets/styles/base.css",
  "/assets/styles/tokens.css",
  "/assets/styles/surfaces.css",
  "/assets/styles/tables.css",
  "/assets/styles/overlays.css",
  "/assets/styles/industrial-ui.css",
  "/assets/styles/responsive.css",
  "/assets/styles/polish.css",
  "/assets/styles/polish-responsive.css",
  "/assets/icon.svg",
  "/manifest.webmanifest"
];

self.addEventListener("install", event => {
  event.waitUntil(caches.open(CACHE_NAME).then(cache => cache.addAll(SHELL_ASSETS)));
  self.skipWaiting();
});

self.addEventListener("activate", event => {
  event.waitUntil(
    caches.keys().then(keys => Promise.all(
      keys.filter(key => key !== CACHE_NAME).map(key => caches.delete(key))
    ))
  );
  self.clients.claim();
});

self.addEventListener("fetch", event => {
  const url = new URL(event.request.url);
  if (url.pathname.startsWith("/api/") || event.request.method !== "GET") return;

  event.respondWith(
    fetch(event.request)
      .then(response => {
        const copy = response.clone();
        caches.open(CACHE_NAME).then(cache => cache.put(event.request, copy));
        return response;
      })
      .catch(() => caches.match(event.request))
  );
});
