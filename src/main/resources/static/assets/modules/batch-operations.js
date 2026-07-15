export function versionedBatchPayload(rows = []) {
  return {
    items: rows
      .map(row => ({
        id: Number(row?.id),
        version: Number(row?.version)
      }))
      .filter(item => Number.isInteger(item.id) && item.id > 0
        && Number.isInteger(item.version) && item.version >= 0)
      .sort((left, right) => left.id - right.id)
  };
}
