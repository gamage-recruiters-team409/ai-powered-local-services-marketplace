import { useEffect, useState } from 'react';
import { getRecentBookings } from '../services/customerAccountApi';
import { getBookingStatusInfo } from '../config/bookingStatus';
import { StatusBadge } from './StatusBadge';
import styles from '../customerAccount.module.css';

function formatAmount(amount) {
  if (amount == null) return '';
  const num = Number(amount);
  if (isNaN(num)) return String(amount);
  return num.toLocaleString('en-US');
}

function formatBookingSchedule(booking) {
  const date = booking.localDate || '';
  const time = booking.localStart && booking.localEnd
    ? `${booking.localStart}–${booking.localEnd}`
    : (booking.localStart || booking.localEnd || '');
  if (date && time) return `${date}, ${time}`;
  return date || time || '—';
}

export function RecentBookings() {
  const [bookings, setBookings] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);

  useEffect(() => {
    let mounted = true;

    getRecentBookings({ size: 5 })
      .then((data) => {
        if (mounted) {
          setBookings(data?.content || []);
          setLoading(false);
        }
      })
      .catch(() => {
        if (mounted) {
          setError(true);
          setLoading(false);
        }
      });

    return () => {
      mounted = false;
    };
  }, []);

  return (
    <div className={styles.card}>
      <div className={styles.cardHeader}>
        <h2 className={styles.cardTitle}>Recent bookings</h2>
      </div>

      {loading && (
        <p className={styles.inputHint} style={{ margin: '16px 0' }}>
          Loading recent bookings...
        </p>
      )}

      {error && !loading && (
        <p className={styles.inputHint} style={{ margin: '16px 0', color: '#b91c1c' }}>
          Recent bookings are unavailable right now.
        </p>
      )}

      {!loading && !error && bookings.length === 0 && (
        <p className={styles.inputHint} style={{ margin: '16px 0' }}>
          You have no bookings yet.
        </p>
      )}

      {/* Note: A "View all bookings" link or per-row links pointing to CUSTOMER_BOOKINGS_PATH
          should be added when the /customer/bookings route is delivered. */}
      {!loading && !error && bookings.length > 0 && (
        <div className={styles.tableWrapper}>
          <table className={styles.table} aria-label="Recent bookings">
            <thead>
              <tr>
                <th>Service</th>
                <th>Reference</th>
                <th>Schedule</th>
                <th>Amount</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              {bookings.map((b) => {
                const statusInfo = getBookingStatusInfo(b.status);
                const schedule = formatBookingSchedule(b);
                const hasPrice = b.totalPrice != null;

                return (
                  <tr key={b.id || b.ref} className={styles.tableRow}>
                    <td style={{ fontWeight: 600 }}>{b.serviceTitle || 'Service booking'}</td>
                    <td style={{ color: '#475569' }}>{b.ref || '—'}</td>
                    <td style={{ color: '#475569' }}>{schedule}</td>
                    <td style={{ fontWeight: 600 }}>
                      {hasPrice ? `${formatAmount(b.totalPrice)} ${b.currency || 'LKR'}` : ''}
                    </td>
                    <td>
                      <StatusBadge variant={statusInfo.variant}>{statusInfo.label}</StatusBadge>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

export default RecentBookings;
