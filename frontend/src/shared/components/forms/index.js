
/**
 * File: index.js
 * Module: WP07 - Shared Form & Basic UI Components
 *
 * Purpose:
 * Provides a central export point for reusable
 * form controls, data display components, and modals.
 *
 * Usage:
 * Import shared components from this directory
 * instead of importing individual component files.
 *
 * Example:
 * import { Button, Input, Badge } from
 *   "./shared/components/forms";
 */

// Reusable button component.
export { default as Button } from "./Button";

// Standard text and password inputs.
export { default as Input } from "./Input";
export { default as PasswordInput } from "./PasswordInput";

// Multiline text input and dropdown selection.
export { default as Textarea } from "./Textarea";
export { default as Select } from "./Select";

// Checkbox and radio form controls.
export { default as Checkbox } from "./Checkbox";
export { default as Radio } from "./Radio";

// Reusable form structure and action layout.
export { default as FormStructure } from "./FormStructure";

// Data display and navigation components.
export { default as DataTable } from "./DataTable";
export { default as Pagination } from "./Pagination";
export { default as Badge } from "./Badge";

// Generic reusable modal dialog.
export { default as Modal } from "./Modal";
