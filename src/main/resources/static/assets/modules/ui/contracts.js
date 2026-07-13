/**
 * @typedef {Object} ListViewConfig
 * @property {string} tableKey
 * @property {Function|null} selectableRow
 * @property {Object|null} batch
 * @property {Function|string|null} emptyState
 */

/**
 * @typedef {Object} DrawerAction
 * @property {string} id
 * @property {string} label
 * @property {string} [tone]
 * @property {boolean} [visible]
 * @property {boolean} [disabled]
 * @property {string} [permission]
 * @property {Object} [data]
 */

/**
 * @typedef {Object} FormWorkspaceConfig
 * @property {string} kind
 * @property {boolean} workspace
 * @property {Array<{key: string, title: string, fields: Array<Object>}>} sections
 * @property {boolean} saveAndContinue
 */

/**
 * @typedef {Object} OverlayHandle
 * @property {HTMLElement} element
 * @property {HTMLElement|null} returnFocus
 * @property {Function|null} onEscape
 * @property {boolean} trapFocus
 */

export function defineListViewConfig(config = {}) {
  return {
    tableKey: config.tableKey || "",
    selectableRow: config.selectableRow || null,
    batch: config.batch || null,
    emptyState: config.emptyState || null
  };
}

export function defineDrawerAction(action = {}) {
  return {
    tone: "default",
    visible: true,
    disabled: false,
    data: {},
    ...action
  };
}

export function defineFormWorkspaceConfig(config = {}) {
  return {
    kind: config.kind || "",
    workspace: Boolean(config.workspace),
    sections: Array.isArray(config.sections) ? config.sections : [],
    saveAndContinue: Boolean(config.saveAndContinue)
  };
}
