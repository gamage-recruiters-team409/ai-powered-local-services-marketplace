import { Navigate } from 'react-router-dom';

import Spinner from '../../../shared/components/feedback/Spinner';
import { useAuth } from '../hooks/useAuth';
import { getRoleHomePath } from '../utils/roleRedirect';

export default function GuestOnly({ children }) {
  const { isAuthenticated, isLoading, user } = useAuth();

  if (isLoading) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', padding: '64px' }}>
        <Spinner label="Loading..." />
      </div>
    );
  }

  if (isAuthenticated) {
    return <Navigate to={getRoleHomePath(user?.role)} replace />;
  }

  return children;
}
