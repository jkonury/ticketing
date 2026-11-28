import 'server-only';
import type { Section } from '@/lib/section';
import { SessionExpiredError } from '@/server/api/gateway-client';
import { ApiError } from '@/lib/problem';
export async function optional<T>(
  work: Promise<T>,
  propagateAuthorizationErrors = false,
): Promise<Section<T>> {
  try {
    return { status: 'ok', data: await work };
  } catch (error) {
    if (error instanceof SessionExpiredError) throw error;
    if (
      propagateAuthorizationErrors &&
      error instanceof ApiError &&
      (error.status === 401 || error.status === 403)
    )
      throw error;
    return { status: 'unavailable' };
  }
}
