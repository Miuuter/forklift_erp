export function createUserWorkflow(deps) {
  const {
    state,
    api,
    endpoints,
    markReferenceDataStale,
    refreshAfterMutation,
    renderCurrentTab,
    showToast,
    confirmDanger,
    entityDisplayName,
    findEntity,
    filterRows,
    hasRole,
    hasPermission,
    renderToolbar,
    renderSurface,
    renderTable,
    listTableOptions,
    renderPagination,
    roleBadges,
    jobTagBadge,
    jobTagLabel,
    jobTagType,
    nextJobTag,
    normalizeJobTag,
    badge,
    emptyState,
    escapeAttr,
    escapeHtml,
    dateTime,
    icon
  } = deps;

  async function toggleUserJobTag(id) {
    const user = findEntity("user", id);
    if (!user?.id || !canUpdateUserJobTag(user)) return;
    const nextTag = nextJobTag(user.jobTag);
    if (!(await confirmDanger({
      title: "确认切换用户职务",
      target: entityDisplayName("user", user),
      impact: `用户职务将切换为${jobTagLabel(nextTag)}，可能影响维修人员选择和业务分配。`
    }))) return;
    await api(endpoints.user.updateJobTag(user.id), {
      method: "PUT",
      body: {
        version: user.version,
        jobTag: nextTag
      }
    });
    showToast(`职务已切换为${jobTagLabel(nextTag)}`, "success");
    markReferenceDataStale(["repairUser"]);
    await refreshAfterMutation("user", { refreshDetail: false });
    renderCurrentTab();
  }

  async function toggleUserEnabled(id) {
    const user = findEntity("user", id);
    if (!user?.id || !canUpdateUserEnabled(user)) return;
    const nextEnabled = !Boolean(user.enabled);
    if (!(await confirmDanger({
      title: nextEnabled ? "确认启用用户" : "确认停用用户",
      target: entityDisplayName("user", user),
      impact: nextEnabled ? "启用后该用户可以继续登录和参与业务。" : "停用后该用户将无法继续登录使用系统。"
    }))) return;
    await api(endpoints.user.updateEnabled(user.id), {
      method: "PUT",
      body: {
        version: user.version,
        enabled: nextEnabled
      }
    });
    showToast(nextEnabled ? "用户已启用" : "用户已停用", "success");
    markReferenceDataStale(["repairUser"]);
    await refreshAfterMutation("user", { refreshDetail: false });
    renderCurrentTab();
  }

  function renderUsers() {
    if (!hasRole("SUPER_ADMIN")) {
      return `<div class="page">${renderSurface("用户管理", emptyState("当前账号无权访问用户管理"))}</div>`;
    }
    const rows = filterRows(state.data.users, state.search.users, [
      "username", "roles", "jobTag"
    ]);

    return `
      <div class="page">
        ${renderToolbar("users", "搜索用户名、角色或职务", "user", "新建用户", "user:write", {
          exportType: false
        })}
        ${renderSurface("用户列表", renderTable([
          { label: "用户名", key: "username" },
          { label: "角色", html: true, render: row => roleBadges(row.roles) },
          { label: "职务", html: true, render: row => jobTagControl(row) },
          { label: "状态", html: true, render: row => userEnabledControl(row) },
          { label: "创建时间", key: "createdAt", formatter: dateTime }
        ], rows, listTableOptions("user", null, { batch: false })))}
        ${renderPagination("users")}
      </div>
    `;
  }

  function jobTagControl(row = {}) {
    const tag = normalizeJobTag(row.jobTag, row.roles);
    return jobTagBadge(tag);
  }

  function userEnabledControl(row = {}) {
    const enabled = Boolean(row.enabled);
    return badge(enabled ? "启用" : "停用", enabled ? "teal" : "danger");
  }

  function userActions(row) {
    if (!hasPermission("user:admin") || row.roles?.includes("SUPER_ADMIN")) return "";
    return `
      <div class="action-row">
        <button class="btn btn-sm" type="button" data-action="user-username" data-id="${escapeAttr(row.id)}">${icon("edit")}改用户名</button>
        <button class="btn btn-sm" type="button" data-action="user-password" data-id="${escapeAttr(row.id)}">${icon("swap")}改密码</button>
        <button class="btn btn-sm btn-danger" type="button" data-action="delete" data-kind="user" data-id="${escapeAttr(row.id)}">${icon("trash")}删除</button>
      </div>
    `;
  }

  function userRoleOptions() {
    const options = [{ value: "USER", label: "普通用户" }];
    if (hasPermission("user:admin")) {
      options.push({ value: "ADMIN", label: "管理员" });
    }
    return options;
  }

  function jobTagOptions() {
    return [
      { value: "MANAGEMENT", label: "管理" },
      { value: "CLERK", label: "文员" },
      { value: "REPAIR", label: "维修" }
    ];
  }

  function canUpdateUserJobTag(row = {}) {
    if (row.roles?.includes("SUPER_ADMIN")) return false;
    if (hasPermission("user:admin")) return true;
    return hasPermission("user:write") && row.roles?.every(role => role === "USER");
  }

  function canUpdateUserEnabled(row = {}) {
    if (row.roles?.includes("SUPER_ADMIN")) return false;
    if (row.username && state.user?.username && row.username === state.user.username) return false;
    if (hasPermission("user:admin")) return true;
    return hasPermission("user:write") && row.roles?.every(role => role === "USER");
  }

  return {
    toggleUserJobTag,
    toggleUserEnabled,
    renderUsers,
    jobTagControl,
    userEnabledControl,
    userActions,
    userRoleOptions,
    jobTagOptions,
    canUpdateUserJobTag,
    canUpdateUserEnabled
  };
}
