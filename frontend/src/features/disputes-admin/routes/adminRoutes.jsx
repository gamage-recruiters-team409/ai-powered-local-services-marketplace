import { AdminAccessProvider } from '../access/AdminAccessProvider'
import { AdminShell } from '../AdminShell'
import { ADMIN_AREAS, ADMIN_BASE_PATH, AREA_ORDER } from '../config/adminAreas'
import { findNavConfigProblems, getAreaEntries } from '../config/navModel'
import { RequireAreaAccess } from '../guards/RequireAreaAccess'
import { RequireAuth } from '../guards/RequireAuth'
import { AdminNotFoundPage } from '../pages/AdminNotFoundPage'
import { AreaPlaceholderPage } from '../pages/AreaPlaceholderPage'
import { RoleSelectionPage } from '../pages/RoleSelectionPage'
import { AdminIndexRedirect } from './AdminIndexRedirect'

if (import.meta.env.DEV) {
  findNavConfigProblems().forEach((problem) => console.warn(`[adminNavConfig] ${problem}`))
}

function renderEntry(entry) {
  const Page = entry.page
  return Page ? <Page entry={entry} /> : <AreaPlaceholderPage entry={entry} />
}

function areaRoute(area) {
  return {
    path: ADMIN_AREAS[area].slug,
    element: <RequireAreaAccess area={area} />,
    children: [
      ...getAreaEntries(area).map((entry) =>
        entry.path === ''
          ? { index: true, element: renderEntry(entry) }
          : { path: entry.path, element: renderEntry(entry) },
      ),
      { path: '*', element: <AdminNotFoundPage /> },
    ],
  }
}

/** All /admin routes. Area pages are generated from config/adminNavConfig.js. */
export const adminRoutes = {
  path: ADMIN_BASE_PATH,
  element: (
    <RequireAuth>
      <AdminAccessProvider>
        <AdminShell />
      </AdminAccessProvider>
    </RequireAuth>
  ),
  children: [
    { index: true, element: <AdminIndexRedirect /> },
    { path: 'select-role', element: <RoleSelectionPage /> },
    ...AREA_ORDER.map(areaRoute),
    { path: '*', element: <AdminNotFoundPage /> },
  ],
}
