import { useEffect, useRef, useState } from "react";

import { ProviderPage } from "../../../shared/layouts/provider/ProviderPage";
import Button from "../../../shared/components/forms/Button";
import Input from "../../../shared/components/forms/Input";
import Badge from "../../../shared/components/forms/Badge";
import DataTable from "../../../shared/components/forms/DataTable";
import Pagination from "../../../shared/components/forms/Pagination";
import Modal from "../../../shared/components/forms/Modal";
import Alert from "../../../shared/components/feedback/Alert";
import PageLoader from "../../../shared/components/feedback/PageLoader";

import {
  getProviderEarningsSummary,
  requestProviderPayout,
  getProviderPayoutHistory,
  getProviderPayoutDetails,
} from "../services/paymentsEarningsApi";

import { useAuth } from "../../auth/hooks/useAuth";

import {
  loadPendingPayoutRequest,
  savePendingPayoutRequest,
  clearPendingPayoutRequest,
} from "../utils/payoutRequestStorage";

import styles from "./ProviderEarningsPage.module.css";

const PAGE_SIZE = 10;
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

  const [detailsOpen, setDetailsOpen] = useState(false);
  const [details, setDetails] = useState(null);
  const [detailsLoading, setDetailsLoading] = useState(false);
  const [detailsError, setDetailsError] = useState("");

  // Preserve the original request payload when its outcome is uncertain.
  const pendingRequestRef = useRef(recovery.payload);

  // Prevent concurrent submissions before React updates the button state.
  const submissionLockRef = useRef(false);

  // Ignore outdated detail responses after closing or switching records.
  const detailsVersionRef = useRef(0);

  const currency = summary?.currency ?? "LKR";

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

  function refreshData() {
    setRefreshVersion((previous) => previous + 1);
  }

  function handleAmountChange(event) {
    if (pendingRequestRef.current) return;

    setAmount(event.target.value);
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

    let payload;
    let wasRecovery;

    try {
      // Re-read storage before submission to detect unresolved requests.
      const stored = loadPendingPayoutRequest(providerId);

      if (!pendingRequestRef.current && stored) {
        // Require explicit confirmation before retrying a request
        // discovered after the current page was opened.
        pendingRequestRef.current = stored;
        setAmount(String(stored.amount));
        setAmountError("");
        setSuccessMessage("");
        setRetryRequired(true);
        setSubmitError(
          "An unresolved payout request was found. Review the original amount and select Retry Payout Request.",
        );
        return;
      }

      payload = pendingRequestRef.current ?? stored;
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

    submissionLockRef.current = true;

    setSubmitting(true);
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
      const initialClientRejection =
        !wasRecovery &&
        [400, 401, 403, 422].includes(status);

      if (!wasRecovery && (eligibilityRejected || initialClientRejection)) {
        clearResolvedRequest();
      } else {
        // Preserve the exact payload after uncertain outcomes.
        setRetryRequired(true);
      }

      setSubmitError(
        error.message || "Unable to confirm the payout request.",
      );

      refreshData();
    } finally {
      submissionLockRef.current = false;
      setSubmitting(false);
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
      title="Earnings & Payouts"
      subtitle="Track your earnings and manage payout requests."
      showBack={false}
      plain
    >
      <div className={styles.container}>
        <div className={styles.sectionHeader}>
          <h2>Earnings Overview</h2>

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
              label="Pending Earnings"
              amount={summary.pending}
              currency={currency}
              description="Earnings awaiting release."
            />

            <EarningsCard
              label="Available Earnings"
              amount={summary.available}
              currency={currency}
              description="Available balance, subject to payout eligibility."
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
          <h2>Request a Payout</h2>

          <p className={styles.description}>
            Request a manual payout from your eligible earnings. The minimum
            payout amount is LKR 5,000.00.
          </p>

          <Alert variant="info" title="Payout eligibility">
            Eligible completed bookings must satisfy the 72-hour maturity
            period. Disputed or refunded bookings may be excluded. The final
            eligible amount is verified by the server.
          </Alert>

          {successMessage && (
            <Alert
              variant="success"
              title="Payout request received"
              onClose={() => setSuccessMessage("")}
            >
              {successMessage}
            </Alert>
          )}

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
                submitting || retryRequired || Boolean(storageError)
              }
              required
            />

            <Button
              type="submit"
              loading={submitting}
              disabled={
                Boolean(storageError) ||
                (!retryRequired &&
                  (summaryLoading ||
                    !summary ||
                    Number(summary.available) < MINIMUM_PAYOUT))
              }
            >
              {retryRequired ? "Retry Payout Request" : "Request Payout"}
            </Button>
          </form>
        </section>

        <section className={styles.panel}>
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
