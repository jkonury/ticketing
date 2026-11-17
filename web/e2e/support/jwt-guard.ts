import {
  expect,
  type APIResponse,
  type BrowserContext,
  type Response,
} from '@playwright/test';

const jwt = /eyJ[A-Za-z0-9_-]+\.eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+/;

export async function attachJwtGuard(context: BrowserContext) {
  const violations: string[] = [];
  const pending = new Set<Promise<void>>();
  const inspect = (value: string, source: string) => {
    if (jwt.test(value)) violations.push(source);
  };
  // route.fulfill로 내려준 문서는 주소 공간이 불명확해 loopback(Gateway) 호출이 막히므로 허용한다.
  await context.grantPermissions(['local-network-access']);
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
  const isStream = (url: string, accept: string | undefined) =>
    (accept ?? '').includes('text/event-stream') ||
    new URL(url).pathname.includes('/api/v1/streams/');
  // 본문을 응답 이벤트에서 읽으면 페이지 이동으로 본문이 사라지는 race가 있어,
  // route로 직접 받아 검사한 뒤 넘긴다. SSE는 fetch가 전체를 버퍼링하므로 route에 넣지 않는다
  // (교차 출처 EventSource는 가로채기만 해도 연결이 실패한다).
  await context.route(
    (url) => !isStream(url.href, undefined),
    async (route) => {
      const request = route.request();
      if (
        ['image', 'font', 'media', 'websocket'].includes(
          request.resourceType(),
        ) ||
        isStream(request.url(), request.headers().accept)
      ) {
        await route.fallback();
        return;
      }
      let response: APIResponse;
      let body: Buffer;
      try {
        // 3xx는 따라가지 않고 그대로 브라우저에 넘긴다.
        response = await route.fetch({ maxRedirects: 0 });
        body = await response.body();
      } catch {
        // 페이지 닫힘·이동으로 요청이 취소된 경우이며 JWT 노출이 아니므로 위반으로 집계하지 않는다.
        await route.abort().catch(() => undefined);
        return;
      }
      const headers = response.headers();
      inspect(JSON.stringify(headers), `응답 헤더 ${response.url()}`);
      const type = headers['content-type'] ?? '';
      if (
        /text|json|javascript|xml|html|x-www-form-urlencoded/i.test(type) &&
        !(response.status() >= 300 && response.status() < 400)
      ) {
        inspect(body.toString('utf8'), `응답 본문 ${response.url()}`);
      }
      // body()는 이미 압축이 풀려 있으므로 인코딩·길이 헤더는 넘기지 않는다.
      const passthrough = Object.fromEntries(
        Object.entries(headers).filter(
          ([name]) =>
            ![
              'content-encoding',
              'content-length',
              'transfer-encoding',
            ].includes(name),
        ),
      );
      await route
        .fulfill({ response, body, headers: passthrough })
        .catch(() => undefined);
    },
  );
  // 통과시킨 SSE 응답은 본문을 읽지 않고 헤더만 검사한다.
  context.on('response', (response: Response) => {
    if (!isStream(response.url(), response.request().headers().accept)) return;
    const task = response
      .allHeaders()
      .then((headers) => {
        inspect(JSON.stringify(headers), `응답 헤더 ${response.url()}`);
      })
      .catch(() => undefined);
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
