import { useEffect, useState } from "react";
import { Link } from "react-router-dom";

import { ProviderPage } from "../../../shared/layouts/provider/ProviderPage";
import Button from "../../../shared/components/forms/Button";
import DataTable from "../../../shared/components/forms/DataTable";
import Pagination from "../../../shared/components/forms/Pagination";
import Alert from "../../../shared/components/feedback/Alert";
import PageLoader from "../../../shared/components/feedback/PageLoader";

import { useAuth } from "../../auth/hooks/useAuth";
import { getProviderItemizedEarnings } from "../services/paymentsEarningsApi";

import styles from "./ProviderItemizedEarningsPage.module.css";

const PAGE_SIZE = 10;

/**
 * Formats backend-provided monetary values without substituting missing data.
 */
function formatMoney(value, currency) {
  if (
    value == null ||
    value === "" ||
    !Number.isFinite(Number(value)) ||
    !currency
  ) {
    return "Unavailable";
  }

  try {
    return new Intl.NumberFormat("en-LK", {
      style: "currency",
      currency,
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    }).format(Number(value));
  } catch {
    return "Unavailable";
  }
}

function formatDate(value) {
  if (!value) return "Unavailable";

  const date = new Date(value);

  return Number.isNaN(date.getTime())
    ? "Unavailable"
    : date.toLocaleDateString("en-LK", {
        year: "numeric",
        month: "short",
        day: "numeric",
      });
}

function formatCommissionRate(value) {
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

const columns = [
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
    header: "Gross Service Amount",
    render: (value, row) => formatMoney(value, row.currency),
  },
  {
    key: "commissionRate",
    header: "Commission Rate",
    render: (value) => formatCommissionRate(value),
  },
  {
    key: "commissionAmount",
    header: "Commission",
    render: (value, row) => formatMoney(value, row.currency),
  },
  {
    key: "refundAdjustmentAmount",
    header: "Refund Adjustments",
    render: (value, row) => formatMoney(value, row.currency),
  },
  {
    key: "netEarnings",
    header: "Net Earnings",
    render: (value, row) => (
      <strong className={styles.netAmount}>
        {formatMoney(value, row.currency)}
      </strong>
    ),
  },
];

/**
 * Displays authenticated provider earnings at booking-payment level.
 */
export default function ProviderItemizedEarningsPage() {
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

  // Prevent financial data from surviving an authenticated account change.
  return <ItemizedEarningsContent key={user.id} />;
}

/**
 * Loads server-paginated earnings and ignores obsolete request results.
 */
function ItemizedEarningsContent() {
  const [page, setPage] = useState(0);
  const [revision, setRevision] = useState(0);
  const [result, setResult] = useState(null);

  const currentResult =
    result?.page === page && result?.revision === revision
      ? result
      : null;

  const loading = currentResult === null;
  const history = currentResult?.data ?? null;
  const error = currentResult?.error ?? "";

  useEffect(() => {
    let active = true;

    async function loadEarnings() {
      try {
        const data = await getProviderItemizedEarnings(page, PAGE_SIZE);

        if (!active) return;

        if (
          !data ||
          !Array.isArray(data.content) ||
          data.page !== page ||
          !Number.isInteger(data.totalPages) ||
          data.totalPages < 0 ||
          !Number.isInteger(data.totalElements) ||
          data.totalElements < 0
        ) {
          throw new Error("Invalid earnings response");
        }

        // Recover when the requested page no longer exists.
        const lastPage = Math.max(0, data.totalPages - 1);

        if (page > lastPage) {
          setPage(lastPage);
          return;
        }

        setResult({ page, revision, data, error: "" });
      } catch {
        if (active) {
          setResult({
            page,
            revision,
            data: null,
            error: "Unable to load itemized earnings. Please try again.",
          });
        }
      }
    }

    loadEarnings();

    return () => {
      active = false;
    };
  }, [page, revision]);

  function refreshEarnings() {
    setRevision((previous) => previous + 1);
  }

  return (
    <ProviderPage
      title="Itemized Earnings"
      subtitle="View detailed earnings for each booking, including commission and refund adjustments."
      showBack={false}
      plain
    >
      <div className={styles.container}>
        <div className={styles.toolbar}>
          <Link
            to="/provider/earnings"
            className={styles.backLink}
          >
            Back to Earnings Dashboard
          </Link>

          <Button
            type="button"
            disabled={loading}
            onClick={refreshEarnings}
          >
            Refresh
          </Button>
        </div>

        <Alert variant="info">
          Net earnings reflect the remaining financial entitlement
          after commission and refund adjustments. They do not
          represent the currently withdrawable balance.
        </Alert>

        <section
          className={styles.panel}
          aria-label="Itemized earnings history"
        >
          <div className={styles.sectionHeader}>
            <h2>Earnings History</h2>

            {history && (
              <span className={styles.recordCount}>
                {history.totalElements} records
              </span>
            )}
          </div>

          <DataTable
            columns={columns}
            data={history?.content ?? []}
            getRowId={(row) => row.paymentId}
            loading={loading}
            error={error || undefined}
            emptyMessage="No itemized earnings are available yet."
            pagination={
              history?.totalPages > 1 ? (
                <Pagination
                  currentPage={page + 1}
                  totalPages={history.totalPages}
                  onPageChange={(nextPage) => setPage(nextPage - 1)}
                  disabled={loading}
                />
              ) : null
            }
          />
        </section>
      </div>
    </ProviderPage>
  );
}
