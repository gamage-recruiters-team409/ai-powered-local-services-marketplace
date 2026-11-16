/**
 * File: Textarea.jsx
 * Module: WP07 - Shared Form & Basic UI Components
 *
 * Purpose:
 * Provides a reusable multi-line text input component
 * with consistent labels, validation feedback,
 * helper text, and accessibility support.
 *
 * Supported states:
 * - Default
 * - Focus
 * - Error
 * - Disabled
 * - Required
 *
 * Design reference:
 * Shared Form & Basic UI Components - Figma.
 */

import { useId } from "react";
import "./forms.css";

/**
 * Textarea
 *
 * Reusable multi-line text field for forms throughout
 * the AI-Powered Local Services Marketplace.
 *
 * Props:
 * - label: Visible field label
 * - helperText: Optional guidance below the field
 * - error: Validation error message
 * - required: Marks the field as mandatory
 * - disabled: Prevents editing
 * - rows: Initial number of visible text rows
 * - id: Optional custom field identifier
 * - className: Additional wrapper CSS classes
 *
 * Other native textarea attributes are forwarded
 * to the underlying HTML textarea element.
 */
function Textarea({
  label,
  helperText,
  error,
  required = false,
  disabled = false,
  rows = 4,
  id,
  className = "",
  ...rest
}) {
  // Generates a stable, unique ID for label association.
  const generatedId = useId();
  const textareaId = id || generatedId;

  // IDs connect helper and error messages to the field.
  const helperId = `${textareaId}-helper`;
  const errorId = `${textareaId}-error`;

  // Determines whether the field has a validation error.
  const hasError = Boolean(error);

  // Combines parent-provided and component-generated descriptions.
  const describedBy =
    [
      rest["aria-describedby"],
      helperText ? helperId : null,
      hasError ? errorId : null,
    ]
      .filter(Boolean)
      .join(" ") || undefined;

  return (
    <div
      className={[
        "shared-field",
        disabled ? "shared-field--disabled" : "",
        className,
      ]
        .filter(Boolean)
        .join(" ")}
    >
      {/* Render the field label when provided. */}
      {label && (
        <label className="shared-field__label" htmlFor={textareaId}>
          {label}

          {/* Display an asterisk for required fields. */}
          {required && (
            <span className="shared-field__required" aria-hidden="true">
              {" *"}
            </span>
          )}
        </label>
      )}

      {/* Native textarea supports multi-line text entry. */}
      <textarea
        {...rest}
        id={textareaId}
        rows={rows}
        required={required}
        disabled={disabled}
        aria-invalid={hasError ? "true" : undefined}
        aria-describedby={describedBy}
        className={["shared-textarea", hasError ? "shared-textarea--error" : ""]
          .filter(Boolean)
          .join(" ")}
      />

      {/* Display optional supporting information. */}
      {helperText && (
        <p className="shared-field__helper" id={helperId}>
          {helperText}
        </p>
      )}

      {/* Display validation errors below the textarea. */}
      {hasError && (
        <p className="shared-field__error" id={errorId}>
          {error}
        </p>
      )}
    </div>
  );
}

export default Textarea;
