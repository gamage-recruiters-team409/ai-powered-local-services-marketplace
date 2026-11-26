// @vitest-environment jsdom

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";

import { MemoryRouter } from "react-router-dom";

import AdminPayoutQueuePage from "../pages/AdminPayoutQueuePage";

import { useAreaAccess } from "../../disputes-admin/access/useAdminAccess";

import {
  getAdminPayoutSummary,
  getAdminPayoutHistory,
  getAdminPayoutDetails,
  markPayoutProcessing,
  markPayoutPaid,
  markPayoutFailed,
} from "../services/paymentsEarningsApi";

/**
 * WP07 - Finance Admin payout queue component tests.
 *
 * Verifies payout management, access restrictions, confirmation
 * requirements, status transitions and API failure handling.
 *
 * API responses are mocked while the actual page and shared
 * UI components remain under test.
 */

vi.mock("../../disputes-admin/access/useAdminAccess", () => ({
  useAreaAccess: vi.fn(),
}));

vi.mock("../services/paymentsEarningsApi", () => ({
  getAdminPayoutSummary: vi.fn(),
  getAdminPayoutHistory: vi.fn(),
  getAdminPayoutDetails: vi.fn(),
  markPayoutProcessing: vi.fn(),
  markPayoutPaid: vi.fn(),
  markPayoutFailed: vi.fn(),
}));

const PAYOUT_ID = "payout-admin-test-001";

const PENDING_PAYOUT = {
  id: PAYOUT_ID,
  providerId: "provider-test-001",
  requestKey: "request-test-001",
  currency: "LKR",
  amount: 7500,
  status: "PENDING",
  createdAt: "2026-11-25T10:00:00Z",
  updatedAt: "2026-11-25T10:00:00Z",
  paidAt: null,
  processedBy: null,
  transferReference: null,
  failureReason: null,
};

const PROCESSING_PAYOUT = {
  ...PENDING_PAYOUT,
  status: "PROCESSING",
  processedBy: "finance-admin-001",
};

const PAID_PAYOUT = {
  ...PROCESSING_PAYOUT,
  status: "PAID",
  transferReference: "BANK-TRANSFER-001",
  paidAt: "2026-11-25T11:00:00Z",
};

const FAILED_PAYOUT = {
  ...PROCESSING_PAYOUT,
  status: "FAILED",
  failureReason: "Bank transfer rejected.",
};

const EMPTY_HISTORY = {
  content: [],
  page: 0,
  size: 10,
  totalElements: 0,
  totalPages: 0,
};

/** Creates a paginated response using the supplied payout. */
function createHistory(payout) {
  return {
    content: [payout],
    page: 0,
    size: 10,
    totalElements: 1,
    totalPages: 1,
  };
}

/** Configures consistent queue and detail API responses. */
function configurePayout(payout) {
  getAdminPayoutHistory.mockResolvedValue(createHistory(payout));

  getAdminPayoutDetails.mockResolvedValue(payout);
}

/** Renders the real Finance Admin payout page. */
function renderPage() {
  return render(
    <MemoryRouter>
      <AdminPayoutQueuePage />
    </MemoryRouter>,
  );
}

/** Opens the dedicated payout review and waits for its data. */
async function openDetails() {
  const detailsButton = await screen.findByRole("button", {
    name: /^(view details|review payout)$/i,
  });

  fireEvent.click(detailsButton);

  const dialog = await screen.findByRole("region", {
    name: /^provider payout details$/i,
  });

  await within(dialog).findByText(PAYOUT_ID, {
    exact: true,
  });

  return dialog;
}

/** Opens a financial operation confirmation. */
function beginAction(dialog, label) {
  fireEvent.click(
    within(dialog).getByRole("button", {
      name: label,
      exact: true,
    }),
  );
}

/** Submits the active financial operation confirmation. */
function confirmAction(_review, label) {
  const confirmation = screen.getByRole("dialog");

  if (label === "Start Processing") {
    const checkbox = within(confirmation).getByRole("checkbox", {
      name: /I confirm that I have reviewed this payout request/i,
    });

    if (!checkbox.checked) {
      fireEvent.click(checkbox);
    }
  }

  fireEvent.click(
    within(confirmation).getByRole("button", {
      name: label,
      exact: true,
    }),
  );
}

/** Ensures no financial mutation has been requested. */
function expectNoMutations() {
  expect(markPayoutProcessing).not.toHaveBeenCalled();
  expect(markPayoutPaid).not.toHaveBeenCalled();
  expect(markPayoutFailed).not.toHaveBeenCalled();
}

beforeEach(() => {
  vi.resetAllMocks();

  // Provide independent global counts for the summary cards.
  getAdminPayoutSummary.mockResolvedValue({
    pending: 1,
    processing: 0,
    paid: 0,
    failed: 0,
  });

  useAreaAccess.mockReturnValue({
    area: "FINANCE_ADMIN",
    mode: "EDIT",
    canEdit: true,
    isViewOnly: false,
  });

  configurePayout(PENDING_PAYOUT);

  markPayoutProcessing.mockResolvedValue(PROCESSING_PAYOUT);

  markPayoutPaid.mockResolvedValue(PAID_PAYOUT);
  markPayoutFailed.mockResolvedValue(FAILED_PAYOUT);

  // Supply native dialog methods only when the test
  // environment does not implement them.
  if (typeof HTMLDialogElement.prototype.showModal !== "function") {
    HTMLDialogElement.prototype.showModal = function showModal() {
      this.setAttribute("open", "");
    };
  }

  if (typeof HTMLDialogElement.prototype.close !== "function") {
    HTMLDialogElement.prototype.close = function close() {
      this.removeAttribute("open");
    };
  }
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("AdminPayoutQueuePage", () => {
  it("loads the payout queue using server pagination", async () => {
    renderPage();

    expect(
      screen.getByRole("heading", {
        name: /^payout queue$/i,
      }),
    ).toBeTruthy();

    expect(
      await screen.findByText(PAYOUT_ID, {
        exact: true,
      }),
    ).toBeTruthy();

    expect(getAdminPayoutHistory).toHaveBeenCalledWith(0, 10, {
      search: "",
      status: "",
      from: undefined,
      to: undefined,
    });

    expect(
      screen.getByText("UNDER REVIEW", {
        exact: true,
      }),
    ).toBeTruthy();

    expectNoMutations();
  });

  it("renders an empty queue without inventing payout records", async () => {
    getAdminPayoutHistory.mockResolvedValue(EMPTY_HISTORY);

    renderPage();

    await waitFor(() => {
      expect(screen.getByText(/total records:\s*0/i)).toBeTruthy();
    });

    expect(
      screen.queryByRole("button", {
        name: /^(view details|review payout)$/i,
      }),
    ).toBeNull();

    expectNoMutations();
  });

  it("displays a queue retrieval failure", async () => {
    getAdminPayoutHistory.mockRejectedValueOnce(
      new Error("Unable to retrieve payout queue."),
    );

    renderPage();

    expect(
      await screen.findByText("Unable to retrieve payout queue.", {
        exact: true,
      }),
    ).toBeTruthy();

    expectNoMutations();
  });

  it("retrieves the selected payout details from the API", async () => {
    renderPage();

    const dialog = await openDetails();

    expect(getAdminPayoutDetails).toHaveBeenCalledWith(PAYOUT_ID);

    expect(
      within(dialog).getByText(PENDING_PAYOUT.providerId, { exact: true }),
    ).toBeTruthy();

    expect(
      within(dialog).getByText(PENDING_PAYOUT.requestKey, { exact: true }),
    ).toBeTruthy();

    expectNoMutations();
  });

  it("displays a payout details retrieval failure", async () => {
    getAdminPayoutDetails.mockRejectedValueOnce(
      new Error("Unable to retrieve payout details."),
    );

    renderPage();

    fireEvent.click(
      await screen.findByRole("button", {
        name: /^(view details|review payout)$/i,
      }),
    );

    const dialog = await screen.findByRole("region", {
      name: /^provider payout details$/i,
    });

    expect(
      await within(dialog).findByText("Unable to retrieve payout details.", {
        exact: true,
      }),
    ).toBeTruthy();

    expectNoMutations();
  });

  it.each([PENDING_PAYOUT, PROCESSING_PAYOUT])(
    "prevents view-only users from modifying $status payouts",
    async (payout) => {
      useAreaAccess.mockReturnValue({
        area: "FINANCE_ADMIN",
        mode: "VIEW",
        canEdit: false,
        isViewOnly: true,
      });

      configurePayout(payout);

      renderPage();

      expect(
        screen.getByText("View-only access", {
          exact: true,
        }),
      ).toBeTruthy();

      const dialog = await openDetails();

      expect(
        within(dialog).queryByRole("button", {
          name: /^start processing$/i,
        }),
      ).toBeNull();

      expect(
        within(dialog).queryByRole("button", {
          name: /^mark as paid$/i,
        }),
      ).toBeNull();

      expect(
        within(dialog).queryByRole("button", {
          name: /^mark as failed$/i,
        }),
      ).toBeNull();

      expectNoMutations();
    },
  );

  it("requires confirmation before starting payout processing", async () => {
    renderPage();

    const dialog = await openDetails();

    beginAction(dialog, "Start Processing");

    expect(
      within(screen.getByRole("dialog")).getByRole("heading", {
        name: /^start payout processing$/i,
      }),
    ).toBeTruthy();

    expectNoMutations();
  });

  it("cancels an operation without modifying the payout", async () => {
    renderPage();

    const dialog = await openDetails();

    beginAction(dialog, "Start Processing");

    fireEvent.click(
      within(screen.getByRole("dialog")).getByRole("button", {
        name: /^cancel$/i,
      }),
    );

    expect(
      screen.queryByRole("dialog", {
        name: /^start payout processing$/i,
      }),
    ).toBeNull();

    expectNoMutations();
  });

  it("transitions a pending payout to processing after confirmation", async () => {
    renderPage();

    const dialog = await openDetails();

    beginAction(dialog, "Start Processing");
    confirmAction(dialog, "Start Processing");

    await waitFor(() => {
      expect(markPayoutProcessing).toHaveBeenCalledTimes(1);

      expect(markPayoutProcessing).toHaveBeenCalledWith(PAYOUT_ID);
    });

    expect(
      await within(dialog).findByText("PROCESSING", { exact: true }),
    ).toBeTruthy();

    expect(markPayoutPaid).not.toHaveBeenCalled();
    expect(markPayoutFailed).not.toHaveBeenCalled();
  });

  it("does not allow pending payouts to be marked paid or failed", async () => {
    renderPage();

    const dialog = await openDetails();

    expect(
      within(dialog).queryByRole("button", {
        name: /^mark as paid$/i,
      }),
    ).toBeNull();

    expect(
      within(dialog).queryByRole("button", {
        name: /^mark as failed$/i,
      }),
    ).toBeNull();

    expectNoMutations();
  });

  it("requires a non-blank bank transfer reference", async () => {
    configurePayout(PROCESSING_PAYOUT);

    renderPage();

    const dialog = await openDetails();

    beginAction(dialog, "Mark as Paid");

    const referenceInput = within(screen.getByRole("dialog")).getByRole("textbox", {
      name: /bank transfer reference/i,
    });

    fireEvent.change(referenceInput, {
      target: { value: "   " },
    });

    confirmAction(dialog, "Mark as Paid");

    expect(
      await within(screen.getByRole("dialog")).findByText(
        "A bank transfer reference is required.",
        { exact: true },
      ),
    ).toBeTruthy();

    expectNoMutations();
  });

  it("marks a processing payout paid with a trimmed transfer reference", async () => {
    configurePayout(PROCESSING_PAYOUT);

    renderPage();

    const dialog = await openDetails();

    beginAction(dialog, "Mark as Paid");

    fireEvent.change(
      within(screen.getByRole("dialog")).getByRole("textbox", {
        name: /bank transfer reference/i,
      }),
      {
        target: {
          value: "  BANK-TRANSFER-001  ",
        },
      },
    );

    expectNoMutations();

    confirmAction(dialog, "Mark as Paid");

    await waitFor(() => {
      expect(markPayoutPaid).toHaveBeenCalledTimes(1);

      expect(markPayoutPaid).toHaveBeenCalledWith(
        PAYOUT_ID,
        "BANK-TRANSFER-001",
      );
    });

    expect(
      await within(dialog).findByText("PAID", {
        exact: true,
      }),
    ).toBeTruthy();

    expect(markPayoutFailed).not.toHaveBeenCalled();
  });

  it("requires a non-blank payout failure reason", async () => {
    configurePayout(PROCESSING_PAYOUT);

    renderPage();

    const dialog = await openDetails();

    beginAction(dialog, "Mark as Failed");

    fireEvent.change(
      within(screen.getByRole("dialog")).getByRole("textbox", {
        name: /failure reason/i,
      }),
      {
        target: { value: "   " },
      },
    );

    confirmAction(dialog, "Mark as Failed");

    expect(
      await within(screen.getByRole("dialog")).findByText("A failure reason is required.", {
        exact: true,
      }),
    ).toBeTruthy();

    expectNoMutations();
  });

  it("marks a processing payout failed with a trimmed reason", async () => {
    configurePayout(PROCESSING_PAYOUT);

    renderPage();

    const dialog = await openDetails();

    beginAction(dialog, "Mark as Failed");

    fireEvent.change(
      within(screen.getByRole("dialog")).getByRole("textbox", {
        name: /failure reason/i,
      }),
      {
        target: {
          value: "  Bank transfer rejected.  ",
        },
      },
    );

    expectNoMutations();

    confirmAction(dialog, "Mark as Failed");

    await waitFor(() => {
      expect(markPayoutFailed).toHaveBeenCalledTimes(1);

      expect(markPayoutFailed).toHaveBeenCalledWith(
        PAYOUT_ID,
        "Bank transfer rejected.",
      );
    });

    expect(
      await within(dialog).findByText("FAILED", {
        exact: true,
      }),
    ).toBeTruthy();

    expect(markPayoutPaid).not.toHaveBeenCalled();
  });

  it.each([PAID_PAYOUT, FAILED_PAYOUT])(
    "does not expose mutation actions for terminal $status payouts",
    async (payout) => {
      configurePayout(payout);

      renderPage();

      const dialog = await openDetails();

      expect(
        within(dialog).queryByRole("button", {
          name: /^start processing$/i,
        }),
      ).toBeNull();

      expect(
        within(dialog).queryByRole("button", {
          name: /^mark as paid$/i,
        }),
      ).toBeNull();

      expect(
        within(dialog).queryByRole("button", {
          name: /^mark as failed$/i,
        }),
      ).toBeNull();

      expectNoMutations();
    },
  );

  it("refreshes authoritative payout details after an uncertain operation", async () => {
    getAdminPayoutDetails
      .mockResolvedValueOnce(PENDING_PAYOUT)
      .mockResolvedValueOnce(PROCESSING_PAYOUT);

    markPayoutProcessing.mockRejectedValueOnce(
      Object.assign(new Error("Network connection interrupted."), {
        status: 0,
        code: "NETWORK_ERROR",
      }),
    );

    renderPage();

    const dialog = await openDetails();

    beginAction(dialog, "Start Processing");
    confirmAction(dialog, "Start Processing");

    expect(
      await within(dialog).findByText(/network connection interrupted/i),
    ).toBeTruthy();

    await waitFor(() => {
      expect(getAdminPayoutDetails).toHaveBeenCalledTimes(2);
    });

    expect(
      await within(dialog).findByText("PROCESSING", { exact: true }),
    ).toBeTruthy();

    expect(markPayoutProcessing).toHaveBeenCalledTimes(1);
    expect(markPayoutPaid).not.toHaveBeenCalled();
    expect(markPayoutFailed).not.toHaveBeenCalled();
  });
});


/**
 * Ensures operation feedback does not leak between payout records.
 */
describe("Admin payout message isolation", () => {
  const secondPayout = {
    ...PENDING_PAYOUT,
    id: "payout-admin-test-002",
    requestKey: "request-test-002",
  };

  async function openSecondPayout() {
    fireEvent.click(
      screen.getAllByRole("button", {
        name: /^(view details|review payout)$/i,
      })[1],
    );

    const dialog = await screen.findByRole("region", {
      name: /^provider payout details$/i,
    });

    await within(dialog).findByText(secondPayout.id, {
      exact: true,
    });

    return dialog;
  }

  function configureTwoPayouts() {
    getAdminPayoutHistory.mockResolvedValue({
      ...createHistory(PENDING_PAYOUT),
      content: [PENDING_PAYOUT, secondPayout],
      totalElements: 2,
    });

    getAdminPayoutDetails.mockImplementation(
      async (id) =>
        id === secondPayout.id
          ? secondPayout
          : PENDING_PAYOUT,
    );
  }

  it("clears previous success feedback when another payout is opened", async () => {
    configureTwoPayouts();
    renderPage();

    fireEvent.click(
      (await screen.findAllByRole("button", {
        name: /^(view details|review payout)$/i,
      }))[0],
    );

    const firstDialog = await screen.findByRole("region", { name: /^provider payout details$/i });

    await within(firstDialog).findByText(PAYOUT_ID, {
      exact: true,
    });

    beginAction(firstDialog, "Start Processing");
    confirmAction(firstDialog, "Start Processing");

    await within(firstDialog).findByText("PROCESSING", {
      exact: true,
    });

    expect(
      within(firstDialog).getByText(
        /payout updated successfully/i,
      ),
    ).toBeTruthy();

    fireEvent.click(
      within(firstDialog).getByRole("button", {
        name: /^back to payout queue$/i,
      }),
    );

    await screen.findByText(secondPayout.id, {
      exact: true,
    });

    const secondDialog = await openSecondPayout();

    expect(
      within(secondDialog).queryByText(
        /payout updated successfully/i,
      ),
    ).toBeNull();

    expect(
      within(secondDialog).getByText("PENDING", {
        exact: true,
      }),
    ).toBeTruthy();
  });

  it("clears previous operation errors when another payout is opened", async () => {
    configureTwoPayouts();

    markPayoutProcessing.mockRejectedValueOnce(
      new Error("Previous payout operation failed."),
    );

    renderPage();

    fireEvent.click(
      (await screen.findAllByRole("button", {
        name: /^(view details|review payout)$/i,
      }))[0],
    );

    const firstDialog = await screen.findByRole("region", { name: /^provider payout details$/i });

    await within(firstDialog).findByText(PAYOUT_ID, {
      exact: true,
    });

    beginAction(firstDialog, "Start Processing");
    confirmAction(firstDialog, "Start Processing");

    await within(firstDialog).findByText(
      /previous payout operation failed/i,
    );

    await waitFor(() => {
      expect(
        within(firstDialog).getByRole("button", {
          name: /^back to payout queue$/i,
        }).disabled,
      ).toBe(false);
    });

    fireEvent.click(
      within(firstDialog).getByRole("button", {
        name: /^back to payout queue$/i,
      }),
    );

    await screen.findByText(secondPayout.id, {
      exact: true,
    });

    const secondDialog = await openSecondPayout();

    expect(
      within(secondDialog).queryByText(
        /previous payout operation failed/i,
      ),
    ).toBeNull();

    expect(markPayoutProcessing).toHaveBeenCalledTimes(1);
  });
});

/**
 * Verifies global payout summaries and server-side filtering
 * independently of the existing financial mutation workflows.
 */
describe("Admin payout summary and filters", () => {
  it("displays global counts independently of the current queue page", async () => {
    configurePayout(PENDING_PAYOUT);

    getAdminPayoutSummary.mockResolvedValue({
      pending: 6,
      processing: 12,
      paid: 148,
      failed: 3,
    });

    renderPage();

    const expectedCounts = [
      ["Pending Review", "6"],
      ["Processing", "12"],
      ["Paid", "148"],
      ["Failed", "3"],
    ];

    for (const [label, count] of expectedCounts) {
      const card = screen.getByRole("article", { name: label });

      expect(
        await within(card).findByText(count, { exact: true }),
      ).toBeTruthy();
    }

    expect(getAdminPayoutSummary).toHaveBeenCalledTimes(1);
  });

  it("preserves genuine zero counts returned by the summary API", async () => {
    getAdminPayoutHistory.mockResolvedValue(EMPTY_HISTORY);

    getAdminPayoutSummary.mockResolvedValue({
      pending: 0,
      processing: 0,
      paid: 0,
      failed: 0,
    });

    renderPage();

    for (const label of [
      "Pending Review",
      "Processing",
      "Paid",
      "Failed",
    ]) {
      const card = screen.getByRole("article", { name: label });

      expect(
        await within(card).findByText("0", { exact: true }),
      ).toBeTruthy();
    }
  });

  it("does not display fabricated zero counts when summary loading fails", async () => {
    configurePayout(PENDING_PAYOUT);

    getAdminPayoutSummary.mockRejectedValue(
      new Error("Summary request failed."),
    );

    renderPage();

    expect(
      await screen.findByText("Summary request failed."),
    ).toBeTruthy();

    const card = screen.getByRole("article", {
      name: "Pending Review",
    });

    expect(
      within(card).queryByText("0", { exact: true }),
    ).toBeNull();

    expect(
      await screen.findByText(PAYOUT_ID, { exact: true }),
    ).toBeTruthy();
  });

  it("does not request filtered results before filters are applied", async () => {
    configurePayout(PENDING_PAYOUT);
    renderPage();

    await screen.findByText(PAYOUT_ID, { exact: true });

    fireEvent.change(screen.getByLabelText("Search payouts"), {
      target: { value: "provider-test-001" },
    });

    expect(getAdminPayoutHistory).toHaveBeenCalledTimes(1);
  });

  it("applies a trimmed exact identifier search", async () => {
    configurePayout(PENDING_PAYOUT);
    renderPage();

    await screen.findByText(PAYOUT_ID, { exact: true });

    fireEvent.change(screen.getByLabelText("Search payouts"), {
      target: { value: "  provider-test-001  " },
    });

    fireEvent.click(
      screen.getByRole("button", { name: /^apply filters$/i }),
    );

    await waitFor(() => {
      expect(getAdminPayoutHistory).toHaveBeenLastCalledWith(
        0,
        10,
        {
          search: "provider-test-001",
          status: "",
          from: undefined,
          to: undefined,
        },
      );
    });

    expect(getAdminPayoutSummary).toHaveBeenCalledTimes(1);
  });

  it("applies the backend status value for Under Review", async () => {
    configurePayout(PENDING_PAYOUT);
    renderPage();

    await screen.findByText(PAYOUT_ID, { exact: true });

    fireEvent.change(
      screen.getByRole("combobox", { name: /^status$/i }),
      { target: { value: "PENDING" } },
    );

    fireEvent.click(
      screen.getByRole("button", { name: /^apply filters$/i }),
    );

    await waitFor(() => {
      expect(getAdminPayoutHistory).toHaveBeenLastCalledWith(
        0,
        10,
        {
          search: "",
          status: "PENDING",
          from: undefined,
          to: undefined,
        },
      );
    });
  });

  it("includes the entire selected end date using local date boundaries", async () => {
    configurePayout(PENDING_PAYOUT);
    renderPage();

    await screen.findByText(PAYOUT_ID, { exact: true });

    fireEvent.change(screen.getByLabelText("From date"), {
      target: { value: "2026-11-01" },
    });

    fireEvent.change(screen.getByLabelText("To date"), {
      target: { value: "2026-11-30" },
    });

    fireEvent.click(
      screen.getByRole("button", { name: /^apply filters$/i }),
    );

    await waitFor(() => {
      expect(getAdminPayoutHistory).toHaveBeenLastCalledWith(
        0,
        10,
        {
          search: "",
          status: "",
          from: new Date("2026-11-01T00:00:00").toISOString(),
          to: new Date("2026-12-01T00:00:00").toISOString(),
        },
      );
    });
  });

  it("rejects an invalid date range without requesting filtered results", async () => {
    configurePayout(PENDING_PAYOUT);
    renderPage();

    await screen.findByText(PAYOUT_ID, { exact: true });

    fireEvent.change(screen.getByLabelText("From date"), {
      target: { value: "2026-11-30" },
    });

    fireEvent.change(screen.getByLabelText("To date"), {
      target: { value: "2026-11-01" },
    });

    fireEvent.click(
      screen.getByRole("button", { name: /^apply filters$/i }),
    );

    expect(
      screen.getByText(
        "The start date must not be after the end date.",
      ),
    ).toBeTruthy();

    expect(getAdminPayoutHistory).toHaveBeenCalledTimes(1);
    expectNoMutations();
  });

  it("resets active filters and returns to the first result page", async () => {
    configurePayout(PENDING_PAYOUT);
    renderPage();

    await screen.findByText(PAYOUT_ID, { exact: true });

    const search = screen.getByLabelText("Search payouts");

    fireEvent.change(search, {
      target: { value: "provider-test-001" },
    });

    fireEvent.click(
      screen.getByRole("button", { name: /^apply filters$/i }),
    );

    await waitFor(() => {
      expect(getAdminPayoutHistory).toHaveBeenCalledTimes(2);
    });

    fireEvent.click(
      screen.getByRole("button", { name: /^reset filters$/i }),
    );

    await waitFor(() => {
      expect(getAdminPayoutHistory).toHaveBeenLastCalledWith(
        0,
        10,
        {
          search: "",
          status: "",
          from: undefined,
          to: undefined,
        },
      );
    });

    expect(search.value).toBe("");
  });

  it("refreshes both the global summary and the queue", async () => {
    configurePayout(PENDING_PAYOUT);
    renderPage();

    await screen.findByText(PAYOUT_ID, { exact: true });

    fireEvent.click(
      screen.getByRole("button", { name: /^refresh$/i }),
    );

    await waitFor(() => {
      expect(getAdminPayoutSummary).toHaveBeenCalledTimes(2);
      expect(getAdminPayoutHistory).toHaveBeenCalledTimes(2);
    });

    expectNoMutations();
  });

  it("shows Review Payout for editable pending requests", async () => {
    configurePayout(PENDING_PAYOUT);
    renderPage();

    expect(
      await screen.findByRole("button", {
        name: /^review payout$/i,
      }),
    ).toBeTruthy();

    expectNoMutations();
  });

  it("shows View Details instead of Review Payout for view-only users", async () => {
    useAreaAccess.mockReturnValue({
      area: "FINANCE_ADMIN",
      mode: "VIEW",
      canEdit: false,
      isViewOnly: true,
    });

    configurePayout(PENDING_PAYOUT);
    renderPage();

    expect(
      await screen.findByRole("button", {
        name: /^view details$/i,
      }),
    ).toBeTruthy();

    expect(
      screen.queryByRole("button", {
        name: /^review payout$/i,
      }),
    ).toBeNull();

    expectNoMutations();
  });
});

/**
 * Verifies pagination boundaries, filter preservation,
 * empty-state recovery and financial operation refresh.
 */
describe("Admin payout pagination and refresh", () => {
  it("displays the correct result range and navigation boundaries", async () => {
    getAdminPayoutHistory.mockResolvedValue({
      content: Array.from({ length: 10 }, (_, index) => ({
        ...PENDING_PAYOUT,
        id: `payout-page-${index}`,
      })),
      page: 0,
      size: 10,
      totalElements: 24,
      totalPages: 3,
    });

    renderPage();

    expect(
      await screen.findByText("Showing 1\u201310 of 24 results"),
    ).toBeTruthy();

    expect(
      screen.getByRole("button", {
        name: "Go to previous page",
      }).disabled,
    ).toBe(true);

    expect(
      screen.getByRole("button", {
        name: "Go to next page",
      }).disabled,
    ).toBe(false);
  });

  it("preserves applied filters when navigating to another page", async () => {
    getAdminPayoutHistory.mockImplementation(async (page) => ({
      content: Array.from(
        { length: page === 0 ? 10 : 4 },
        (_, index) => ({
          ...PENDING_PAYOUT,
          id: `payout-${page}-${index}`,
        }),
      ),
      page,
      size: 10,
      totalElements: 14,
      totalPages: 2,
    }));

    renderPage();

    await screen.findByText("Showing 1\u201310 of 14 results");

    fireEvent.change(
      screen.getByRole("combobox", { name: /^status$/i }),
      { target: { value: "PENDING" } },
    );

    fireEvent.click(
      screen.getByRole("button", { name: /^apply filters$/i }),
    );

    await waitFor(() => {
      expect(getAdminPayoutHistory).toHaveBeenLastCalledWith(
        0,
        10,
        {
          search: "",
          status: "PENDING",
          from: undefined,
          to: undefined,
        },
      );

      expect(
        screen.getByRole("button", {
          name: "Go to next page",
        }).disabled,
      ).toBe(false);
    });

    fireEvent.click(
      screen.getByRole("button", {
        name: "Go to next page",
      }),
    );

    expect(
      await screen.findByText("Showing 11\u201314 of 14 results"),
    ).toBeTruthy();

    expect(getAdminPayoutHistory).toHaveBeenLastCalledWith(
      1,
      10,
      {
        search: "",
        status: "PENDING",
        from: undefined,
        to: undefined,
      },
    );

    expect(
      screen.getByRole("button", {
        name: "Go to next page",
      }).disabled,
    ).toBe(true);

    expect(getAdminPayoutSummary).toHaveBeenCalledTimes(1);
    expectNoMutations();
  });

  it("disables both navigation buttons for empty results", async () => {
    getAdminPayoutHistory.mockResolvedValue(EMPTY_HISTORY);

    renderPage();

    expect(
      await screen.findByText("0 matching results"),
    ).toBeTruthy();

    expect(
      screen.getByRole("button", {
        name: "Go to previous page",
      }).disabled,
    ).toBe(true);

    expect(
      screen.getByRole("button", {
        name: "Go to next page",
      }).disabled,
    ).toBe(true);
  });

  it("clears applied filters from the empty state", async () => {
    getAdminPayoutHistory.mockImplementation(
      async (_page, _size, filters) =>
        filters.search
          ? EMPTY_HISTORY
          : createHistory(PENDING_PAYOUT),
    );

    renderPage();

    await screen.findByText(PAYOUT_ID, { exact: true });

    fireEvent.change(screen.getByLabelText("Search payouts"), {
      target: { value: "missing-provider" },
    });

    fireEvent.click(
      screen.getByRole("button", { name: /^apply filters$/i }),
    );

    await screen.findByText("0 matching results");

    fireEvent.click(
      screen.getByRole("button", { name: /^clear filters$/i }),
    );

    expect(
      await screen.findByText(PAYOUT_ID, { exact: true }),
    ).toBeTruthy();

    expect(screen.getByLabelText("Search payouts").value).toBe("");

    expect(getAdminPayoutHistory).toHaveBeenLastCalledWith(
      0,
      10,
      {
        search: "",
        status: "",
        from: undefined,
        to: undefined,
      },
    );

    expectNoMutations();
  });

  it("disables pagination when the queue request fails", async () => {
    getAdminPayoutHistory.mockRejectedValue(
      new Error("Queue request failed."),
    );

    renderPage();

    expect(
      await screen.findByText("Results unavailable"),
    ).toBeTruthy();

    expect(
      screen.getByRole("button", {
        name: "Go to previous page",
      }).disabled,
    ).toBe(true);

    expect(
      screen.getByRole("button", {
        name: "Go to next page",
      }).disabled,
    ).toBe(true);
  });

  it("refreshes global counts and queue after successful processing", async () => {
    configurePayout(PENDING_PAYOUT);

    getAdminPayoutSummary
      .mockResolvedValueOnce({
        pending: 1,
        processing: 0,
        paid: 0,
        failed: 0,
      })
      .mockResolvedValue({
        pending: 0,
        processing: 1,
        paid: 0,
        failed: 0,
      });

    markPayoutProcessing.mockImplementation(async () => {
      configurePayout(PROCESSING_PAYOUT);
      return PROCESSING_PAYOUT;
    });

    renderPage();

    const dialog = await openDetails();

    beginAction(dialog, "Start Processing");
    confirmAction(dialog, "Start Processing");

    await waitFor(() => {
      expect(markPayoutProcessing).toHaveBeenCalledTimes(1);
      expect(getAdminPayoutSummary).toHaveBeenCalledTimes(2);
      expect(getAdminPayoutHistory).toHaveBeenCalledTimes(2);
    });

    // Return to the queue to verify the refreshed global summary.
    fireEvent.click(
      within(dialog).getByRole("button", {
        name: /^back to payout queue$/i,
      }),
    );

    const processingCard = await screen.findByRole("article", {
      name: "Processing",
    });

    expect(
      await within(processingCard).findByText("1", {
        exact: true,
      }),
    ).toBeTruthy();

    expect(markPayoutPaid).not.toHaveBeenCalled();
    expect(markPayoutFailed).not.toHaveBeenCalled();
  });
});

/**
 * F3 - Filtered payout queue pagination recovery.
 * Page correction must preserve all applied query filters.
 */
describe("Admin payout filtered page recovery", () => {
  const expectedFilters = {
    search: "provider-test-001",
    status: "PENDING",
    from: new Date("2026-11-01T00:00:00").toISOString(),
    to: new Date("2026-12-01T00:00:00").toISOString(),
  };

  function createPage(page, totalElements) {
    const count = Math.max(
      0,
      Math.min(10, totalElements - page * 10),
    );

    return {
      content: Array.from({ length: count }, (_, index) => ({
        ...PENDING_PAYOUT,
        id: page === 1 && index === 0
          ? PAYOUT_ID
          : `filtered-payout-${page}-${index}`,
      })),
      page,
      size: 10,
      totalElements,
      totalPages: Math.ceil(totalElements / 10),
    };
  }

  async function applyFiltersAndOpenSecondPage(totalElements) {
    getAdminPayoutHistory.mockImplementation(
      async (page) => createPage(page, totalElements),
    );

    renderPage();

    await screen.findByText(
      `Showing 1\u201310 of ${totalElements} results`,
    );

    fireEvent.change(screen.getByLabelText("Search payouts"), {
      target: { value: "provider-test-001" },
    });

    fireEvent.change(
      screen.getByRole("combobox", { name: /^status$/i }),
      { target: { value: "PENDING" } },
    );

    fireEvent.change(screen.getByLabelText("From date"), {
      target: { value: "2026-11-01" },
    });

    fireEvent.change(screen.getByLabelText("To date"), {
      target: { value: "2026-11-30" },
    });

    fireEvent.click(
      screen.getByRole("button", { name: /^apply filters$/i }),
    );

    await waitFor(() => {
      expect(getAdminPayoutHistory).toHaveBeenLastCalledWith(
        0,
        10,
        expectedFilters,
      );

      expect(
        screen.getByRole("button", {
          name: "Go to next page",
        }).disabled,
      ).toBe(false);
    });

    fireEvent.click(
      screen.getByRole("button", {
        name: "Go to next page",
      }),
    );

    await screen.findByText(
      `Showing 11\u2013${totalElements} of ${totalElements} results`,
    );

    expect(getAdminPayoutHistory).toHaveBeenLastCalledWith(
      1,
      10,
      expectedFilters,
    );
  }

  it("returns to the last valid filtered page after processing its final payout", async () => {
    await applyFiltersAndOpenSecondPage(11);

    getAdminPayoutDetails.mockResolvedValue(PENDING_PAYOUT);

    markPayoutProcessing.mockImplementation(async () => {
      getAdminPayoutHistory.mockImplementation(
        async (page) => createPage(page, 10),
      );

      return PROCESSING_PAYOUT;
    });

    const dialog = await openDetails();

    beginAction(dialog, "Start Processing");
    confirmAction(dialog, "Start Processing");

    await within(dialog).findByText("PROCESSING", {
      exact: true,
    });

    await waitFor(() => {
      expect(markPayoutProcessing).toHaveBeenCalledTimes(1);
      expect(markPayoutProcessing).toHaveBeenCalledWith(PAYOUT_ID);

      expect(getAdminPayoutHistory).toHaveBeenLastCalledWith(
        0,
        10,
        expectedFilters,
      );

      expect(getAdminPayoutHistory).toHaveBeenCalledTimes(5);
    });

    fireEvent.click(
      within(dialog).getByRole("button", {
        name: /^back to payout queue$/i,
      }),
    );

    expect(
      await screen.findByText("Showing 1\u201310 of 10 results"),
    ).toBeTruthy();

    expect(screen.getByLabelText("Search payouts").value)
      .toBe("provider-test-001");

    expect(
      screen.getByRole("combobox", { name: /^status$/i }).value,
    ).toBe("PENDING");

    expect(screen.getByLabelText("From date").value)
      .toBe("2026-11-01");

    expect(screen.getByLabelText("To date").value)
      .toBe("2026-11-30");

    expect(
      screen.getByRole("button", {
        name: "Go to previous page",
      }).disabled,
    ).toBe(true);

    expect(
      screen.getByRole("button", {
        name: "Go to next page",
      }).disabled,
    ).toBe(true);

    expect(markPayoutPaid).not.toHaveBeenCalled();
    expect(markPayoutFailed).not.toHaveBeenCalled();
  });

  it("returns to page zero when refreshed filters have no matching results", async () => {
    await applyFiltersAndOpenSecondPage(11);

    getAdminPayoutHistory.mockImplementation(
      async (page) => createPage(page, 0),
    );

    fireEvent.click(
      screen.getByRole("button", { name: /^refresh$/i }),
    );

    expect(
      await screen.findByText("0 matching results"),
    ).toBeTruthy();

    expect(getAdminPayoutHistory).toHaveBeenLastCalledWith(
      0,
      10,
      expectedFilters,
    );

    expect(getAdminPayoutHistory).toHaveBeenCalledTimes(5);

    expect(
      screen.getByRole("button", {
        name: "Go to previous page",
      }).disabled,
    ).toBe(true);

    expect(
      screen.getByRole("button", {
        name: "Go to next page",
      }).disabled,
    ).toBe(true);

    expectNoMutations();
  });

  it("preserves the current page when refreshed results still fit that page", async () => {
    await applyFiltersAndOpenSecondPage(15);

    getAdminPayoutHistory.mockImplementation(
      async (page) => createPage(page, 14),
    );

    fireEvent.click(
      screen.getByRole("button", { name: /^refresh$/i }),
    );

    expect(
      await screen.findByText("Showing 11\u201314 of 14 results"),
    ).toBeTruthy();

    expect(getAdminPayoutHistory).toHaveBeenLastCalledWith(
      1,
      10,
      expectedFilters,
    );

    expect(getAdminPayoutHistory).toHaveBeenCalledTimes(4);

    expectNoMutations();
  });
});

describe("Admin payout confirmation safety", () => {
  function getProcessingConfirmation() {
    return screen.getByRole("dialog", {
      name: /^start payout processing$/i,
    });
  }

  function getConfirmationCheckbox(modal) {
    return within(modal).getByRole("checkbox", {
      name: /I confirm that I have reviewed this payout request/i,
    });
  }

  function getProcessingButton(modal) {
    return within(modal).getByRole("button", {
      name: /^start processing$/i,
    });
  }

  it("disables processing confirmation until acknowledgement", async () => {
    renderPage();

    const review = await openDetails();
    beginAction(review, "Start Processing");

    const modal = getProcessingConfirmation();
    const checkbox = getConfirmationCheckbox(modal);
    const button = getProcessingButton(modal);

    expect(checkbox.checked).toBe(false);
    expect(button.disabled).toBe(true);

    fireEvent.click(button);

    expectNoMutations();
  });

  it("enables confirmation only while acknowledgement is selected", async () => {
    renderPage();

    const review = await openDetails();
    beginAction(review, "Start Processing");

    const modal = getProcessingConfirmation();
    const checkbox = getConfirmationCheckbox(modal);
    const button = getProcessingButton(modal);

    fireEvent.click(checkbox);

    expect(checkbox.checked).toBe(true);
    expect(button.disabled).toBe(false);
    expectNoMutations();

    fireEvent.click(checkbox);

    expect(checkbox.checked).toBe(false);
    expect(button.disabled).toBe(true);
    expectNoMutations();
  });

  it("blocks direct form submission without acknowledgement", async () => {
    renderPage();

    const review = await openDetails();
    beginAction(review, "Start Processing");

    const modal = getProcessingConfirmation();
    const form = getProcessingButton(modal).closest("form");

    expect(form).not.toBeNull();

    // Verify the handler independently of the disabled button.
    fireEvent.submit(form);

    expect(
      await within(modal).findByText(
        "Confirm the payout review before starting processing.",
        { exact: true },
      ),
    ).toBeTruthy();

    expectNoMutations();
  });

  it("resets acknowledgement after cancelling and reopening", async () => {
    renderPage();

    const review = await openDetails();
    beginAction(review, "Start Processing");

    const modal = getProcessingConfirmation();

    fireEvent.click(getConfirmationCheckbox(modal));

    expect(getProcessingButton(modal).disabled).toBe(false);

    fireEvent.click(
      within(modal).getByRole("button", {
        name: /^cancel$/i,
      }),
    );

    expect(screen.queryByRole("dialog")).toBeNull();

    beginAction(review, "Start Processing");

    const reopenedModal = getProcessingConfirmation();

    expect(getConfirmationCheckbox(reopenedModal).checked).toBe(false);
    expect(getProcessingButton(reopenedModal).disabled).toBe(true);

    expectNoMutations();
  });

  it("submits processing only after explicit acknowledgement", async () => {
    renderPage();

    const review = await openDetails();
    beginAction(review, "Start Processing");

    const modal = getProcessingConfirmation();

    fireEvent.click(getConfirmationCheckbox(modal));

    expectNoMutations();

    fireEvent.click(getProcessingButton(modal));

    await waitFor(() => {
      expect(markPayoutProcessing).toHaveBeenCalledTimes(1);
      expect(markPayoutProcessing).toHaveBeenCalledWith(PAYOUT_ID);
    });

    expect(
      await within(review).findByText("PROCESSING", {
        exact: true,
      }),
    ).toBeTruthy();

    expect(markPayoutPaid).not.toHaveBeenCalled();
    expect(markPayoutFailed).not.toHaveBeenCalled();
  });
});
