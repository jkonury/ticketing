import 'server-only';

import { ApiError } from '@/lib/problem';
import { env } from '@/server/env';

export function assertSameOrigin(headers: Headers): void {
  const origin = headers.get('origin');
  const referer = headers.get('referer');
  let actual = origin;
  if (!actual && referer) {
    try {
      actual = new URL(referer).origin;
    } catch {
      actual = null;
    }
  }
  if (actual !== new URL(env.WEB_ORIGIN).origin)
    throw new ApiError(403, 'CSRF_REJECTED');
}
