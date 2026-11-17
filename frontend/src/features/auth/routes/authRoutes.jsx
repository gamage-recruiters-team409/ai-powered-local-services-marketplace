import AuthLayout from '../components/AuthLayout';
import GuestOnly from '../guards/GuestOnly';
import RequireAuth from '../guards/RequireAuth';
import LoginPage from '../pages/LoginPage';
import RegisterPage from '../pages/RegisterPage';
import CheckEmailPage from '../pages/CheckEmailPage';
import VerifyEmailPage from '../pages/VerifyEmailPage';
import ForgotPasswordPage from '../pages/ForgotPasswordPage';
import ResetPasswordPage from '../pages/ResetPasswordPage';
import ForceChangePasswordPage from '../pages/ForceChangePasswordPage';

export const authRoutes = {
  element: <AuthLayout />,
  children: [
    {
      path: '/login',
      element: (
        <GuestOnly>
          <LoginPage />
        </GuestOnly>
      ),
    },
    {
      path: '/register',
      element: (
        <GuestOnly>
          <RegisterPage />
        </GuestOnly>
      ),
    },
    {
      path: '/check-email',
      element: <CheckEmailPage />,
    },
    {
      path: '/verify-email',
      element: <VerifyEmailPage />,
    },
    {
      path: '/forgot-password',
      element: (
        <GuestOnly>
          <ForgotPasswordPage />
        </GuestOnly>
      ),
    },
    {
      path: '/reset-password',
      element: (
        <GuestOnly>
          <ResetPasswordPage />
        </GuestOnly>
      ),
    },
    {
      path: '/change-password',
      element: (
        <RequireAuth>
          <ForceChangePasswordPage />
        </RequireAuth>
      ),
    },
  ],
};

export default authRoutes;
