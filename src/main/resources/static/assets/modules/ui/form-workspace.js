import { defineFormWorkspaceConfig } from "./contracts.js";

const WORKSPACE_FIELD_THRESHOLD = 8;

export function buildFormWorkspaceConfig(kind, fields = [], canSaveAndContinue = () => false, item = {}) {
  const visibleFields = fields.filter(field => field.type !== "hidden");
  const sections = groupFormSections(visibleFields);
  const workspace = visibleFields.length > WORKSPACE_FIELD_THRESHOLD || sections.length > 1;
  return defineFormWorkspaceConfig({
    kind,
    workspace,
    sections,
    saveAndContinue: canSaveAndContinue(kind, item)
  });
}

export function groupFormSections(fields = []) {
  const sections = [];
  const byKey = new Map();
  fields.forEach(field => {
    const title = field.section || "基本信息";
    const key = sectionKey(title, sections.length);
    let section = byKey.get(title);
    if (!section) {
      section = { key, title, fields: [] };
      byKey.set(title, section);
      sections.push(section);
    }
    section.fields.push(field);
  });
  return sections;
}

function sectionKey(title, index) {
  const normalized = String(title || "")
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9\u4e00-\u9fa5]+/g, "-")
    .replace(/^-+|-+$/g, "");
  return `form-section-${normalized || index + 1}`;
}
