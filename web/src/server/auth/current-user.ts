import 'server-only';

import { sessions } from './redis';
import { readSessionId } from './session-cookie';

export async function getCurrentUser() {
  const id = await readSessionId();
  const session = id ? await sessions.get(id) : null;
  return session
    ? { memberId: session.memberId, name: session.name, role: session.role }
    : null;
}
