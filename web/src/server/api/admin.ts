import 'server-only';
import { z } from 'zod';
import { gateway } from './gateway-client';

export const roleSchema = z.enum(['USER', 'ORGANIZER', 'ADMIN']);
export const adminMemberPageSchema = z.object({
  items: z.array(
    z.object({
      id: z.number(),
      email: z.string(),
      name: z.string(),
      role: roleSchema,
      createdAt: z.string(),
    }),
  ),
  nextCursor: z.number().nullable(),
});
export const roleChangeSchema = z.object({
  id: z.number(),
  email: z.string(),
  name: z.string(),
  role: roleSchema,
});
export const auditLogPageSchema = z.object({
  items: z.array(
    z.object({
      id: z.number(),
      actorId: z.number().nullable(),
      action: z.string(),
      targetType: z.string(),
      targetId: z.string(),
      detail: z.record(z.string(), z.unknown()).nullable().optional(),
      occurredAt: z.string(),
    }),
  ),
  nextCursor: z.number().nullable(),
});

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
    schema: adminMemberPageSchema,
    auth: 'required',
  });
}

export function changeMemberRole(id: number, role: z.infer<typeof roleSchema>) {
  return gateway.call({
    method: 'PUT',
    path: `/api/v1/admin/members/${id}/role`,
    body: { role },
    schema: roleChangeSchema,
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
    schema: auditLogPageSchema,
    auth: 'required',
  });
}
