/**
 * File: Radio.jsx
 * Module: WP07 - Shared Form & Basic UI Components
 *
 * Purpose:
 * Provides a reusable radio input with consistent
 * labels, helper text, validation feedback, and
 * accessible native radio button interactions.
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
 * Radio
 *
 * Reusable radio button for selecting one option
 * from a group of related options.
 *
 * Props:
 * - label: Text displayed beside the radio button
 * - helperText: Optional guidance below the field
 * - error: Optional validation error message
 * - required: Marks the radio group as mandatory
 * - disabled: Prevents user interaction
 * - id: Optional custom radio identifier
 * - className: Additional wrapper CSS classes
 *
 * Native input props such as name, value, checked,
 * defaultChecked, and onChange are forwarded.
 *
 * Important:
 * Radio buttons in the same group must share
 * the same name attribute.
 */
function Radio({
  label,
  helperText,
  error,
  required = false,
  disabled = false,
  id,
  className = "",
  ...rest
}) {
  // Generates a stable identifier for the radio input.
  const generatedId = useId();
  const radioId = id || generatedId;

  // Associates helper and validation messages with the input.
  const helperId = `${radioId}-helper`;
  const errorId = `${radioId}-error`;

  // Determines whether validation feedback is present.
  const hasError = Boolean(error);

  // Combines external descriptions with component messages.
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
        "shared-radio-field",
        disabled ? "shared-radio-field--disabled" : "",
        hasError ? "shared-radio-field--error" : "",
        className,
      ]
        .filter(Boolean)
        .join(" ")}
    >
      {/* Aligns the radio input with its visible label. */}
      <div className="shared-radio-row">
        {/* Native radio input supports keyboard navigation. */}
        <input
          {...rest}
          id={radioId}
          type="radio"
          required={required}
          disabled={disabled}
          aria-invalid={hasError ? "true" : undefined}
          aria-describedby={describedBy}
          className="shared-radio"
        />

        {/* Clicking the label selects the associated radio. */}
        {label && (
          <label className="shared-radio__label" htmlFor={radioId}>
            {label}

            {/* Indicates that selection is required. */}
            {required && (
              <span className="shared-field__required" aria-hidden="true">
                {" *"}
              </span>
            )}
          </label>
        )}
      </div>

      {/* Optional guidance for this radio option. */}
      {helperText && (
        <p className="shared-radio__helper" id={helperId}>
          {helperText}
        </p>
      )}

      {/* Displays validation feedback when supplied. */}
      {hasError && (
        <p className="shared-radio__error" id={errorId}>
          {error}
        </p>
      )}
    </div>
  );
}

export default Radio;
