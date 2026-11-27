import { Link } from "react-router-dom";
import { useEffect, useRef, useState } from "react";

import { ProviderPage } from "../../../shared/layouts/provider/ProviderPage";
import Button from "../../../shared/components/forms/Button";
import Input from "../../../shared/components/forms/Input";
import Checkbox from "../../../shared/components/forms/Checkbox";
import Badge from "../../../shared/components/forms/Badge";
import DataTable from "../../../shared/components/forms/DataTable";
import Pagination from "../../../shared/components/forms/Pagination";
import Modal from "../../../shared/components/forms/Modal";
import Alert from "../../../shared/components/feedback/Alert";
import PageLoader from "../../../shared/components/feedback/PageLoader";

import {
  getProviderEarningsSummary,
  getProviderItemizedEarnings,
  requestProviderPayout,
  getProviderPayoutHistory,
  getProviderPayoutDetails,
} from "../services/paymentsEarningsApi";

import { useAuth } from "../../auth/hooks/useAuth";

import {
  loadPendingPayoutRequest,
  savePendingPayoutRequest,
  clearPendingPayoutRequest,
  withPayoutSubmissionLock,
  loadPayoutSubmissionRevision,
  advancePayoutSubmissionRevision,
} from "../utils/payoutRequestStorage";

import styles from "./ProviderEarningsPage.module.css";

const PAGE_SIZE = 10;
const RECENT_EARNINGS_SIZE = 5;
const MINIMUM_PAYOUT = 5000;

const STATUS_VARIANTS = {
  PENDING: "warning",
  PROCESSING: "info",
  PAID: "success",
  FAILED: "error",
};

/** Formats financial amounts using the supported payout currency. */
function formatMoney(amount, currency = "LKR") {
  if (amount == null || !Number.isFinite(Number(amount))) {
    return "—";
  }

  return new Intl.NumberFormat("en-LK", {
    style: "currency",
    currency,
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(Number(amount));
}

/** Formats backend timestamps for display. */
function formatDate(value) {
  if (!value) return "—";

  const date = new Date(value);

  return Number.isNaN(date.getTime()) ? "—" : date.toLocaleString("en-LK");
}

/** Validates the amount against the backend request contract. */
function validateAmount(value, available) {
  const normalized = value.trim();

  if (!/^\d{1,12}(\.\d{1,2})?$/.test(normalized)) {
    return "Enter a valid amount with up to two decimal places.";
  }

  const amount = Number(normalized);

  if (amount < MINIMUM_PAYOUT) {
    return "The minimum payout amount is LKR 5,000.00.";
  }

  if (available == null || !Number.isFinite(Number(available))) {
    return "Your available earnings could not be verified.";
  }

  if (amount > Number(available)) {
    return "The requested amount exceeds your available earnings.";
  }

  return "";
}

/** Formats verified earnings amounts without inventing missing values. */
function formatRecentMoney(value, currency) {
  if (
    value == null ||
    value === "" ||
    !currency ||
    !Number.isFinite(Number(value))
  ) {
    return "Unavailable";
  }

  try {
    return formatMoney(value, currency);
  } catch {
    return "Unavailable";
  }
}

/** Displays the actual commission rate recorded for each payment. */
function formatRecentCommissionRate(value) {
  if (
    value == null ||
    value === "" ||
    !Number.isFinite(Number(value))
  ) {
    return "Unavailable";
  }

  return new Intl.NumberFormat("en-LK", {
    style: "percent",
    maximumFractionDigits: 2,
  }).format(Number(value));
}

/** Booking-level earnings preview; amounts are not withdrawable balances. */
const recentEarningsColumns = [
  {
    key: "recognizedAt",
    header: "Recognized Date",
    render: (value) => formatDate(value),
  },
  {
    key: "bookingId",
    header: "Booking ID",
  },
  {
    key: "grossAmount",
    header: "Gross Amount",
    render: (value, row) => formatRecentMoney(value, row.currency),
  },
  {
    key: "commissionRate",
    header: "Commission Rate",
    render: (value) => formatRecentCommissionRate(value),
  },
  {
    key: "commissionAmount",
    header: "Commission",
    render: (value, row) => formatRecentMoney(value, row.currency),
  },
  {
    key: "refundAdjustmentAmount",
    header: "Refund Adjustments",
    render: (value, row) => formatRecentMoney(value, row.currency),
  },
  {
    key: "netEarnings",
    header: "Net Earnings",
    render: (value, row) => (
      <strong>{formatRecentMoney(value, row.currency)}</strong>
    ),
  },
];

function PayoutStatusBadge({ status }) {
  return (
    <Badge variant={STATUS_VARIANTS[status] ?? "neutral"}>
      {status ?? "UNKNOWN"}
    </Badge>
  );
}

/** Displays a single earnings balance without recalculating ledger totals. */
function EarningsCard({ label, amount, currency, description }) {
  return (
    <article className={styles.earningsCard}>
      <span className={styles.cardLabel}>{label}</span>

      <strong className={styles.cardAmount}>
        {formatMoney(amount, currency)}
      </strong>

      <p className={styles.cardDescription}>{description}</p>
    </article>
  );
}

/**
 * Provider-facing earnings and manual payout management page.
 * All financial balances and payout decisions remain backend-authoritative.
 */
export default function ProviderEarningsPage() {
  const { user, isLoading } = useAuth();

  if (isLoading) {
    return <PageLoader label="Loading provider account..." />;
  }

  if (!user?.id || user.role !== "PROVIDER") {
    return (
      <Alert variant="error" title="Provider account required">
        An authenticated provider account is required.
      </Alert>
    );
  }

  // Remount financial state when the authenticated account changes.
  return (
    <ProviderEarningsContent
      key={user.id}
      providerId={user.id}
    />
  );
}

/**
 * Restores recovery data without silently discarding invalid records.
 */
function restoreRecoveryState(providerId) {
  try {
    return {
      payload: loadPendingPayoutRequest(providerId),
      error: "",
    };
  } catch (error) {
    return {
      payload: null,
      error: error.message || "Payout recovery storage is unavailable.",
    };
  }
}

/** Account-scoped provider earnings and payout content. */
function ProviderEarningsContent({ providerId }) {
  const [recovery] = useState(() =>
    restoreRecoveryState(providerId),
  );

  const [storageError, setStorageError] = useState(
    recovery.error,
  );
  const [summary, setSummary] = useState(null);
  const [summaryLoading, setSummaryLoading] = useState(true);
  const [summaryError, setSummaryError] = useState("");

  const [recentEarningsResult, setRecentEarningsResult] = useState(null);

  const [history, setHistory] = useState(null);
  const [historyLoading, setHistoryLoading] = useState(true);
  const [historyError, setHistoryError] = useState("");

  const [page, setPage] = useState(0);
  const [refreshVersion, setRefreshVersion] = useState(0);

  const [amount, setAmount] = useState(
    recovery.payload ? String(recovery.payload.amount) : "",
  );
  const [amountError, setAmountError] = useState("");
  const [submitError, setSubmitError] = useState("");
  const [successMessage, setSuccessMessage] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [retryRequired, setRetryRequired] = useState(
    Boolean(recovery.payload),
  );

  const [requestOpen, setRequestOpen] = useState(false);
  const [requestConfirmed, setRequestConfirmed] = useState(false);

  const [detailsOpen, setDetailsOpen] = useState(false);
  const [details, setDetails] = useState(null);
  const [detailsLoading, setDetailsLoading] = useState(false);
  const [detailsError, setDetailsError] = useState("");

  // Preserve the original request payload when its outcome is uncertain.
  const pendingRequestRef = useRef(recovery.payload);

  // Prevent concurrent submissions before React updates the button state.
  const submissionLockRef = useRef(false);

  // Detect confirmations prepared before another tab submitted a payout.
  const submissionRevisionRef = useRef(null);

  // Ignore outdated detail responses after closing or switching records.
  const detailsVersionRef = useRef(0);

  const currency = summary?.currency ?? "LKR";

  const insufficientBalance =
    !summaryLoading &&
    summary != null &&
    Number(summary.available) < MINIMUM_PAYOUT;

  /** Opens the payout dialog without discarding recovery information. */
  function openRequest() {
    if (submissionLockRef.current) return;

    try {
      submissionRevisionRef.current =
        loadPayoutSubmissionRevision(providerId);
    } catch (error) {
      setStorageError(
        error.message || "Unable to verify payout coordination data.",
      );
    }

    setRequestConfirmed(false);
    setRequestOpen(true);
  }

  /** Prevents dismissal while a financial submission is in progress. */
  function closeRequest() {
    if (submissionLockRef.current) return;

    setRequestOpen(false);
    setRequestConfirmed(false);
  }

  useEffect(() => {
    let active = true;

    async function loadSummary() {
      setSummaryLoading(true);
      setSummaryError("");

      try {
        const result = await getProviderEarningsSummary();

        if (active) {
          setSummary(result);
        }
      } catch (error) {
        if (active) {
          setSummary(null);
          setSummaryError(error.message || "Unable to load earnings summary.");
        }
      } finally {
        if (active) {
          setSummaryLoading(false);
        }
      }
    }

    loadSummary();

    return () => {
      active = false;
    };
  }, [refreshVersion]);

  useEffect(() => {
    let active = true;

    async function loadHistory() {
      setHistoryLoading(true);
      setHistoryError("");

      try {
        const result = await getProviderPayoutHistory(page, PAGE_SIZE);

        if (active) {
          setHistory(result);
        }
      } catch (error) {
        if (active) {
          setHistory(null);
          setHistoryError(error.message || "Unable to load payout history.");
        }
      } finally {
        if (active) {
          setHistoryLoading(false);
        }
      }
    }

    loadHistory();

    return () => {
      active = false;
    };
  }, [page, refreshVersion]);

  useEffect(() => {
    let active = true;

    async function loadRecentEarnings() {
      try {
        const result = await getProviderItemizedEarnings(
          0,
          RECENT_EARNINGS_SIZE,
        );

        if (
          !Array.isArray(result?.content) ||
          result.page !== 0 ||
          result.content.length > RECENT_EARNINGS_SIZE
        ) {
          throw new Error("Invalid recent earnings response.");
        }

        if (active) {
          setRecentEarningsResult({
            version: refreshVersion,
            content: result.content,
            error: "",
          });
        }
      } catch {
        if (active) {
          setRecentEarningsResult({
            version: refreshVersion,
            content: [],
            error: "Unable to load recent earnings.",
          });
        }
      }
    }

    loadRecentEarnings();

    // Ignore responses from obsolete requests or unmounted accounts.
    return () => {
      active = false;
    };
  }, [refreshVersion]);

  // Hide stale results immediately when the dashboard is refreshed.
  const currentRecentEarnings =
    recentEarningsResult?.version === refreshVersion
      ? recentEarningsResult
      : null;

  function refreshData() {
    setRefreshVersion((previous) => previous + 1);
  }

  function handleAmountChange(event) {
    if (pendingRequestRef.current) return;

    setAmount(event.target.value);
    setRequestConfirmed(false);
    setAmountError("");
    setSubmitError("");
    setSuccessMessage("");
  }

  /**
   * Persists recovery information before any financial request.
   * Uncertain outcomes must retain the original request key.
   */
  async function handleSubmit(event) {
    event.preventDefault();

    if (submissionLockRef.current || storageError) return;

    if (!requestConfirmed) {
      setSubmitError("Confirm the payout amount before submitting.");
      return;
    }

    submissionLockRef.current = true;
    setSubmitting(true);

    try {
      const acquired = await withPayoutSubmissionLock(
        providerId,
        submitUnderLock,
      );

      if (!acquired) {
        setRequestConfirmed(false);
        setSubmitError(
          "A payout submission is active in another tab. Review your payout history before trying again.",
        );
        refreshData();
      }
    } catch (error) {
      setRequestConfirmed(false);
      setStorageError(
        error.message || "Unable to coordinate payout submission safely.",
      );
    } finally {
      submissionLockRef.current = false;
      setSubmitting(false);
    }
  }

  /**
   * Executes the complete payout lifecycle under the provider's Web Lock.
   * Recovery lookup, persistence, API submission and cleanup are serialized.
   */
  async function submitUnderLock() {
    let payload;
    let wasRecovery;

    try {
      // Re-read storage only after acquiring the exclusive provider lock.
      const stored = loadPendingPayoutRequest(providerId);

      const currentRevision =
        loadPayoutSubmissionRevision(providerId);

      const previousPayload = pendingRequestRef.current;

      // A missing recovery record is not sufficient evidence of resolution.
      if (previousPayload && !stored) {
        if (
          currentRevision === null ||
          submissionRevisionRef.current === currentRevision
        ) {
          // Preserve the original in-memory identity and fail closed.
          setRetryRequired(true);
          setRequestConfirmed(false);
          setSuccessMessage("");
          setStorageError(
            "The original payout recovery record is missing. Review your payout history and contact support before requesting another payout.",
          );
          return;
        }

        // A changed revision indicates intervening coordinated activity.
        pendingRequestRef.current = null;
        submissionRevisionRef.current = currentRevision;
        setRetryRequired(false);
        setAmount("");
        setRequestConfirmed(false);
        setSuccessMessage("");
        setSubmitError(
          "The original payout request is no longer pending locally. Review your payout history before starting another request.",
        );
        refreshData();
        return;
      }

      if (
        stored &&
        (!previousPayload ||
          previousPayload.requestKey !== stored.requestKey ||
          previousPayload.amount !== stored.amount)
      ) {
        submissionRevisionRef.current = currentRevision;
        // Require explicit confirmation before retrying a request
        // discovered after the current page was opened.
        pendingRequestRef.current = stored;
        setAmount(String(stored.amount));
        setAmountError("");
        setSuccessMessage("");
        setRetryRequired(true);
        setRequestConfirmed(false);
        setSubmitError(
          "An unresolved payout request was found. Review the original amount and select Retry Payout Request.",
        );
        return;
      }

      if (submissionRevisionRef.current !== currentRevision) {
        submissionRevisionRef.current = currentRevision;
        setRequestConfirmed(false);
        setSuccessMessage("");
        setSubmitError(
          "Payout activity changed in another tab. Review your updated earnings and confirm the request again.",
        );
        refreshData();
        return;
      }

      payload = stored;
      wasRecovery = Boolean(payload);

      if (!payload) {
        const validationError = validateAmount(
          amount,
          summary?.available,
        );

        if (validationError) {
          setAmountError(validationError);
          return;
        }

        if (!globalThis.crypto?.randomUUID) {
          setSubmitError(
            "A secure request identifier could not be generated.",
          );
          return;
        }

        payload = {
          requestKey: globalThis.crypto.randomUUID(),
          amount: Number(amount.trim()),
        };
      }

      // Invalidate other tabs' previously prepared confirmations.
      submissionRevisionRef.current =
        advancePayoutSubmissionRevision(providerId);

      // Fail closed if recovery information cannot be persisted.
      savePendingPayoutRequest(providerId, payload);

      pendingRequestRef.current = payload;
      setAmount(String(payload.amount));
    } catch (error) {
      setStorageError(
        error.message || "Unable to persist payout recovery data.",
      );
      return;
    }

    setAmountError("");
    setSubmitError("");
    setSuccessMessage("");

    /**
     * Removes only the recovery record belonging to this request.
     * Cleanup failures block new submissions until recovery is checked.
     */
    function clearResolvedRequest() {
      try {
        const cleared = clearPendingPayoutRequest(
          providerId,
          payload.requestKey,
        );

        if (!cleared && loadPendingPayoutRequest(providerId)) {
          throw new Error(
            "Another unresolved payout request exists.",
          );
        }

        pendingRequestRef.current = null;
        setRetryRequired(false);
        return true;
      } catch {
        setRetryRequired(true);
        setStorageError(
          "Unable to clear payout recovery data. Reload this page before submitting another request.",
        );
        return false;
      }
    }

    try {
      const result = await requestProviderPayout(payload);

      // The server has confirmed the request outcome.
      const cleared = clearResolvedRequest();

      if (cleared) {
        setAmount("");
        setRequestOpen(false);
        setRequestConfirmed(false);
      }

      setSuccessMessage(
        `Payout request received. Current status: ${
          result.status ?? "UNKNOWN"
        }.`,
      );

      setPage(0);
      refreshData();
    } catch (error) {
      const status = Number(error.status);

      // The backend checks existing request keys before eligibility.
      const eligibilityRejected =
        status === 409 &&
        error.code === "INSUFFICIENT_ELIGIBLE_EARNINGS";

      // Authentication or validation rejection is only definitive
      // when no earlier uncertain submission exists.
      // Missing provider profiles are rejected before any financial mutation.
      // A previously uncertain request must still retain its original key.
      const missingProfileRejected =
        status === 404 &&
        error.code === "PROVIDER_PROFILE_NOT_FOUND";

      const initialClientRejection =
        !wasRecovery &&
        ([400, 401, 403, 422].includes(status) ||
          missingProfileRejected);

      if (!wasRecovery && (eligibilityRejected || initialClientRejection)) {
        clearResolvedRequest();
      } else {
        // Preserve the exact payload after uncertain outcomes.
        setRetryRequired(true);
      }

      setSubmitError(
        missingProfileRejected
          ? wasRecovery
            ? "Complete or restore your provider profile before retrying the original payout request."
            : "Complete or restore your provider profile before requesting a payout."
          : error.message || "Unable to confirm the payout request.",
      );

      refreshData();
    }
  }

  /** Loads the selected payout through the secured details endpoint. */
  async function openDetails(payoutId) {
    const version = ++detailsVersionRef.current;

    setDetailsOpen(true);
    setDetails(null);
    setDetailsError("");
    setDetailsLoading(true);

    try {
      const result = await getProviderPayoutDetails(payoutId);

      if (version === detailsVersionRef.current) {
        setDetails(result);
      }
    } catch (error) {
      if (version === detailsVersionRef.current) {
        setDetailsError(error.message || "Unable to load payout details.");
      }
    } finally {
      if (version === detailsVersionRef.current) {
        setDetailsLoading(false);
      }
    }
  }

  function closeDetails() {
    detailsVersionRef.current += 1;
    setDetailsOpen(false);
    setDetails(null);
    setDetailsError("");
    setDetailsLoading(false);
  }

  const columns = [
    {
      key: "id",
      header: "Payout ID",
    },
    {
      key: "amount",
      header: "Amount",
      render: (value, row) => formatMoney(value, row.currency),
    },
    {
      key: "status",
      header: "Status",
      render: (value) => <PayoutStatusBadge status={value} />,
    },
    {
      key: "createdAt",
      header: "Requested At",
      render: (value) => formatDate(value),
    },
    {
      key: "paidAt",
      header: "Paid At",
      render: (value) => formatDate(value),
    },
  ];

  return (
    <ProviderPage
      title="Earnings Overview"
      subtitle="Track your earnings and manage payout requests."
      showBack={false}
      plain
    >
      <div className={styles.container}>
        <Alert variant="info">
          Captured funds are held until the service is completed and the
          72-hour holding period is satisfied. Held earnings cannot be withdrawn.
        </Alert>

        <div className={styles.sectionHeader}>
          <h2>Earnings Balances</h2>

          <Button
            onClick={refreshData}
            disabled={summaryLoading || historyLoading}
          >
            Refresh
          </Button>
        </div>

        {summaryLoading ? (
          <PageLoader label="Loading earnings..." />
        ) : summaryError ? (
          <Alert variant="error" title="Earnings unavailable">
            {summaryError}
          </Alert>
        ) : summary ? (
          <div className={styles.earningsGrid}>
            <EarningsCard
              label="Available Balance"
              amount={summary.available}
              currency={currency}
              description="Available earnings, subject to payout eligibility."
            />

            <EarningsCard
              label="Pending Earnings"
              amount={summary.pending}
              currency={currency}
              description="Earnings awaiting release."
            />

            <EarningsCard
              label="Held Earnings"
              amount={summary.held}
              currency={currency}
              description="Earnings currently held or reserved."
            />

            <EarningsCard
              label="Total Paid Out"
              amount={summary.paidOut}
              currency={currency}
              description="Earnings already paid to you."
            />
          </div>
        ) : null}

        <section className={styles.panel}>
          <div className={styles.sectionHeader}>
            <div>
              <h2>Recent Earnings</h2>
              <p className={styles.description}>
                Your latest booking-level earnings, commissions and refund
                adjustments.
              </p>
            </div>

            <Link to="/provider/earnings/itemized">
              View All Earnings
            </Link>
          </div>

          <DataTable
            columns={recentEarningsColumns}
            data={currentRecentEarnings?.content ?? []}
            getRowId={(row) => row.paymentId}
            loading={currentRecentEarnings === null}
            error={currentRecentEarnings?.error || undefined}
            emptyMessage="No recognized earnings are available yet."
          />

          <p className={styles.description}>
            Net earnings reflect booking-level earnings after commission and
            refund adjustments, not the amount currently available for payout.
          </p>
        </section>

        <section className={styles.panel}>
          <div className={styles.sectionHeader}>
            <div>
              <h2>Payout Actions</h2>
              <p className={styles.description}>
                Manual payouts require Finance Admin review.
                Minimum payout: LKR 5,000.00.
              </p>
            </div>

            <div className={styles.payoutActions}>
              <Button
                variant="secondary"
                onClick={() =>
                  document.getElementById("wp07-payout-history")
                    ?.scrollIntoView({ behavior: "smooth" })
                }
              >
                View Payout History
              </Button>

              <Button onClick={openRequest}>
                Request Payout
              </Button>
            </div>
          </div>

          {successMessage && (
            <Alert
              variant="success"
              title="Payout request received"
              onClose={() => setSuccessMessage("")}
            >
              {successMessage}
            </Alert>
          )}

          {retryRequired && (
            <Alert variant="warning" title="Request not confirmed">
              Open Request Payout to recover the original request safely.
            </Alert>
          )}

          {storageError && (
            <Alert variant="error" title="Payout recovery unavailable">
              {storageError}
            </Alert>
          )}
        </section>

        <Modal
          isOpen={requestOpen}
          onClose={closeRequest}
          title="Request Payout"
          closeOnBackdrop={false}
          showCloseButton={!submitting}
        >
          <div className={styles.requestContent}>
            <div
              className={`${styles.balancePanel} ${
                insufficientBalance ? styles.balanceWarning : ""
              }`}
            >
              <span>Available payout balance</span>
              <strong>{formatMoney(summary?.available, currency)}</strong>
              <small>Held earnings: {formatMoney(summary?.held, currency)}</small>
              <small>Minimum manual payout: LKR 5,000.00</small>
            </div>

            {insufficientBalance && !retryRequired && (
              <Alert variant="warning" title="Insufficient balance">
                Your available balance is below the minimum payout amount.
              </Alert>
            )}

          <p className={styles.description}>
            Request a manual payout from your eligible earnings. The minimum
            payout amount is LKR 5,000.00.
          </p>

          <Alert variant="info" title="Payout eligibility">
            Eligible completed bookings must satisfy the 72-hour maturity
            period. Disputed or refunded bookings may be excluded. The final
            eligible amount is verified by the server.
          </Alert>

          {submitError && (
            <Alert variant="error" title="Payout request issue">
              {submitError}
            </Alert>
          )}

          {storageError && (
            <Alert variant="error" title="Payout recovery unavailable">
              {storageError}
            </Alert>
          )}

          {retryRequired && (
            <Alert variant="warning" title="Request not confirmed">
              Retry the original request to safely confirm its outcome. Do not
              submit a separate payout for the same intended withdrawal.
            </Alert>
          )}

          <form
            className={styles.payoutForm}
            onSubmit={handleSubmit}
            noValidate
          >
            <Input
              label="Payout amount (LKR)"
              type="text"
              inputMode="decimal"
              placeholder="5000.00"
              value={amount}
              onChange={handleAmountChange}
              error={amountError || undefined}
              helperText={`Available balance: ${formatMoney(
                summary?.available,
                currency,
              )}`}
              disabled={
                submitting ||
                retryRequired ||
                Boolean(storageError) ||
                insufficientBalance
              }
              required
            />

            <p className={styles.description}>
              Payouts are reviewed manually. Bank account details are not
              available in this view. Submission does not confirm a transfer.
            </p>

            <Checkbox
              label="I confirm that the payout amount is correct."
              checked={requestConfirmed}
              onChange={(event) =>
                setRequestConfirmed(event.target.checked)
              }
              disabled={submitting || Boolean(storageError)}
            />

            <div className={styles.modalActions}>
              <Button
                type="button"
                variant="secondary"
                onClick={closeRequest}
                disabled={submitting}
              >
                Cancel
              </Button>

            <Button
              type="submit"
              loading={submitting}
              disabled={
                !requestConfirmed ||
                Boolean(storageError) ||
                (!retryRequired &&
                  (summaryLoading ||
                    !summary ||
                    Number(summary.available) < MINIMUM_PAYOUT))
              }
            >
              {retryRequired ? "Retry Payout Request" : "Submit Payout Request"}
            </Button>
            </div>
          </form>
          </div>
        </Modal>

        <section id="wp07-payout-history" className={styles.panel}>
          <h2>Payout History</h2>

          <DataTable
            columns={columns}
            data={history?.content ?? []}
            getRowId={(row) => row.id}
            loading={historyLoading}
            error={historyError || undefined}
            emptyMessage="You have not requested any payouts yet."
            actions={(row) => (
              <Button size="small" onClick={() => openDetails(row.id)}>
                View Details
              </Button>
            )}
            pagination={
              (history?.totalPages ?? 0) > 1 ? (
                <Pagination
                  currentPage={page + 1}
                  totalPages={history.totalPages}
                  onPageChange={(nextPage) => setPage(nextPage - 1)}
                  disabled={historyLoading}
                />
              ) : null
            }
          />
        </section>

        <Modal
          isOpen={detailsOpen}
          onClose={closeDetails}
          title="Payout Details"
          description="View the current status of your payout."
          footer={<Button onClick={closeDetails}>Close</Button>}
        >
          {detailsLoading && <PageLoader label="Loading payout details..." />}

          {detailsError && <Alert variant="error">{detailsError}</Alert>}

          {details && (
            <dl className={styles.detailsList}>
              <dt>Payout ID</dt>
              <dd>{details.id}</dd>

              <dt>Amount</dt>
              <dd>{formatMoney(details.amount, details.currency)}</dd>

              <dt>Status</dt>
              <dd>
                <PayoutStatusBadge status={details.status} />
              </dd>

              <dt>Requested At</dt>
              <dd>{formatDate(details.createdAt)}</dd>

              <dt>Last Updated</dt>
              <dd>{formatDate(details.updatedAt)}</dd>

              <dt>Paid At</dt>
              <dd>{formatDate(details.paidAt)}</dd>
            </dl>
          )}
        </Modal>
      </div>
    </ProviderPage>
  );
}
