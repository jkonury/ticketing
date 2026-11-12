import 'server-only';
import type { Section } from '@/lib/section';
import { SessionExpiredError } from '@/server/api/gateway-client';
import { ApiError } from '@/lib/problem';
export async function optional<T>(
  work: Promise<T>,
  propagateForbidden = false,
): Promise<Section<T>> {
  try {
    return { status: 'ok', data: await work };
  } catch (error) {
    if (error instanceof SessionExpiredError) throw error;
    if (propagateForbidden && error instanceof ApiError && error.status === 403)
      throw error;
    return { status: 'unavailable' };
  }
}
