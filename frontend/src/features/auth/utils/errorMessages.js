export const ERROR_MESSAGES = {
  BAD_CREDENTIALS: 'Incorrect email or password. Please verify your credentials or reset your password below.',
  EMAIL_NOT_VERIFIED: 'Your email is not verified yet. Please verify your email to log in.',
  ACCOUNT_LOCKED: 'Your account has been temporarily locked for 15 minutes due to multiple failed login attempts.',
  ACCOUNT_SUSPENDED: 'Your account has been suspended. Please contact customer support.',
  ACCOUNT_DEACTIVATED: 'Your account has been deactivated. Please contact customer support.',
  ACCOUNT_NOT_ACTIVE: 'Your account is not active. Please contact customer support.',
  EMAIL_ALREADY_EXISTS: 'An account with this email already exists.',
  PHONE_ALREADY_EXISTS: 'An account with this phone number already exists.',
  INVALID_OR_EXPIRED_TOKEN: 'This link is invalid or has expired.',
  TOKEN_EXPIRED: 'This link has expired. Please request a new one.',
  TOKEN_REVOKED: 'This link is no longer valid. Please request a new one.',
  TOKEN_REUSED: 'This link has already been used. Please request a new one.',
  SAME_PASSWORD: 'Your new password must be different from your current password.',
  WEAK_PASSWORD: 'Password must have at least 8 characters, an uppercase letter, a lowercase letter and a digit.',
  INVALID_CURRENT_PASSWORD: 'The current password you entered is incorrect.',
  RATE_LIMITED: 'Too many requests. Please try again later.',
  NETWORK_ERROR: 'Unable to connect to the server. Please check your internet connection and try again.',
  VALIDATION_FAILED: 'Please correct the errors indicated below.',
};

export function getErrorMessage(code, fallbackMessage = 'An unexpected error occurred. Please try again.') {
  if (code && ERROR_MESSAGES[code]) {
    return ERROR_MESSAGES[code];
  }
  return fallbackMessage;
}

export default ERROR_MESSAGES;
