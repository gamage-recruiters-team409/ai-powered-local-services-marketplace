import { useContext } from 'react'

import { AdminAccessContext, AreaAccessContext } from './accessContexts'

/** Access for the signed-in admin: { status, access, user, errorKind, reload }. */
export function useAdminAccess() {
  const value = useContext(AdminAccessContext)
  if (!value) {
    throw new Error('useAdminAccess must be used inside <AdminAccessProvider>')
  }
  return value
}

/**
 * For admin pages: { area, mode, canEdit, isViewOnly }.
 * Disable create/update/delete actions when canEdit is false.
 * The backend still enforces every action; this only controls the UI.
 */
export function useAreaAccess() {
  return useContext(AreaAccessContext)
}
