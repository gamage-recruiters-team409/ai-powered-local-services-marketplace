# WP07 - Interrupted Refund Recovery

## Purpose

Recover interrupted refund operations without submitting duplicate
external refunds or prematurely releasing financial reservations.

## Recovery Eligibility

- REQUIRES_RECONCILIATION operations.
- PROCESSING operations older than the 15-minute stale threshold.
- Recently claimed PROCESSING operations are excluded from batch recovery.
- Terminal operations are not submitted again.

## Financial Safety

- Recovery uses read-only gateway lookups.
- Missing or uncertain gateway evidence retains reservations.
- Verified terminal outcomes are processed through transactional
  refund finalization.
- Repeated recovery must not duplicate refunds or ledger settlement.
- Gateway communication occurs outside MongoDB transactions.

## Controlled Maintenance Execution

The recovery runner is disabled by default.

Configuration:

- app.payment.refund.reconciliation.enabled=true
- app.payment.refund.reconciliation.batch-size=25

Equivalent environment variables:

- APP_PAYMENT_REFUND_RECONCILIATION_ENABLED=true
- APP_PAYMENT_REFUND_RECONCILIATION_BATCH_SIZE=25

The permitted batch size is 1 to 100.

Only authorized operators should enable recovery against the intended
database and Stripe account.

The runner executes one bounded batch per enabled application startup.
Review the resulting succeeded, failed, unresolved and error counts.

Disable the runner after the maintenance execution.

## Targeted Operation Recovery

The explicitly enabled maintenance runner supports recovery
of a specific persisted refund operation.

Application property:

- app.payment.refund.reconciliation.operation-id=<operation-id>

Equivalent environment variable:

- APP_PAYMENT_REFUND_RECONCILIATION_OPERATION_ID=<operation-id>

Recovery must still be explicitly enabled.

Use the persisted refund operation ID, not the payment ID
or Stripe refund reference.

A configured operation ID selects targeted recovery.
An absent or blank operation ID preserves batch recovery.

Existing stale PROCESSING checks, verified gateway lookup
and transactional finalization remain applicable.

No automatic retry, fallback batch or additional refund
submission is performed.

### Batch Ordering Limitation

Older unresolved or erroneous operations may repeatedly
occupy batch capacity.

Authorized operators can use targeted recovery to process
other eligible operations without bypassing safety checks.

Automatic batch fairness is not implemented.

Remove the operation ID setting and disable the runner
after the controlled recovery attempt.

### Additional Verification

- Refund reconciliation runner tests: 14 passed.
- Refund reconciliation service tests: 10 passed.
- Clean test execution: 24 passed.
- Failures, errors and skipped tests: zero.

Actual MongoDB transaction integration, live Stripe
verification and operational policy approval remain pending.

## Unresolved Operations

Do not manually clear financial reservations or blindly resubmit refunds.

Investigate unresolved operations using verified gateway records.
An empty gateway lookup does not prove that a refund was never submitted.

## Verification

- Reconciliation service and maintenance runner: 18 tests passed.
- Refund MongoDB integration suite: 40 tests discovered.
- Three additional integration scenarios cover interrupted processing,
  repeated successful recovery and fresh processing exclusion.
- Integration compilation and Docker skip compatibility verified.

## Limitations

- Actual MongoDB integration execution remains pending because Docker
  is unavailable in the current development environment.
- Live Stripe recovery has not been verified.
- The 15-minute stale threshold and startup-based maintenance policy
  require Team Lead review.
- An enabled runner may cause application startup failure if recovery
  infrastructure is unavailable.
- No automatic scheduler or public recovery endpoint is introduced.