export function createModalRenderer({
  state,
  getEls,
  overlayManager,
  resetModalPointerDown,
  modalTitle,
  modalSubtitle,
  getFields,
  renderModalFields,
  usesVehicleInboundConfigEditor,
  renderConfigSelectionEditor,
  canSaveAndContinue,
  formWorkspaceConfig,
  confirmDiscard,
  icon,
  escapeAttr,
  escapeHtml
}) {
  function closeModal() {
    const els = getEls();
    state.modal = null;
    resetModalPointerDown();
    els.modalOverlay.classList.add("is-hidden");
    els.modalOverlay.setAttribute("aria-hidden", "true");
    overlayManager?.close(els.modalOverlay);
    els.modalCard.innerHTML = "";
  }

  async function requestCloseModal() {
    const els = getEls();
    const form = els?.modalCard?.querySelector("form");
    if (isFormDirty(form, state.modal?.initialSnapshot) && confirmDiscard) {
      const confirmed = await confirmDiscard();
      if (!confirmed) return false;
    }
    closeModal();
    return true;
  }

  function renderModal() {
    const els = getEls();
    if (!state.modal) return;
    const { kind, item } = state.modal;
    const title = modalTitle(kind, item);
    const modalFields = getFields(kind, item);
    const workspace = formWorkspaceConfig(kind, modalFields);
    const titleId = `modalTitle-${escapeAttr(kind)}`;
    els.modalCard.innerHTML = `
      <div class="modal-head">
        <div>
          <div class="modal-kicker">${item?.id ? "编辑业务数据" : "新建业务数据"}</div>
          <h2 class="surface-title" id="${titleId}">${escapeHtml(title)}</h2>
          <div class="helper">${escapeHtml(modalSubtitle(kind))}</div>
        </div>
        <button class="btn btn-icon-only btn-ghost" type="button" data-close-modal aria-label="关闭表单">${icon("close")}</button>
      </div>
      <form data-kind="${escapeAttr(kind)}" data-form-workspace="${workspace.workspace ? "true" : "false"}">
        <div class="modal-body">
          <div class="form-validation-summary is-hidden" data-validation-summary role="alert"></div>
          <div class="${workspace.workspace ? "form-workspace" : "modal-grid"}">
            ${workspace.workspace ? `
              <nav class="form-anchor-nav" aria-label="表单分区">
                <div class="form-anchor-title">填写进度</div>
                ${workspace.sections.map((section, index) => `
                  <button class="form-anchor${index === 0 ? " is-active" : ""}" type="button" data-action="scroll-form-section" data-section-id="${escapeAttr(section.key)}">
                    <span>${escapeHtml(String(index + 1).padStart(2, "0"))}</span>
                    ${escapeHtml(section.title)}
                  </button>
                `).join("")}
              </nav>
              <div class="form-workspace-content">
                ${renderModalFields(modalFields, item, { sections: workspace.sections })}
              </div>
            ` : renderModalFields(modalFields, item)}
          </div>
          ${usesVehicleInboundConfigEditor(kind, item) ? renderConfigSelectionEditor(item) : ""}
        </div>
        <div class="modal-actions">
          <button class="btn btn-ghost" type="button" data-close-modal>取消</button>
          ${canSaveAndContinue(kind, item) ? `<button class="btn" type="submit" data-submit-mode="continue">保存并继续</button>` : ""}
          <button class="btn btn-primary" type="submit">保存</button>
        </div>
      </form>
    `;
    state.modal.initialSnapshot ||= serializeFormSnapshot(els.modalCard.querySelector("form"));
    els.modalCard.classList.toggle("is-form-workspace", workspace.workspace);
    els.modalCard.setAttribute("role", "dialog");
    els.modalCard.setAttribute("aria-modal", "true");
    els.modalCard.setAttribute("aria-labelledby", titleId);
    els.modalOverlay.classList.remove("is-hidden");
    els.modalOverlay.setAttribute("aria-hidden", "false");
    overlayManager?.open(els.modalOverlay, {
      initialFocus: () => els.modalCard.querySelector("input:not([type='hidden']):not([readonly]), textarea:not([readonly]), select, button"),
      onEscape: () => {
        void requestCloseModal();
      }
    });
  }

  return {
    closeModal,
    requestCloseModal,
    renderModal
  };
}

function serializeFormSnapshot(form) {
  if (!form) return "";
  return [...form.elements]
    .filter(control => control.name)
    .map(control => {
      if (control.type === "file") {
        return `${control.name}:${[...(control.files || [])].map(file => file.name).join(",")}`;
      }
      return `${control.name}:${control.type === "checkbox" ? control.checked : control.value}`;
    })
    .join("|");
}

function isFormDirty(form, snapshot) {
  if (!form) return false;
  return snapshot !== undefined && snapshot !== serializeFormSnapshot(form);
}

export function setModalSubmitting(form, submitting) {
  if (!form) return;
  if (submitting) {
    form.dataset.submitting = "true";
    form.setAttribute("aria-busy", "true");
    const card = form.closest(".modal-card");
    const controls = [...(card || form).querySelectorAll("button, input, select, textarea")];
    form.__submitControls = controls;
    controls.forEach(control => {
      control.__disabledBeforeSubmit = control.disabled;
      control.disabled = true;
      if (control.matches('button[type="submit"]')) {
        control.__contentBeforeSubmit = control.innerHTML;
        control.textContent = "提交中...";
      }
    });
    return;
  }

  form.dataset.submitting = "false";
  form.removeAttribute("aria-busy");
  (form.__submitControls || []).forEach(control => {
    control.disabled = Boolean(control.__disabledBeforeSubmit);
    if (control.__contentBeforeSubmit !== undefined) {
      control.innerHTML = control.__contentBeforeSubmit;
      delete control.__contentBeforeSubmit;
    }
    delete control.__disabledBeforeSubmit;
  });
  delete form.__submitControls;
}
