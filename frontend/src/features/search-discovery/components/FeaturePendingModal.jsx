import { useEffect } from 'react';
import Modal from '../../../shared/components/forms/Modal';

/**
 * FeaturePendingModal
 *
 * Wraps the project's shared accessible Modal dialog to provide
 * explicit milestone-boundary notices when users click pending
 * actions such as "View profile" or "Compare".
 *
 * Accessibility guarantees (delegated to the shared Modal):
 * - Native <dialog> + showModal() places the dialog in the browser
 *   top layer, automatically containing Tab/Shift-Tab within it and
 *   isolating background content from keyboard and pointer access.
 * - Escape key dismissal is handled natively by the browser and
 *   forwarded to onClose via the shared Modal's onCancel handler.
 * - Focus is moved into the dialog on open by the browser.
 *
 * Focus restoration:
 * - The optional triggerRef prop should reference the DOM element
 *   that opened the modal. Focus is returned to it on close so
 *   keyboard users are not stranded at the top of the page.
 *
 * Props:
 * - isOpen {boolean} – controlled visibility
 * - title {string} – modal heading
 * - message {string} – body copy
 * - badgeText {string} – optional milestone badge label
 * - onClose {function} – called when the modal should close
 * - triggerRef {React.RefObject} – ref of the element that opened
 *   the modal; focus is restored to it when the modal closes
 */
export function FeaturePendingModal({
  isOpen,
  title,
  message,
  badgeText,
  onClose,
  triggerRef,
}) {
  // Restore focus to the triggering element when the modal closes.
  useEffect(() => {
    if (!isOpen && triggerRef?.current) {
      triggerRef.current.focus();
    }
  }, [isOpen, triggerRef]);

  const footer = (
    <button
      type="button"
      className="pending-modal-btn"
      onClick={onClose}
    >
      Understood
    </button>
  );

  return (
    <Modal
      isOpen={isOpen}
      onClose={onClose}
      title={title}
      showCloseButton={false}
      closeOnBackdrop
      className="pending-modal-skin"
      footer={footer}
    >
      {badgeText && (
        <span className="pending-modal-badge">{badgeText}</span>
      )}
      <p className="pending-modal-message">{message}</p>
    </Modal>
  );
}

export default FeaturePendingModal;
