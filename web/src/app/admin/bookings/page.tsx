import { Suspense } from 'react';
import Link from 'next/link';
import { RouteLoading } from '@/components/route-loading';
import { Page, PageHeader } from '@/components/layout/page';
import { Badge } from '@/components/ui/badge';
import { Button, buttonClassName } from '@/components/ui/button';
import { Field } from '@/components/ui/field';
import { Input, Select } from '@/components/ui/input';
import {
  secondaryCol,
  tableClass,
  tdClass,
  thClass,
} from '@/components/ui/table-styles';
import {
  parsePositiveId,
  readDateFilter,
  readSearchParam,
} from '@/features/admin/admin-filters';
import {
  bookingStatuses,
  parseBookingStatus,
} from '@/features/admin/booking-status';
import { formatSeoul } from '@/lib/datetime';
import { listAdminBookings } from '@/server/api/admin';
import { withSessionRedirect } from '@/server/auth/require-user';

type SearchParams = Promise<Record<string, string | string[] | undefined>>;

export default function Route({
  searchParams,
}: {
  searchParams: SearchParams;
}) {
  return (
    <Suspense fallback={<RouteLoading />}>
      <Bookings searchParams={searchParams} />
    </Suspense>
  );
}

async function Bookings({ searchParams }: { searchParams: SearchParams }) {
  const params = await searchParams;
  const memberId = parsePositiveId(params.memberId);
  const scheduleId = parsePositiveId(params.scheduleId);
  const performanceId = parsePositiveId(params.performanceId);
  const status = parseBookingStatus(readSearchParam(params.status));
  const from = readSearchParam(params.createdFrom);
  const to = readSearchParam(params.createdTo);
  const createdFrom = readDateFilter(from);
  const createdTo = readDateFilter(to);
  const invalidPeriod = Boolean(
    (from && !createdFrom) ||
    (to && !createdTo) ||
    (createdFrom && createdTo && createdFrom > createdTo),
  );
  const page = invalidPeriod
    ? null
    : await withSessionRedirect(() =>
        listAdminBookings({
          memberId,
          scheduleId,
          performanceId,
          status,
          createdFrom,
          createdTo,
          cursor: parsePositiveId(params.cursor),
        }),
      );
  const next = new URLSearchParams();
  if (memberId !== undefined) next.set('memberId', String(memberId));
  if (scheduleId !== undefined) next.set('scheduleId', String(scheduleId));
  if (performanceId !== undefined)
    next.set('performanceId', String(performanceId));
  if (status) next.set('status', status);
  if (from) next.set('createdFrom', from);
  if (to) next.set('createdTo', to);
  if (page && page.nextCursor != null)
    next.set('cursor', String(page.nextCursor));

  return (
    <Page>
      <PageHeader title="예매 관리" />
      <form className="flex flex-wrap items-end gap-3">
        <Field label="회원 ID" className="w-32">
          {(control) => (
            <Input
              {...control}
              name="memberId"
              type="number"
              min={1}
              defaultValue={memberId}
            />
          )}
        </Field>
        <Field label="회차 ID" className="w-32">
          {(control) => (
            <Input
              {...control}
              name="scheduleId"
              type="number"
              min={1}
              defaultValue={scheduleId}
            />
          )}
        </Field>
        <Field label="공연 ID" className="w-32">
          {(control) => (
            <Input
              {...control}
              name="performanceId"
              type="number"
              min={1}
              defaultValue={performanceId}
            />
          )}
        </Field>
        <Field label="예매 상태">
          {(control) => (
            <Select {...control} name="status" defaultValue={status ?? ''}>
              <option value="">전체</option>
              {Object.entries(bookingStatuses).map(([code, item]) => (
                <option key={code} value={code}>
                  {item.label}
                </option>
              ))}
            </Select>
          )}
        </Field>
        <Field label="생성 시작 (KST)">
          {(control) => (
            <Input
              {...control}
              name="createdFrom"
              type="datetime-local"
              defaultValue={from}
            />
          )}
        </Field>
        <Field label="생성 종료 (KST)">
          {(control) => (
            <Input
              {...control}
              name="createdTo"
              type="datetime-local"
              defaultValue={to}
            />
          )}
        </Field>
        <Button type="submit">검색</Button>
      </form>
      {page ? (
        <>
          {page.items.length === 0 ? (
            <p className="my-6 text-body text-fg-muted">
              조건에 맞는 예매가 없어요.
            </p>
          ) : (
            <table className={`${tableClass} lg:table`} aria-label="예매 목록">
              <thead>
                <tr>
                  <th scope="col" className={thClass}>
                    예매 번호
                  </th>
                  <th scope="col" className={thClass}>
                    공연
                  </th>
                  <th scope="col" className={`${thClass} ${secondaryCol}`}>
                    회원 ID
                  </th>
                  <th scope="col" className={`${thClass} ${secondaryCol}`}>
                    회차 ID
                  </th>
                  <th scope="col" className={thClass}>
                    예매 상태
                  </th>
                  <th scope="col" className={`${thClass} ${secondaryCol}`}>
                    금액
                  </th>
                  <th scope="col" className={`${thClass} ${secondaryCol}`}>
                    생성일
                  </th>
                </tr>
              </thead>
              <tbody>
                {page.items.map((booking) => {
                  const state = bookingStatuses[booking.status];
                  return (
                    <tr key={booking.bookingId}>
                      <th scope="row" className={tdClass}>
                        <Link
                          className="font-semibold text-brand underline"
                          href={`/admin/bookings/${booking.bookingId}`}
                        >
                          #{booking.bookingId}
                        </Link>
                      </th>
                      <td className={tdClass}>{booking.performanceTitle}</td>
                      <td className={`${tdClass} ${secondaryCol}`}>
                        {booking.memberId}
                      </td>
                      <td className={`${tdClass} ${secondaryCol}`}>
                        {booking.scheduleId}
                      </td>
                      <td className={tdClass}>
                        <Badge tone={state.tone}>{state.label}</Badge>
                      </td>
                      <td className={`${tdClass} ${secondaryCol}`}>
                        {booking.totalAmount.toLocaleString('ko-KR')}원
                      </td>
                      <td className={`${tdClass} ${secondaryCol}`}>
                        {formatSeoul(booking.createdAt)}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          )}
          {page.nextCursor != null && (
            <Link
              href={`/admin/bookings?${next}`}
              className={buttonClassName({ variant: 'secondary' })}
            >
              다음 페이지
            </Link>
          )}
        </>
      ) : (
        <p role="alert" className="my-6 text-body text-danger">
          생성 기간을 확인해 주세요. 시작 시각은 종료 시각보다 늦을 수 없어요.
        </p>
      )}
    </Page>
  );
}
