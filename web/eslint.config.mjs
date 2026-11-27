import { defineConfig, globalIgnores } from 'eslint/config';
import nextVitals from 'eslint-config-next/core-web-vitals';
import nextTs from 'eslint-config-next/typescript';
import prettier from 'eslint-config-prettier';

const useEffectMessage =
  'useEffect는 사용할 수 없습니다. 초기 데이터는 Server Component나 쿼리 훅으로, 사용자 동작은 이벤트 핸들러로, 외부 연결은 구독이나 콜백 ref로 처리하세요.';
const useEffectSelectors = [
  "ImportDeclaration[source.value='react'] > ImportSpecifier:matches([imported.name='useEffect'], [imported.value='useEffect'])",
  "ExportNamedDeclaration[source.value='react'] > ExportSpecifier:matches([local.name='useEffect'], [local.value='useEffect'])",
  "CallExpression[callee.name='useEffect']",
  "MemberExpression:matches([property.name='useEffect'], [computed=true][property.value='useEffect'])",
  "ObjectPattern > Property:matches([key.name='useEffect'], [key.value='useEffect'])",
];

const eslintConfig = defineConfig([
  ...nextVitals,
  ...nextTs,
  prettier,
  { rules: { '@typescript-eslint/no-explicit-any': 'error' } },
  {
    files: ['**/*.{js,jsx,ts,tsx,mjs,cjs,mts,cts}'],
    rules: {
      'react-hooks/rules-of-hooks': 'error',
      'react-hooks/exhaustive-deps': 'error',
      'no-restricted-syntax': [
        'error',
        ...useEffectSelectors.map((selector) => ({
          selector,
          message: useEffectMessage,
        })),
      ],
    },
  },
  // Override default ignores of eslint-config-next.
  globalIgnores([
    // Default ignores of eslint-config-next:
    '.next/**',
    'out/**',
    'build/**',
    'next-env.d.ts',
    // Playwright 산출물(trace의 번들 JS)
    'test-results/**',
    'playwright-report/**',
  ]),
]);

export default eslintConfig;
