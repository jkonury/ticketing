import 'server-only';

import { z } from 'zod';

const envSchema = z.object({
  GATEWAY_URL: z.url().default('http://localhost:8000'),
  GATEWAY_PUBLIC_URL: z.url().default('http://localhost:8000'),
  REDIS_URL: z.url().default('redis://localhost:6379'),
  WEB_ORIGIN: z.url().default('http://localhost:3000'),
  SESSION_COOKIE_SECURE: z
    .enum(['true', 'false'])
    .default('false')
    .transform((value) => value === 'true'),
});

export const env = envSchema.parse(process.env);
