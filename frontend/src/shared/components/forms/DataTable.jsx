/**
 * File: DataTable.jsx
 * Module: WP07 - Shared Form & Basic UI Components
 *
 * Purpose:
 * Provides a reusable, accessible data table for
 * displaying structured records across the application.
 *
 * Features:
 * - Configurable columns and rows
 * - Custom cell rendering
 * - Optional search and filter controls
 * - Optional row actions
 * - Loading, empty, and error content slots
 * - Optional pagination integration
 * - Responsive horizontal scrolling
 *
 * Design reference:
 * SharedForms/DataTable - Figma Component Sheet.
 */

import { useId } from "react";
import "./forms.css";

/**
 * DataTable
 *
 * Props:
 * - columns: Array of column definitions
 * - data: Array of records to display
 * - getRowId: Optional function returning a unique row ID
 * - title: Optional table heading
 * - description: Optional supporting description
 * - toolbar: Optional search/filter controls
 * - actions: Optional row action renderer
 * - loading: Indicates that data is being fetched
 * - error: Optional data-loading error
 * - emptyMessage: Message shown when no records exist
 * - loadingContent: Optional shared loading component
 * - emptyContent: Optional shared empty-state component
 * - errorContent: Optional shared error-state component
 * - pagination: Optional pagination component
 * - className: Additional wrapper CSS classes
 *
 * Column format:
 * {
 *   key: "name",
 *   header: "Name",
 *   render: (value, row) => value
 * }
 *
 * The optional render function supports custom content,
 * including badges, formatted values, and action elements.
 */
function DataTable({
  columns = [],
  data = [],
  getRowId,
  title,
  description,
  toolbar,
  actions,
  loading = false,
  error,
  emptyMessage = "No records found.",
  loadingContent,
  emptyContent,
  errorContent,
  pagination,
  className = "",
}) {
  // Generates stable IDs for accessible table descriptions.
  const generatedId = useId();
  const titleId = `${generatedId}-title`;
  const descriptionId = `${generatedId}-description`;

  // Determines which table content state should be displayed.
  const hasError = Boolean(error);
  const isEmpty = data.length === 0;

  // Normalize errors into renderable text to prevent React from
  // attempting to render JavaScript Error objects directly.
  const errorMessage =
    error instanceof Error
      ? error.message || "Unable to load records."
      : typeof error === "string"
        ? error
        : "Unable to load records.";

  /**
   * Returns the content for a table cell.
   *
   * Custom render functions receive the field value,
   * full row record, and row index.
   */
  const renderCell = (column, row, rowIndex) => {
    const value = row[column.key];

    if (typeof column.render === "function") {
      return column.render(value, row, rowIndex);
    }

    // Avoid rendering undefined or null values directly.
    return value ?? "—";
  };

  /**
   * Resolves a stable React key for each record.
   *
   * A caller-provided getRowId function is preferred.
   * Otherwise, the record's id field is used.
   */
  const resolveRowId = (row, rowIndex) => {
    if (typeof getRowId === "function") {
      return getRowId(row, rowIndex);
    }

    return row.id ?? rowIndex;
  };

  // An optional actions renderer adds one final table column.
  const hasActions = typeof actions === "function";
  const columnCount = Math.max(columns.length + (hasActions ? 1 : 0), 1);

  return (
    <section
      className={["shared-table-section", className].filter(Boolean).join(" ")}
      aria-labelledby={title ? titleId : undefined}
      aria-describedby={description ? descriptionId : undefined}
    >
      {/* Optional heading and supporting description. */}
      {(title || description) && (
        <div className="shared-table__header">
          {title && (
            <h2 className="shared-table__title" id={titleId}>
              {title}
            </h2>
          )}

          {description && (
            <p className="shared-table__description" id={descriptionId}>
              {description}
            </p>
          )}
        </div>
      )}

      {/* Parent-provided search and filter controls. */}
      {toolbar && <div className="shared-table__toolbar">{toolbar}</div>}

      {/* Horizontal scrolling keeps wide tables usable. */}
      <div
        className="shared-table__container"
        role="region"
        aria-label={title || "Data table"}
        tabIndex={0}
        aria-busy={loading}
      >
        <table className="shared-table">
          {/* Column headings. */}
          <thead className="shared-table__head">
            <tr>
              {columns.map((column) => (
                <th
                  key={column.key}
                  scope="col"
                  className="shared-table__heading"
                >
                  {column.header}
                </th>
              ))}

              {hasActions && (
                <th scope="col" className="shared-table__heading">
                  Actions
                </th>
              )}
            </tr>
          </thead>

          <tbody className="shared-table__body">
            {/* Loading state takes priority over table data. */}
            {loading ? (
              <tr>
                <td colSpan={columnCount} className="shared-table__state">
                  {loadingContent || "Loading records..."}
                </td>
              </tr>
            ) : hasError ? (
              /* Display loading or retrieval errors. */
              <tr>
                <td
                  colSpan={columnCount}
                  className="shared-table__state shared-table__state--error"
                >
                  {errorContent ?? errorMessage}
                </td>
              </tr>
            ) : isEmpty ? (
              /* Display an empty state when no rows exist. */
              <tr>
                <td colSpan={columnCount} className="shared-table__state">
                  {emptyContent || emptyMessage}
                </td>
              </tr>
            ) : (
              /* Render the supplied data records. */
              data.map((row, rowIndex) => (
                <tr
                  key={resolveRowId(row, rowIndex)}
                  className="shared-table__row"
                >
                  {columns.map((column) => (
                    <td key={column.key} className="shared-table__cell">
                      {renderCell(column, row, rowIndex)}
                    </td>
                  ))}

                  {/* Optional actions for the current row. */}
                  {hasActions && (
                    <td className="shared-table__cell shared-table__cell--actions">
                      {actions(row, rowIndex)}
                    </td>
                  )}
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      {/* Parent-provided pagination control. */}
      {!loading && !hasError && pagination && (
        <div className="shared-table__pagination">{pagination}</div>
      )}
    </section>
  );
}

export default DataTable;
