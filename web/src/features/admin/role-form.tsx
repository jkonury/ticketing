'use client';

import { useActionState } from 'react';
import { FormMessage } from '@/components/form-message';
import { changeRoleAction, type RoleChangeState } from './actions';

const initial: RoleChangeState = { message: '' };

export function RoleForm({
  memberId,
  role,
}: {
  memberId: number;
  role: string;
}) {
  const [state, action, pending] = useActionState(changeRoleAction, initial);
  return (
    <form action={action}>
      <input type="hidden" name="memberId" value={memberId} />
      <select name="role" defaultValue={role} aria-label="역할">
        <option value="USER">USER</option>
        <option value="ORGANIZER">ORGANIZER</option>
        <option value="ADMIN">ADMIN</option>
      </select>
      <button disabled={pending}>변경</button>
      <FormMessage message={state.message} />
    </form>
  );
}
