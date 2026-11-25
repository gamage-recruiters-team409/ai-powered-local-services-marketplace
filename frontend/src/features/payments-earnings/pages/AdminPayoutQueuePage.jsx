import { useEffect, useRef, useState } from "react";

import Button from "../../../shared/components/forms/Button";
import Badge from "../../../shared/components/forms/Badge";
import Input from "../../../shared/components/forms/Input";
import DataTable from "../../../shared/components/forms/DataTable";
import Pagination from "../../../shared/components/forms/Pagination";
import Modal from "../../../shared/components/forms/Modal";
import Alert from "../../../shared/components/feedback/Alert";
import PageLoader from "../../../shared/components/feedback/PageLoader";

import { useAreaAccess } from "../../disputes-admin/access/useAdminAccess";

import {
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
  const [actionError, setActionError] = useState("");
  const [submitting, setSubmitting] = useState(false);

  const [successMessage, setSuccessMessage] = useState("");
  const [operationError, setOperationError] = useState("");

  // Prevent duplicate financial operations and stale detail responses.
  const operationLockRef = useRef(false);
  const detailsVersionRef = useRef(0);

  /** Loads the current server-side payout queue page. */
  useEffect(() => {
    let active = true;

    async function loadQueue() {
      setLoading(true);
      setLoadError("");

      try {
        const result = await getAdminPayoutHistory(page, PAGE_SIZE);

        if (active) {
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
  }, [page, refreshVersion]);

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
    setActionValue("");
    setActionError("");
    setOperationError("");
    setSuccessMessage("");
  }

  function cancelAction() {
    if (operationLockRef.current) return;

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
      setActionValue("");
      refreshQueue();
    } catch (error) {
      setOperationError(
        `${
          error.message || "Unable to confirm the payout operation."
        } Review the latest payout status before attempting another operation.`,
      );

      // Disable further actions until the authoritative state is known.
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
      header: "Payout ID",
    },
    {
      key: "providerId",
      header: "Provider ID",
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
  ];

  const actionConfig = action ? ACTION_CONFIG[action] : null;

  return (
    <div className={styles.container}>
      <header className={styles.pageHeader}>
        <div>
          <h1>Payout Queue</h1>

          <p>
            Monitor provider payout requests and manage manual bank transfer
            operations.
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

      <Alert variant="info" title="Manual bank transfers">
        Starting payout processing does not initiate an automatic bank transfer.
        Confirm payment only after the external transfer has been completed.
      </Alert>

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

      <section className={styles.panel}>
        <div className={styles.sectionHeader}>
          <h2>Provider Payout Requests</h2>

          <span className={styles.recordCount}>
            Total records: {history?.totalElements ?? 0}
          </span>
        </div>

        <DataTable
          columns={columns}
          data={history?.content ?? []}
          getRowId={(row) => row.id}
          loading={loading}
          error={loadError || undefined}
          emptyMessage="No provider payout requests found."
          actions={(row) => (
            <Button
              size="small"
              disabled={submitting}
              onClick={() => openDetails(row.id)}
            >
              View Details
            </Button>
          )}
          pagination={
            (history?.totalPages ?? 0) > 1 ? (
              <Pagination
                currentPage={page + 1}
                totalPages={history.totalPages}
                onPageChange={(nextPage) => setPage(nextPage - 1)}
                disabled={loading || submitting}
              />
            ) : null
          }
        />
      </section>

      <Modal
        isOpen={detailsOpen}
        onClose={closeDetails}
        title="Provider Payout Details"
        description="Review the current payout record and available operations."
        closeOnBackdrop={!submitting}
        showCloseButton={!submitting}
        footer={
          <Button onClick={closeDetails} disabled={submitting}>
            Close
          </Button>
        }
      >
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
              <dl className={styles.detailsList}>
                <dt>Payout ID</dt>
                <dd>{details.id}</dd>

                <dt>Provider ID</dt>
                <dd>{details.providerId}</dd>

                <dt>Request Key</dt>
                <dd>{details.requestKey}</dd>

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

                <dt>Processed By</dt>
                <dd>{details.processedBy || "\u2014"}</dd>

                <dt>Transfer Reference</dt>
                <dd>{details.transferReference || "\u2014"}</dd>

                <dt>Failure Reason</dt>
                <dd>{details.failureReason || "\u2014"}</dd>
              </dl>

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

              {/* Explicit confirmation prevents accidental transitions. */}
              {canEdit && actionConfig && (
                <form
                  className={styles.confirmationPanel}
                  onSubmit={handleActionSubmit}
                  noValidate
                >
                  <h3>{actionConfig.title}</h3>

                  <p>{actionConfig.description}</p>

                  <p className={styles.confirmationAmount}>
                    Payout amount:{" "}
                    {formatMoney(details.amount, details.currency)}
                  </p>

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
                    <Button onClick={cancelAction} disabled={submitting}>
                      Cancel
                    </Button>

                    <Button type="submit" loading={submitting}>
                      {actionConfig.confirmLabel}
                    </Button>
                  </div>
                </form>
              )}
            </>
          )}
        </div>
      </Modal>
    </div>
  );
}
