import 'server-only';

import { z } from 'zod';

const envSchema = z.object({
  GATEWAY_URL: z.url().default('http://localhost:18000'),
  GATEWAY_PUBLIC_URL: z.url().default('http://localhost:18000'),
  REDIS_URL: z.url().default('redis://localhost:16379'),
  WEB_ORIGIN: z.url().default('http://localhost:13000'),
  TICKETING_INGRESS_PROOF: z
    .union([
      z.literal(''),
      z
        .string()
        .length(64)
        .regex(/^[0-9a-f]{64}$/),
    ])
    .default(''),
  SESSION_COOKIE_SECURE: z
    .enum(['true', 'false'])
    .default('false')
    .transform((value) => value === 'true'),
});

export const env = envSchema.parse(process.env);
