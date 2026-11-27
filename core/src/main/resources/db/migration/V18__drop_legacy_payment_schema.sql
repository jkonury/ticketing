DROP TABLE payment.reconciliation_mismatch,
    payment.pg_transaction_snapshot,
    payment.webhook_event,
    payment.payment RESTRICT;

DROP SCHEMA payment RESTRICT;
