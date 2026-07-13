export function createConfirmDialog({
  overlayManager,
  escapeHtml,
  icon
}) {
  let dialogSequence = 0;

  function confirmDanger({
    title,
    target,
    impact,
    confirmText = "确认继续",
    cancelText = "取消",
    tone = "danger"
  } = {}) {
    if (typeof document === "undefined" || !document.body) {
      return Promise.resolve(window.confirm([
        title || "确认操作",
        target ? `对象：${target}` : "",
        impact ? `影响：${impact}` : "",
        "",
        `${confirmText}？`
      ].filter(line => line !== "").join("\n")));
    }

    return new Promise(resolve => {
      const titleId = `confirmDialogTitle${++dialogSequence}`;
      let settled = false;
      let pointerDownStartedOnOverlay = false;
      const overlay = document.createElement("div");
      overlay.className = "confirm-overlay";
      overlay.innerHTML = `
        <section class="confirm-dialog" role="dialog" aria-modal="true" aria-labelledby="${titleId}">
          <div class="confirm-icon ${escapeHtml(tone)}">${icon(tone === "danger" ? "trash" : "swap")}</div>
          <div class="confirm-content">
            <h2 id="${titleId}">${escapeHtml(title || "确认操作")}</h2>
            ${target ? `<p class="confirm-target">对象：${escapeHtml(target)}</p>` : ""}
            ${impact ? `<p class="confirm-impact">${escapeHtml(impact)}</p>` : ""}
          </div>
          <div class="confirm-actions">
            <button class="btn btn-ghost" type="button" data-confirm-cancel>${escapeHtml(cancelText)}</button>
            <button class="btn ${tone === "danger" ? "btn-danger" : "btn-primary"}" type="button" data-confirm-ok>${icon(tone === "danger" ? "trash" : "swap")}${escapeHtml(confirmText)}</button>
          </div>
        </section>
      `;
      document.body.appendChild(overlay);

      const okButton = overlay.querySelector("[data-confirm-ok]");
      const cancelButton = overlay.querySelector("[data-confirm-cancel]");
      const finish = confirmed => {
        if (settled) return;
        settled = true;
        overlay.classList.remove("is-open");
        overlayManager.close(overlay);
        window.setTimeout(() => overlay.remove(), 180);
        resolve(confirmed);
      };

      okButton?.addEventListener("click", () => finish(true), { once: true });
      cancelButton?.addEventListener("click", () => finish(false), { once: true });
      overlay.addEventListener("pointerdown", event => {
        pointerDownStartedOnOverlay = event.target === overlay;
      });
      overlay.addEventListener("click", event => {
        if (event.target === overlay && pointerDownStartedOnOverlay) finish(false);
        pointerDownStartedOnOverlay = false;
      });

      overlayManager.open(overlay, {
        initialFocus: cancelButton,
        onEscape: () => finish(false)
      });
      window.requestAnimationFrame(() => overlay.classList.add("is-open"));
    });
  }

  return { confirmDanger };
}
