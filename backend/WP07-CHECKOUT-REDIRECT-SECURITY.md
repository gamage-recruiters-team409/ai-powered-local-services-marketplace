# WP07 - Stripe Checkout Redirect Security

## Purpose

Checkout success and cancellation destinations are restricted
to explicitly approved server-side configuration.

Redirect validation occurs before payment lookup, financial
reservation, submission authorization or Stripe session creation.

## Configuration

Application properties:

- app.payment.stripe.checkout.success-url
- app.payment.stripe.checkout.cancel-url

Equivalent environment variables:

- STRIPE_CHECKOUT_SUCCESS_URL
- STRIPE_CHECKOUT_CANCEL_URL

Both values are empty by default. Checkout initiation fails
closed until both trusted destinations are configured.

## Security Policy

- Requested URLs must exactly match their configured destinations.
- HTTPS is required for non-local destinations.
- Explicitly configured HTTP localhost and 127.0.0.1 URLs
  are permitted for local development.
- Relative, malformed and opaque URLs are rejected.
- User information, fragments and invalid ports are rejected.
- Unapproved hosts, paths and query modifications are rejected.
- Success and cancellation destinations cannot be substituted
  for each other unless configured identically.
- Invalid requests do not create financial reservations or
  external Checkout sessions.

## Deployment Requirements

Production redirect destinations must be approved before
public Checkout integration.

Configured frontend destinations must not implement unsafe
open redirects.

Local HTTP exceptions are intended for development only.
Production deployments must configure HTTPS destinations.

No production frontend domain or route has been assumed.

## Verification

- Checkout initiation tests: 20 passed.
- Redirect configuration policy tests: 29 passed.
- Shared application startup tests: 2 passed.
- Total: 51 passed, zero failures, errors or skipped tests.

## Remaining Limitations

- Production redirect destinations require approval.
- Stripe CHECKOUT_SESSION_ID template compatibility has not
  been implemented or verified.
- Live Stripe Checkout verification remains pending.
- Public Checkout exposure remains dependent on the trusted
  WP05 booking payment quote integration.

This correction does not introduce a public Checkout endpoint
or modify existing booking and dispute integration contracts.