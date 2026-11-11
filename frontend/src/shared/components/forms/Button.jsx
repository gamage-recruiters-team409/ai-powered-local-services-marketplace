import "./forms.css";

/**
 * Reusable button component for WP07 shared UI.
 *
 * Supports visual variants, multiple sizes, optional icons,
 * disabled states, and loading feedback.
 *
 * The default type is "button" to prevent accidental
 * form submissions when used inside a form.
 */
const Button = ({
  children,
  variant = "primary",
  size = "medium",
  type = "button",
  disabled = false,
  loading = false,
  icon,
  iconPosition = "left",
  className = "",
  onClick,
  ...rest
}) => {
  // Prevent interaction while the button is disabled or loading.
  const isDisabled = disabled || loading;

  // Combine the shared base class with variant, size, and custom styles.
  const buttonClasses = [
    "shared-btn",
    `shared-btn--${variant}`,
    `shared-btn--${size}`,
    className,
  ]
    .filter(Boolean)
    .join(" ");

  return (
    <button
      type={type}
      className={buttonClasses}
      disabled={isDisabled}
      onClick={onClick}
      aria-busy={loading}
      {...rest}
    >
      {loading ? (
        <>
          {/* Show loading feedback without changing the button label. */}
          <span className="shared-btn__spinner" aria-hidden="true" />
          <span>{children}</span>
        </>
      ) : (
        <>
          {/* Decorative icons are hidden from screen readers. */}
          {icon && iconPosition === "left" && (
            <span className="shared-btn__icon" aria-hidden="true">
              {icon}
            </span>
          )}

          <span>{children}</span>

          {icon && iconPosition === "right" && (
            <span className="shared-btn__icon" aria-hidden="true">
              {icon}
            </span>
          )}
        </>
      )}
    </button>
  );
};

export default Button;
