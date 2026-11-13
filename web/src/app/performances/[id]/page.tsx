import { notFound } from 'next/navigation';
import { connection } from 'next/server';
import { ApiError } from '@/lib/problem';
import { PerformanceDetail } from '@/features/performance/performance-detail';
import { getPerformanceDetail } from '@/server/aggregate/performance-detail';
import { withSessionRedirect } from '@/server/auth/require-user';
export default async function Page({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  const number = Number(id);
  if (!Number.isSafeInteger(number) || number < 1) notFound();
  await connection();
  const view = await withSessionRedirect(() =>
    getPerformanceDetail(number),
  ).catch((error: unknown) => {
    if (error instanceof ApiError && error.status === 404) notFound();
    throw error;
  });
  return <PerformanceDetail view={view} />;
}
