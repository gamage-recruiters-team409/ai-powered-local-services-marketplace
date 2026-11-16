/**
 * File: Checkbox.jsx
 * Module: WP07 - Shared Form & Basic UI Components
 *
 * Purpose:
 * Provides a reusable checkbox component with
 * consistent labels, helper text, validation feedback,
 * and accessible native checkbox interactions.
 *
 * Supported states:
 * - Unchecked
 * - Checked
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
 * Checkbox
 *
 * Reusable checkbox field for forms throughout
 * the AI-Powered Local Services Marketplace.
 *
 * Props:
 * - label: Text displayed beside the checkbox
 * - helperText: Optional guidance below the field
 * - error: Optional validation error message
 * - required: Marks the checkbox as mandatory
 * - disabled: Prevents user interaction
 * - id: Optional custom checkbox identifier
 * - className: Additional wrapper CSS classes
 *
 * Native input props such as checked, defaultChecked,
 * onChange, name, and value are forwarded to the input.
 */
function Checkbox({
  label,
  helperText,
  error,
  required = false,
  disabled = false,
  id,
  className = "",
  ...rest
}) {
  // Generates a stable ID for the checkbox and its label.
  const generatedId = useId();
  const checkboxId = id || generatedId;

  // IDs connect supporting messages to the checkbox.
  const helperId = `${checkboxId}-helper`;
  const errorId = `${checkboxId}-error`;

  // Determines whether validation feedback is present.
  const hasError = Boolean(error);

  // Combines custom and component-generated descriptions.
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
        "shared-checkbox-field",
        disabled ? "shared-checkbox-field--disabled" : "",
        hasError ? "shared-checkbox-field--error" : "",
        className,
      ]
        .filter(Boolean)
        .join(" ")}
    >
      {/* Groups the checkbox control and visible label. */}
      <div className="shared-checkbox-row">
        {/* Native checkbox supports keyboard and form behaviour. */}
        <input
          {...rest}
          id={checkboxId}
          type="checkbox"
          required={required}
          disabled={disabled}
          aria-invalid={hasError ? "true" : undefined}
          aria-describedby={describedBy}
          className="shared-checkbox"
        />

        {/* Clicking the label toggles the associated checkbox. */}
        {label && (
          <label className="shared-checkbox__label" htmlFor={checkboxId}>
            {label}

            {/* Indicates that checking the box is required. */}
            {required && (
              <span className="shared-field__required" aria-hidden="true">
                {" *"}
              </span>
            )}
          </label>
        )}
      </div>

      {/* Displays optional guidance below the checkbox. */}
      {helperText && (
        <p className="shared-checkbox__helper" id={helperId}>
          {helperText}
        </p>
      )}

      {/* Displays validation feedback when an error exists. */}
      {hasError && (
        <p className="shared-checkbox__error" id={errorId}>
          {error}
        </p>
      )}
    </div>
  );
}

export default Checkbox;
