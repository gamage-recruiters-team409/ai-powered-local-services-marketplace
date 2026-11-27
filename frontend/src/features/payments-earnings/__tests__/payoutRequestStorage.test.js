// @vitest-environment jsdom


import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";

import {
  loadPendingPayoutRequest,
  savePendingPayoutRequest,
  clearPendingPayoutRequest,
} from "../utils/payoutRequestStorage";

/**
 * WP07 - Payout Request Persistence Regression Tests
 *
 * Verifies account isolation, request recovery, payload integrity,
 * safe cleanup and browser storage failure handling.
 */

const PROVIDER_ID = "provider-001";
const OTHER_PROVIDER_ID = "provider-002";

const STORAGE_KEY =
  "localserve.wp07.payout.pending.v1:provider-001";

const PAYLOAD = {
  requestKey: "payout-request-001",
  amount: 7500.25,
};

describe("payoutRequestStorage", () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  afterEach(() => {
    vi.restoreAllMocks();
    window.localStorage.clear();
  });

  // Request persistence and recovery.

  it("returns null when no pending request exists", () => {
    expect(
      loadPendingPayoutRequest(PROVIDER_ID),
    ).toBeNull();
  });

  it("persists the original request key and amount", () => {
    savePendingPayoutRequest(PROVIDER_ID, PAYLOAD);

    expect(
      loadPendingPayoutRequest(PROVIDER_ID),
    ).toEqual(PAYLOAD);
  });

  it("restores a previously persisted request from browser storage", () => {
    window.localStorage.setItem(
      STORAGE_KEY,
      JSON.stringify(PAYLOAD),
    );

    expect(
      loadPendingPayoutRequest(PROVIDER_ID),
    ).toEqual(PAYLOAD);
  });

  it("allows an identical request to be persisted again", () => {
    savePendingPayoutRequest(PROVIDER_ID, PAYLOAD);

    expect(() =>
      savePendingPayoutRequest(PROVIDER_ID, {
        ...PAYLOAD,
      }),
    ).not.toThrow();

    expect(
      loadPendingPayoutRequest(PROVIDER_ID),
    ).toEqual(PAYLOAD);
  });

  // Account isolation and identity validation.

  it("isolates unresolved requests between provider accounts", () => {
    const otherPayload = {
      requestKey: "payout-request-002",
      amount: 9000,
    };

    savePendingPayoutRequest(PROVIDER_ID, PAYLOAD);
    savePendingPayoutRequest(
      OTHER_PROVIDER_ID,
      otherPayload,
    );

    expect(
      loadPendingPayoutRequest(PROVIDER_ID),
    ).toEqual(PAYLOAD);

    expect(
      loadPendingPayoutRequest(OTHER_PROVIDER_ID),
    ).toEqual(otherPayload);
  });

  it("does not expose another provider's pending request", () => {
    savePendingPayoutRequest(PROVIDER_ID, PAYLOAD);

    expect(
      loadPendingPayoutRequest(OTHER_PROVIDER_ID),
    ).toBeNull();
  });

  it.each([null, undefined, "", "   ", 123])(
    "rejects an invalid provider identity: %s",
    (providerId) => {
      expect(() =>
        loadPendingPayoutRequest(providerId),
      ).toThrow("Authenticated provider identity is required.");
    },
  );

  // Idempotency and unresolved request protection.

  it("rejects replacement with a different request key", () => {
    savePendingPayoutRequest(PROVIDER_ID, PAYLOAD);

    expect(() =>
      savePendingPayoutRequest(PROVIDER_ID, {
        ...PAYLOAD,
        requestKey: "different-request-key",
      }),
    ).toThrow("An unresolved payout request already exists.");

    expect(
      loadPendingPayoutRequest(PROVIDER_ID),
    ).toEqual(PAYLOAD);
  });

  it("rejects replacement with a different amount", () => {
    savePendingPayoutRequest(PROVIDER_ID, PAYLOAD);

    expect(() =>
      savePendingPayoutRequest(PROVIDER_ID, {
        ...PAYLOAD,
        amount: 8000,
      }),
    ).toThrow("An unresolved payout request already exists.");

    expect(
      loadPendingPayoutRequest(PROVIDER_ID),
    ).toEqual(PAYLOAD);
  });

  // Financial payload validation.

  it.each([
    0,
    -5000,
    4999.99,
    5000.001,
    1000000000000,
    NaN,
    Infinity,
    "7500.25",
    null,
    undefined,
  ])("rejects an invalid payout amount: %s", (amount) => {
    expect(() =>
      savePendingPayoutRequest(PROVIDER_ID, {
        ...PAYLOAD,
        amount,
      }),
    ).toThrow("Invalid payout request recovery data.");

    expect(
      loadPendingPayoutRequest(PROVIDER_ID),
    ).toBeNull();
  });

  it.each([
    "",
    "   ",
    null,
    undefined,
    123,
    "x".repeat(129),
  ])("rejects an invalid request key: %s", (requestKey) => {
    expect(() =>
      savePendingPayoutRequest(PROVIDER_ID, {
        ...PAYLOAD,
        requestKey,
      }),
    ).toThrow("Invalid payout request recovery data.");
  });

  it("accepts the minimum supported payout amount", () => {
    const payload = {
      ...PAYLOAD,
      amount: 5000,
    };

    savePendingPayoutRequest(PROVIDER_ID, payload);

    expect(
      loadPendingPayoutRequest(PROVIDER_ID),
    ).toEqual(payload);
  });

  it("accepts the maximum supported payout amount", () => {
    const payload = {
      ...PAYLOAD,
      amount: 999999999999.99,
    };

    savePendingPayoutRequest(PROVIDER_ID, payload);

    expect(
      loadPendingPayoutRequest(PROVIDER_ID),
    ).toEqual(payload);
  });

  it("persists only the fields required for request recovery", () => {
    savePendingPayoutRequest(PROVIDER_ID, {
      ...PAYLOAD,
      extraField: "not-required",
    });

    const stored = JSON.parse(
      window.localStorage.getItem(STORAGE_KEY),
    );

    expect(stored).toEqual(PAYLOAD);
  });

  // Corrupted recovery data must not be silently discarded.

  it("rejects malformed JSON without deleting recovery data", () => {
    const corruptedValue = "{invalid-json";

    window.localStorage.setItem(
      STORAGE_KEY,
      corruptedValue,
    );

    expect(() =>
      loadPendingPayoutRequest(PROVIDER_ID),
    ).toThrow("Stored payout recovery data is corrupted.");

    expect(
      window.localStorage.getItem(STORAGE_KEY),
    ).toBe(corruptedValue);
  });

  it("rejects invalid stored payloads without deleting them", () => {
    const invalidValue = JSON.stringify({
      requestKey: PAYLOAD.requestKey,
      amount: 100,
    });

    window.localStorage.setItem(
      STORAGE_KEY,
      invalidValue,
    );

    expect(() =>
      loadPendingPayoutRequest(PROVIDER_ID),
    ).toThrow("Stored payout recovery data is invalid.");

    expect(
      window.localStorage.getItem(STORAGE_KEY),
    ).toBe(invalidValue);
  });

  it("prevents a new request from replacing corrupted recovery data", () => {
    window.localStorage.setItem(
      STORAGE_KEY,
      "{invalid-json",
    );

    expect(() =>
      savePendingPayoutRequest(PROVIDER_ID, PAYLOAD),
    ).toThrow("Stored payout recovery data is corrupted.");

    expect(
      window.localStorage.getItem(STORAGE_KEY),
    ).toBe("{invalid-json");
  });

  // Safe request cleanup.

  it("clears a resolved request when the request key matches", () => {
    savePendingPayoutRequest(PROVIDER_ID, PAYLOAD);

    expect(
      clearPendingPayoutRequest(
        PROVIDER_ID,
        PAYLOAD.requestKey,
      ),
    ).toBe(true);

    expect(
      loadPendingPayoutRequest(PROVIDER_ID),
    ).toBeNull();
  });

  it("preserves recovery data when the cleanup key differs", () => {
    savePendingPayoutRequest(PROVIDER_ID, PAYLOAD);

    expect(
      clearPendingPayoutRequest(
        PROVIDER_ID,
        "different-request-key",
      ),
    ).toBe(false);

    expect(
      loadPendingPayoutRequest(PROVIDER_ID),
    ).toEqual(PAYLOAD);
  });

  it("returns false when no recovery record exists", () => {
    expect(
      clearPendingPayoutRequest(
        PROVIDER_ID,
        PAYLOAD.requestKey,
      ),
    ).toBe(false);
  });

  it("does not clear another provider's recovery record", () => {
    savePendingPayoutRequest(PROVIDER_ID, PAYLOAD);

    expect(
      clearPendingPayoutRequest(
        OTHER_PROVIDER_ID,
        PAYLOAD.requestKey,
      ),
    ).toBe(false);

    expect(
      loadPendingPayoutRequest(PROVIDER_ID),
    ).toEqual(PAYLOAD);
  });

  // Browser storage failures must propagate to the caller.

  it("propagates browser storage read failures", () => {
    vi.spyOn(Storage.prototype, "getItem")
      .mockImplementation(() => {
        throw new Error("Storage read denied");
      });

    expect(() =>
      loadPendingPayoutRequest(PROVIDER_ID),
    ).toThrow("Storage read denied");
  });

  it("propagates browser storage write failures", () => {
    vi.spyOn(Storage.prototype, "setItem")
      .mockImplementation(() => {
        throw new Error("Storage quota exceeded");
      });

    expect(() =>
      savePendingPayoutRequest(PROVIDER_ID, PAYLOAD),
    ).toThrow("Storage quota exceeded");
  });

  it("rejects a write that cannot be verified", () => {
    vi.spyOn(Storage.prototype, "setItem")
      .mockImplementation(() => {});

    expect(() =>
      savePendingPayoutRequest(PROVIDER_ID, PAYLOAD),
    ).toThrow("Unable to verify payout request recovery data.");
  });

  it("propagates cleanup failures and preserves recovery data", () => {
    savePendingPayoutRequest(PROVIDER_ID, PAYLOAD);

    vi.spyOn(Storage.prototype, "removeItem")
      .mockImplementation(() => {
        throw new Error("Storage removal denied");
      });

    expect(() =>
      clearPendingPayoutRequest(
        PROVIDER_ID,
        PAYLOAD.requestKey,
      ),
    ).toThrow("Storage removal denied");

    expect(
      loadPendingPayoutRequest(PROVIDER_ID),
    ).toEqual(PAYLOAD);
  });
});
