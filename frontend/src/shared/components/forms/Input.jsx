/**
 * File: Input.jsx
 * Module: WP07 - Shared Form & Basic UI Components
 *
 * Purpose:
 * Provides a reusable text-based input component
 * with consistent labels, helper text, validation
 * feedback, and accessibility support.
 *
 * Supported states:
 * - Default
 * - Focused (styled through forms.css)
 * - Error
 * - Disabled
 * - Required
 *
 * Note:
 * Password visibility controls will be implemented
 * separately in PasswordInput.jsx.
 */

import { useId } from "react";
import "./forms.css";

/**
 * Input
 *
 * A reusable input field for forms throughout
 * the AI-Powered Local Services Marketplace.
 *
 * Props:
 * - label: Visible field label
 * - type: Native HTML input type
 * - helperText: Optional supporting information
 * - error: Validation error message
 * - required: Marks the field as mandatory
 * - disabled: Prevents user interaction
 * - className: Additional wrapper CSS classes
 * - id: Optional custom input identifier
 *
 * Other native input attributes are forwarded
 * to the underlying HTML input element.
 */
function Input({
  label,
  type = "text",
  helperText,
  error,
  required = false,
  disabled = false,
  className = "",
  id,
  ...rest
}) {
  // Generates a unique ID when the parent does not provide one.
  const generatedId = useId();
  const inputId = id || generatedId;

  // Associates helper text and errors with the input.
  const helperId = `${inputId}-helper`;
  const errorId = `${inputId}-error`;

  // Uses the presence of an error message to set the error state.
  const hasError = Boolean(error);

  // Builds an accessible description from available messages.
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
      {/* Render a label only when label text is supplied. */}
      {label && (
        <label className="shared-field__label" htmlFor={inputId}>
          {label}

          {/* Visually identify mandatory form fields. */}
          {required && (
            <span className="shared-field__required" aria-hidden="true">
              {" *"}
            </span>
          )}
        </label>
      )}

      {/* Native input supports keyboard and browser interactions. */}
      <input
        {...rest}
        id={inputId}
        type={type}
        required={required}
        disabled={disabled}
        aria-invalid={hasError ? "true" : undefined}
        aria-describedby={describedBy}
        className={["shared-input", hasError ? "shared-input--error" : ""]
          .filter(Boolean)
          .join(" ")}
      />

      {/* Optional guidance appears below the input. */}
      {helperText && (
        <p className="shared-field__helper" id={helperId}>
          {helperText}
        </p>
      )}

      {/* Validation errors are linked to the corresponding field. */}
      {hasError && (
        <p className="shared-field__error" id={errorId}>
          {error}
        </p>
      )}
    </div>
  );
}

export default Input;
