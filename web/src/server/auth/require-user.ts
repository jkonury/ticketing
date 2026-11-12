import 'server-only';

import { redirect } from 'next/navigation';
import { SessionExpiredError } from '@/server/api/gateway-client';
import { getCurrentUser } from './current-user';

export async function requireUser() {
  const user = await getCurrentUser();
  if (!user) redirect('/login');
  return user;
}
export async function withSessionRedirect<T>(
  work: () => Promise<T>,
): Promise<T> {
  try {
    return await work();
  } catch (error) {
    if (error instanceof SessionExpiredError) redirect('/login');
    throw error;
  }
}
