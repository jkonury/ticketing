import { readFileSync } from 'node:fs';
import { defineConfig } from '@playwright/test';

// 데이터를 바꾸는 요청은 BFF CSRF 검사 때문에 WEB_ORIGIN 도메인으로 접속해야 한다.
// 도메인 값은 저장소에 적지 않고 실행 시 .env.local에서 읽는다.
function webOrigin(): string {
  if (process.env.CAPTURE_BASE_URL) return process.env.CAPTURE_BASE_URL;
  try {
    const line = readFileSync('.env.local', 'utf8')
      .split('\n')
      .find((l) => l.startsWith('WEB_ORIGIN='));
    const value = line?.slice('WEB_ORIGIN='.length).trim();
    if (value) return value.replace(/^["']|["']$/g, '');
  } catch {
    // .env.local이 없으면 localhost(조회만 가능)로 간다.
  }
  return 'http://localhost:13000';
}

export default defineConfig({
  testDir: './capture',
  timeout: 180_000,
  workers: 1,
  fullyParallel: false,
  reporter: 'list',
  // 기준 이미지는 CI에서만 만들어 커밋한다(capture/README.md).
  snapshotPathTemplate: '{testDir}/__screenshots__/{projectName}/{arg}{ext}',
  expect: { toHaveScreenshot: { animations: 'disabled', caret: 'hide' } },
  use: {
    baseURL: webOrigin(),
    browserName: 'chromium',
    ignoreHTTPSErrors: true,
  },
  projects: [
    {
      name: 'mobile',
      use: {
        viewport: { width: 390, height: 844 },
        deviceScaleFactor: 1,
        isMobile: true,
        hasTouch: true,
      },
    },
    {
      name: 'desktop',
      use: { viewport: { width: 1440, height: 900 } },
    },
  ],
});
