import { z } from 'zod';

export const problemSchema = z.object({
  status: z.number(),
  detail: z.string().optional(),
  code: z.string().optional(),
  seatIds: z.array(z.number()).optional(),
});

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code?: string,
    message?: string,
    public readonly seatIds?: number[],
  ) {
    super(message);
    this.name = 'ApiError';
  }
}
