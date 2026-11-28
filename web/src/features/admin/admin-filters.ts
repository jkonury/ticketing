import { seoulLocalToInstant } from '@/lib/datetime';

export function parsePositiveId(value: unknown): number | undefined {
  if (typeof value !== 'string' || !/^\d+$/.test(value)) return undefined;
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : undefined;
}

export function readDateFilter(value: string | undefined): string | undefined {
  if (!value) return undefined;
  try {
    return seoulLocalToInstant(value);
  } catch {
    return undefined;
  }
}

export function readSearchParam(value: string | string[] | undefined): string {
  return typeof value === 'string' ? value : '';
}
