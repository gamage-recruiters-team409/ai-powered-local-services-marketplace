import { Button, Modal } from '../../../shared/components/forms'
import CompletionIcon from './CompletionIcons'
import ModalTitle from './ModalTitle'
import { formatSlot } from '../utils/completionUtils'

/** S01 – "Start this service?" confirmation. */
export default function StartServiceModal({ isOpen, onClose, onConfirm, booking, loading = false, error = null }) {
  const details = [`#${booking.ref}`, formatSlot(booking.slotStart, booking.slotEnd), booking.customerName]
    .filter(Boolean)
    .join(' · ')

  return (
    <Modal
      isOpen={isOpen}
      onClose={loading ? undefined : onClose}
      closeOnBackdrop={!loading}
      className="sc-modal sc-modal--narrow"
      title={<ModalTitle icon="play">Start this service?</ModalTitle>}
      footer={
        <>
          <Button variant="outline" onClick={onClose} disabled={loading}>Cancel</Button>
          <Button variant="primary" onClick={onConfirm} loading={loading}>Start Service</Button>
        </>
      }
    >
      <div className="sc-summary-card">
        <p className="sc-summary-card__title">{booking.title}</p>
        <p className="sc-summary-card__meta">{details}</p>
      </div>

      <p className="sc-callout sc-callout--info">
        <CompletionIcon name="info" />
        <span>The customer will be notified that the service has started.</span>
      </p>

      {error && (
        <p className="sc-callout sc-callout--error" role="alert">
          <CompletionIcon name="alert" />
          <span>{error}</span>
        </p>
      )}
    </Modal>
  )
}
