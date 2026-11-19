import { Route, Routes } from 'react-router-dom'

import { ToastProvider } from '../../../shared/components/feedback'
import { AdminNotFoundPage } from '../../disputes-admin/pages/AdminNotFoundPage'
import { AnnouncementFormPage } from './AnnouncementFormPage'
import { AnnouncementsListPage } from './AnnouncementsListPage'
import { AnnouncementViewPage } from './AnnouncementViewPage'

/**
 * All announcement screens under the ADMIN area's "announcements" route (the sidebar item stays active on each):
 *   ''          list
 *   'new'       create form
 *   ':id'       view
 *   ':id/edit'  edit form (same component as create)
 * The shared ToastProvider is not mounted at the app root yet, so this section provides it.
 */
export default function AnnouncementsSection() {
  return (
    <ToastProvider>
      <Routes>
        <Route index element={<AnnouncementsListPage />} />
        <Route path="new" element={<AnnouncementFormPage />} />
        <Route path=":id" element={<AnnouncementViewPage />} />
        <Route path=":id/edit" element={<AnnouncementFormPage />} />
        <Route path="*" element={<AdminNotFoundPage />} />
      </Routes>
    </ToastProvider>
  )
}
