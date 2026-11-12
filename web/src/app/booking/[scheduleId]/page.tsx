import { notFound } from 'next/navigation';
import { ApiError } from '@/lib/problem';
import { BookingFlow } from '@/features/booking/booking-flow';
import { getSchedule, getSeats } from '@/server/api/catalog';
import { requireUser } from '@/server/auth/require-user';

export default async function Page({
  params,
}: {
  params: Promise<{ scheduleId: string }>;
}) {
  await requireUser();
  const { scheduleId } = await params;
  const id = Number(scheduleId);
  if (!Number.isSafeInteger(id) || id < 1) notFound();
  const [schedule, seats] = await Promise.all([
    getSchedule(id),
    getSeats(id),
  ]).catch((error: unknown) => {
    if (error instanceof ApiError && error.status === 404) notFound();
    throw error;
  });
  return <BookingFlow schedule={schedule} seats={seats} />;
}
