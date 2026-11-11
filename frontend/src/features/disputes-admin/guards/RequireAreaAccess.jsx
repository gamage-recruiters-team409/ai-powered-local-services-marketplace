import { useMemo } from 'react'
import { Outlet } from 'react-router-dom'

import { ViewOnlyBanner } from '../../../shared/layouts/admin'
import { roleLabel } from '../config/adminAreas'
import { AREA_MODE, getAreaMode } from '../access/accessRules'
import { AreaAccessContext } from '../access/accessContexts'
import { useAdminAccess } from '../access/useAdminAccess'
import { AccessDeniedPage } from '../pages/AccessDeniedPage'

/**
 * Guards every page of one admin area, based on /admin/access:
 * canEdit -> page; canView -> page with the view-only banner; neither -> Access Denied.
 */
export function RequireAreaAccess({ area }) {
  const { access } = useAdminAccess()
  const mode = getAreaMode(access, area)

  const value = useMemo(
    () => ({ area, mode, canEdit: mode === AREA_MODE.EDIT, isViewOnly: mode === AREA_MODE.VIEW }),
    [area, mode],
  )

  if (mode === AREA_MODE.NONE) {
    return <AccessDeniedPage area={area} />
  }

  return (
    <AreaAccessContext.Provider value={value}>
      {value.isViewOnly && <ViewOnlyBanner areaLabel={roleLabel(area)} />}
      <Outlet />
    </AreaAccessContext.Provider>
  )
}
