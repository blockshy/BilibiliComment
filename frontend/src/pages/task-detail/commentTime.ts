export function beijingLocalDateTimeToIso(value: string | null): string | null {
  if (!value) return null;
  const normalized = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(value)
    ? `${value}:00+08:00`
    : `${value}+08:00`;
  const date = new Date(normalized);
  return Number.isNaN(date.getTime()) ? null : date.toISOString();
}

export function isoToBeijingLocalDateTime(value: string | null | undefined): string {
  if (!value) return '';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '';
  return new Date(date.getTime() + 8 * 60 * 60 * 1_000).toISOString().slice(0, 16);
}
