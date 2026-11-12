const bookingLabels: Record<string, string> = {
  PENDING: '결제 대기',
  CONFIRMED: '예매 확정',
  EXPIRED: '만료',
  CANCELLED: '취소',
};
const paymentLabels: Record<string, string> = {
  REQUESTED: '결제 요청',
  SUCCEEDED: '결제 완료',
  FAILED: '결제 실패',
  REFUND_REQUESTED: '환불 요청',
  REFUNDED: '환불 완료',
};
export function bookingLabel(status: string) {
  return bookingLabels[status] ?? status;
}
export function paymentLabel(status: string) {
  return paymentLabels[status] ?? status;
}
