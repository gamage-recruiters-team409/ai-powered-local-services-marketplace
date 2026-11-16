/**
 * File: Badge.jsx
 * Module: WP07 - Shared Form & Basic UI Components
 *
 * Purpose:
 * Provides reusable status badges with consistent
 * colours, typography, and sizing across the application.
 *
 * Supported variants:
 * - Success
 * - Warning
 * - Error
 * - Info
 * - Neutral
 *
 * Supported sizes:
 * - Small
 * - Medium
 *
 * Design reference:
 * Shared Form & Basic UI Components - Figma.
 */

import "./forms.css";

// Restrict variants to the styles supported by the design system.
const BADGE_VARIANTS = ["success", "warning", "error", "info", "neutral"];

// Restrict sizes to the supported badge dimensions.
const BADGE_SIZES = ["small", "medium"];

/**
 * Badge
 *
 * Displays short status text with an optional icon.
 *
 * Props:
 * - children: Visible badge text or content
 * - variant: Visual status variant
 * - size: Badge size
 * - icon: Optional decorative React icon element
 * - className: Additional CSS classes
 *
 * Additional HTML attributes are forwarded to the
 * underlying span element.
 *
 * Accessibility:
 * Status meaning should always be communicated
 * through visible text, not colour alone.
 */
function Badge({
  children,
  variant = "neutral",
  size = "medium",
  icon,
  className = "",
  ...rest
}) {
  // Use safe defaults when unsupported values are supplied.
  const safeVariant = BADGE_VARIANTS.includes(variant) ? variant : "neutral";

  const safeSize = BADGE_SIZES.includes(size) ? size : "medium";

  return (
    <span
      {...rest}
      className={[
        "shared-badge",
        `shared-badge--${safeVariant}`,
        `shared-badge--${safeSize}`,
        className,
      ]
        .filter(Boolean)
        .join(" ")}
    >
      {/* Optional decorative icon displayed before the text. */}
      {icon && (
        <span className="shared-badge__icon" aria-hidden="true">
          {icon}
        </span>
      )}

      {/* Visible status text communicates badge meaning. */}
      <span className="shared-badge__label">{children}</span>
    </span>
  );
}

export default Badge;
