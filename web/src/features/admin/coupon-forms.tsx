'use client';

import { useActionState, useState } from 'react';
import { Button } from '@/components/ui/button';
import { Field } from '@/components/ui/field';
import { Input, Select, Textarea } from '@/components/ui/input';
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
    <form action={action} className="grid max-w-160 gap-4">
      {editing && <input type="hidden" name="id" value={values.id} />}
      <Field label="이름">
        {(c) => (
          <Input {...c} name="name" defaultValue={values.name} required />
        )}
      </Field>
      <Field label="설명">
        {(c) => (
          <Textarea
            {...c}
            name="description"
            defaultValue={values.description}
          />
        )}
      </Field>
      <Field label="종료일(KST)">
        {(c) => (
          <Input
            {...c}
            type="datetime-local"
            name="validUntil"
            defaultValue={values.validUntil}
            required
          />
        )}
      </Field>
      {!locked && (
        <>
          <Field label="할인 유형">
            {(c) => (
              <Select
                {...c}
                name="discountType"
                value={type}
                onChange={(e) => setType(e.target.value as 'FIXED' | 'RATE')}
              >
                <option value="FIXED">정액(원)</option>
                <option value="RATE">정률(%)</option>
              </Select>
            )}
          </Field>
          <Field label={type === 'RATE' ? '할인율(%)' : '할인 금액(원)'}>
            {(c) => (
              <Input
                {...c}
                name="discountValue"
                inputMode="numeric"
                defaultValue={values.discountValue}
                required
              />
            )}
          </Field>
          {type === 'RATE' && (
            <Field label="최대 할인 금액(원)">
              {(c) => (
                <Input
                  {...c}
                  name="maxDiscount"
                  inputMode="numeric"
                  defaultValue={text(values.maxDiscount)}
                />
              )}
            </Field>
          )}
          <Field label="최소 주문 금액(원)">
            {(c) => (
              <Input
                {...c}
                name="minOrderAmount"
                inputMode="numeric"
                defaultValue={values.minOrderAmount}
                required
              />
            )}
          </Field>
          <Field label="공연 ID(비우면 전체)">
            {(c) => (
              <Input
                {...c}
                name="performanceId"
                inputMode="numeric"
                defaultValue={text(values.performanceId)}
              />
            )}
          </Field>
          <Field label="시작일(KST)">
            {(c) => (
              <Input
                {...c}
                type="datetime-local"
                name="validFrom"
                defaultValue={values.validFrom}
                required
              />
            )}
          </Field>
          <Field label="발급 방식">
            {(c) => (
              <Select
                {...c}
                name="issueMode"
                value={mode}
                onChange={(e) =>
                  setMode(e.target.value as 'UNLIMITED' | 'LIMITED' | 'DIRECT')
                }
              >
                <option value="UNLIMITED">무제한</option>
                <option value="LIMITED">수량 한정</option>
                <option value="DIRECT">관리자 직접 지급</option>
              </Select>
            )}
          </Field>
          <Field label="총 수량(한정일 때)">
            {(c) => (
              <Input
                {...c}
                name="totalQuantity"
                inputMode="numeric"
                defaultValue={text(values.totalQuantity)}
              />
            )}
          </Field>
        </>
      )}
      <Button type="submit" disabled={pending} className="justify-self-start">
        {editing ? '수정' : '생성'}
      </Button>
      <FormMessage message={state.message} />
    </form>
  );
}

export function StopForm({ id }: { id: number }) {
  const [state, action, pending] = useActionState(stopCouponAction, initial);
  return (
    <form action={action} className="grid max-w-160 gap-3">
      <input type="hidden" name="id" value={id} />
      <Button type="submit" disabled={pending} className="justify-self-start">
        발급 중지
      </Button>
      <FormMessage message={state.message} />
    </form>
  );
}

export function GrantForm({ id }: { id: number }) {
  const [state, action, pending] = useActionState(grantCouponsAction, initial);
  return (
    <form action={action} className="grid max-w-160 gap-3">
      <input type="hidden" name="id" value={id} />
      <Field label="회원 ID(쉼표·줄바꿈 구분, 최대 100명)">
        {(c) => <Textarea {...c} name="memberIds" required />}
      </Field>
      <Button type="submit" disabled={pending} className="justify-self-start">
        지급
      </Button>
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
    <form action={action} className="grid max-w-160 gap-3">
      <input type="hidden" name="issuedCouponId" value={issuedCouponId} />
      <input type="hidden" name="definitionId" value={definitionId} />
      <Button type="submit" disabled={pending} className="justify-self-start">
        회수
      </Button>
      <FormMessage message={state.message} />
    </form>
  );
}
