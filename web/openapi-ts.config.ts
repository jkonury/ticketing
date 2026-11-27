import { defineConfig } from '@hey-api/openapi-ts';
import { join } from 'node:path';

const format = {
  command: 'prettier',
  args: ['--config', '.prettierrc.json', '--write', '{{path}}'],
};

export default defineConfig(
  ['core', 'payment', 'realtime', 'settlement'].map((app) => ({
    input: `../docs/api/openapi/${app}.json`,
    output: {
      path: join(
        process.env.API_CONTRACT_OUTPUT ?? 'src/server/api/generated',
        app,
      ),
      postProcess: [
        {
          command: 'node',
          args: ['scripts/api-contracts.mjs', 'mark', '{{path}}'],
        },
        // 생성기의 메서드 체인은 두 번째 포맷에서 최종 줄바꿈이 결정된다.
        format,
        format,
      ],
    },
    plugins: [
      '@hey-api/typescript',
      {
        name: 'zod',
        compatibilityVersion: 4,
        dates: { offset: true },
        $resolvers: {
          number: (context) => {
            if (context.schema.format !== 'int64') return undefined;
            // JSON은 number로 읽되 스펙에 명시된 제약은 그대로 생성한다.
            const numeric = {
              ...context,
              schema: { ...context.schema, format: undefined },
            };
            const { chain, nodes } = numeric;
            chain.current = nodes.const(numeric) ?? nodes.base(numeric);
            if (context.schema.const === undefined) {
              chain.current = nodes.min(numeric) ?? chain.current;
              chain.current = nodes.max(numeric) ?? chain.current;
            }
            return chain.current;
          },
        },
      },
    ],
  })),
);
