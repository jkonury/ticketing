'use client';

import { useActionState } from 'react';
import { Button } from '@/components/ui/button';
import { Select } from '@/components/ui/input';
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
    <form action={action} className="flex flex-wrap items-center gap-2">
      <input type="hidden" name="memberId" value={memberId} />
      <Select
        name="role"
        defaultValue={role}
        aria-label="역할"
        className="w-auto min-w-28 md:min-w-36"
      >
        <option value="USER">일반 회원</option>
        <option value="ORGANIZER">주최자</option>
        <option value="ADMIN">운영자</option>
      </Select>
      <Button type="submit" variant="secondary" disabled={pending}>
        변경
      </Button>
      <FormMessage message={state.message} />
    </form>
  );
}
