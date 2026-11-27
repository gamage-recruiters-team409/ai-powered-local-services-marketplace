// @vitest-environment jsdom

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  act,
  cleanup,
  render,
  screen,
  waitFor,
  fireEvent,
} from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

import ProviderEarningsPage from "../pages/ProviderEarningsPage";

import {
  getProviderEarningsSummary,
  getProviderItemizedEarnings,
  getProviderPayoutHistory,
  getProviderPayoutDetails,
  requestProviderPayout,
} from "../services/paymentsEarningsApi";

import {
  loadPendingPayoutRequest,
  savePendingPayoutRequest,
  clearPendingPayoutRequest,
  withPayoutSubmissionLock,
  loadPayoutSubmissionRevision,
  advancePayoutSubmissionRevision,
} from "../utils/payoutRequestStorage";

import { useAuth } from "../../auth/hooks/useAuth";

/**
 * WP07 - Provider Earnings and Payout component tests.
 *
 * Tests financial UI behaviour using mocked API responses while
 * retaining the actual shared components and recovery storage.
 */

vi.mock("../services/paymentsEarningsApi", () => ({
  getProviderEarningsSummary: vi.fn(),
  getProviderItemizedEarnings: vi.fn(),
  getProviderPayoutHistory: vi.fn(),
  getProviderPayoutDetails: vi.fn(),
  requestProviderPayout: vi.fn(),
}));

vi.mock("../../auth/hooks/useAuth", () => ({
  useAuth: vi.fn(),
}));

const PROVIDER_ID = "provider-test-001";

const SUMMARY = {
  currency: "LKR",
  pending: 2000,
  available: 15000,
  held: 1000,
  paidOut: 5000,
};

const PAYOUT = {
  id: "payout-test-001",
  requestKey: "request-test-001",
  currency: "LKR",
  amount: 7500,
  status: "PENDING",
  createdAt: "2026-11-25T10:00:00Z",
  updatedAt: "2026-11-25T10:00:00Z",
  paidAt: null,
};

const EMPTY_HISTORY = {
  content: [],
  page: 0,
  size: 10,
  totalElements: 0,
  totalPages: 0,
};

const PAYOUT_HISTORY = {
  content: [PAYOUT],
  page: 0,
  size: 10,
  totalElements: 1,
  totalPages: 1,
};

/** Renders the real provider page inside a router context. */
function renderPage() {
  return render(
    <MemoryRouter>
      <ProviderEarningsPage />
    </MemoryRouter>,
  );
}

/** Waits for the summary and opens the payout request dialog. */
async function waitForPage() {
  await screen.findByText("Available Balance", { exact: true });
  openRequestDialog();
}

/** Opens the actual shared payout request modal. */
function openRequestDialog() {
  fireEvent.click(
    screen.getByRole("button", {
      name: /^request payout$/i,
    }),
  );
}

/** Confirms the amount without bypassing the shared checkbox. */
function confirmRequest() {
  const checkbox = screen.getByRole("checkbox", {
    name: /I confirm that the payout amount is correct/i,
  });

  if (!checkbox.checked) {
    fireEvent.click(checkbox);
  }
}

/** Retrieves the actual payout amount input. */
function getAmountInput() {
  return screen.getByRole("textbox", {
    name: /amount/i,
  });
}

/** Enters a payout amount and submits the request. */
function submitAmount(amount) {
  fireEvent.change(getAmountInput(), {
    target: { value: amount },
  });

  confirmRequest();

  fireEvent.click(
    screen.getByRole("button", {
      name: /^submit payout request$/i,
    }),
  );
}

/** Creates an error matching the shared API client's contract. */
function createApiError(message, status, code) {
  return Object.assign(new Error(message), {
    status,
    code,
  });
}

/**
 * Simulates provider-specific exclusive Web Locks in jsdom.
 * Competing operations receive no lock and are never queued.
 */
function installWebLocksMock() {
  const activeLocks = new Set();

  const request = vi.fn(async (name, options, callback) => {
    expect(options).toEqual({
      mode: "exclusive",
      ifAvailable: true,
    });

    if (activeLocks.has(name)) {
      return callback(null);
    }

    activeLocks.add(name);

    try {
      return await callback({
        name,
        mode: "exclusive",
      });
    } finally {
      activeLocks.delete(name);
    }
  });

  vi.stubGlobal("navigator", Object.create(window.navigator, {
    locks: {
      configurable: true,
      value: { request },
    },
  }));

  return request;
}

beforeEach(() => {
  vi.resetAllMocks();

  getProviderItemizedEarnings.mockResolvedValue({
    content: [],
    page: 0,
    size: 5,
    totalElements: 0,
    totalPages: 0,
  });
  window.localStorage.clear();
  installWebLocksMock();

  useAuth.mockReturnValue({
    user: {
      id: PROVIDER_ID,
      role: "PROVIDER",
      name: "Test Provider",
    },
    isLoading: false,
    isAuthenticated: true,
  });

  getProviderEarningsSummary.mockResolvedValue(SUMMARY);
  getProviderPayoutHistory.mockResolvedValue(EMPTY_HISTORY);
  getProviderPayoutDetails.mockResolvedValue(PAYOUT);
  requestProviderPayout.mockResolvedValue(PAYOUT);
});

afterEach(() => {
  cleanup();
  window.localStorage.clear();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe("ProviderEarningsPage", () => {
  it("loads the authenticated provider earnings and payout history", async () => {
    renderPage();

    await waitForPage();

    expect(
      screen.getByRole("heading", {
        name: /^earnings overview$/i,
      }),
    ).toBeTruthy();

    expect(getProviderEarningsSummary).toHaveBeenCalled();
    expect(getProviderPayoutHistory).toHaveBeenCalledWith(0, 10);
  });

  it("rejects access when the authenticated user is not a provider", () => {
    useAuth.mockReturnValue({
      user: {
        id: "customer-test-001",
        role: "CUSTOMER",
      },
      isLoading: false,
      isAuthenticated: true,
    });

    renderPage();

    expect(getProviderEarningsSummary).not.toHaveBeenCalled();
    expect(getProviderPayoutHistory).not.toHaveBeenCalled();
    expect(requestProviderPayout).not.toHaveBeenCalled();
  });

  it("does not load financial information without a provider identity", () => {
    useAuth.mockReturnValue({
      user: null,
      isLoading: false,
      isAuthenticated: false,
    });

    renderPage();

    expect(getProviderEarningsSummary).not.toHaveBeenCalled();
    expect(getProviderPayoutHistory).not.toHaveBeenCalled();
  });

  it.each([
    ["4999", /^The minimum payout amount is LKR 5,000\.00\.$/i],
    ["15001", /exceeds your available earnings/i],
    ["5000.123", /valid amount/i],
    ["-5000", /valid amount/i],
    ["invalid", /valid amount/i],
  ])(
    "rejects invalid payout amount %s without calling the API",
    async (amount, expectedMessage) => {
      renderPage();

      await waitForPage();
      submitAmount(amount);

      expect(await screen.findByText(expectedMessage)).toBeTruthy();

      expect(requestProviderPayout).not.toHaveBeenCalled();

      expect(loadPendingPayoutRequest(PROVIDER_ID)).toBeNull();
    },
  );

  it("persists the request before submitting and clears it after success", async () => {
    requestProviderPayout.mockImplementation(async (payload) => {
      expect(loadPendingPayoutRequest(PROVIDER_ID)).toEqual(payload);

      return {
        ...PAYOUT,
        requestKey: payload.requestKey,
        amount: payload.amount,
      };
    });

    renderPage();

    await waitForPage();
    submitAmount("7500");

    expect(await screen.findByText("Payout request received. Current status: PENDING.", { exact: true })).toBeTruthy();

    expect(requestProviderPayout).toHaveBeenCalledTimes(1);

    const payload = requestProviderPayout.mock.calls[0][0];

    expect(payload.amount).toBe(7500);
    expect(typeof payload.requestKey).toBe("string");
    expect(payload.requestKey.length).toBeGreaterThan(0);

    expect(loadPendingPayoutRequest(PROVIDER_ID)).toBeNull();

    await waitFor(() => {
      expect(getProviderEarningsSummary.mock.calls.length).toBeGreaterThan(1);
    });
  });

  it("preserves the original request after an ambiguous network failure", async () => {
    requestProviderPayout.mockRejectedValueOnce(
      createApiError("Network connection interrupted.", 0, "NETWORK_ERROR"),
    );

    renderPage();

    await waitForPage();
    submitAmount("7500");

    expect(
      await screen.findByText("Network connection interrupted."),
    ).toBeTruthy();

    const originalPayload = requestProviderPayout.mock.calls[0][0];

    expect(loadPendingPayoutRequest(PROVIDER_ID)).toEqual(originalPayload);

    expect(getAmountInput().disabled).toBe(true);

    expect(
      screen.getByRole("button", {
        name: /retry payout request/i,
      }),
    ).toBeTruthy();
  });

  it("retries an uncertain request using the exact original payload", async () => {
    requestProviderPayout
      .mockRejectedValueOnce(
        createApiError("Request timed out.", 0, "NETWORK_ERROR"),
      )
      .mockResolvedValueOnce(PAYOUT);

    renderPage();

    await waitForPage();
    submitAmount("7500");

    const retryButton = await screen.findByRole("button", {
      name: /retry payout request/i,
    });

    const originalPayload = requestProviderPayout.mock.calls[0][0];

    fireEvent.click(retryButton);

    expect(await screen.findByText("Payout request received. Current status: PENDING.", { exact: true })).toBeTruthy();

    expect(requestProviderPayout).toHaveBeenCalledTimes(2);

    expect(requestProviderPayout.mock.calls[1][0]).toEqual(originalPayload);

    expect(loadPendingPayoutRequest(PROVIDER_ID)).toBeNull();
  });

  it("restores an unresolved request after the page is reopened", async () => {
    const storedPayload = {
      requestKey: "persisted-request-001",
      amount: 7500,
    };

    savePendingPayoutRequest(PROVIDER_ID, storedPayload);

    renderPage();

    await waitForPage();
    confirmRequest();

    const retryButton = await screen.findByRole("button", {
      name: /retry payout request/i,
    });

    expect(getAmountInput().value).toBe("7500");
    expect(getAmountInput().disabled).toBe(true);

    fireEvent.click(retryButton);

    expect(await screen.findByText("Payout request received. Current status: PENDING.", { exact: true })).toBeTruthy();

    expect(requestProviderPayout).toHaveBeenCalledWith(storedPayload);

    expect(loadPendingPayoutRequest(PROVIDER_ID)).toBeNull();
  });

  it("allows correction after a definitive initial validation rejection", async () => {
    requestProviderPayout.mockRejectedValueOnce(
      createApiError("Invalid payout request.", 400, "VALIDATION_ERROR"),
    );

    renderPage();

    await waitForPage();
    submitAmount("7500");

    expect(await screen.findByText("Invalid payout request.")).toBeTruthy();

    expect(loadPendingPayoutRequest(PROVIDER_ID)).toBeNull();

    expect(getAmountInput().disabled).toBe(false);
  });

  it("preserves recovery information when a retry receives an authorization error", async () => {
    const storedPayload = {
      requestKey: "unresolved-request-001",
      amount: 7500,
    };

    savePendingPayoutRequest(PROVIDER_ID, storedPayload);

    requestProviderPayout.mockRejectedValueOnce(
      createApiError("Authentication required.", 401, "UNAUTHORIZED"),
    );

    renderPage();

    await waitForPage();
    confirmRequest();

    fireEvent.click(
      await screen.findByRole("button", {
        name: /retry payout request/i,
      }),
    );

    expect(await screen.findByText("Authentication required.")).toBeTruthy();

    expect(loadPendingPayoutRequest(PROVIDER_ID)).toEqual(storedPayload);
  });

  it("prevents new requests when stored recovery information is corrupted", async () => {
    window.localStorage.setItem(
      `localserve.wp07.payout.pending.v1:${PROVIDER_ID}`,
      "{invalid-json",
    );

    renderPage();

    expect(
      (await screen.findAllByText(/payout recovery unavailable/i))[0],
    ).toBeTruthy();

    openRequestDialog();

    expect(getAmountInput().disabled).toBe(true);

    expect(
      screen.getByRole("button", {
        name: /^submit payout request$/i,
      }).disabled,
    ).toBe(true);

    expect(requestProviderPayout).not.toHaveBeenCalled();
  });

  it("renders server-provided payout history records", async () => {
    getProviderPayoutHistory.mockResolvedValue(PAYOUT_HISTORY);

    renderPage();

    expect(await screen.findByText(PAYOUT.id)).toBeTruthy();

    expect(screen.getByText("PENDING", { exact: true })).toBeTruthy();

    expect(
      screen.getByRole("button", {
        name: /view details/i,
      }),
    ).toBeTruthy();
  });

  it("displays a payout history loading failure", async () => {
    getProviderPayoutHistory.mockRejectedValueOnce(
      new Error("Unable to retrieve payout history."),
    );

    renderPage();

    expect(
      await screen.findByText("Unable to retrieve payout history."),
    ).toBeTruthy();
  });

  it("prevents payout submission when earnings cannot be loaded", async () => {
    getProviderEarningsSummary.mockRejectedValueOnce(
      new Error("Unable to retrieve earnings."),
    );

    renderPage();

    expect(
      await screen.findByText("Unable to retrieve earnings."),
    ).toBeTruthy();

    openRequestDialog();
    confirmRequest();

    expect(
      screen.getByRole("button", {
        name: /^submit payout request$/i,
      }).disabled,
    ).toBe(true);

    expect(requestProviderPayout).not.toHaveBeenCalled();
  });
});


/**
 * Regression coverage for payout recovery safety.
 * Previously uncertain requests must retain their original identity.
 */
describe("Provider payout recovery safety", () => {
  it("requires explicit retry when an unresolved request appears after page loading", async () => {
    renderPage();

    await waitForPage();

    // Simulate a request persisted by another browser tab.
    const storedPayload = {
      requestKey: "external-tab-request-001",
      amount: 7500,
    };

    savePendingPayoutRequest(
      PROVIDER_ID,
      storedPayload,
    );

    // The newly entered amount must not silently replace
    // or submit the existing unresolved request.
    submitAmount("9000");

    expect(
      await screen.findByText(
        "An unresolved payout request was found. Review the original amount and select Retry Payout Request.",
        { exact: true },
      ),
    ).toBeTruthy();

    expect(requestProviderPayout).not.toHaveBeenCalled();
    expect(getAmountInput().value).toBe("7500");
    expect(getAmountInput().disabled).toBe(true);

    expect(
      loadPendingPayoutRequest(PROVIDER_ID),
    ).toEqual(storedPayload);

    // Only an explicitly confirmed retry may submit the original request.
    confirmRequest();

    fireEvent.click(
      screen.getByRole("button", {
        name: /^retry payout request$/i,
      }),
    );

    await waitFor(() => {
      expect(requestProviderPayout).toHaveBeenCalledTimes(1);

      expect(requestProviderPayout).toHaveBeenCalledWith(
        storedPayload,
      );

      expect(
        loadPendingPayoutRequest(PROVIDER_ID),
      ).toBeNull();
    });
  });

  it("preserves an uncertain request after an eligibility rejection during retry", async () => {
    const storedPayload = {
      requestKey: "uncertain-eligibility-request-001",
      amount: 7500,
    };

    savePendingPayoutRequest(
      PROVIDER_ID,
      storedPayload,
    );

    requestProviderPayout.mockRejectedValueOnce(
      createApiError(
        "Insufficient eligible booking earnings for payout.",
        409,
        "INSUFFICIENT_ELIGIBLE_EARNINGS",
      ),
    );

    renderPage();

    await waitForPage();
    confirmRequest();

    fireEvent.click(
      await screen.findByRole("button", {
        name: /^retry payout request$/i,
      }),
    );

    expect(
      await screen.findByText(
        "Insufficient eligible booking earnings for payout.",
        { exact: true },
      ),
    ).toBeTruthy();

    expect(requestProviderPayout).toHaveBeenCalledWith(
      storedPayload,
    );

    expect(
      loadPendingPayoutRequest(PROVIDER_ID),
    ).toEqual(storedPayload);

    expect(getAmountInput().value).toBe("7500");
    expect(getAmountInput().disabled).toBe(true);

    expect(
      screen.getByRole("button", {
        name: /^retry payout request$/i,
      }),
    ).toBeTruthy();
  });

  it("clears recovery data after a definitive initial eligibility rejection", async () => {
    requestProviderPayout.mockRejectedValueOnce(
      createApiError(
        "Insufficient eligible booking earnings for payout.",
        409,
        "INSUFFICIENT_ELIGIBLE_EARNINGS",
      ),
    );

    renderPage();

    await waitForPage();
    submitAmount("7500");

    expect(
      await screen.findByText(
        "Insufficient eligible booking earnings for payout.",
        { exact: true },
      ),
    ).toBeTruthy();

    expect(requestProviderPayout).toHaveBeenCalledTimes(1);

    expect(
      loadPendingPayoutRequest(PROVIDER_ID),
    ).toBeNull();

    expect(getAmountInput().disabled).toBe(false);

    expect(
      screen.queryByRole("button", {
        name: /^retry payout request$/i,
      }),
    ).toBeNull();
  });
});


/**
 * F2 - Missing provider profile recovery regression coverage.
 * Only definitive initial rejections may release the stored request identity.
 */
describe("Provider payout missing profile recovery", () => {
  it("clears recovery after an initial missing provider profile rejection", async () => {
    requestProviderPayout.mockRejectedValueOnce(
      createApiError(
        "Provider profile not found",
        404,
        "PROVIDER_PROFILE_NOT_FOUND",
      ),
    );

    renderPage();
    await waitForPage();
    submitAmount("7500");

    expect(
      await screen.findByText(
        "Complete or restore your provider profile before requesting a payout.",
      ),
    ).toBeTruthy();

    expect(requestProviderPayout).toHaveBeenCalledTimes(1);
    expect(loadPendingPayoutRequest(PROVIDER_ID)).toBeNull();
    expect(getAmountInput().disabled).toBe(false);

    expect(
      screen.queryByRole("button", {
        name: /^retry payout request$/i,
      }),
    ).toBeNull();
  });

  it("preserves the original request when an uncertain retry encounters a missing profile", async () => {
    const storedPayload = {
      requestKey: "uncertain-missing-profile-001",
      amount: 7500,
    };

    savePendingPayoutRequest(PROVIDER_ID, storedPayload);

    requestProviderPayout.mockRejectedValueOnce(
      createApiError(
        "Provider profile not found",
        404,
        "PROVIDER_PROFILE_NOT_FOUND",
      ),
    );

    renderPage();
    await waitForPage();
    confirmRequest();

    fireEvent.click(
      screen.getByRole("button", {
        name: /^retry payout request$/i,
      }),
    );

    expect(
      await screen.findByText(
        "Complete or restore your provider profile before retrying the original payout request.",
      ),
    ).toBeTruthy();

    expect(requestProviderPayout).toHaveBeenCalledTimes(1);
    expect(requestProviderPayout).toHaveBeenCalledWith(storedPayload);
    expect(loadPendingPayoutRequest(PROVIDER_ID)).toEqual(storedPayload);
    expect(getAmountInput().disabled).toBe(true);
    expect(getAmountInput().value).toBe("7500");
  });

  it("preserves recovery for an unrelated initial HTTP 404 response", async () => {
    requestProviderPayout.mockRejectedValueOnce(
      createApiError(
        "Resource not found.",
        404,
        "RESOURCE_NOT_FOUND",
      ),
    );

    renderPage();
    await waitForPage();
    submitAmount("7500");

    expect(
      await screen.findByText("Resource not found."),
    ).toBeTruthy();

    expect(requestProviderPayout).toHaveBeenCalledTimes(1);

    const originalPayload = requestProviderPayout.mock.calls[0][0];

    expect(loadPendingPayoutRequest(PROVIDER_ID)).toEqual(originalPayload);
    expect(getAmountInput().disabled).toBe(true);

    expect(
      screen.getByRole("button", {
        name: /^retry payout request$/i,
      }),
    ).toBeTruthy();
  });

  it("allows a new payout after a definitive missing profile rejection is corrected", async () => {
    requestProviderPayout
      .mockRejectedValueOnce(
        createApiError(
          "Provider profile not found",
          404,
          "PROVIDER_PROFILE_NOT_FOUND",
        ),
      )
      .mockImplementationOnce(async (payload) => ({
        ...PAYOUT,
        requestKey: payload.requestKey,
        amount: payload.amount,
      }));

    renderPage();
    await waitForPage();
    submitAmount("7500");

    await screen.findByText(
      "Complete or restore your provider profile before requesting a payout.",
    );

    const rejectedPayload = requestProviderPayout.mock.calls[0][0];

    expect(loadPendingPayoutRequest(PROVIDER_ID)).toBeNull();

    // A corrected initial rejection may start a new financial request.
    submitAmount("8000");

    expect(
      await screen.findByText(
        "Payout request received. Current status: PENDING.",
        { exact: true },
      ),
    ).toBeTruthy();

    expect(requestProviderPayout).toHaveBeenCalledTimes(2);

    const newPayload = requestProviderPayout.mock.calls[1][0];

    expect(newPayload.amount).toBe(8000);
    expect(newPayload.requestKey).not.toBe(rejectedPayload.requestKey);
    expect(loadPendingPayoutRequest(PROVIDER_ID)).toBeNull();
  });
});

/**
 * F1 - Cross-tab payout coordination regression coverage.
 * Financial submissions must never bypass the provider-specific lock.
 */
describe("Provider payout cross-tab coordination", () => {
  it("blocks a competing submission while another tab holds the provider lock", async () => {
    renderPage();
    await waitForPage();

    let releaseFirstTab;

    const firstTabResponse = new Promise((resolve) => {
      releaseFirstTab = resolve;
    });

    const firstPayload = {
      requestKey: "first-tab-request-001",
      amount: 7500,
    };

    requestProviderPayout.mockImplementationOnce(
      () => firstTabResponse,
    );

    const firstTab = withPayoutSubmissionLock(
      PROVIDER_ID,
      async () => {
        advancePayoutSubmissionRevision(PROVIDER_ID);
        savePendingPayoutRequest(PROVIDER_ID, firstPayload);

        await requestProviderPayout(firstPayload);

        clearPendingPayoutRequest(
          PROVIDER_ID,
          firstPayload.requestKey,
        );
      },
    );

    try {
      await waitFor(() => {
        expect(requestProviderPayout).toHaveBeenCalledTimes(1);
      });

      submitAmount("7500");

      expect(
        await screen.findByText(
          /A payout submission is active in another tab/i,
        ),
      ).toBeTruthy();

      expect(requestProviderPayout).toHaveBeenCalledTimes(1);
      expect(requestProviderPayout).toHaveBeenCalledWith(firstPayload);
      expect(loadPendingPayoutRequest(PROVIDER_ID)).toEqual(firstPayload);
    } finally {
      releaseFirstTab(PAYOUT);
      await firstTab;
    }

    expect(loadPendingPayoutRequest(PROVIDER_ID)).toBeNull();
  });

  it("rejects a stale confirmation after another tab completes its payout", async () => {
    renderPage();
    await waitForPage();

    fireEvent.change(getAmountInput(), {
      target: { value: "7500" },
    });

    confirmRequest();

    const firstPayload = {
      requestKey: "completed-other-tab-001",
      amount: 7500,
    };

    await withPayoutSubmissionLock(PROVIDER_ID, async () => {
      advancePayoutSubmissionRevision(PROVIDER_ID);
      savePendingPayoutRequest(PROVIDER_ID, firstPayload);

      await requestProviderPayout(firstPayload);

      clearPendingPayoutRequest(
        PROVIDER_ID,
        firstPayload.requestKey,
      );
    });

    fireEvent.click(
      screen.getByRole("button", {
        name: /^submit payout request$/i,
      }),
    );

    expect(
      await screen.findByText(
        /Payout activity changed in another tab/i,
      ),
    ).toBeTruthy();

    expect(requestProviderPayout).toHaveBeenCalledTimes(1);
    expect(loadPendingPayoutRequest(PROVIDER_ID)).toBeNull();

    expect(
      screen.getByRole("checkbox", {
        name: /I confirm that the payout amount is correct/i,
      }).checked,
    ).toBe(false);

    // A newly confirmed independent request remains permitted.
    await waitFor(() => {
      expect(getAmountInput().disabled).toBe(false);
    });

    submitAmount("8000");

    await screen.findByText(
      "Payout request received. Current status: PENDING.",
      { exact: true },
    );

    expect(requestProviderPayout).toHaveBeenCalledTimes(2);

    const newPayload = requestProviderPayout.mock.calls[1][0];

    expect(newPayload.amount).toBe(8000);
    expect(newPayload.requestKey).not.toBe(firstPayload.requestKey);
  });

  it("does not recreate a recovery record already resolved by another tab", async () => {
    const storedPayload = {
      requestKey: "resolved-recovery-001",
      amount: 7500,
    };

    savePendingPayoutRequest(PROVIDER_ID, storedPayload);

    renderPage();
    await waitForPage();
    confirmRequest();

    await withPayoutSubmissionLock(PROVIDER_ID, async () => {
      advancePayoutSubmissionRevision(PROVIDER_ID);

      clearPendingPayoutRequest(
        PROVIDER_ID,
        storedPayload.requestKey,
      );
    });

    fireEvent.click(
      screen.getByRole("button", {
        name: /^retry payout request$/i,
      }),
    );

    expect(
      await screen.findByText(
        /The original payout request is no longer pending locally/i,
      ),
    ).toBeTruthy();

    expect(requestProviderPayout).not.toHaveBeenCalled();
    expect(loadPendingPayoutRequest(PROVIDER_ID)).toBeNull();
    expect(getAmountInput().value).toBe("");
  });

  it("recovers the exact original request after a network failure and page reload", async () => {
    requestProviderPayout.mockRejectedValueOnce(
      createApiError("Network outcome uncertain.", 0, "NETWORK_ERROR"),
    );

    const firstPage = renderPage();

    await waitForPage();
    submitAmount("7500");

    await screen.findByText("Network outcome uncertain.");

    const originalPayload = requestProviderPayout.mock.calls[0][0];

    expect(loadPendingPayoutRequest(PROVIDER_ID)).toEqual(originalPayload);

    firstPage.unmount();

    renderPage();
    await waitForPage();
    confirmRequest();

    fireEvent.click(
      screen.getByRole("button", {
        name: /^retry payout request$/i,
      }),
    );

    await screen.findByText(
      "Payout request received. Current status: PENDING.",
      { exact: true },
    );

    expect(requestProviderPayout).toHaveBeenCalledTimes(2);
    expect(requestProviderPayout.mock.calls[1][0]).toEqual(originalPayload);
    expect(loadPendingPayoutRequest(PROVIDER_ID)).toBeNull();
  });

  it("fails closed when the browser does not support Web Locks", async () => {
    vi.stubGlobal("navigator", Object.create(window.navigator, {
      locks: {
        configurable: true,
        value: undefined,
      },
    }));

    renderPage();
    await waitForPage();
    submitAmount("7500");

    expect(
      (await screen.findAllByText(
        /Secure payout submission is unavailable in this browser/i,
      ))[0],
    ).toBeTruthy();

    expect(requestProviderPayout).not.toHaveBeenCalled();
    expect(loadPendingPayoutRequest(PROVIDER_ID)).toBeNull();
    expect(loadPayoutSubmissionRevision(PROVIDER_ID)).toBeNull();
  });

  it("preserves recovery when the browser lock request fails", async () => {
    const storedPayload = {
      requestKey: "lock-failure-recovery-001",
      amount: 7500,
    };

    savePendingPayoutRequest(PROVIDER_ID, storedPayload);

    globalThis.navigator.locks.request.mockRejectedValueOnce(
      new Error("Browser lock service unavailable."),
    );

    renderPage();
    await waitForPage();
    confirmRequest();

    fireEvent.click(
      screen.getByRole("button", {
        name: /^retry payout request$/i,
      }),
    );

    expect(
      (await screen.findAllByText(
        /Browser lock service unavailable/i,
      ))[0],
    ).toBeTruthy();

    expect(requestProviderPayout).not.toHaveBeenCalled();
    expect(loadPendingPayoutRequest(PROVIDER_ID)).toEqual(storedPayload);
  });

  it("keeps submission locks independent between provider accounts", async () => {
    let releaseFirstProvider;

    const firstProviderOperation = new Promise((resolve) => {
      releaseFirstProvider = resolve;
    });

    const firstProvider = withPayoutSubmissionLock(
      PROVIDER_ID,
      () => firstProviderOperation,
    );

    const otherOperation = vi.fn(async () => {});

    try {
      expect(
        await withPayoutSubmissionLock(
          "provider-test-002",
          otherOperation,
        ),
      ).toBe(true);

      expect(otherOperation).toHaveBeenCalledTimes(1);
    } finally {
      releaseFirstProvider();
      await firstProvider;
    }
  });

  it("releases the provider lock when the protected operation fails", async () => {
    await expect(
      withPayoutSubmissionLock(PROVIDER_ID, async () => {
        throw new Error("Protected operation failed.");
      }),
    ).rejects.toThrow("Protected operation failed.");

    const nextOperation = vi.fn(async () => {});

    expect(
      await withPayoutSubmissionLock(PROVIDER_ID, nextOperation),
    ).toBe(true);

    expect(nextOperation).toHaveBeenCalledTimes(1);
  });
});

/**
 * Protects unresolved payout identity when browser recovery data disappears.
 */
describe("Provider payout unexpected recovery record loss", () => {
  it.each([
    ["unchanged submission revision", false],
    ["missing submission revision", true],
  ])("fails closed with %s", async (_scenario, removeRevision) => {
    const storedPayload = {
      requestKey: "unexpected-record-loss-001",
      amount: 7500,
    };

    const originalRevision =
      advancePayoutSubmissionRevision(PROVIDER_ID);

    savePendingPayoutRequest(PROVIDER_ID, storedPayload);

    renderPage();
    await waitForPage();
    confirmRequest();

    // Simulate unexpected browser storage loss without resolving the payout.
    if (removeRevision) {
      window.localStorage.clear();

      expect(
        loadPayoutSubmissionRevision(PROVIDER_ID),
      ).toBeNull();
    } else {
      clearPendingPayoutRequest(
        PROVIDER_ID,
        storedPayload.requestKey,
      );

      expect(
        loadPayoutSubmissionRevision(PROVIDER_ID),
      ).toBe(originalRevision);
    }

    expect(loadPendingPayoutRequest(PROVIDER_ID)).toBeNull();

    fireEvent.click(
      screen.getByRole("button", {
        name: /^retry payout request$/i,
      }),
    );

    expect(
      (await screen.findAllByText(
        /The original payout recovery record is missing/i,
      ))[0],
    ).toBeTruthy();

    // No new financial request may be created after recovery data loss.
    expect(requestProviderPayout).not.toHaveBeenCalled();
    expect(loadPendingPayoutRequest(PROVIDER_ID)).toBeNull();

    expect(getAmountInput().value).toBe("7500");
    expect(getAmountInput().disabled).toBe(true);

    expect(
      screen.getByRole("button", {
        name: /^retry payout request$/i,
      }).disabled,
    ).toBe(true);

    expect(
      screen.getByRole("checkbox", {
        name: /I confirm that the payout amount is correct/i,
      }).checked,
    ).toBe(false);
  });
});

describe("Provider payout confirmation dialog", () => {
  it("requires confirmation before submitting a payout", async () => {
    renderPage();
    await waitForPage();

    fireEvent.change(getAmountInput(), {
      target: { value: "7500" },
    });

    const submitButton = screen.getByRole("button", {
      name: /^submit payout request$/i,
    });

    expect(submitButton.disabled).toBe(true);

    fireEvent.submit(getAmountInput().closest("form"));

    expect(requestProviderPayout).not.toHaveBeenCalled();
    expect(loadPendingPayoutRequest(PROVIDER_ID)).toBeNull();

    confirmRequest();

    expect(submitButton.disabled).toBe(false);

    fireEvent.change(getAmountInput(), {
      target: { value: "8000" },
    });

    expect(submitButton.disabled).toBe(true);
  });

  it("blocks new payouts below the minimum available balance", async () => {
    getProviderEarningsSummary.mockResolvedValue({
      ...SUMMARY,
      available: 3200,
    });

    renderPage();
    await waitForPage();

    expect(screen.getByText("Insufficient balance")).toBeTruthy();
    expect(getAmountInput().disabled).toBe(true);

    confirmRequest();

    expect(
      screen.getByRole("button", {
        name: /^submit payout request$/i,
      }).disabled,
    ).toBe(true);

    expect(requestProviderPayout).not.toHaveBeenCalled();
  });
});

// Native dialog support for the jsdom test environment.
if (typeof HTMLDialogElement.prototype.showModal !== "function") {
  HTMLDialogElement.prototype.showModal = function () {
    this.setAttribute("open", "");
  };
}

if (typeof HTMLDialogElement.prototype.close !== "function") {
  HTMLDialogElement.prototype.close = function () {
    this.removeAttribute("open");
  };
}

/**
 * Verifies the recent earnings preview independently of payout operations.
 * All financial values below are synthetic test fixtures.
 */
describe("Provider Recent Earnings", () => {
  const EARNING = {
    paymentId: "recent-payment-001",
    bookingId: "recent-booking-001",
    grossAmount: 10000,
    commissionRate: 0.15,
    commissionAmount: 1500,
    refundedAmount: 2000,
    refundAdjustmentAmount: 1700,
    netEarnings: 6800,
    currency: "LKR",
    recognizedAt: "2026-11-26T10:00:00Z",
  };

  function earningsPage(content) {
    return {
      content,
      page: 0,
      size: 5,
      totalElements: content.length,
      totalPages: content.length ? 1 : 0,
    };
  }

  it("renders current and legacy recognized earnings without omitting records", async () => {
    const legacy = {
      ...EARNING,
      paymentId: "legacy-payment-001",
      bookingId: "legacy-booking-001",
      commissionRate: 0.10,
      commissionAmount: 1000,
      refundAdjustmentAmount: 1800,
      netEarnings: 7200,
    };

    getProviderItemizedEarnings.mockResolvedValue({
      content: [EARNING, legacy],
      page: 0,
      size: 5,
      totalElements: 2,
      totalPages: 1,
    });

    renderPage();

    const currentRow = (await screen.findByText(EARNING.bookingId))
      .closest("tr");
    const legacyRow = (await screen.findByText(legacy.bookingId))
      .closest("tr");

    expect(currentRow.textContent).toContain("6,800.00");
    expect(legacyRow.textContent).toContain("7,200.00");
    expect(legacyRow.textContent).toContain("1,000.00");
    expect(legacyRow.textContent).toContain("1,800.00");
    expect(getProviderItemizedEarnings)
      .toHaveBeenCalledWith(0, 5);
  });

  it("requests the first five recognized earnings", async () => {
    renderPage();

    await waitFor(() => {
      expect(getProviderItemizedEarnings).toHaveBeenCalledWith(0, 5);
    });

    expect(await screen.findByText(
      "No recognized earnings are available yet.",
    )).toBeTruthy();
  });

  it("displays actual booking earnings and commission values", async () => {
    getProviderItemizedEarnings.mockResolvedValue(
      earningsPage([EARNING]),
    );

    renderPage();

    const booking = await screen.findByText(EARNING.bookingId);
    const row = booking.closest("tr");
    const text = row.textContent.replace(/\s+/g, " ");

    expect(text).toContain("10,000.00");
    expect(text).toContain("15%");
    expect(text).toContain("1,500.00");
    expect(text).toContain("1,700.00");
    expect(text).toContain("6,800.00");
  });

  it("does not invent a missing commission rate", async () => {
    getProviderItemizedEarnings.mockResolvedValue(
      earningsPage([{ ...EARNING, commissionRate: null }]),
    );

    renderPage();

    const booking = await screen.findByText(EARNING.bookingId);

    expect(booking.closest("tr").textContent).toContain("Unavailable");
  });

  it("handles an earnings API failure independently of balances", async () => {
    getProviderItemizedEarnings.mockRejectedValue(
      new Error("Network failure"),
    );

    renderPage();

    expect(await screen.findByText(
      "Unable to load recent earnings.",
    )).toBeTruthy();

    expect(await screen.findByText(
      "Available Balance",
      { exact: true },
    )).toBeTruthy();
  });

  it("rejects malformed earnings responses", async () => {
    getProviderItemizedEarnings.mockResolvedValue({
      content: null,
      page: 0,
    });

    renderPage();

    expect(await screen.findByText(
      "Unable to load recent earnings.",
    )).toBeTruthy();
  });

  it("rejects an unexpected earnings page", async () => {
    getProviderItemizedEarnings.mockResolvedValue({
      ...earningsPage([EARNING]),
      page: 1,
    });

    renderPage();

    expect(await screen.findByText(
      "Unable to load recent earnings.",
    )).toBeTruthy();

    expect(screen.queryByText(EARNING.bookingId)).toBeNull();
  });

  it("links to the complete itemized earnings history", async () => {
    renderPage();

    const link = await screen.findByRole("link", {
      name: /view all earnings/i,
    });

    expect(link.getAttribute("href")).toBe(
      "/provider/earnings/itemized",
    );
  });

  it("does not display an empty state while earnings are loading", async () => {
    let resolveEarnings;

    getProviderItemizedEarnings.mockReturnValueOnce(
      new Promise((resolve) => {
        resolveEarnings = resolve;
      }),
    );

    renderPage();

    await waitFor(() => {
      expect(getProviderItemizedEarnings).toHaveBeenCalledWith(0, 5);
    });

    expect(screen.queryByText(
      "No recognized earnings are available yet.",
    )).toBeNull();

    resolveEarnings(earningsPage([EARNING]));

    expect(await screen.findByText(EARNING.bookingId)).toBeTruthy();
  });

  it("ignores delayed earnings from a previous provider account", async () => {
    let resolvePrevious;

    getProviderItemizedEarnings.mockReturnValueOnce(
      new Promise((resolve) => {
        resolvePrevious = resolve;
      }),
    );

    const view = renderPage();

    await waitFor(() => {
      expect(getProviderItemizedEarnings).toHaveBeenCalledTimes(1);
    });

    const nextEarning = {
      ...EARNING,
      paymentId: "recent-payment-002",
      bookingId: "recent-booking-002",
    };

    getProviderItemizedEarnings.mockResolvedValue(
      earningsPage([nextEarning]),
    );

    useAuth.mockReturnValue({
      user: {
        id: "provider-test-002",
        role: "PROVIDER",
        name: "Second Provider",
      },
      isLoading: false,
      isAuthenticated: true,
    });

    view.rerender(
      <MemoryRouter>
        <ProviderEarningsPage />
      </MemoryRouter>,
    );

    await screen.findByText(nextEarning.bookingId);

    await act(async () => {
      resolvePrevious(earningsPage([EARNING]));
    });

    expect(screen.queryByText(EARNING.bookingId)).toBeNull();
    expect(screen.getByText(nextEarning.bookingId)).toBeTruthy();
  });

  it("refreshes recent earnings after the dashboard is refreshed", async () => {
    getProviderItemizedEarnings.mockResolvedValue(
      earningsPage([EARNING]),
    );

    renderPage();

    await screen.findByText(EARNING.bookingId);

    getProviderItemizedEarnings.mockResolvedValue(
      earningsPage([]),
    );

    fireEvent.click(
      screen.getByRole("button", { name: /^refresh$/i }),
    );

    expect(await screen.findByText(
      "No recognized earnings are available yet.",
    )).toBeTruthy();

    expect(screen.queryByText(EARNING.bookingId)).toBeNull();
    expect(getProviderItemizedEarnings).toHaveBeenCalledTimes(2);
  });

  it("clears previous earnings when the provider account changes", async () => {
    getProviderItemizedEarnings.mockResolvedValueOnce(
      earningsPage([EARNING]),
    );

    const view = renderPage();

    await screen.findByText(EARNING.bookingId);

    useAuth.mockReturnValue({
      user: {
        id: "provider-test-002",
        role: "PROVIDER",
        name: "Second Provider",
      },
      isLoading: false,
      isAuthenticated: true,
    });

    view.rerender(
      <MemoryRouter>
        <ProviderEarningsPage />
      </MemoryRouter>,
    );

    expect(screen.queryByText(EARNING.bookingId)).toBeNull();

    expect(await screen.findByText(
      "No recognized earnings are available yet.",
    )).toBeTruthy();
  });
});
