import { Route, Routes } from 'react-router-dom'

import { ToastProvider } from '../../../../shared/components/feedback'
import { AdminNotFoundPage } from '../../../disputes-admin/pages/AdminNotFoundPage'
import ModerationDetailPage from './ModerationDetailPage'
import ModerationQueuePage from './ModerationQueuePage'
import '../../styles/completion.css'
import '../../styles/reviews.css'
import '../../styles/moderation.css'

/**
 * WP08 review moderation inside the admin layout, under the MODERATOR area's "moderation/reviews" route
 * (Moderation › Review moderation stays active on each screen):
 *   ''           S14 queue
 *   ':reviewId'  S15 detail (S16 decision dialog)
 * Moderators decide; Super Admin sees the same screens without the decision buttons.
 * The shared ToastProvider is not mounted at the app root yet, so this section provides it.
 */
export default function ReviewModerationSection() {
  return (
    <ToastProvider>
      <Routes>
        <Route index element={<ModerationQueuePage />} />
        <Route path=":reviewId" element={<ModerationDetailPage />} />
        <Route path="*" element={<AdminNotFoundPage />} />
      </Routes>
    </ToastProvider>
  )
}
