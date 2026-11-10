# Shared system feedback components (WP06)

Reusable feedback patterns for all modules. Import from the folder index:

```jsx
import { ToastProvider, useToast, Alert, ConfirmDialog, EmptyState } from '@/shared/components/feedback'
```
(use a relative path if the `@` alias is not configured)

| Need | Component |
|---|---|
| Toast (success, error, warning, info, new message) | `ToastProvider` once at the app root, then `useToast()` |
| Page or inline alert | `Alert` (`variant`, `title`, `action`, `onClose`, `inline`) |
| Connection lost / offline banner | `ConnectionBanner` (`status="reconnecting" \| "offline"`) |
| Session expired message | `SessionExpiredNotice` |
| Field error | `FieldError` (link with `aria-describedby`) |
| Inline message, success panel, page error | `InlineMessage`, `SuccessPanel`, `PageError` |
| Confirmation (standard, destructive, with reason) | `ConfirmDialog` |
| Loading | `Spinner`, `PageLoader`, `Skeleton`, `SkeletonList`, `SkeletonCard`, `SkeletonTable` |
| Empty state | `EmptyState` (`default`, `search`, `error`) |

Notes
- Styles are in `feedback.css`; every class starts with `fb-` and every token with `--fb-`.
- `fb-btn` buttons are used only inside these components. They can be swapped for the shared
  Button component later without changing the public props.
- Colours follow the team theme: teal #0F766E primary, status colours for success, warning, error, info.
