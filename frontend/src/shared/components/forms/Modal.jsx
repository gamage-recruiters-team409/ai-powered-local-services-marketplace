/**
 * File: Modal.jsx
 * Module: WP07 - Shared Form & Basic UI Components
 *
 * Purpose:
 * Provides a reusable generic modal dialog for
 * displaying custom content across the application.
 *
 * Features:
 * - Controlled open and close behaviour
 * - Native modal dialog accessibility
 * - Escape key handling
 * - Optional backdrop click to close
 * - Custom title, description, body, and footer
 * - Close button with an accessible label
 * - Portal rendering outside the application layout
 *
 * Design reference:
 * Shared Form & Basic UI Components - Figma.
 */

import { useEffect, useId, useRef } from "react";
import { createPortal } from "react-dom";
import "./forms.css";

/**
 * Modal
 *
 * Props:
 * - isOpen: Controls whether the modal is visible
 * - onClose: Callback requesting the modal to close
 * - title: Optional modal heading
 * - description: Optional supporting description
 * - children: Custom modal body content
 * - footer: Optional footer actions or buttons
 * - closeOnBackdrop: Enables backdrop click to close
 * - showCloseButton: Controls close button visibility
 * - className: Additional modal CSS classes
 *
 * The parent component owns the isOpen state.
 * This component requests closure through onClose.
 */
function Modal({
  isOpen = false,
  onClose,
  title,
  description,
  children,
  footer,
  closeOnBackdrop = true,
  showCloseButton = true,
  className = "",
}) {
  // References the native HTML dialog element.
  const dialogRef = useRef(null);

  // Generates stable IDs for accessible modal descriptions.
  const generatedId = useId();
  const titleId = `${generatedId}-title`;
  const descriptionId = `${generatedId}-description`;

  /**
   * Synchronizes the native dialog state with
   * the controlled isOpen prop.
   *
   * showModal() places the dialog in the browser's
   * top layer and activates native modal behaviour.
   */
  useEffect(() => {
    const dialog = dialogRef.current;

    if (!dialog) {
      return;
    }

    if (isOpen && !dialog.open) {
      dialog.showModal();
    } else if (!isOpen && dialog.open) {
      dialog.close();
    }
  }, [isOpen]);

  /**
   * Requests closure from the parent component.
   * The parent should update isOpen to false.
   */
  const handleClose = () => {
    if (typeof onClose === "function") {
      onClose();
    }
  };

  /**
   * Handles native dialog cancellation, including
   * the Escape key.
   *
   * Preventing the default close keeps the dialog
   * synchronized with the parent's controlled state.
   */
  const handleCancel = (event) => {
    event.preventDefault();
    handleClose();
  };

  /**
   * Allows optional backdrop-click dismissal.
   *
   * A click directly on the dialog element indicates
   * a backdrop click because the visible content is
   * contained within a separate inner wrapper.
   */
  const handleBackdropClick = (event) => {
    if (closeOnBackdrop && event.target === event.currentTarget) {
      handleClose();
    }
  };

  /**
   * Keeps the parent informed if the browser closes
   * the dialog through a native close mechanism.
   */
  const handleNativeClose = () => {
    if (isOpen) {
      handleClose();
    }
  };

  // Portal rendering requires a browser document.
  if (typeof document === "undefined") {
    return null;
  }

  return createPortal(
    <dialog
      ref={dialogRef}
      className={["shared-modal", className].filter(Boolean).join(" ")}
      aria-labelledby={title ? titleId : undefined}
      aria-label={title ? undefined : "Dialog"}
      aria-describedby={description ? descriptionId : undefined}
      onCancel={handleCancel}
      onClose={handleNativeClose}
      onClick={handleBackdropClick}
    >
      {/* Inner container holds all visible modal content. */}
      <div className="shared-modal__content">
        {/* Modal header with optional title and close button. */}
        {(title || description || showCloseButton) && (
          <div className="shared-modal__header">
            <div className="shared-modal__heading">
              {title && (
                <h2 className="shared-modal__title" id={titleId}>
                  {title}
                </h2>
              )}

              {description && (
                <p className="shared-modal__description" id={descriptionId}>
                  {description}
                </p>
              )}
            </div>

            {/* Accessible close control. */}
            {showCloseButton && (
              <button
                type="button"
                className="shared-modal__close"
                onClick={handleClose}
                aria-label="Close dialog"
              >
                <svg
                  width="20"
                  height="20"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="1.9"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  aria-hidden="true"
                >
                  <path d="M18 6 6 18" />
                  <path d="m6 6 12 12" />
                </svg>
              </button>
            )}
          </div>
        )}

        {/* Custom content supplied by the parent component. */}
        <div className="shared-modal__body">{children}</div>

        {/* Optional footer for custom action buttons. */}
        {footer && <div className="shared-modal__footer">{footer}</div>}
      </div>
    </dialog>,
    document.body,
  );
}

export default Modal;
