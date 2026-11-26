// @vitest-environment jsdom

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
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
  getProviderPayoutHistory,
  getProviderPayoutDetails,
  requestProviderPayout,
} from "../services/paymentsEarningsApi";

import {
  loadPendingPayoutRequest,
  savePendingPayoutRequest,
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

beforeEach(() => {
  vi.resetAllMocks();
  window.localStorage.clear();

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
