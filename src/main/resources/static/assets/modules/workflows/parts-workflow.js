export function createPartsWorkflow(deps) {
  const {
    state,
    filterRows,
    filterPartRows,
    renderToolbar,
    partFilterControls,
    hasPermission,
    icon,
    renderExportableSurface,
    renderTable,
    stockBadge,
    money,
    rowActions,
    listTableOptions,
    renderPagination
  } = deps;

  function renderParts() {
    const rows = filterPartRows(filterRows(state.data.parts, state.search.parts, [
      "partCode", "partName", "partBrand", "partCategory", "applicableModels", "source"
    ]));

    return `
      <div class="page">
        ${renderToolbar("parts", "搜索编码、名称、品牌、分类", "part", "新增配件", "part:write", {
          main: [partFilterControls()],
          actions: [
            hasPermission("stock:adjust") ? `<button class="btn" type="button" data-action="part-stock" data-direction="inbound">${icon("plus")}配件入库</button>` : "",
            hasPermission("stock:adjust") ? `<button class="btn" type="button" data-action="part-stock" data-direction="outbound">${icon("minus")}配件出库</button>` : ""
          ]
        })}
        ${renderExportableSurface("配件列表", "parts", renderTable([
          { label: "编码", key: "partCode" },
          { label: "名称", key: "partName" },
          { label: "品牌", key: "partBrand" },
          { label: "分类", key: "partCategory" },
          { label: "数量", html: true, render: row => stockBadge(row.quantity, row.unit) },
          { label: "销售价", key: "salePrice", formatter: money },
          { label: "来源", key: "source" }
        ], rows, listTableOptions("part", "parts")))}
        ${renderPagination("parts")}
      </div>
    `;
  }

  return {
    renderParts
  };
}
