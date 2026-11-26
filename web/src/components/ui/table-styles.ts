/** 표 요소에 붙이는 유틸리티. 모바일에서는 표 자체가 가로로 스크롤된다. */
export const tableClass =
  'my-5 block w-full overflow-x-auto border-collapse bg-surface text-body';
export const thClass =
  'min-w-30 border-b border-line p-3.5 text-left text-caption font-normal text-fg-muted';
export const tdClass = 'min-w-30 border-b border-line p-3.5 text-left';
/** 모바일에서 숨기는 보조 열. th·td 클래스 뒤에 붙인다. */
export const secondaryCol = 'hidden md:table-cell';
