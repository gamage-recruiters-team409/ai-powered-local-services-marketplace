import { Button, Modal } from '../../../shared/components/forms'
import CompletionIcon from './CompletionIcons'
import ModalTitle from './ModalTitle'

/** S04 – "Confirm the service is completed?" dialog (customer). */
export default function ConfirmCompletionModal({ isOpen, onClose, onConfirm, loading = false, error = null }) {
  return (
    <Modal
      isOpen={isOpen}
      onClose={loading ? undefined : onClose}
      closeOnBackdrop={!loading}
      className="sc-modal sc-modal--narrow"
      title={<ModalTitle icon="checkCircle">Confirm the service is completed?</ModalTitle>}
      footer={
        <>
          <Button variant="outline" onClick={onClose} disabled={loading}>Cancel</Button>
          <Button variant="primary" onClick={onConfirm} loading={loading}>Yes, confirm</Button>
        </>
      }
    >
      <p className="sc-muted">
        After you confirm, the provider&apos;s payment is processed and you can leave a review. This can&apos;t be undone.
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
