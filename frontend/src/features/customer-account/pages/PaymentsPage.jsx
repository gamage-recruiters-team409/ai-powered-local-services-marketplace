import { useEffect, useState } from 'react';
import { CustomerPage } from '../../../shared/layouts/customer/CustomerPage';
import { getMyPayments } from '../services/paymentsApi';
import { getPaymentStatusInfo } from '../config/paymentStatus';
import { StatusBadge } from '../components/StatusBadge';
import { Pagination } from '../components/Pagination';
import { EmptyState } from '../components/EmptyState';
import styles from '../customerAccount.module.css';

function formatDate(isoString) {
  if (!isoString) return '—';
  try {
    const d = new Date(isoString);
    return d.toLocaleDateString('en-GB', {
      day: 'numeric',
      month: 'short',
      year: 'numeric',
    });
  } catch {
    return '—';
  }
}

function formatAmount(amount) {
  if (amount == null) return '0';
  const num = Number(amount);
  if (isNaN(num)) return String(amount);
  return num.toLocaleString('en-US');
}

function formatBookingId(bookingId) {
  if (!bookingId) return '—';
  if (bookingId.startsWith('#')) return bookingId;
  if (bookingId.length <= 8) return `#${bookingId.toUpperCase()}`;
  return `#BK-${bookingId.slice(-4).toUpperCase()}`;
}

export function PaymentsPage() {
  const [page, setPage] = useState(0);
  const pageSize = 10;
  const [result, setResult] = useState({
    key: null,
    data: null,
    error: null,
  });

  const loading = result.key !== page;
  const payments = result.data?.content || [];
  const totalPages = result.data?.totalPages ?? 0;
  const totalElements = result.data?.totalElements ?? 0;
  const errorMessage = result.error ? (result.error.message || 'Failed to load payments') : '';

  useEffect(() => {
    let cancelled = false;
    getMyPayments(page, pageSize)
      .then((data) => {
        if (!cancelled) {
          setResult({ key: page, data, error: null });
        }
      })
      .catch((err) => {
        if (!cancelled) {
          setResult({ key: page, data: null, error: err });
        }
      });

    return () => {
      cancelled = true;
    };
  }, [page, pageSize]);

  const renderRefundStatus = (p) => {
    const { status, refundedAmount, amount, currency } = p;
    const curr = currency || 'LKR';

    if (status === 'REFUND_PENDING') {
      return (
        <div>
          <StatusBadge variant="info">Processing</StatusBadge>
          <div style={{ fontSize: '12px', color: '#64748b', marginTop: '4px' }}>
            Requested {formatAmount(refundedAmount)} {curr}
          </div>
          <div>
            <button
              type="button"
              disabled
              data-testid="Link_ViewRefundDetails"
              title="Not available yet"
              style={{
                background: 'none',
                border: 'none',
                padding: 0,
                color: '#94a3b8',
                fontSize: '12px',
                cursor: 'not-allowed',
                marginTop: '2px',
              }}
            >
              View refund details &gt;
            </button>
          </div>
        </div>
      );
    }

    if (status === 'PARTIALLY_REFUNDED') {
      return (
        <div>
          <StatusBadge variant="success">Completed</StatusBadge>
          <div style={{ fontSize: '12px', color: '#64748b', marginTop: '4px' }}>
            Refunded {formatAmount(refundedAmount)} of {formatAmount(amount)} {curr}
          </div>
          <div>
            <button
              type="button"
              disabled
              data-testid="Link_ViewRefundDetails"
              title="Not available yet"
              style={{
                background: 'none',
                border: 'none',
                padding: 0,
                color: '#94a3b8',
                fontSize: '12px',
                cursor: 'not-allowed',
                marginTop: '2px',
              }}
            >
              View refund details &gt;
            </button>
          </div>
        </div>
      );
    }

    if (status === 'REFUNDED') {
      return (
        <div>
          <StatusBadge variant="success">Completed</StatusBadge>
          <div style={{ fontSize: '12px', color: '#64748b', marginTop: '4px' }}>
            Refunded {formatAmount(refundedAmount)} {curr}
          </div>
          <div>
            <button
              type="button"
              disabled
              data-testid="Link_ViewRefundDetails"
              title="Not available yet"
              style={{
                background: 'none',
                border: 'none',
                padding: 0,
                color: '#94a3b8',
                fontSize: '12px',
                cursor: 'not-allowed',
                marginTop: '2px',
              }}
            >
              View refund details &gt;
            </button>
          </div>
        </div>
      );
    }

    return <span>—</span>;
  };

  return (
    <CustomerPage title="Payments">
      <div className={styles.accountContainer}>
        <div>
          <p className={styles.pageHeaderSubtitle} style={{ marginTop: '-12px', marginBottom: '20px' }}>
            Your payments, refunds and receipts.
          </p>
        </div>

        {errorMessage && (
          <div className={styles.alertWarning} role="alert" style={{ background: '#fef2f2', borderColor: '#fecaca', color: '#dc2626' }}>
            {errorMessage}
          </div>
        )}

        {loading ? (
          <div className={styles.card} style={{ minHeight: '200px', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
            <p className={styles.inputHint}>Loading payments...</p>
          </div>
        ) : payments.length === 0 ? (
          <div className={styles.card}>
            <EmptyState
              icon={
                <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
                  <rect width="20" height="14" x="2" y="5" rx="2" />
                  <path d="M2 10h20" />
                </svg>
              }
              title="No payments yet"
              description="Your payments and refunds will appear here."
              actionLabel="Browse services"
              actionTo="/search"
            />
          </div>
        ) : (
          <div className={styles.card}>
            <div className={styles.tableWrapper}>
              <table className={styles.table}>
                <thead>
                  <tr>
                    <th>Booking</th>
                    <th>Date</th>
                    <th>Amount</th>
                    <th>Payment status</th>
                    <th>Refund status</th>
                    <th>Receipt</th>
                  </tr>
                </thead>
                <tbody>
                  {payments.map((p) => {
                    const statusInfo = getPaymentStatusInfo(p.status);
                    return (
                      <tr key={p.paymentId} className={styles.tableRow}>
                        <td style={{ fontWeight: 600 }}>{formatBookingId(p.bookingId)}</td>
                        <td style={{ color: '#475569' }}>{formatDate(p.createdAt)}</td>
                        <td style={{ fontWeight: 600 }}>
                          {formatAmount(p.amount)} <span style={{ fontSize: '12px', color: '#64748b', fontWeight: 400 }}>{p.currency || 'LKR'}</span>
                        </td>
                        <td>
                          <StatusBadge variant={statusInfo.variant}>
                            {statusInfo.label}
                          </StatusBadge>
                        </td>
                        <td>{renderRefundStatus(p)}</td>
                        <td>
                          <button
                            type="button"
                            disabled
                            data-testid="Link_ViewReceipt"
                            title="Not available yet"
                            style={{
                              background: 'none',
                              border: 'none',
                              padding: 0,
                              color: '#94a3b8',
                              fontSize: '13px',
                              fontWeight: 500,
                              cursor: 'not-allowed',
                            }}
                          >
                            View receipt
                          </button>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>

            <Pagination
              currentPage={page}
              totalPages={totalPages}
              totalElements={totalElements}
              pageSize={pageSize}
              itemLabel="payments"
              onPageChange={(newPage) => setPage(newPage)}
            />
          </div>
        )}
      </div>
    </CustomerPage>
  );
}

export default PaymentsPage;
