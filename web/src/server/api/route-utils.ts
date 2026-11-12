import 'server-only';
import { z, ZodError } from 'zod';
import { ApiError } from '@/lib/problem';
import { SessionExpiredError } from './gateway-client';
export const positiveId = z.coerce.number().int().positive();
export function errorResponse(error: unknown) {
  if (error instanceof SessionExpiredError)
    return Response.json(
      { code: 'SESSION_EXPIRED', status: 401 },
      { status: 401 },
    );
  if (error instanceof ZodError || error instanceof SyntaxError)
    return Response.json(
      { code: 'INVALID_REQUEST', status: 400 },
      { status: 400 },
    );
  if (error instanceof ApiError) {
    const body = {
      code: error.code ?? 'UPSTREAM_ERROR',
      status: error.status,
      ...(error.code === 'SEAT_ALREADY_HELD' && error.seatIds
        ? { seatIds: error.seatIds }
        : {}),
    };
    return Response.json(body, { status: error.status });
  }
  return Response.json(
    { code: 'INTERNAL_ERROR', status: 500 },
    { status: 500 },
  );
}
