export function createCommandPalette({
  state,
  getEls,
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
  scrollToVehicleDetail,
  repairStatusText,
  handleActionError
}) {
  let commandPalette = null;
  let returnFocus = null;

  function openCommandPalette() {
    if (commandPalette?.overlay?.isConnected) {
      commandPalette.input?.focus();
      commandPalette.input?.select();
      return;
    }
    const overlay = document.createElement("div");
    returnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    overlay.className = "command-overlay";
    overlay.innerHTML = `
      <section class="command-palette" role="dialog" aria-modal="true" aria-label="全局操作">
        <div class="command-search-row">
          <span class="search-icon">${icons.search}</span>
          <input class="command-input" type="search" placeholder="搜索车号、客户、配件、订单或输入操作" autocomplete="off" spellcheck="false">
        </div>
        <div class="command-results" role="listbox"></div>
      </section>
    `;
    document.body.appendChild(overlay);
    commandPalette = {
      overlay,
      input: overlay.querySelector(".command-input"),
      results: overlay.querySelector(".command-results"),
      items: [],
      activeIndex: 0
    };
    commandPalette.input.addEventListener("input", () => renderCommandPalette(commandPalette.input.value));
    commandPalette.input.addEventListener("keydown", handleCommandPaletteKeydown);
    overlay.addEventListener("pointerdown", event => {
      if (event.target === overlay) closeCommandPalette();
    });
    overlay.addEventListener("click", event => {
      const item = event.target.closest("[data-command-index]");
      if (!item) return;
      void executeCommandItem(commandPalette.items[Number(item.dataset.commandIndex || 0)]);
    });
    renderCommandPalette("");
    setBackgroundInert(true);
    requestAnimationFrame(() => {
      overlay.classList.add("is-open");
      commandPalette.input.focus();
    });
  }

  function closeCommandPalette() {
    const overlay = commandPalette?.overlay;
    commandPalette = null;
    if (!overlay) return;
    setBackgroundInert(false);
    overlay.classList.remove("is-open");
    window.setTimeout(() => {
      overlay.remove();
      if (returnFocus?.isConnected) returnFocus.focus({ preventScroll: true });
      returnFocus = null;
    }, 160);
  }

  function setBackgroundInert(inert) {
    [getEls()?.appScreen, getEls()?.loginScreen].filter(Boolean).forEach(root => {
      if (inert) {
        root.dataset.commandInertBefore = root.inert ? "true" : "false";
        root.inert = true;
        return;
      }
      if (root.dataset.commandInertBefore === undefined) return;
      root.inert = root.dataset.commandInertBefore === "true";
      delete root.dataset.commandInertBefore;
    });
  }

  function handleCommandPaletteKeydown(event) {
    if (!commandPalette) return;
    if (event.key === "Escape") {
      event.preventDefault();
      closeCommandPalette();
      return;
    }
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      const delta = event.key === "ArrowDown" ? 1 : -1;
      const length = commandPalette.items.length || 1;
      commandPalette.activeIndex = (commandPalette.activeIndex + delta + length) % length;
      paintCommandActiveItem();
      return;
    }
    if (event.key === "Enter") {
      event.preventDefault();
      void executeCommandItem(commandPalette.items[commandPalette.activeIndex]);
    }
  }

  function renderCommandPalette(query = "") {
    if (!commandPalette) return;
    commandPalette.items = commandItems(query).slice(0, 12);
    commandPalette.activeIndex = Math.min(commandPalette.activeIndex, Math.max(0, commandPalette.items.length - 1));
    commandPalette.results.innerHTML = commandPalette.items.length
      ? commandPalette.items.map((item, index) => `
        <button class="command-item${index === commandPalette.activeIndex ? " is-active" : ""}" type="button" data-command-index="${escapeAttr(index)}" role="option" aria-selected="${index === commandPalette.activeIndex ? "true" : "false"}">
          <span class="command-item-main">
            <strong>${escapeHtml(item.title)}</strong>
            <span>${escapeHtml(item.detail || item.group || "")}</span>
          </span>
          <em>${escapeHtml(item.group || "")}</em>
        </button>
      `).join("")
      : `<div class="command-empty">没有匹配结果</div>`;
  }

  function paintCommandActiveItem() {
    if (!commandPalette) return;
    commandPalette.results.querySelectorAll("[data-command-index]").forEach(button => {
      const active = Number(button.dataset.commandIndex) === commandPalette.activeIndex;
      button.classList.toggle("is-active", active);
      button.setAttribute("aria-selected", active ? "true" : "false");
      if (active) button.scrollIntoView({ block: "nearest" });
    });
  }

  function commandItems(query = "") {
    const normalized = normalizeText(query);
    const items = [
      { group: "日常操作", title: "整车入库", detail: "新车号入库", action: () => openEntityModal("vehicleInbound", vehicleInboundDefaultsForModel({})) },
      { group: "日常操作", title: "配件入库", detail: "增加配件库存", action: () => openEntityModal("partStock", { direction: "inbound" }) },
      { group: "日常操作", title: "销售出库", detail: "创建整车出库订单", action: () => openEntityModal("vehicleOutbound", defaultEntity("vehicleOutbound")) },
      { group: "日常操作", title: "租赁登记", detail: "创建租赁记录", action: () => openEntityModal("rental", defaultEntity("rental")) },
      { group: "日常操作", title: "维修登记", detail: "创建维修记录", action: () => openEntityModal("repair", defaultEntity("repair")) },
      { group: "日常操作", title: "库存调拨", detail: "整车或配件转仓", action: () => openEntityModal("stockTransfer", defaultEntity("stockTransfer")) },
      ...Object.entries(tabs)
        .filter(([tab]) => canAccessTab(tab))
        .map(([tab, config]) => ({
          group: "模块",
          title: config.title,
          detail: config.subtitle,
          action: () => goToTab(tab)
        })),
      ...commandRows("车辆", state.data.vehicles.filter(item => !item.modelOnly), row => ({
        title: row.vehicleProductNumber || `车辆 ${row.id}`,
        detail: [row.name, row.specificationModel, row.customerName].filter(Boolean).join(" / "),
        action: async () => {
          const els = getEls();
          state.activeTab = "vehicles";
          els.content.innerHTML = renderLoading();
          await loadCurrentTab({ force: true });
          await loadVehicleDetail(row.id);
          renderCurrentTab();
          scrollToVehicleDetail();
        }
      })),
      ...commandRows("配件", state.data.parts, row => ({
        title: [row.partCode, row.partName].filter(Boolean).join(" / ") || `配件 ${row.id}`,
        detail: [row.partCategory, `库存 ${row.quantity ?? 0}${row.unit || ""}`].filter(Boolean).join(" / "),
        action: () => goToSearchedTab("parts", "parts", row.partCode || row.partName || "")
      })),
      ...commandRows("客户", state.data.customers, row => ({
        title: row.companyName || row.contactName || `客户 ${row.id}`,
        detail: [row.contactName, row.contactPhone, row.address].filter(Boolean).join(" / "),
        action: () => goToSearchedTab("customers", "customers", row.companyName || row.contactPhone || "")
      })),
      ...commandRows("订单", state.data.outboundOrders, row => ({
        title: row.orderNo || row.resourceCode || `订单 ${row.id}`,
        detail: [row.customerName, row.resourceName, row.paymentSettled ? "已结清" : "待收款"].filter(Boolean).join(" / "),
        action: () => goToSearchedTab("outboundOrders", "outboundOrders", row.orderNo || row.resourceCode || "")
      })),
      ...commandRows("维修", state.data.repairs, row => ({
        title: row.vehicleNumber || row.customerName || `维修 ${row.id}`,
        detail: [row.customerName, repairStatusText(row.status), row.faultDescription].filter(Boolean).join(" / "),
        action: () => goToSearchedTab("repairs", "repairs", row.vehicleNumber || row.customerName || "")
      }))
    ];
    if (!normalized) return items;
    return items.filter(item => normalizeText(`${item.group} ${item.title} ${item.detail}`).includes(normalized));
  }

  function commandRows(group, rows = [], factory) {
    return (rows || []).slice(0, 80).map(row => ({ group, ...factory(row) }));
  }

  async function goToSearchedTab(tab, searchKey, value) {
    if (Object.prototype.hasOwnProperty.call(state.search, searchKey)) {
      state.search[searchKey] = value || "";
    }
    if (state.pages[tab]) resetPage(state.pages[tab]);
    await goToTab(tab);
  }

  async function executeCommandItem(item) {
    if (!item?.action) return;
    closeCommandPalette();
    try {
      await item.action();
    } catch (error) {
      handleActionError(error);
    }
  }

  return {
    openCommandPalette,
    closeCommandPalette
  };
}
