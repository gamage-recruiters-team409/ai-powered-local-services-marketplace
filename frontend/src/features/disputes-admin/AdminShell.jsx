import { Suspense, useCallback, useMemo, useState } from 'react'
import { Navigate, Outlet, useLocation, useNavigate } from 'react-router-dom'

import { AdminLayout } from '../../shared/layouts/admin'
import { ACCESS_ERROR, hasAnyArea, resolveNavArea } from './access/accessRules'
import { ACCESS_STATUS } from './access/accessContexts'
import { useAdminAccess } from './access/useAdminAccess'
import {
  EmptyAccessState,
  ErrorState,
  LoadingState,
  NoAdminAccessState,
  StatePage,
} from './components/AccessStates'
import { SwitchRoleModal } from './components/SwitchRoleModal'
import { ADMIN_PATHS, roleLabel } from './config/adminAreas'
import { buildNavSections } from './config/navModel'
import { endSession, LOGIN_PATH } from './services/authBridge'

/** Connects /admin/access, the nav config and auth to the shared AdminLayout. */
export function AdminShell() {
  const { status, access, user, errorKind, reload } = useAdminAccess()
  const { pathname } = useLocation()
  const navigate = useNavigate()
  const [switchOpen, setSwitchOpen] = useState(false)

  const logout = useCallback(() => {
    endSession()
    navigate(LOGIN_PATH, { replace: true })
  }, [navigate])

  const navArea = resolveNavArea(access, pathname)
  const navSections = useMemo(() => (navArea ? buildNavSections(navArea) : []), [navArea])

  if (status === ACCESS_STATUS.LOADING) {
    return (
      <StatePage>
        <LoadingState />
      </StatePage>
    )
  }

  if (status === ACCESS_STATUS.ERROR) {
    if (errorKind === ACCESS_ERROR.UNAUTHORIZED) {
      return <Navigate to={LOGIN_PATH} replace state={{ from: pathname }} />
    }
    return (
      <StatePage>
        {errorKind === ACCESS_ERROR.FORBIDDEN ? <NoAdminAccessState onSignOut={logout} /> : <ErrorState onRetry={reload} />}
      </StatePage>
    )
  }

  if (!hasAnyArea(access)) {
    return (
      <StatePage>
        <EmptyAccessState onSignOut={logout} />
      </StatePage>
    )
  }

  const displayUser = {
    name: user?.name || roleLabel(access.role),
    roleLabel: roleLabel(access.role),
  }

  // Role selection is a full page of its own (no sidebar); it gets the user and logout from the outlet context.
  if (pathname.replace(/\/+$/, '') === ADMIN_PATHS.selectRole) {
    return (
      <Suspense fallback={<LoadingState label="Loading page…" />}>
        <Outlet context={{ user: displayUser, onLogout: logout }} />
      </Suspense>
    )
  }

  return (
    <>
      <AdminLayout
        navSections={navSections}
        user={displayUser}
        onLogout={logout}
        onSwitchRole={() => setSwitchOpen(true)}
      >
        <Suspense fallback={<LoadingState label="Loading page…" />}>
          <Outlet />
        </Suspense>
      </AdminLayout>
      <SwitchRoleModal open={switchOpen} onClose={() => setSwitchOpen(false)} />
    </>
  )
}
