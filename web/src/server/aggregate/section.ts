import 'server-only';
import type { Section } from '@/lib/section';
import { SessionExpiredError } from '@/server/api/gateway-client';
export async function optional<T>(work: Promise<T>): Promise<Section<T>> {
  try {
    return { status: 'ok', data: await work };
  } catch (error) {
    if (error instanceof SessionExpiredError) throw error;
    return { status: 'unavailable' };
  }
}
