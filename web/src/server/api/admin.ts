import 'server-only';
import { gateway } from './gateway-client';
import {
  zAdminBookingDetailResponse,
  zAdminBookingPageResponse,
  zAdminPerformancePage,
  zAuditLogPage,
  zBookingResponse,
  zCancelPerformanceRequest,
  zChangeRoleRequest,
  zForceCancelBookingRequest,
  zMemberPage,
  zMemberResponse,
  zMemberSuspensionResponse,
  zPerformanceCancellationResponse,
  zPerformanceSuspensionResponse,
  zSuspendMemberRequest,
  zSuspendPerformanceRequest,
} from './generated/core/zod.gen';
import type {
  AdminBookingSummaryResponse,
  AdminPerformanceSummary,
  ChangeRoleRequest,
} from './generated/core/types.gen';
import { zAdminByBookingIdResponse } from './generated/payment/zod.gen';

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

export function listAdminBookings(params: {
  memberId?: number;
  scheduleId?: number;
  performanceId?: number;
  status?: AdminBookingSummaryResponse['status'];
  createdFrom?: string;
  createdTo?: string;
  cursor?: number;
  size?: number;
}) {
  return gateway.call({
    path: `/api/v1/admin/bookings${toQuery(params)}`,
    schema: zAdminBookingPageResponse,
    auth: 'required',
  });
}

export function getAdminBooking(id: number) {
  return gateway.call({
    path: `/api/v1/admin/bookings/${id}`,
    schema: zAdminBookingDetailResponse,
    auth: 'required',
  });
}

export function forceCancelBooking(id: number, reason: string) {
  return gateway.call({
    method: 'POST',
    path: `/api/v1/admin/bookings/${id}/cancellation`,
    body: { reason },
    bodySchema: zForceCancelBookingRequest,
    schema: zBookingResponse,
    auth: 'required',
  });
}

export function getAdminPayment(bookingId: number) {
  return gateway.call({
    path: `/api/v1/admin/payments${toQuery({ bookingId })}`,
    schema: zAdminByBookingIdResponse,
    auth: 'required',
    timeoutMs: 2000,
  });
}

export function listAdminPerformances(params: {
  organizerId?: number;
  title?: string;
  status?: AdminPerformanceSummary['status'];
  cursor?: number;
  size?: number;
}) {
  return gateway.call({
    path: `/api/v1/admin/performances${toQuery(params)}`,
    schema: zAdminPerformancePage,
    auth: 'required',
  });
}

export function suspendMember(id: number, reason: string) {
  return gateway.call({
    method: 'PUT',
    path: `/api/v1/admin/members/${id}/suspension`,
    body: { reason },
    bodySchema: zSuspendMemberRequest,
    schema: zMemberSuspensionResponse,
    auth: 'required',
  });
}

export function reinstateMember(id: number) {
  return gateway.call({
    method: 'DELETE',
    path: `/api/v1/admin/members/${id}/suspension`,
    schema: zMemberSuspensionResponse,
    auth: 'required',
  });
}

export function suspendPerformance(id: number, reason: string) {
  return gateway.call({
    method: 'PUT',
    path: `/api/v1/admin/performances/${id}/suspension`,
    body: { reason },
    bodySchema: zSuspendPerformanceRequest,
    schema: zPerformanceSuspensionResponse,
    auth: 'required',
  });
}

export function resumePerformance(id: number) {
  return gateway.call({
    method: 'DELETE',
    path: `/api/v1/admin/performances/${id}/suspension`,
    schema: zPerformanceSuspensionResponse,
    auth: 'required',
  });
}

export function cancelPerformance(id: number, reason: string) {
  return gateway.call({
    method: 'PUT',
    path: `/api/v1/admin/performances/${id}/cancellation`,
    body: { reason },
    bodySchema: zCancelPerformanceRequest,
    schema: zPerformanceCancellationResponse,
    auth: 'required',
  });
}
