import CompletionPreviewPage from '../pages/CompletionPreviewPage'

/**
 * WP08 routes. The preview page is only registered in development (npm run dev);
 * production pages are added when the booking details page (WP05) is available.
 */
export const completionRoutes = import.meta.env.DEV
  ? [{ path: '/dev/completion-preview', element: <CompletionPreviewPage /> }]
  : []
