import type { NextConfig } from 'next';

const nextConfig: NextConfig = {
  allowedDevOrigins: process.env.WEB_ORIGIN
    ? [new URL(process.env.WEB_ORIGIN).hostname]
    : [],
  cacheComponents: true,
  partialPrefetching: true,
  turbopack: {
    rules: {
      '*.css': {
        loaders: ['@tailwindcss/turbopack'],
        as: '*.css',
      },
    },
  },
};

export default nextConfig;
