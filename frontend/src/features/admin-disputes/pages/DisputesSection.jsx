import { Route, Routes } from 'react-router-dom'

import { AdminNotFoundPage } from '../../disputes-admin/pages/AdminNotFoundPage'
import { AdminAppealReviewPage } from './AdminAppealReviewPage'
import { AdminDisputeCasePage } from './AdminDisputeCasePage'
import { AdminDisputesListPage } from './AdminDisputesListPage'

/**
 * All admin dispute screens under the ADMIN area's "disputes" route (the sidebar item stays active on each):
 *   ''                    list
 *   ':disputeId'          case
 *   ':disputeId/appeal'   appeal review
 */
export default function DisputesSection() {
  return (
    <Routes>
      <Route index element={<AdminDisputesListPage />} />
      <Route path=":disputeId" element={<AdminDisputeCasePage />} />
      <Route path=":disputeId/appeal" element={<AdminAppealReviewPage />} />
      <Route path="*" element={<AdminNotFoundPage />} />
    </Routes>
  )
}
