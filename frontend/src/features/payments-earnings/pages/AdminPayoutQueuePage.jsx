import { useEffect, useRef, useState } from "react";

import Button from "../../../shared/components/forms/Button";
import Badge from "../../../shared/components/forms/Badge";
import Input from "../../../shared/components/forms/Input";
import DataTable from "../../../shared/components/forms/DataTable";
import Pagination from "../../../shared/components/forms/Pagination";
import Modal from "../../../shared/components/forms/Modal";
import Checkbox from "../../../shared/components/forms/Checkbox";
import Alert from "../../../shared/components/feedback/Alert";
import PageLoader from "../../../shared/components/feedback/PageLoader";

import { useAreaAccess } from "../../disputes-admin/access/useAdminAccess";

import {
  getAdminPayoutSummary,
  getAdminPayoutHistory,
  getAdminPayoutDetails,
  markPayoutProcessing,
  markPayoutPaid,
  markPayoutFailed,
} from "../services/paymentsEarningsApi";

import styles from "./AdminPayoutQueuePage.module.css";

/**
 * WP07 - Finance Admin Payout Queue
 *
 * Provides authorized finance administrators with payout monitoring
 * and manual bank transfer lifecycle management.
 *
 * Financial decisions, authorization and status transitions
 * remain enforced by the backend.
 */

const PAGE_SIZE = 10;

const EMPTY_FILTERS = Object.freeze({
  search: "",
  status: "",
  fromDate: "",
  toDate: "",
});

const SUMMARY_CARDS = [
  {
    key: "pending",
    label: "Pending Review",
    description: "Manual requests to review",
    tone: "pending",
  },
  {
    key: "processing",
    label: "Processing",
    description: "Payouts in progress",
    tone: "processing",
  },
  {
    key: "paid",
    label: "Paid",
    description: "Completed payouts",
    tone: "paid",
  },
  {
    key: "failed",
    label: "Failed",
    description: "Payouts requiring follow-up",
    tone: "failed",
  },
];

/**
 * Converts a local calendar date into an ISO timestamp.
 * The selected end date includes the entire calendar day.
 */
function toDateBoundary(value, exclusiveEnd = false) {
  if (!value) return undefined;

  const date = new Date(`${value}T00:00:00`);

  if (exclusiveEnd) {
    date.setDate(date.getDate() + 1);
  }

  return date.toISOString();
}

/** Builds the supported backend filter parameters. */
function buildQueryFilters(filters) {
  return {
    search: filters.search.trim(),
    status: filters.status,
    from: toDateBoundary(filters.fromDate),
    to: toDateBoundary(filters.toDate, true),
  };
}

const STATUS_VARIANTS = {
  PENDING: "warning",
  PROCESSING: "info",
  PAID: "success",
  FAILED: "error",
};

const ACTION_CONFIG = {
  process: {
    title: "Start Payout Processing",
    description:
      "Confirm that this payout is ready for manual bank transfer processing.",
    confirmLabel: "Start Processing",
  },
  paid: {
    title: "Confirm Payout Payment",
    description:
      "Confirm only after the actual bank transfer has been completed.",
    confirmLabel: "Mark as Paid",
    fieldLabel: "Bank transfer reference",
  },
  failed: {
    title: "Mark Payout as Failed",
    description:
      "Confirm the transfer has failed. The backend will handle the release of reserved earnings.",
    confirmLabel: "Mark as Failed",
    fieldLabel: "Failure reason",
  },
};

/** Displays a decorative icon for each payout lifecycle category. */
function SummaryIcon({ tone }) {
  return (
    <svg
      width="22"
      height="22"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      {tone === "pending" && (
        <>
          <circle cx="12" cy="12" r="9" />
          <path d="M12 7v5l3 2" />
        </>
      )}

      {tone === "processing" && (
        <>
          <path d="M20 7v5h-5M4 17v-5h5" />
          <path d="M5 9a8 8 0 0 1 13-4l2 3M19 15a8 8 0 0 1-13 4l-2-3" />
        </>
      )}

      {tone === "paid" && (
        <>
          <circle cx="12" cy="12" r="9" />
          <path d="m8 12 3 3 5-6" />
        </>
      )}

      {tone === "failed" && (
        <>
          <circle cx="12" cy="12" r="9" />
          <path d="m9 9 6 6m0-6-6 6" />
        </>
      )}
    </svg>
  );
}

/** Formats server-provided financial amounts. */
function formatMoney(amount, currency = "LKR") {
  if (amount == null || !Number.isFinite(Number(amount))) {
    return "\u2014";
  }

  return new Intl.NumberFormat("en-LK", {
    style: "currency",
    currency,
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(Number(amount));
}

/** Formats timestamps without displaying invalid date values. */
function formatDate(value) {
  if (!value) return "\u2014";

  const date = new Date(value);

  return Number.isNaN(date.getTime()) ? "\u2014" : date.toLocaleString("en-LK");
}

/** Maps backend payout states to shared badge variants. */
function PayoutStatusBadge({ status }) {
  return (
    <Badge variant={STATUS_VARIANTS[status] ?? "neutral"}>
      {status ?? "UNKNOWN"}
    </Badge>
  );
}

/** Restricts available actions to supported lifecycle transitions. */
function isActionAllowed(status, action) {
  if (action === "process") {
    return status === "PENDING";
  }

  if (action === "paid" || action === "failed") {
    return status === "PROCESSING";
  }

  return false;
}

/**
 * Finance Admin payout management page.
 *
 * View-only administrators can inspect payout records but cannot
 * initiate financial state changes.
 */
export default function AdminPayoutQueuePage() {
  const { canEdit } = useAreaAccess();

  const [summary, setSummary] = useState(null);
  const [summaryLoading, setSummaryLoading] = useState(true);
  const [summaryError, setSummaryError] = useState("");

  const [draftFilters, setDraftFilters] = useState(EMPTY_FILTERS);
  const [appliedFilters, setAppliedFilters] = useState(EMPTY_FILTERS);
  const [filterError, setFilterError] = useState("");

  const [history, setHistory] = useState(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState("");

  const [page, setPage] = useState(0);
  const [refreshVersion, setRefreshVersion] = useState(0);

  const [detailsOpen, setDetailsOpen] = useState(false);
  const [details, setDetails] = useState(null);
  const [detailsLoading, setDetailsLoading] = useState(false);
  const [detailsError, setDetailsError] = useState("");

  const [action, setAction] = useState(null);
  const [actionValue, setActionValue] = useState("");
  const [actionConfirmed, setActionConfirmed] = useState(false);
  const [actionError, setActionError] = useState("");
  const [submitting, setSubmitting] = useState(false);

  const [successMessage, setSuccessMessage] = useState("");
  const [operationError, setOperationError] = useState("");

  // Prevent duplicate financial operations and stale detail responses.
  const operationLockRef = useRef(false);
  const detailsVersionRef = useRef(0);

  /** Loads global counts independently of pagination and filters. */
  useEffect(() => {
    let active = true;

    async function loadSummary() {
      setSummaryLoading(true);
      setSummaryError("");

      try {
        const result = await getAdminPayoutSummary();

        if (active) {
          setSummary(result);
        }
      } catch (error) {
        if (active) {
          setSummary(null);
          setSummaryError(
            error.message || "Unable to load payout summary.",
          );
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

  /** Loads the current server-side payout queue page. */
  useEffect(() => {
    let active = true;

    async function loadQueue() {
      setLoading(true);
      setLoadError("");

      try {
        const result = await getAdminPayoutHistory(
          page,
          PAGE_SIZE,
          buildQueryFilters(appliedFilters),
        );

        if (active) {
          // A status transition may remove the final row from a filtered page.
          // Return to the last valid page without changing the applied filters.
          const lastValidPage = Math.max(0, result.totalPages - 1);

          if (page > lastValidPage) {
            // Keep loading until the corrected page has been retrieved.
            active = false;
            setPage(lastValidPage);
            return;
          }

          setHistory(result);
        }
      } catch (error) {
        if (active) {
          setHistory(null);
          setLoadError(error.message || "Unable to load the payout queue.");
        }
      } finally {
        if (active) {
          setLoading(false);
        }
      }
    }

    loadQueue();

    return () => {
      active = false;
    };
  }, [page, refreshVersion, appliedFilters]);

  /** Updates draft filters without requesting data on every keystroke. */
  function updateFilter(field, value) {
    setDraftFilters((previous) => ({
      ...previous,
      [field]: value,
    }));
    setFilterError("");
  }

  /** Validates and applies filters from the first result page. */
  function applyFilters(event) {
    event.preventDefault();

    if (draftFilters.search.trim().length > 128) {
      setFilterError("Search must not exceed 128 characters.");
      return;
    }

    if (
      draftFilters.fromDate &&
      draftFilters.toDate &&
      draftFilters.fromDate > draftFilters.toDate
    ) {
      setFilterError("The start date must not be after the end date.");
      return;
    }

    setFilterError("");
    setPage(0);
    setAppliedFilters({
      ...draftFilters,
      search: draftFilters.search.trim(),
    });
  }

  /** Clears active filters and returns to the first result page. */
  function resetFilters() {
    setDraftFilters(EMPTY_FILTERS);
    setAppliedFilters(EMPTY_FILTERS);
    setFilterError("");
    setPage(0);
  }

  function refreshQueue() {
    setRefreshVersion((previous) => previous + 1);
  }

  /** Retrieves authoritative payout details before an operation. */
  async function openDetails(payoutId) {
    if (operationLockRef.current) return;

    const version = ++detailsVersionRef.current;

    setDetailsOpen(true);
    setDetails(null);
    setDetailsError("");
    setDetailsLoading(true);
    setAction(null);
    setActionValue("");
    setActionError("");
    // Prevent messages from a previous payout appearing on this record.
    setOperationError("");
    setSuccessMessage("");

    try {
      const result = await getAdminPayoutDetails(payoutId);

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

  /** Prevents dismissal while a financial operation is in progress. */
  function closeDetails() {
    if (operationLockRef.current) return;

    detailsVersionRef.current += 1;

    setDetailsOpen(false);
    setDetails(null);
    setDetailsLoading(false);
    setDetailsError("");
    setAction(null);
    setActionValue("");
    setActionError("");
  }

  /** Opens an explicit confirmation for an authorized action. */
  function beginAction(nextAction) {
    if (
      !canEdit ||
      !details ||
      operationLockRef.current ||
      !isActionAllowed(details.status, nextAction)
    ) {
      return;
    }

    setAction(nextAction);
    setActionConfirmed(false);
    setActionValue("");
    setActionError("");
    setOperationError("");
    setSuccessMessage("");
  }

  function cancelAction() {
    if (operationLockRef.current) return;

    setActionConfirmed(false);
    setAction(null);
    setActionValue("");
    setActionError("");
  }

  /**
   * Executes a confirmed payout transition.
   *
   * Failed or uncertain responses trigger a fresh details request.
   * The UI never assumes that a failed response means the server
   * did not complete the operation.
   */
  async function handleActionSubmit(event) {
    event.preventDefault();

    if (
      !canEdit ||
      !details ||
      !action ||
      operationLockRef.current ||
      !isActionAllowed(details.status, action)
    ) {
      return;
    }

    // Explicit acknowledgement is mandatory before starting processing.
    if (action === "process" && !actionConfirmed) {
      setActionError("Confirm the payout review before starting processing.");
      return;
    }

    const value = actionValue.trim();

    if (action === "paid" && !value) {
      setActionError("A bank transfer reference is required.");
      return;
    }

    if (action === "failed" && !value) {
      setActionError("A failure reason is required.");
      return;
    }

    const payoutId = details.id;

    operationLockRef.current = true;

    setSubmitting(true);
    setActionError("");
    setOperationError("");
    setSuccessMessage("");

    try {
      let result;

      switch (action) {
        case "process":
          result = await markPayoutProcessing(payoutId);
          break;

        case "paid":
          result = await markPayoutPaid(payoutId, value);
          break;

        case "failed":
          result = await markPayoutFailed(payoutId, value);
          break;

        default:
          throw new Error("Unsupported payout operation.");
      }

      setDetails(result);

      setSuccessMessage(
        `Payout updated successfully. Current status: ${
          result.status ?? "UNKNOWN"
        }.`,
      );

      setAction(null);
      setActionConfirmed(false);
      setActionValue("");
      refreshQueue();
    } catch (error) {
      setOperationError(
        `${
          error.message || "Unable to confirm the payout operation."
        } Review the latest payout status before attempting another operation.`,
      );

      // Disable further actions until the authoritative state is known.
      setActionConfirmed(false);
      setAction(null);
      setActionValue("");
      setDetails(null);
      setDetailsLoading(true);
      setDetailsError("");

      try {
        const latest = await getAdminPayoutDetails(payoutId);
        setDetails(latest);
      } catch (refreshError) {
        setDetailsError(
          refreshError.message ||
            "Unable to verify the latest payout status. Close and reopen this record.",
        );
      } finally {
        setDetailsLoading(false);
      }

      refreshQueue();
    } finally {
      operationLockRef.current = false;
      setSubmitting(false);
    }
  }

  const columns = [
    {
      key: "id",
      header: "Payout Reference",
      render: (value) => (
        <span className={styles.reference}>{value}</span>
      ),
    },
    {
      key: "providerId",
      header: "Provider",
      render: (value) => (
        <span className={styles.providerIdentifier}>{value}</span>
      ),
    },
    {
      key: "payoutType",
      header: "Payout Type",
      render: () => (
        <div className={styles.payoutType}>
          <strong>Manual</strong>
          <span>By provider</span>
        </div>
      ),
    },
    {
      key: "amount",
      header: "Amount",
      render: (value, row) => (
        <span className={styles.amount}>
          {formatMoney(value, row.currency)}
        </span>
      ),
    },
    {
      key: "createdAt",
      header: "Submitted Date",
      render: (value) => formatDate(value),
    },
    {
      key: "status",
      header: "Status",
      render: (value) => (
        <Badge variant={STATUS_VARIANTS[value] ?? "neutral"}>
          {value === "PENDING" ? "UNDER REVIEW" : value ?? "UNKNOWN"}
        </Badge>
      ),
    },
  ];

  const actionConfig = action ? ACTION_CONFIG[action] : null;

  // Preserve queue filters and pagination while reviewing a payout.
  if (detailsOpen) {
    return (
      <div
        className={styles.container}
        role="region"
        aria-label="Provider Payout Details"
      >
        <header className={styles.pageHeader}>
          <div>
            <h1>Review Payout Request</h1>
            <p>Finance / Payout Queue / Payout Review</p>
          </div>

          <Button
            variant="secondary"
            onClick={closeDetails}
            disabled={submitting}
          >
            Back to Payout Queue
          </Button>
        </header>

        {!canEdit && (
          <Alert variant="info" title="View-only access">
            Financial operations require Finance Admin edit permission.
          </Alert>
        )}

        <div className={styles.modalContent}>
          {detailsLoading && <PageLoader label="Loading payout details..." />}

          {detailsError && (
            <Alert variant="error" title="Details unavailable">
              {detailsError}
            </Alert>
          )}

          {operationError && (
            <Alert variant="error" title="Operation not confirmed">
              {operationError}
            </Alert>
          )}

          {successMessage && <Alert variant="success">{successMessage}</Alert>}

          {details && (
            <>
              {/* Review layout uses only authoritative payout API data. */}
              <div className={styles.reviewGrid}>
                <div className={styles.reviewColumn}>
                  <section className={styles.reviewCard}>
                    <h2>Payout Request Summary</h2>

                    <dl className={styles.detailsList}>
                      <dt>Payout ID</dt>
                      <dd className={styles.reference}>{details.id}</dd>

                      <dt>Payout Type</dt>
                      <dd>Manual</dd>

                      <dt>Amount</dt>
                      <dd>{formatMoney(details.amount, details.currency)}</dd>

                      <dt>Requested At</dt>
                      <dd>{formatDate(details.createdAt)}</dd>

                      <dt>Status</dt>
                      <dd>
                        <PayoutStatusBadge status={details.status} />
                      </dd>

                      <dt>Request Key</dt>
                      <dd>{details.requestKey}</dd>
                    </dl>
                  </section>

                  <section className={styles.reviewCard}>
                    <h2>Provider Information</h2>

                    <dl className={styles.detailsList}>
                      <dt>Provider ID</dt>
                      <dd>{details.providerId}</dd>
                    </dl>

                    <Alert variant="info">
                      Bank account details are not available through the
                      current payout API. Verify the transfer destination
                      through the authorized financial process before
                      transferring funds.
                    </Alert>
                  </section>

                  <section className={styles.reviewCard}>
                    <h2>Processing Information</h2>

                    <dl className={styles.detailsList}>
                      <dt>Last Updated</dt>
                      <dd>{formatDate(details.updatedAt)}</dd>

                      <dt>Paid At</dt>
                      <dd>{formatDate(details.paidAt)}</dd>

                      <dt>Processed By</dt>
                      <dd>{details.processedBy || "\u2014"}</dd>

                      <dt>Transfer Reference</dt>
                      <dd>{details.transferReference || "\u2014"}</dd>

                      <dt>Failure Reason</dt>
                      <dd>{details.failureReason || "\u2014"}</dd>
                    </dl>
                  </section>

                  <Alert variant="info" title="Workflow information">
                    Starting processing does not transfer funds.
                    Record payment only after the external bank transfer
                    has completed. Final payout states cannot be processed
                    again.
                  </Alert>
                </div>

                <div className={styles.reviewColumn}>
                  <section className={styles.reviewCard}>
                    <h2>Payout Amount</h2>

                    <div className={styles.reviewAmount}>
                      <span>Requested payout amount</span>
                      <strong>
                        {formatMoney(details.amount, details.currency)}
                      </strong>
                    </div>

                    <Alert variant="info">
                      Financial eligibility and payout state transitions
                      are enforced by the backend. This record does not
                      provide a live eligible balance breakdown.
                    </Alert>
                  </section>

                  <section className={styles.reviewCard}>
                    <h2>Review Decision</h2>

                    <p className={styles.reviewDescription}>
                      {details.status === "PENDING"
                        ? "Review the payout before starting manual processing."
                        : details.status === "PROCESSING"
                          ? "Record the outcome of the external bank transfer."
                          : "This payout has reached a final status."}
                    </p>

                    {!canEdit && (
                      <Alert variant="info">
                        You have read-only access to this payout.
                      </Alert>
                    )}

              {/* Only authorized editors receive mutation controls. */}
              {canEdit && !action && (
                <div className={styles.actionGroup}>
                  {details.status === "PENDING" && (
                    <Button
                      onClick={() => beginAction("process")}
                      disabled={submitting}
                    >
                      Start Processing
                    </Button>
                  )}

                  {details.status === "PROCESSING" && (
                    <>
                      <Button
                        onClick={() => beginAction("paid")}
                        disabled={submitting}
                      >
                        Mark as Paid
                      </Button>

                      <Button
                        onClick={() => beginAction("failed")}
                        disabled={submitting}
                      >
                        Mark as Failed
                      </Button>
                    </>
                  )}

                  {["PAID", "FAILED"].includes(details.status) && (
                    <Alert variant="info">
                      This payout has reached a final status. No further
                      transitions are available.
                    </Alert>
                  )}
                </div>
              )}

                  </section>
                </div>
              </div>

              {/* Explicit confirmation prevents accidental transitions. */}
              {canEdit && actionConfig && (
                <Modal
                  isOpen={true}
                  onClose={cancelAction}
                  title={actionConfig.title}
                  description={actionConfig.description}
                  closeOnBackdrop={false}
                  showCloseButton={!submitting}
                >
                  <form
                    className={styles.confirmationPanel}
                    onSubmit={handleActionSubmit}
                    noValidate
                  >
                    <div className={styles.reviewAmount}>
                      <span>Payout amount</span>
                      <strong>
                        {formatMoney(details.amount, details.currency)}
                      </strong>
                    </div>

                    {action === "process" && (
                      <>
                        <Alert variant="info">
                          Starting processing does not initiate a bank
                          transfer. Verify the payout record and transfer
                          destination before proceeding.
                        </Alert>

                        <Checkbox
                          label="I confirm that I have reviewed this payout request."
                          checked={actionConfirmed}
                          onChange={(event) => {
                            setActionConfirmed(event.target.checked);
                            setActionError("");
                          }}
                          error={actionError || undefined}
                          disabled={submitting}
                          required
                        />
                      </>
                    )}

                    {actionConfig.fieldLabel && (
                      <Input
                        label={actionConfig.fieldLabel}
                        value={actionValue}
                        onChange={(event) => {
                          setActionValue(event.target.value);
                          setActionError("");
                        }}
                        error={actionError || undefined}
                        disabled={submitting}
                        required
                      />
                    )}

                    <div className={styles.actionGroup}>
                      <Button
                        type="button"
                        variant="secondary"
                        onClick={cancelAction}
                        disabled={submitting}
                      >
                        Cancel
                      </Button>

                      <Button
                        type="submit"
                        loading={submitting}
                        disabled={
                          submitting ||
                          (action === "process" && !actionConfirmed)
                        }
                      >
                        {actionConfig.confirmLabel}
                      </Button>
                    </div>
                  </form>
                </Modal>
              )}
            </>
          )}
        </div>

      </div>
    );
  }


  return (
    <div className={styles.container}>
      <header className={styles.pageHeader}>
        <div>
          <h1>Payout Queue</h1>

          <p>
            Manual payout requests require Finance Admin review and
            external bank transfer processing.
          </p>
        </div>

        <Button onClick={refreshQueue} disabled={loading || submitting}>
          Refresh
        </Button>
      </header>

      {!canEdit && (
        <Alert variant="info" title="View-only access">
          You can inspect payout records. Financial operations require Finance
          Admin edit permission.
        </Alert>
      )}

      <section
        className={styles.summarySection}
        aria-label="Payout summary"
        aria-busy={summaryLoading}
      >
        {summaryError && (
          <Alert variant="error" title="Summary unavailable">
            {summaryError}
          </Alert>
        )}

        <div className={styles.summaryGrid}>
          {SUMMARY_CARDS.map((card) => (
            <article
              key={card.key}
              className={styles.summaryCard}
              data-tone={card.tone}
              aria-label={card.label}
            >
              <div className={styles.summaryCardHeader}>
                <span>{card.label}</span>

                <span className={styles.summaryIcon}>
                  <SummaryIcon tone={card.tone} />
                </span>
              </div>

              <strong className={styles.summaryValue}>
                {summaryLoading
                  ? "..."
                  : summary?.[card.key] ?? "\u2014"}
              </strong>

              <p>{card.description}</p>
            </article>
          ))}
        </div>
      </section>

      {successMessage && (
        <Alert
          variant="success"
          title="Payout updated"
          onClose={() => setSuccessMessage("")}
        >
          {successMessage}
        </Alert>
      )}

      {operationError && !detailsOpen && (
        <Alert variant="error" title="Payout operation issue">
          {operationError}
        </Alert>
      )}

      <form
        className={styles.filterPanel}
        onSubmit={applyFilters}
        aria-label="Payout filters"
        noValidate
      >
        <div className={styles.filterGrid}>
          <div className={styles.searchField}>
            <Input
              label="Search payouts"
              placeholder="Payout reference or provider ID"
              value={draftFilters.search}
              onChange={(event) =>
                updateFilter("search", event.target.value)
              }
              maxLength={128}
              disabled={submitting}
            />
          </div>

          <Input
            label="From date"
            type="date"
            value={draftFilters.fromDate}
            onChange={(event) =>
              updateFilter("fromDate", event.target.value)
            }
            disabled={submitting}
          />

          <Input
            label="To date"
            type="date"
            value={draftFilters.toDate}
            onChange={(event) =>
              updateFilter("toDate", event.target.value)
            }
            disabled={submitting}
          />

          <div className={styles.filterField}>
            <label htmlFor="wp07-payout-status">Status</label>

            <select
              id="wp07-payout-status"
              value={draftFilters.status}
              onChange={(event) =>
                updateFilter("status", event.target.value)
              }
              disabled={submitting}
            >
              <option value="">All statuses</option>
              <option value="PENDING">Under Review</option>
              <option value="PROCESSING">Processing</option>
              <option value="PAID">Paid</option>
              <option value="FAILED">Failed</option>
            </select>
          </div>
        </div>

        <div className={styles.filterFooter}>
          <span className={styles.filterHint}>
            Search by exact payout reference or provider ID.
            Dates use your local timezone.
          </span>

          <div className={styles.filterActions}>
            <Button
              type="button"
              variant="secondary"
              onClick={resetFilters}
              disabled={submitting}
            >
              Reset Filters
            </Button>

            <Button
              type="submit"
              disabled={submitting}
            >
              Apply Filters
            </Button>
          </div>
        </div>

        {filterError && (
          <Alert variant="error">{filterError}</Alert>
        )}
      </form>

      <section className={styles.panel}>
        <div className={styles.sectionHeader}>
          <h2>Provider Payout Requests</h2>

          <span className={styles.recordCount}>
            Total records: {loading ? "..." : history?.totalElements ?? "\u2014"}
          </span>
        </div>

        <DataTable
          columns={columns}
          data={history?.content ?? []}
          getRowId={(row) => row.id}
          loading={loading}
          error={loadError || undefined}
          emptyMessage="No Payout Requests Found"
          emptyContent={
            <div className={styles.emptyState}>
              <span className={styles.emptyIcon} aria-hidden="true">
                <svg
                  width="36"
                  height="36"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="1.5"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                >
                  <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h5" />
                  <path d="M14 2v6h6l-6-6M8 12h4M8 16h2" />
                  <circle cx="17" cy="16" r="4" />
                  <path d="m20 19 3 3" />
                </svg>
              </span>

              <h3>No Payout Requests Found</h3>

              <p>
                No payout requests match your current search or filter criteria.
              </p>

              <Button
                variant="secondary"
                onClick={resetFilters}
                disabled={submitting}
              >
                Clear Filters
              </Button>
            </div>
          }
          actions={(row) => (
            <Button
              size="small"
              variant={
                canEdit && row.status === "PENDING"
                  ? "primary"
                  : "secondary"
              }
              disabled={submitting}
              onClick={() => openDetails(row.id)}
            >
              {canEdit && row.status === "PENDING"
                ? "Review Payout"
                : "View Details"}
            </Button>
          )}
        />

        {/* Keep pagination visible for empty and single-page results. */}
        <footer className={styles.paginationFooter}>
          <span
            className={styles.paginationSummary}
            aria-live="polite"
          >
            {loading
              ? "Loading results..."
              : loadError || !history
                ? "Results unavailable"
                : history.totalElements === 0
                  ? "0 matching results"
                  : history.content.length === 0
                    ? `0 results on this page of ${history.totalElements}`
                    : `Showing ${history.page * history.size + 1}\u2013${history.page * history.size + history.content.length} of ${history.totalElements} results`}
          </span>

          <Pagination
            currentPage={page + 1}
            totalPages={history?.totalPages ?? 0}
            onPageChange={(nextPage) => setPage(nextPage - 1)}
            disabled={
              loading ||
              submitting ||
              Boolean(loadError) ||
              !history ||
              history.totalElements === 0
            }
            className={
              history?.totalElements === 0
                ? styles.emptyPagination
                : ""
            }
          />
        </footer>
      </section>

      <Alert variant="info" title="Payout processing information">
        Payout status reflects platform processing. Bank settlement times
        may vary. Starting payout processing does not initiate an automatic
        bank transfer. Confirm payment only after the external transfer
        has been completed.
      </Alert>


    </div>
  );
}
