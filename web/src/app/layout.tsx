import type { Metadata } from 'next';
import type { ReactNode } from 'react';
import { SiteHeader } from '@/components/layout/site-header';
import { Toaster } from '@/components/ui/toast';
import { siteConfig } from '@/lib/site';
import { pretendard } from './fonts/pretendard';
import './globals.css';

export const metadata: Metadata = {
  title: { default: siteConfig.name, template: `%s | ${siteConfig.name}` },
  description: siteConfig.description,
};

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="ko" className={pretendard.variable}>
      <body>
        <SiteHeader />
        {children}
        <Toaster />
      </body>
    </html>
  );
}
