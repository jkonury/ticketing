import { expect, type BrowserContext, type Response } from '@playwright/test';

const jwt = /eyJ[A-Za-z0-9_-]+\.eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+/;

export async function attachJwtGuard(context: BrowserContext) {
  const violations: string[] = [];
  const pending = new Set<Promise<void>>();
  const inspect = (value: string, source: string) => {
    if (jwt.test(value)) violations.push(source);
  };
  await context.exposeFunction('__inspectSseData', (data: string) => {
    inspect(data, 'SSE 데이터');
  });
  await context.addInitScript(() => {
    const add = EventSource.prototype.addEventListener;
    Reflect.set(
      EventSource.prototype,
      'addEventListener',
      function (
        this: EventSource,
        type: string,
        listener: EventListenerOrEventListenerObject,
        options?: boolean | AddEventListenerOptions,
      ) {
        add.call(this, type, (event: Event) => {
          if (event instanceof MessageEvent && typeof event.data === 'string') {
            const inspector = Reflect.get(window, '__inspectSseData');
            if (typeof inspector === 'function') void inspector(event.data);
          }
        });
        return add.call(this, type, listener, options);
      },
    );
  });
  context.on('response', (response: Response) => {
    const task = (async () => {
      const headers = await response.allHeaders();
      inspect(JSON.stringify(headers), `응답 헤더 ${response.url()}`);
      const type = headers['content-type'] ?? '';
      if (
        !type.includes('text/event-stream') &&
        /text|json|javascript|xml|html|x-www-form-urlencoded/i.test(type) &&
        !(response.status() >= 300 && response.status() < 400)
      ) {
        inspect(await response.text(), `응답 본문 ${response.url()}`);
      }
    })().catch((error: unknown) => {
      violations.push(`응답 검사 실패 ${response.url()}: ${String(error)}`);
    });
    pending.add(task);
    void task.finally(() => pending.delete(task));
  });
  return {
    async violations() {
      await Promise.all([...pending]);
      for (const cookie of await context.cookies()) {
        inspect(cookie.value, `쿠키 ${cookie.name}`);
      }
      for (const page of context.pages()) {
        if (!/^https?:/.test(page.url())) continue;
        const storage = await page.evaluate(async () => ({
          local: Object.entries(localStorage),
          session: Object.entries(sessionStorage),
          databases: await indexedDB.databases(),
        }));
        inspect(JSON.stringify(storage), `브라우저 저장소 ${page.url()}`);
      }
      return violations;
    },
    async assertClean() {
      const cookies = await context.cookies();
      const session = cookies.find(
        (cookie) => cookie.name === 'ticketing_session',
      );
      expect(session, '세션 쿠키').toBeDefined();
      expect(session?.httpOnly).toBe(true);
      expect(session?.value).not.toMatch(jwt);
      expect(await this.violations()).toEqual([]);
    },
  };
}
