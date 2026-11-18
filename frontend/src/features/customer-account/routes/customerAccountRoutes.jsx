import { Navigate } from 'react-router-dom';
import ProfilePage from '../pages/ProfilePage';
import SavedAddressesPage from '../pages/SavedAddressesPage';
import SettingsPage from '../pages/SettingsPage';
import DeactivatePage from '../pages/DeactivatePage';
import PaymentsPage from '../pages/PaymentsPage';
import FavoritesPage from '../pages/FavoritesPage';

/**
 * Child routes under /customer/account (WP01).
 *
 * NOTE: The customer layout routes file (e.g. frontend/src/routes/customerRoutes.jsx)
 * is pending in Disura's PR (#38 layout common shell integration). Once that PR is merged,
 * these child routes should be spread into the /customer/account branch inside the
 * customer layout route, guarded by:
 * RequireAuth -> RequirePasswordChanged -> RequireRole('CUSTOMER').
 */
export const customerAccountRoutes = [
  { index: true, element: <Navigate to="profile" replace /> },
  { path: 'profile', element: <ProfilePage /> },
  { path: 'addresses', element: <SavedAddressesPage /> },
  { path: 'settings', element: <SettingsPage /> },
  { path: 'deactivate', element: <DeactivatePage /> },
  { path: 'payments', element: <PaymentsPage /> },
  { path: 'favorites', element: <FavoritesPage /> },
];

export default customerAccountRoutes;
