import { z } from 'zod';

export const problemSchema = z.object({
  status: z.number(),
  detail: z.string().optional(),
  code: z.string().optional(),
});

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code?: string,
    message?: string,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}
