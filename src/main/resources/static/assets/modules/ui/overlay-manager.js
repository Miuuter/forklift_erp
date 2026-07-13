const FOCUSABLE_SELECTOR = [
  "a[href]",
  "button:not([disabled])",
  "input:not([disabled]):not([type='hidden'])",
  "select:not([disabled])",
  "textarea:not([disabled])",
  "[tabindex]:not([tabindex='-1'])"
].join(",");

export function createOverlayManager({ getBackgroundRoots = () => [] } = {}) {
  const stack = [];
  const inertSnapshots = new Map();
  let listening = false;

  function open(element, options = {}) {
    if (!element) return null;
    const existing = stack.find(entry => entry.element === element);
    if (existing) {
      Object.assign(existing, normalizeOptions(element, options, existing.returnFocus));
      syncInert();
      return existing;
    }

    const entry = normalizeOptions(
      element,
      options,
      options.returnFocus || safeActiveElement()
    );
    stack.push(entry);
    element.setAttribute("aria-hidden", "false");
    document.body.classList.add("has-overlay");
    syncInert();
    ensureListener();
    focusInitial(entry);
    return entry;
  }

  function close(element, { restoreFocus = true } = {}) {
    const index = stack.findIndex(entry => entry.element === element);
    if (index < 0) return;
    const [entry] = stack.splice(index, 1);
    releaseManagedInert(element);
    element.setAttribute("aria-hidden", "true");
    syncInert();

    if (!stack.length) {
      document.body.classList.remove("has-overlay");
      removeListener();
    }
    if (restoreFocus) {
      window.requestAnimationFrame(() => {
        if (entry.returnFocus?.isConnected && !entry.returnFocus.closest("[inert]")) {
          entry.returnFocus.focus({ preventScroll: true });
        }
      });
    }
  }

  function closeAll({ restoreFocus = false } = {}) {
    const entries = [...stack].reverse();
    entries.forEach(entry => close(entry.element, { restoreFocus }));
  }

  function top() {
    return stack[stack.length - 1] || null;
  }

  function isOpen(element) {
    return stack.some(entry => entry.element === element);
  }

  function normalizeOptions(element, options, returnFocus) {
    return {
      element,
      returnFocus: returnFocus || null,
      onEscape: typeof options.onEscape === "function" ? options.onEscape : null,
      initialFocus: options.initialFocus || null,
      trapFocus: options.trapFocus !== false
    };
  }

  function focusInitial(entry) {
    window.requestAnimationFrame(() => {
      if (top() !== entry) return;
      const requested = resolveInitialFocus(entry);
      const target = requested || focusableElements(entry.element)[0] || entry.element;
      if (!target.hasAttribute("tabindex") && target === entry.element) {
        target.setAttribute("tabindex", "-1");
      }
      target.focus({ preventScroll: true });
      if (typeof target.select === "function" && target.matches("input:not([type='file'])")) {
        target.select();
      }
    });
  }

  function resolveInitialFocus(entry) {
    if (typeof entry.initialFocus === "function") return entry.initialFocus();
    if (typeof entry.initialFocus === "string") return entry.element.querySelector(entry.initialFocus);
    return entry.initialFocus?.isConnected ? entry.initialFocus : null;
  }

  function handleKeydown(event) {
    const entry = top();
    if (!entry) return;
    if (event.key === "Escape" && entry.onEscape) {
      event.preventDefault();
      event.stopPropagation();
      entry.onEscape(event);
      return;
    }
    if (event.key !== "Tab" || !entry.trapFocus) return;
    trapTab(event, entry.element);
  }

  function trapTab(event, element) {
    const focusable = focusableElements(element);
    if (!focusable.length) {
      event.preventDefault();
      element.focus({ preventScroll: true });
      return;
    }
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    const active = safeActiveElement();
    if (event.shiftKey && (active === first || !element.contains(active))) {
      event.preventDefault();
      last.focus();
      return;
    }
    if (!event.shiftKey && (active === last || !element.contains(active))) {
      event.preventDefault();
      first.focus();
    }
  }

  function focusableElements(element) {
    return [...element.querySelectorAll(FOCUSABLE_SELECTOR)]
      .filter(node => !node.hidden && node.getAttribute("aria-hidden") !== "true" && node.offsetParent !== null);
  }

  function syncInert() {
    const active = top();
    const backgrounds = getBackgroundRoots().filter(Boolean);
    backgrounds.forEach(root => setManagedInert(root, Boolean(active)));
    stack.forEach(entry => setManagedInert(entry.element, entry !== active));
    if (!active) {
      [...inertSnapshots.keys()].forEach(releaseManagedInert);
    }
  }

  function setManagedInert(element, value) {
    if (!element) return;
    if (value) {
      if (!inertSnapshots.has(element)) inertSnapshots.set(element, Boolean(element.inert));
      element.inert = true;
      return;
    }
    releaseManagedInert(element);
  }

  function releaseManagedInert(element) {
    if (!inertSnapshots.has(element)) return;
    element.inert = inertSnapshots.get(element);
    inertSnapshots.delete(element);
  }

  function ensureListener() {
    if (listening) return;
    document.addEventListener("keydown", handleKeydown, true);
    listening = true;
  }

  function removeListener() {
    if (!listening) return;
    document.removeEventListener("keydown", handleKeydown, true);
    listening = false;
  }

  function safeActiveElement() {
    const active = document.activeElement;
    return active instanceof HTMLElement ? active : null;
  }

  return {
    open,
    close,
    closeAll,
    isOpen,
    top
  };
}
