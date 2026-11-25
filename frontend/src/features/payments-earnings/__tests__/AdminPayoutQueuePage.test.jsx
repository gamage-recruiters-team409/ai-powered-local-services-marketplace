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

/** Opens the payout details dialog and waits for its data. */
async function openDetails() {
  const detailsButton = await screen.findByRole("button", {
    name: /^view details$/i,
  });

  fireEvent.click(detailsButton);

  const dialog = await screen.findByRole("dialog", {
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
function confirmAction(dialog, label) {
  fireEvent.click(
    within(dialog).getByRole("button", {
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

    expect(getAdminPayoutHistory).toHaveBeenCalledWith(0, 10);

    expect(
      screen.getByText("PENDING", {
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
        name: /^view details$/i,
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
        name: /^view details$/i,
      }),
    );

    const dialog = await screen.findByRole("dialog", {
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
      within(dialog).getByRole("heading", {
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
      within(dialog).getByRole("button", {
        name: /^cancel$/i,
      }),
    );

    expect(
      within(dialog).queryByRole("heading", {
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

    const referenceInput = within(dialog).getByRole("textbox", {
      name: /bank transfer reference/i,
    });

    fireEvent.change(referenceInput, {
      target: { value: "   " },
    });

    confirmAction(dialog, "Mark as Paid");

    expect(
      await within(dialog).findByText(
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
      within(dialog).getByRole("textbox", {
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
      within(dialog).getByRole("textbox", {
        name: /failure reason/i,
      }),
      {
        target: { value: "   " },
      },
    );

    confirmAction(dialog, "Mark as Failed");

    expect(
      await within(dialog).findByText("A failure reason is required.", {
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
      within(dialog).getByRole("textbox", {
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
        name: /^view details$/i,
      })[1],
    );

    const dialog = await screen.findByRole("dialog", {
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
        name: /^view details$/i,
      }))[0],
    );

    const firstDialog = await screen.findByRole("dialog");

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
        name: /^close$/i,
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
        name: /^view details$/i,
      }))[0],
    );

    const firstDialog = await screen.findByRole("dialog");

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
          name: /^close$/i,
        }).disabled,
      ).toBe(false);
    });

    fireEvent.click(
      within(firstDialog).getByRole("button", {
        name: /^close$/i,
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
