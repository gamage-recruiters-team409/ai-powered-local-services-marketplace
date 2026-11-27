// @vitest-environment jsdom

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  act,
  cleanup,
  render,
  screen,
  fireEvent,
  waitFor,
} from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

import ProviderItemizedEarningsPage from "../pages/ProviderItemizedEarningsPage";
import { getProviderItemizedEarnings } from "../services/paymentsEarningsApi";
import { useAuth } from "../../auth/hooks/useAuth";

vi.mock("../services/paymentsEarningsApi", () => ({
  getProviderItemizedEarnings: vi.fn(),
}));

vi.mock("../../auth/hooks/useAuth", () => ({
  useAuth: vi.fn(),
}));

const PROVIDER = {
  id: "provider-001",
  role: "PROVIDER",
};

const EARNING = {
  paymentId: "payment-001",
  bookingId: "booking-001",
  grossAmount: 10000,
  commissionRate: 0.15,
  commissionAmount: 1500,
  refundedAmount: 2000,
  refundAdjustmentAmount: 1700,
  netEarnings: 6800,
  currency: "LKR",
  recognizedAt: "2026-11-26T10:00:00Z",
};

const EMPTY_PAGE = {
  content: [],
  page: 0,
  size: 10,
  totalElements: 0,
  totalPages: 0,
};

const EARNINGS_PAGE = {
  content: [EARNING],
  page: 0,
  size: 10,
  totalElements: 1,
  totalPages: 1,
};

function pageElement() {
  return (
    <MemoryRouter>
      <ProviderItemizedEarningsPage />
    </MemoryRouter>
  );
}

function renderPage() {
  return render(pageElement());
}

/** Creates a controllable request for asynchronous isolation tests. */
function deferred() {
  let resolve;

  const promise = new Promise((complete) => {
    resolve = complete;
  });

  return { promise, resolve };
}

function formatMoney(value) {
  return new Intl.NumberFormat("en-LK", {
    style: "currency",
    currency: "LKR",
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(value);
}

beforeEach(() => {
  vi.resetAllMocks();

  useAuth.mockReturnValue({
    user: PROVIDER,
    isLoading: false,
  });

  getProviderItemizedEarnings.mockResolvedValue(EARNINGS_PAGE);
});

afterEach(cleanup);

describe("ProviderItemizedEarningsPage", () => {
  it("does not request earnings while authentication is loading", () => {
    useAuth.mockReturnValue({
      user: null,
      isLoading: true,
    });

    renderPage();

    expect(getProviderItemizedEarnings).not.toHaveBeenCalled();
  });

  it("rejects an unauthenticated account", () => {
    useAuth.mockReturnValue({
      user: null,
      isLoading: false,
    });

    renderPage();

    expect(screen.getByText("Provider account required")).toBeTruthy();
    expect(getProviderItemizedEarnings).not.toHaveBeenCalled();
  });

  it.each(["CUSTOMER", "ADMIN", "FINANCE_ADMIN"])(
    "rejects the %s role",
    (role) => {
      useAuth.mockReturnValue({
        user: { id: "other-user", role },
        isLoading: false,
      });

      renderPage();

      expect(screen.getByText("Provider account required")).toBeTruthy();
      expect(getProviderItemizedEarnings).not.toHaveBeenCalled();
    },
  );

  it("rejects a provider without an account identifier", () => {
    useAuth.mockReturnValue({
      user: { role: "PROVIDER" },
      isLoading: false,
    });

    renderPage();

    expect(screen.getByText("Provider account required")).toBeTruthy();
    expect(getProviderItemizedEarnings).not.toHaveBeenCalled();
  });

  it("loads the first server page and displays actual financial values", async () => {
    renderPage();

    await screen.findByText("booking-001");

    expect(getProviderItemizedEarnings).toHaveBeenCalledWith(0, 10);
    expect(screen.getByText("15%")).toBeTruthy();

    for (const amount of [10000, 1500, 1700, 6800]) {
      expect(screen.getByText(formatMoney(amount).replace(/\s+/g, " ").trim())).toBeTruthy();
    }
  });

  it("distinguishes net earnings from withdrawable funds", async () => {
    renderPage();

    await screen.findByText("booking-001");

    expect(
      screen.getByText(/do not represent the currently withdrawable balance/i),
    ).toBeTruthy();
  });

  it("shows unavailable commission rates without inventing a default", async () => {
    getProviderItemizedEarnings.mockResolvedValue({
      ...EARNINGS_PAGE,
      content: [{ ...EARNING, commissionRate: null }],
    });

    renderPage();

    expect(await screen.findByText("Unavailable")).toBeTruthy();
    expect(screen.queryByText("10%")).toBeNull();
  });

  it("displays an empty earnings history", async () => {
    getProviderItemizedEarnings.mockResolvedValue(EMPTY_PAGE);

    renderPage();

    expect(
      await screen.findByText("No itemized earnings are available yet."),
    ).toBeTruthy();
  });

  it("disables refresh while the request is pending", async () => {
    const request = deferred();

    getProviderItemizedEarnings.mockReturnValue(request.promise);

    renderPage();

    expect(
      screen.getByRole("button", { name: "Refresh" }).disabled,
    ).toBe(true);

    await act(async () => {
      request.resolve(EARNINGS_PAGE);
    });

    expect(await screen.findByText("booking-001")).toBeTruthy();
    expect(
      screen.getByRole("button", { name: "Refresh" }).disabled,
    ).toBe(false);
  });

  it("allows retry after an API failure", async () => {
    getProviderItemizedEarnings
      .mockRejectedValueOnce(new Error("Request failed"))
      .mockResolvedValueOnce(EARNINGS_PAGE);

    renderPage();

    await screen.findByText(
      "Unable to load itemized earnings. Please try again.",
    );

    fireEvent.click(screen.getByRole("button", { name: "Refresh" }));

    expect(await screen.findByText("booking-001")).toBeTruthy();
    expect(getProviderItemizedEarnings).toHaveBeenCalledTimes(2);
  });

  it("rejects malformed pagination responses", async () => {
    getProviderItemizedEarnings.mockResolvedValue({
      ...EARNINGS_PAGE,
      page: 4,
    });

    renderPage();

    expect(
      await screen.findByText(
        "Unable to load itemized earnings. Please try again.",
      ),
    ).toBeTruthy();

    expect(screen.queryByText("booking-001")).toBeNull();
  });

  it("requests the next server page using a zero-based index", async () => {
    getProviderItemizedEarnings
      .mockResolvedValueOnce({
        ...EARNINGS_PAGE,
        totalElements: 11,
        totalPages: 2,
      })
      .mockResolvedValueOnce({
        ...EARNINGS_PAGE,
        content: [{
          ...EARNING,
          paymentId: "payment-002",
          bookingId: "booking-002",
        }],
        page: 1,
        totalElements: 11,
        totalPages: 2,
      });

    renderPage();

    await screen.findByText("booking-001");

    fireEvent.click(
      screen.getByRole("button", { name: /next/i }),
    );

    expect(await screen.findByText("booking-002")).toBeTruthy();
    expect(getProviderItemizedEarnings).toHaveBeenLastCalledWith(1, 10);
    expect(screen.queryByText("booking-001")).toBeNull();
  });

  it("clears previous earnings when the provider account changes", async () => {
    const { rerender } = renderPage();

    await screen.findByText("booking-001");

    useAuth.mockReturnValue({
      user: { ...PROVIDER, id: "provider-002" },
      isLoading: false,
    });

    getProviderItemizedEarnings.mockResolvedValue(EMPTY_PAGE);

    rerender(pageElement());

    expect(screen.queryByText("booking-001")).toBeNull();

    await screen.findByText("No itemized earnings are available yet.");

    expect(getProviderItemizedEarnings).toHaveBeenCalledTimes(2);
  });

  it("ignores a previous provider's late API response", async () => {
    const previousRequest = deferred();

    getProviderItemizedEarnings
      .mockReturnValueOnce(previousRequest.promise)
      .mockResolvedValueOnce(EMPTY_PAGE);

    const { rerender } = renderPage();

    await waitFor(() => {
      expect(getProviderItemizedEarnings).toHaveBeenCalledTimes(1);
    });

    useAuth.mockReturnValue({
      user: { ...PROVIDER, id: "provider-002" },
      isLoading: false,
    });

    rerender(pageElement());

    await screen.findByText("No itemized earnings are available yet.");

    await act(async () => {
      previousRequest.resolve(EARNINGS_PAGE);
    });

    expect(screen.queryByText("booking-001")).toBeNull();
  });

  it("provides navigation back to the earnings dashboard", async () => {
    renderPage();

    await screen.findByText("booking-001");

    const link = screen.getByRole("link", {
      name: "Back to Earnings Dashboard",
    });

    expect(link.getAttribute("href")).toBe("/provider/earnings");
  });
});
