import path from 'node:path';
import {
  expect,
  type Browser,
  type BrowserContext,
  type Page,
  type TestInfo,
} from '@playwright/test';

export const password = 'demo-password-123';
export const accounts = {
  user: 'demo-user@openseat.local',
  organizer: 'demo-organizer@openseat.local',
  admin: 'demo-admin@openseat.local',
};

const outDir = path.resolve(__dirname, '../../build/ui-capture');

// 알려진 무해 메시지는 여기에 정규식으로 추가한다.
const ignoredConsole: RegExp[] = [];

export type Watched = { page: Page; errors: string[] };

export async function newWatchedPage(
  browser: Browser,
  testInfo: TestInfo,
): Promise<Watched & { context: BrowserContext }> {
  const { baseURL, viewport, isMobile, hasTouch, deviceScaleFactor } =
    testInfo.project.use;
  const context = await browser.newContext({
    baseURL,
    viewport,
    isMobile,
    hasTouch,
    deviceScaleFactor,
    ignoreHTTPSErrors: true,
  });
  const page = await context.newPage();
  const errors: string[] = [];
  const keep = (text: string) => !ignoredConsole.some((r) => r.test(text));
  page.on('pageerror', (e) => {
    if (keep(e.message)) errors.push(`pageerror: ${e.message}`);
  });
  page.on('console', (m) => {
    if (m.type() === 'error' && keep(m.text()))
      errors.push(`console: ${m.text()}`);
  });
  return { page, errors, context };
}

export function expectNoConsoleErrors(errors: string[]) {
  expect(errors, '콘솔 오류·pageerror').toEqual([]);
}

export async function capture(page: Page, testInfo: TestInfo, name: string) {
  await page.addStyleTag({ content: 'nextjs-portal{display:none!important}' });
  await page.evaluate(() => document.fonts.ready);
  await page.screenshot({
    path: path.join(outDir, testInfo.project.name, `${name}.png`),
    fullPage: true,
  });
}

export async function login(page: Page, email: string) {
  await page.goto('/login');
  await page.getByLabel('이메일').fill(email);
  await page.getByLabel('비밀번호').fill(password);
  await page.getByRole('button', { name: '로그인' }).click();
  // 모바일은 로그아웃 버튼이 시트 안에 있어 URL 이동으로 성공을 확인한다.
  await page.waitForURL((u) => !u.pathname.startsWith('/login'), {
    timeout: 30_000,
  });
}

export async function gotoReady(page: Page, url: string) {
  await page.goto(url);
  await page.waitForLoadState('networkidle').catch(() => undefined);
}
