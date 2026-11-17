import { Navigate, useLocation } from 'react-router-dom';

import Spinner from '../../../shared/components/feedback/Spinner';
import { useAuth } from '../hooks/useAuth';

export default function RequireAuth({ children }) {
  const { isAuthenticated, isLoading } = useAuth();
  const location = useLocation();

  if (isLoading) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', padding: '64px' }}>
        <Spinner label="Checking authentication..." />
      </div>
    );
  }

  if (!isAuthenticated) {
    return <Navigate to="/login" state={{ from: location.pathname }} replace />;
  }

  return children;
}
