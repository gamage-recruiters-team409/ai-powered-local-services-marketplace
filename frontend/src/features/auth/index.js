export { AuthProvider, AuthContext } from './context/AuthContext';
export { useAuth } from './hooks/useAuth';
export { authRoutes } from './routes/authRoutes';
export { default as AuthLayout } from './components/AuthLayout';
export { default as PasswordRules } from './components/PasswordRules';
export { default as RequireAuth } from './guards/RequireAuth';
export { default as RequireRole } from './guards/RequireRole';
export { default as GuestOnly } from './guards/GuestOnly';
export { default as RequirePasswordChanged } from './guards/RequirePasswordChanged';
export { ROLES, ADMIN_ROLES, ALL_ROLES } from './constants/roles';
export { getErrorMessage, ERROR_MESSAGES } from './utils/errorMessages';
export {
  validateEmail,
  validatePhone,
  validatePassword,
  checkPasswordCriteria,
  formatPhoneDigits,
  normalizePhoneForSubmission,
  maskEmail,
} from './utils/validators';
export {
  getRoleHomePath,
  resolvePostLoginRedirect,
  DEFAULT_CUSTOMER_HOME,
  DEFAULT_ADMIN_HOME,
} from './utils/roleRedirect';
export * as authApi from './api/authApi';
