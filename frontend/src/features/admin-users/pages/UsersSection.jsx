import { Route, Routes } from 'react-router-dom'

import { ToastProvider } from '../../../shared/components/feedback'
import { AdminNotFoundPage } from '../../disputes-admin/pages/AdminNotFoundPage'
import { UserDetailsPage } from './UserDetailsPage'
import { UsersListPage } from './UsersListPage'

/**
 * Admin users screens under the ADMIN area's "users" route (the sidebar item stays active on both):
 *   ''     list
 *   ':id'  account details
 * The shared ToastProvider is not mounted at the app root yet, so this section provides it.
 */
export default function UsersSection() {
  return (
    <ToastProvider>
      <Routes>
        <Route index element={<UsersListPage />} />
        <Route path=":id" element={<UserDetailsPage />} />
        <Route path="*" element={<AdminNotFoundPage />} />
      </Routes>
    </ToastProvider>
  )
}
