export function createDetailDrawer({
  state,
  getEls,
  overlayManager,
  transitionMs,
  findEntity,
  renderCurrentTab,
  detailTitle,
  entityLabel,
  detailFields,
  renderDetailGrid,
  renderDetailDrawerActions,
  icon,
  escapeHtml
}) {
  let closeTimer = null;

  function openDetailDrawer(kind, id) {
    const item = findEntity(kind, Number(id || 0));
    if (!item?.id) return;
    state.detailDrawer = { kind, id: Number(id) };
    renderCurrentTab();
  }

  function closeDetailDrawer() {
    const els = getEls();
    state.detailDrawer = null;
    if (!els?.detailDrawerOverlay) return;
    if (closeTimer) {
      window.clearTimeout(closeTimer);
      closeTimer = null;
    }
    els.detailDrawerOverlay.classList.remove("is-open");
    els.detailDrawerOverlay.setAttribute("aria-hidden", "true");
    overlayManager?.close(els.detailDrawerOverlay);
    const hideDrawer = () => {
      els.detailDrawerOverlay.classList.add("is-hidden");
      els.detailDrawer.innerHTML = "";
      closeTimer = null;
    };
    if (els.detailDrawerOverlay.classList.contains("is-hidden")) {
      hideDrawer();
      return;
    }
    closeTimer = window.setTimeout(hideDrawer, transitionMs);
  }

  function renderDetailDrawer() {
    const els = getEls();
    if (!els?.detailDrawerOverlay) return;
    const drawer = state.detailDrawer;
    if (!drawer) {
      closeDetailDrawer();
      return;
    }
    const item = findEntity(drawer.kind, Number(drawer.id));
    if (!item?.id) {
      closeDetailDrawer();
      return;
    }
    if (closeTimer) {
      window.clearTimeout(closeTimer);
      closeTimer = null;
    }
    const title = detailTitle(drawer.kind, item);
    const titleId = `detailDrawerTitle-${escapeHtml(drawer.kind)}-${escapeHtml(item.id)}`;
    els.detailDrawer.innerHTML = `
      <div class="detail-drawer-head">
        <div>
          <div class="detail-drawer-kicker">${escapeHtml(entityLabel(drawer.kind))}</div>
          <h2 id="${titleId}">${escapeHtml(title)}</h2>
        </div>
        <button class="btn btn-icon-only btn-ghost" type="button" data-action="close-detail" aria-label="关闭详情">${icon("close")}</button>
      </div>
      <div class="detail-drawer-body">
        <section class="detail-drawer-section">
          <div class="detail-drawer-section-title">业务摘要</div>
          ${renderDetailGrid(detailFields(drawer.kind, item))}
        </section>
      </div>
      ${renderDetailDrawerActions(drawer.kind, item)}
    `;
    els.detailDrawer.setAttribute("role", "dialog");
    els.detailDrawer.setAttribute("aria-modal", "true");
    els.detailDrawer.setAttribute("aria-labelledby", titleId);
    els.detailDrawerOverlay.classList.remove("is-hidden");
    els.detailDrawerOverlay.setAttribute("aria-hidden", "false");
    overlayManager?.open(els.detailDrawerOverlay, {
      initialFocus: () => els.detailDrawer.querySelector("[data-action='close-detail']"),
      onEscape: closeDetailDrawer
    });
    requestAnimationFrame(() => {
      els.detailDrawerOverlay.classList.add("is-open");
    });
  }

  return {
    openDetailDrawer,
    closeDetailDrawer,
    renderDetailDrawer
  };
}
