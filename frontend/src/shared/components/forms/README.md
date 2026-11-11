# WP07 – Shared Form & Basic UI Components

Reusable React components for the AI-Powered Local Services Marketplace.

## Components

1. Button – Reusable action buttons with variants, sizes and loading states.
2. Input – Text input with labels, helper text and validation feedback.
3. PasswordInput – Password field with show/hide functionality.
4. Textarea – Multiline text input.
5. Select – Dropdown with placeholder and selectable options.
6. Checkbox – Checkbox with validation and disabled states.
7. Radio – Radio input for single-option selection.
8. FormStructure – Standard form layout with submit and cancel actions.
9. DataTable – Reusable table with loading, empty and error states.
10. Pagination – Previous, next and numbered page navigation.
11. Badge – Status indicators with multiple variants.
12. Modal – Generic modal dialog with controlled open/close behavior.

## Usage

Import components from the shared index:

```jsx
import { Button, Input, Badge } from "./shared/components/forms";
```

Adjust the import path according to the consuming file.

## Styling

Components use `forms.css` and follow the shared Figma design reference.

## State and Validation

- Parent components manage custom validation and form state.
- Pagination uses `currentPage`, `totalPages` and `onPageChange`.
- Modal uses `isOpen` and `onClose`.
- DataTable accepts columns and data; filtering and pagination logic are managed by the parent.

## Testing

- ESLint: Passed.
- Vite production build: Passed.
- Shared components export build: Passed.
- Browser interaction testing performed.
- Testing evidence screenshots prepared for PR review.

## Module Ownership

WP07 – Shared Form & Basic UI Components.
