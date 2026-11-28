import { Suspense } from 'react';
import Link from 'next/link';
import { RouteLoading } from '@/components/route-loading';
import { Page, PageHeader } from '@/components/layout/page';
import { Badge } from '@/components/ui/badge';
import { Button, buttonClassName } from '@/components/ui/button';
import { Field } from '@/components/ui/field';
import { Input, Select } from '@/components/ui/input';
import {
  tableClass,
  thClass,
  tdClass,
  secondaryCol,
} from '@/components/ui/table-styles';
import {
  parsePositiveId,
  readSearchParam,
} from '@/features/admin/admin-filters';
import { SuspensionForm } from '@/features/admin/suspension-form';
import { formatSeoul } from '@/lib/datetime';
import { listAdminPerformances } from '@/server/api/admin';
import { withSessionRedirect } from '@/server/auth/require-user';

type SearchParams = Promise<Record<string, string | string[] | undefined>>;

export default function Route({
  searchParams,
}: {
  searchParams: SearchParams;
}) {
  return (
    <Suspense fallback={<RouteLoading />}>
      <Performances searchParams={searchParams} />
    </Suspense>
  );
}

async function Performances({ searchParams }: { searchParams: SearchParams }) {
  const params = await searchParams;
  const title = readSearchParam(params.title).trim();
  const organizerId = parsePositiveId(params.organizerId);
  const cursor = parsePositiveId(params.cursor);
  const status =
    params.status === 'ACTIVE' || params.status === 'SUSPENDED'
      ? params.status
      : undefined;
  const page = await withSessionRedirect(() =>
    listAdminPerformances({ title, organizerId, cursor, status }),
  );
  const next = new URLSearchParams();
  if (title) next.set('title', title);
  if (organizerId !== undefined) next.set('organizerId', String(organizerId));
  if (status) next.set('status', status);
  if (page.nextCursor != null) next.set('cursor', String(page.nextCursor));

  return (
    <Page>
      <PageHeader title="공연 관리" />
      <form className="flex flex-wrap items-end gap-3">
        <Field label="공연 제목" className="w-full sm:max-w-68">
          {(control) => (
            <Input {...control} name="title" defaultValue={title} />
          )}
        </Field>
        <Field label="주최자 ID" className="w-34">
          {(control) => (
            <Input
              {...control}
              name="organizerId"
              type="number"
              min={1}
              defaultValue={organizerId}
            />
          )}
        </Field>
        <Field label="판매 상태">
          {(control) => (
            <Select {...control} name="status" defaultValue={status ?? ''}>
              <option value="">전체</option>
              <option value="ACTIVE">판매 중</option>
              <option value="SUSPENDED">판매 중지</option>
            </Select>
          )}
        </Field>
        <Button type="submit">검색</Button>
      </form>

      {page.items.length === 0 ? (
        <p className="my-6 text-body text-fg-muted">
          조건에 맞는 공연이 없어요.
        </p>
      ) : (
        <table className={`${tableClass} lg:table`} aria-label="공연 목록">
          <thead>
            <tr>
              <th scope="col" className={thClass}>
                공연
              </th>
              <th scope="col" className={`${thClass} ${secondaryCol}`}>
                공연장
              </th>
              <th scope="col" className={`${thClass} ${secondaryCol}`}>
                주최자 ID
              </th>
              <th scope="col" className={`${thClass} ${secondaryCol}`}>
                등록일
              </th>
              <th scope="col" className={thClass}>
                판매 상태
              </th>
              <th scope="col" className={thClass}>
                판매 관리
              </th>
            </tr>
          </thead>
          <tbody>
            {page.items.map((performance) => {
              const suspended = performance.status === 'SUSPENDED';
              return (
                <tr key={performance.id}>
                  <th scope="row" className={tdClass}>
                    <span className="block font-semibold">
                      {performance.title}
                    </span>
                    <span className="block text-caption font-normal text-fg-muted">
                      #{performance.id}
                    </span>
                  </th>
                  <td className={`${tdClass} ${secondaryCol}`}>
                    {performance.venueName}
                  </td>
                  <td className={`${tdClass} ${secondaryCol}`}>
                    {performance.organizerId}
                  </td>
                  <td className={`${tdClass} ${secondaryCol}`}>
                    {formatSeoul(performance.createdAt)}
                  </td>
                  <td className={tdClass}>
                    <Badge tone={suspended ? 'danger' : 'success'}>
                      {suspended ? '판매 중지' : '판매 중'}
                    </Badge>
                  </td>
                  <td className={tdClass}>
                    <SuspensionForm
                      key={performance.status}
                      id={performance.id}
                      target="performance"
                      name={performance.title}
                      suspended={suspended}
                    />
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
      {page.nextCursor != null && (
        <Link
          href={`/admin/performances?${next}`}
          className={buttonClassName({ variant: 'secondary' })}
        >
          다음 페이지
        </Link>
      )}
    </Page>
  );
}
