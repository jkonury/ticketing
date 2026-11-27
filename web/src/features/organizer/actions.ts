'use server';
import { headers } from 'next/headers';
import { revalidatePath } from 'next/cache';
import { redirect } from 'next/navigation';
import { z } from 'zod';
import { ApiError } from '@/lib/problem';
import { seoulLocalToInstant } from '@/lib/datetime';
import { performanceGenre } from '@/lib/performance-genre';
import { assertSameOrigin } from '@/server/auth/csrf';
import { requireUser } from '@/server/auth/require-user';
import {
  createPerformance,
  createSchedule,
  createVenue,
} from '@/server/api/catalog';
import { grades, makeSeats } from './seats';

export type RegistrationState = { message: string } | { performanceId: number };
const integer = z.coerce.number().int().min(0).max(5000);
const price = z.coerce.number().int().min(0).max(999999999999);
const schema = z.object({
  venueName: z.string().trim().min(1).max(100),
  sections: z.string().trim().min(1),
  rows: integer.min(1),
  perRow: integer.min(1),
  title: z.string().trim().min(1).max(200),
  description: z.string().trim().min(1),
  genre: performanceGenre,
  startsAt: z.string().min(1),
  VIP: integer,
  R: integer,
  S: integer,
  A: integer,
  VIPPrice: price,
  RPrice: price,
  SPrice: price,
  APrice: price,
});
export async function registerAction(
  form: FormData,
): Promise<RegistrationState> {
  assertSameOrigin(new Headers(await headers()));
  const user = await requireUser();
  if (user.role !== 'ORGANIZER')
    return { message: '주최자만 등록할 수 있습니다. (FORBIDDEN)' };
  let venueId: number | undefined;
  let performanceId: number | undefined;
  try {
    const parsed = schema.safeParse(Object.fromEntries(form));
    if (!parsed.success)
      return { message: '입력 내용을 확인해 주세요. (INVALID_REQUEST)' };
    const input = parsed.data;
    const seats = makeSeats(
      input.sections.split(',').map((section) => section.trim()),
      input.rows,
      input.perRow,
      { VIP: input.VIP, R: input.R, S: input.S, A: input.A },
    );
    const startsAt = seoulLocalToInstant(input.startsAt);
    const prices = grades
      .filter((grade) => input[grade] > 0)
      .map((grade) => ({ grade, price: input[`${grade}Price`] }));
    const venue = await createVenue({ name: input.venueName, seats });
    venueId = venue.id;
    const performance = await createPerformance({
      title: input.title,
      description: input.description,
      genre: input.genre,
      venueId,
      prices,
    });
    performanceId = performance.id;
    await createSchedule(performanceId, { startsAt });
  } catch (error) {
    const code =
      error instanceof ApiError
        ? (error.code ?? 'UPSTREAM_ERROR')
        : error instanceof Error
          ? error.message
          : 'UNKNOWN';
    return {
      message: `등록 실패 (${code}).${venueId ? ` 공연장 ID: ${venueId}.` : ''}${performanceId ? ` 공연 ID: ${performanceId}.` : ''}`,
    };
  }
  revalidatePath('/');
  if (form.get('withPoster') === 'true') return { performanceId };
  redirect(`/performances/${performanceId}`);
}
