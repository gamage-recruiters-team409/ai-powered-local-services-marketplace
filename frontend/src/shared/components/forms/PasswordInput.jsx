/**
 * File: PasswordInput.jsx
 * Module: WP07 - Shared Form & Basic UI Components
 *
 * Purpose:
 * Provides a reusable password input field with
 * accessible show/hide password functionality.
 *
 * Supported states:
 * - Default
 * - Focus
 * - Error
 * - Disabled
 * - Required
 * - Password visible / hidden
 *
 * Design reference:
 * SharedForms/PasswordInput - Figma Component Sheet.
 */

import { useId, useState } from "react";
import "./forms.css";

/**
 * EyeIcon
 *
 * Displays an eye icon for the password visibility toggle.
 * Uses an inline SVG to avoid additional dependencies.
 */
function EyeIcon({ visible }) {
  return (
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
      {/* Eye outline and pupil. */}
      <path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12Z" />
      <circle cx="12" cy="12" r="3" />

      {/* A diagonal line indicates a hidden password. */}
      {!visible && <path d="M3 3l18 18" />}
    </svg>
  );
}

/**
 * PasswordInput
 *
 * Props:
 * - label: Visible field label
 * - helperText: Optional guidance below the field
 * - error: Validation error message
 * - required: Marks the field as mandatory
 * - disabled: Prevents input and toggle interactions
 * - id: Optional custom input ID
 * - className: Additional wrapper CSS classes
 *
 * Remaining native input props are forwarded to
 * the underlying input element.
 */
function PasswordInput({
  label,
  helperText,
  error,
  required = false,
  disabled = false,
  id,
  className = "",
  ...rest
}) {
  // Tracks whether the password is currently visible.
  const [isVisible, setIsVisible] = useState(false);

  // Generates a stable, unique ID for label association.
  const generatedId = useId();
  const inputId = id || generatedId;

  // Generates IDs for supporting and validation messages.
  const helperId = `${inputId}-helper`;
  const errorId = `${inputId}-error`;

  // Determines whether validation feedback is present.
  const hasError = Boolean(error);

  // Preserves any accessible description supplied by a parent.
  const describedBy =
    [
      rest["aria-describedby"],
      helperText ? helperId : null,
      hasError ? errorId : null,
    ]
      .filter(Boolean)
      .join(" ") || undefined;

  /**
   * Toggles password visibility without submitting
   * the surrounding form.
   */
  const toggleVisibility = () => {
    setIsVisible((previous) => !previous);
  };

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
      {/* Field label and required indicator. */}
      {label && (
        <label className="shared-field__label" htmlFor={inputId}>
          {label}

          {required && (
            <span className="shared-field__required" aria-hidden="true">
              {" *"}
            </span>
          )}
        </label>
      )}

      {/* Positions the password field and visibility toggle. */}
      <div className="shared-password">
        <input
          {...rest}
          id={inputId}
          type={isVisible ? "text" : "password"}
          required={required}
          disabled={disabled}
          aria-invalid={hasError ? "true" : undefined}
          aria-describedby={describedBy}
          className={[
            "shared-input",
            "shared-password__input",
            hasError ? "shared-input--error" : "",
          ]
            .filter(Boolean)
            .join(" ")}
        />

        {/* Native button supports mouse and keyboard activation. */}
        <button
          type="button"
          className="shared-password__toggle"
          onClick={toggleVisibility}
          disabled={disabled}
          aria-label={isVisible ? "Hide password" : "Show password"}
          aria-pressed={isVisible}
          aria-controls={inputId}
        >
          <EyeIcon visible={isVisible} />
        </button>
      </div>

      {/* Optional supporting information. */}
      {helperText && (
        <p className="shared-field__helper" id={helperId}>
          {helperText}
        </p>
      )}

      {/* Accessible validation error message. */}
      {hasError && (
        <p className="shared-field__error" id={errorId}>
          {error}
        </p>
      )}
    </div>
  );
}

export default PasswordInput;
