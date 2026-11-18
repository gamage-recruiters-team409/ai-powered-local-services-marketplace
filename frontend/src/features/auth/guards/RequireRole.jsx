import { Navigate, Outlet } from 'react-router-dom';

import Spinner from '../../../shared/components/feedback/Spinner';
import { useAuth } from '../hooks/useAuth';

export default function RequireRole({ roles = [], children }) {
  const { isAuthenticated, isLoading, hasRole } = useAuth();

  if (isLoading) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', padding: '64px' }}>
        <Spinner label="Checking permissions..." />
      </div>
    );
  }

  if (!isAuthenticated) {
    return <Navigate to="/login" replace />;
  }

  if (roles.length > 0 && !hasRole(roles)) {
    return <Navigate to="/" replace />;
  }

  return children ?? <Outlet />;
}
