/**
 * File: FormStructure.jsx
 * Module: WP07 - Shared Form & Basic UI Components
 *
 * Purpose:
 * Provides a reusable and consistent form layout
 * for the AI-Powered Local Services Marketplace.
 *
 * Features:
 * - Optional form title and description
 * - Accessible native form submission
 * - Consistent field layout and spacing
 * - Optional form-level error message
 * - Cancel and submit actions
 * - Loading and disabled submit states
 *
 * Design reference:
 * SharedForms/FormStructure - Figma Component Sheet.
 */

import { useId } from "react";
import Button from "./Button";
import "./forms.css";

/**
 * FormStructure
 *
 * Provides the shared structure for forms while
 * allowing parent components to manage their own
 * fields, values, and validation logic.
 *
 * Props:
 * - title: Optional form heading
 * - description: Optional supporting text
 * - children: Form fields supplied by the parent
 * - onSubmit: Form submission handler
 * - onCancel: Optional cancel button handler
 * - submitLabel: Text displayed on the submit button
 * - cancelLabel: Text displayed on the cancel button
 * - loading: Indicates that submission is in progress
 * - disabled: Disables the submit button
 * - error: Optional form-level validation message
 * - className: Additional form CSS classes
 *
 * Remaining native form attributes are forwarded
 * to the underlying HTML form element.
 */
function FormStructure({
  title,
  description,
  children,
  onSubmit,
  onCancel,
  submitLabel = "Submit",
  cancelLabel = "Cancel",
  loading = false,
  disabled = false,
  error,
  className = "",
  ...rest
}) {
  // Generates stable IDs for accessible form descriptions.
  const generatedId = useId();
  const descriptionId = `${generatedId}-description`;
  const errorId = `${generatedId}-error`;

  // Determines whether a form-level error is present.
  const hasError = Boolean(error);

  // Associates the form with its supporting messages.
  const describedBy =
    [
      rest["aria-describedby"],
      description ? descriptionId : null,
      hasError ? errorId : null,
    ]
      .filter(Boolean)
      .join(" ") || undefined;

  return (
    <form
      {...rest}
      onSubmit={onSubmit}
      aria-describedby={describedBy}
      className={["shared-form", className].filter(Boolean).join(" ")}
    >
      {/* Optional form heading and supporting description. */}
      {(title || description) && (
        <div className="shared-form__header">
          {title && <h2 className="shared-form__title">{title}</h2>}

          {description && (
            <p className="shared-form__description" id={descriptionId}>
              {description}
            </p>
          )}
        </div>
      )}

      {/* Parent components provide the actual form fields. */}
      <div className="shared-form__fields">{children}</div>

      {/* Optional form-level validation feedback. */}
      {hasError && (
        <p className="shared-form__error" id={errorId} role="alert">
          {error}
        </p>
      )}

      {/* Consistent form action buttons. */}
      <div className="shared-form__actions">
        {/* Render Cancel only when a handler is provided. */}
        {onCancel && (
          <Button type="button" variant="outline" onClick={onCancel}>
            {cancelLabel}
          </Button>
        )}

        {/* Native submit button triggers the form handler. */}
        <Button
          type="submit"
          variant="primary"
          loading={loading}
          disabled={disabled}
        >
          {submitLabel}
        </Button>
      </div>
    </form>
  );
}

export default FormStructure;
