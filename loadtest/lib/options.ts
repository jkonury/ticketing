// 두 시나리오가 공유하는 k6 옵션. thresholds는 잠정 기준이며 abortOnFail을 쓰지 않는다(미달 항목도 기록하기 위해서다).
export const thresholds = {
  'http_req_duration{name:booking_create}': ['p(95)<300'],
  unexpected_errors: ['rate<0.01'],
  sse_connect_success: ['rate>0.999'],
};

// url 태그를 뺀다. 회차·예매 ID·SSE 티켓이 URL에 들어가 시계열이 늘어나는 것을 막고, 요청 구분은 name 태그로 한다.
export const systemTags = [
  'proto',
  'subproto',
  'status',
  'method',
  'name',
  'group',
  'check',
  'error',
  'error_code',
  'tls_version',
  'scenario',
  'service',
  'expected_response',
];

export function vus(defaultValue: number): number {
  const value = Number(__ENV.VUS ?? defaultValue);
  if (!Number.isInteger(value) || value < 1) {
    throw new Error(`VUS는 1 이상의 정수여야 합니다: ${__ENV.VUS}`);
  }
  return value;
}
