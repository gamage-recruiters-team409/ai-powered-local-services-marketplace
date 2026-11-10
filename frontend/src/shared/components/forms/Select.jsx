/**
 * File: Select.jsx
 * Module: WP07 - Shared Form & Basic UI Components
 *
 * Purpose:
 * Provides a reusable dropdown field with consistent
 * labels, placeholder text, validation feedback,
 * helper text, and accessibility support.
 *
 * Supported states:
 * - Default
 * - Focus
 * - Error
 * - Disabled
 * - Required
 * - Placeholder
 *
 * Design reference:
 * Shared Form & Basic UI Components - Figma.
 */

import { useEffect, useId, useRef, useState } from "react";
import "./forms.css";

/**
 * Select
 *
 * Reusable native dropdown component for forms
 * throughout the AI-Powered Local Services Marketplace.
 *
 * Props:
 * - label: Visible field label
 * - options: Array of dropdown options
 * - placeholder: Optional initial placeholder
 * - helperText: Optional guidance below the field
 * - error: Validation error message
 * - required: Marks the field as mandatory
 * - disabled: Prevents selection
 * - id: Optional custom field identifier
 * - className: Additional wrapper CSS classes
 *
 * Each option should contain:
 * { value: "value", label: "Display text" }
 *
 * Remaining native select attributes are forwarded
 * to the underlying HTML select element.
 */
function Select({
  label,
  options = [],
  placeholder,
  helperText,
  error,
  required = false,
  disabled = false,
  id,
  className = "",
  ...rest
}) {
  // Generates a stable ID for label association.
  const generatedId = useId();
  const selectId = id || generatedId;

  // Connects helper and validation messages to the select.
  const helperId = `${selectId}-helper`;
  const errorId = `${selectId}-error`;

  // Determines whether a validation error is present.
  const hasError = Boolean(error);

  // Combines any parent description with field messages.
  const describedBy =
    [
      rest["aria-describedby"],
      helperText ? helperId : null,
      hasError ? errorId : null,
    ]
      .filter(Boolean)
      .join(" ") || undefined;

  // Track the selected value when the parent does not control it.
  const [internalValue, setInternalValue] = useState(
    rest.defaultValue ?? (placeholder ? "" : undefined),
  );

  // Keep uncontrolled selection styling synchronized with native form resets.
  const selectRef = useRef(null);

  useEffect(() => {
    const select = selectRef.current;
    const form = select?.form;

    if (!form || rest.value !== undefined) {
      return;
    }

    const handleReset = () => {
      setTimeout(() => {
        setInternalValue(select.value);
      }, 0);
    };

    form.addEventListener("reset", handleReset);

    return () => {
      form.removeEventListener("reset", handleReset);
    };
  }, [rest.value]);

  // Prefer the parent's value for controlled dropdowns.
  const selectedValue = rest.value !== undefined ? rest.value : internalValue;

  // Apply placeholder styling only while the empty option is selected.
  const isPlaceholderSelected = Boolean(placeholder) && selectedValue === "";

  // Update local selection state and notify the parent.
  const handleChange = (event) => {
    if (rest.value === undefined) {
      setInternalValue(event.target.value);
    }

    rest.onChange?.(event);
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
      {/* Render the field label when provided. */}
      {label && (
        <label className="shared-field__label" htmlFor={selectId}>
          {label}

          {/* Display a required-field indicator. */}
          {required && (
            <span className="shared-field__required" aria-hidden="true">
              {" *"}
            </span>
          )}
        </label>
      )}

      {/* Wrapper positions the custom dropdown arrow. */}
      <div className="shared-select-wrapper">
        <select
          {...rest}
          ref={selectRef}
          value={rest.value !== undefined ? rest.value : undefined}
          defaultValue={
            rest.value === undefined
              ? (rest.defaultValue ?? (placeholder ? "" : undefined))
              : undefined
          }
          onChange={handleChange}
          id={selectId}
          required={required}
          disabled={disabled}
          aria-invalid={hasError ? "true" : undefined}
          aria-describedby={describedBy}
          className={[
            "shared-select",
            hasError ? "shared-select--error" : "",
            isPlaceholderSelected ? "shared-select--placeholder" : "",
          ]
            .filter(Boolean)
            .join(" ")}
        >
          {/* Empty value supports required-field validation. */}
          {placeholder && (
            <option value="" disabled>
              {placeholder}
            </option>
          )}

          {/* Render the supplied dropdown options. */}
          {options.map((option) => (
            <option
              key={option.value}
              value={option.value}
              disabled={option.disabled || false}
            >
              {option.label}
            </option>
          ))}
        </select>

        {/* Decorative arrow; native select remains accessible. */}
        <svg
          className="shared-select__arrow"
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
          <path d="m6 9 6 6 6-6" />
        </svg>
      </div>

      {/* Optional helper text. */}
      {helperText && (
        <p className="shared-field__helper" id={helperId}>
          {helperText}
        </p>
      )}

      {/* Validation error message. */}
      {hasError && (
        <p className="shared-field__error" id={errorId}>
          {error}
        </p>
      )}
    </div>
  );
}

export default Select;
