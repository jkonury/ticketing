import 'server-only';

import { requireUser } from './require-user';

export class ForbiddenError extends Error {
  constructor() {
    super('FORBIDDEN');
    this.name = 'ForbiddenError';
  }
}

/** 비로그인은 /login으로 보내고, ADMIN이 아니면 ForbiddenError를 던진다. */
export async function requireAdmin() {
  const user = await requireUser();
  if (user.role !== 'ADMIN') throw new ForbiddenError();
  return user;
}
