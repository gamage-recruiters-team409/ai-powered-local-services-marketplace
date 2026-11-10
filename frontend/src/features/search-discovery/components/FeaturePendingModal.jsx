/**
 * Accessible Modal Dialog providing explicit milestone feedback
 * when users click pending actions like "View profile" or "Compare".
 */
export function FeaturePendingModal({ isOpen, title, message, badgeText, onClose }) {
  if (!isOpen) return null;

  return (
    <div
      className="pending-modal-overlay"
      role="dialog"
      aria-modal="true"
      aria-labelledby="pending-modal-title"
      onClick={onClose}
    >
      <div
        className="pending-modal-card"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="pending-modal-header">
          {badgeText && <span className="pending-modal-badge">{badgeText}</span>}
          <h3 id="pending-modal-title" className="pending-modal-title">{title}</h3>
        </div>

        <p className="pending-modal-message">{message}</p>

        <div className="pending-modal-footer">
          <button
            type="button"
            className="pending-modal-btn"
            onClick={onClose}
            autoFocus
          >
            Understood
          </button>
        </div>
      </div>
    </div>
  );
}

export default FeaturePendingModal;
