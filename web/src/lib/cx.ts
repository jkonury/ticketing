// 조건부 className 결합. 충돌하는 유틸리티를 병합하지 않으므로, 프리미티브에 넘기는
// className은 여백·배치 같은 바깥 레이아웃에만 쓴다.
export function cx(...parts: Array<string | false | null | undefined>): string {
  return parts.filter(Boolean).join(' ');
}
