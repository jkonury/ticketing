import 'server-only';
import { gateway } from './gateway-client';
import {
  zAuditLogPage,
  zChangeRoleRequest,
  zMemberPage,
  zMemberResponse,
} from './generated/core/zod.gen';
import type { ChangeRoleRequest } from './generated/core/types.gen';

export const roleSchema = zChangeRoleRequest.shape.role;
export {
  zAuditLogPage as auditLogPageSchema,
  zMemberPage as adminMemberPageSchema,
  zMemberResponse as roleChangeSchema,
};

function toQuery(params: Record<string, string | number | undefined>) {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(params))
    if (value !== undefined && value !== '') query.set(key, String(value));
  const text = query.toString();
  return text ? `?${text}` : '';
}

export function listAdminMembers(params: {
  cursor?: number;
  size?: number;
  email?: string;
}) {
  return gateway.call({
    path: `/api/v1/admin/members${toQuery(params)}`,
    schema: zMemberPage,
    auth: 'required',
  });
}

export function changeMemberRole(id: number, role: ChangeRoleRequest['role']) {
  return gateway.call({
    method: 'PUT',
    path: `/api/v1/admin/members/${id}/role`,
    body: { role },
    bodySchema: zChangeRoleRequest,
    schema: zMemberResponse,
    auth: 'required',
  });
}

export function listAuditLogs(params: {
  cursor?: number;
  size?: number;
  action?: string;
  actorId?: number;
  from?: string;
  to?: string;
}) {
  return gateway.call({
    path: `/api/v1/admin/audit-logs${toQuery(params)}`,
    schema: zAuditLogPage,
    auth: 'required',
  });
}
