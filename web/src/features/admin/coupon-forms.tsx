'use client';

import { useActionState, useState } from 'react';
import { FormMessage } from '@/components/form-message';
import {
  createCouponAction,
  grantCouponsAction,
  revokeCouponAction,
  stopCouponAction,
  updateCouponAction,
  type CouponActionState,
} from './coupon-actions';

const initial: CouponActionState = { message: '' };

export type CouponFormValues = {
  id?: number;
  name: string;
  description: string;
  discountType: 'FIXED' | 'RATE';
  discountValue: number;
  maxDiscount: number | null;
  minOrderAmount: number;
  performanceId: number | null;
  validFrom: string;
  validUntil: string;
  issueMode: 'UNLIMITED' | 'LIMITED' | 'DIRECT';
  totalQuantity: number | null;
};

const blank: CouponFormValues = {
  name: '',
  description: '',
  discountType: 'FIXED',
  discountValue: 0,
  maxDiscount: null,
  minOrderAmount: 0,
  performanceId: null,
  validFrom: '',
  validUntil: '',
  issueMode: 'UNLIMITED',
  totalQuantity: null,
};

/** locked이면 발급이 시작된 쿠폰이므로 이름·설명·종료일만 보낸다. */
export function CouponForm({
  values = blank,
  locked = false,
}: {
  values?: CouponFormValues;
  locked?: boolean;
}) {
  const editing = values.id !== undefined;
  const [state, action, pending] = useActionState(
    editing ? updateCouponAction : createCouponAction,
    initial,
  );
  const [type, setType] = useState(values.discountType);
  const [mode, setMode] = useState(values.issueMode);
  const text = (v: number | null) => (v === null ? '' : String(v));
  return (
    <form action={action}>
      {editing && <input type="hidden" name="id" value={values.id} />}
      <label>
        이름 <input name="name" defaultValue={values.name} required />
      </label>
      <label>
        설명 <textarea name="description" defaultValue={values.description} />
      </label>
      <label>
        종료일(KST){' '}
        <input
          type="datetime-local"
          name="validUntil"
          defaultValue={values.validUntil}
          required
        />
      </label>
      {!locked && (
        <>
          <label>
            할인 유형{' '}
            <select
              name="discountType"
              value={type}
              onChange={(e) => setType(e.target.value as 'FIXED' | 'RATE')}
            >
              <option value="FIXED">정액(원)</option>
              <option value="RATE">정률(%)</option>
            </select>
          </label>
          <label>
            {type === 'RATE' ? '할인율(%)' : '할인 금액(원)'}{' '}
            <input
              name="discountValue"
              inputMode="numeric"
              defaultValue={values.discountValue}
              required
            />
          </label>
          {type === 'RATE' && (
            <label>
              최대 할인 금액(원){' '}
              <input
                name="maxDiscount"
                inputMode="numeric"
                defaultValue={text(values.maxDiscount)}
              />
            </label>
          )}
          <label>
            최소 주문 금액(원){' '}
            <input
              name="minOrderAmount"
              inputMode="numeric"
              defaultValue={values.minOrderAmount}
              required
            />
          </label>
          <label>
            공연 ID(비우면 전체){' '}
            <input
              name="performanceId"
              inputMode="numeric"
              defaultValue={text(values.performanceId)}
            />
          </label>
          <label>
            시작일(KST){' '}
            <input
              type="datetime-local"
              name="validFrom"
              defaultValue={values.validFrom}
              required
            />
          </label>
          <label>
            발급 방식{' '}
            <select
              name="issueMode"
              value={mode}
              onChange={(e) =>
                setMode(e.target.value as 'UNLIMITED' | 'LIMITED' | 'DIRECT')
              }
            >
              <option value="UNLIMITED">무제한</option>
              <option value="LIMITED">수량 한정</option>
              <option value="DIRECT">관리자 직접 지급</option>
            </select>
          </label>
          <label>
            총 수량(한정일 때){' '}
            <input
              name="totalQuantity"
              inputMode="numeric"
              defaultValue={text(values.totalQuantity)}
            />
          </label>
        </>
      )}
      <button disabled={pending}>{editing ? '수정' : '생성'}</button>
      <FormMessage message={state.message} />
    </form>
  );
}

export function StopForm({ id }: { id: number }) {
  const [state, action, pending] = useActionState(stopCouponAction, initial);
  return (
    <form action={action}>
      <input type="hidden" name="id" value={id} />
      <button disabled={pending}>발급 중지</button>
      <FormMessage message={state.message} />
    </form>
  );
}

export function GrantForm({ id }: { id: number }) {
  const [state, action, pending] = useActionState(grantCouponsAction, initial);
  return (
    <form action={action}>
      <input type="hidden" name="id" value={id} />
      <label>
        회원 ID(쉼표·줄바꿈 구분, 최대 100명){' '}
        <textarea name="memberIds" required />
      </label>
      <button disabled={pending}>지급</button>
      <FormMessage message={state.message} />
    </form>
  );
}

export function RevokeForm({
  issuedCouponId,
  definitionId,
}: {
  issuedCouponId: number;
  definitionId: number;
}) {
  const [state, action, pending] = useActionState(revokeCouponAction, initial);
  return (
    <form action={action}>
      <input type="hidden" name="issuedCouponId" value={issuedCouponId} />
      <input type="hidden" name="definitionId" value={definitionId} />
      <button disabled={pending}>회수</button>
      <FormMessage message={state.message} />
    </form>
  );
}
