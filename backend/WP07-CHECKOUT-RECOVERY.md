# WP07 - Interrupted Stripe Checkout Recovery

## Purpose

Recover interrupted checkout attempts without blindly creating
another payable Stripe Checkout Session.

## Reservation States

| Submission marker | Meaning |
| --- | --- |
| false | Gateway submission has not been authorized |
| true | Gateway submission may have occurred |
| null | Legacy or absent reservation state |

## Failure Handling

- Definitive pre-submission failures release the matching reservation.
- Ambiguous gateway failures retain the reservation.
- Session persistence failures retain the reservation.
- Explicitly unsubmitted reservations may be released after five minutes
  through an atomic conditional update.
- Unknown or potentially submitted reservations are never released
  solely because a gateway lookup returns no session.

## Controlled Recovery

Recovery is disabled by default.

Only an authorized operator with deployment configuration access
may enable recovery in a controlled maintenance environment.

Required configuration:

- app.payment.stripe.checkout-recovery.enabled=true
- app.payment.stripe.checkout-recovery.payment-id=<payment-id>

Equivalent environment variables:

- APP_PAYMENT_STRIPE_CHECKOUT_RECOVERY_ENABLED=true
- APP_PAYMENT_STRIPE_CHECKOUT_RECOVERY_PAYMENT_ID=<payment-id>

Use the correct database and Stripe account configuration.

The application runner performs one recovery attempt for the
explicitly configured payment during application startup.

Remove both recovery settings after the attempt.

## Recovery Outcomes

- A uniquely matched and validated Stripe session is attached
  using a conditional database update.
- An existing gateway reference is never overwritten.
- A stale, explicitly unsubmitted reservation may be released.
- An unresolved or ambiguous lookup retains the reservation.
- Gateway failures do not trigger automatic session creation.
- Payment capture remains the responsibility of verified webhook
  processing.

Inspect the persisted payment state after every recovery attempt.

A false recovery result does not prove that a reservation was released.

## Safety Restrictions

- Do not manually clear uncertain reservation tokens.
- Do not blindly retry Stripe session creation.
- Do not manually mark payments as captured.
- Do not treat an empty Stripe lookup as proof of non-submission.
- Do not enable the recovery runner during normal application startup.
- Do not expose Stripe credentials in logs or evidence.

## Verification

Targeted checkout regression completed on 25 November 2026:

- Tests: 76
- Failures: 0
- Errors: 0
- Skipped: 0
- Result: BUILD SUCCESS

## Remaining Limitations

- Live Stripe recovery has not been verified.
- Actual MongoDB concurrency verification remains pending.
- A crash after submission authorization but before the external
  request may leave an uncertain reservation requiring investigation.
- Returning a recovered open checkout URL to the customer is not
  implemented.
- Recovery lookup is bounded and may require further investigation
  when the search cannot establish a unique result.
- An enabled recovery runner failure may prevent application startup.
- The five-minute stale reservation policy and operational runner
  approach remain subject to Team Lead review.