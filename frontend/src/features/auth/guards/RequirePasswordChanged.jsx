import { Navigate } from 'react-router-dom';

import Spinner from '../../../shared/components/feedback/Spinner';
import { useAuth } from '../hooks/useAuth';

export default function RequirePasswordChanged({ children }) {
  const { isAuthenticated, isLoading, mustChangePassword } = useAuth();

  if (isLoading) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', padding: '64px' }}>
        <Spinner label="Loading..." />
      </div>
    );
  }

  if (!isAuthenticated) {
    return <Navigate to="/login" replace />;
  }

  if (mustChangePassword) {
    return <Navigate to="/change-password" replace />;
  }

  return children;
}
