export const EMAIL_REGEX = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export function validateEmail(email) {
  if (!email || !email.trim()) {
    return 'Please enter your email address.';
  }
  if (!EMAIL_REGEX.test(email.trim())) {
    return 'Please enter a valid email address.';
  }
  return null;
}

export function validatePhone(phone) {
  if (!phone || !phone.trim()) {
    return 'Please enter your phone number.';
  }
  const digits = phone.replace(/\D/g, '');
  if (digits.length !== 9) {
    return 'Please enter a valid 9-digit phone number.';
  }
  return null;
}

export function checkPasswordCriteria(password = '') {
  const hasMinLength = password.length >= 8;
  const hasUppercase = /[A-Z]/.test(password);
  const hasLowercase = /[a-z]/.test(password);
  const hasDigit = /\d/.test(password);

  const validCount = [hasMinLength, hasUppercase, hasLowercase, hasDigit].filter(Boolean).length;
  const isValid = validCount === 4;

  return {
    hasMinLength,
    hasUppercase,
    hasLowercase,
    hasDigit,
    validCount,
    isValid,
  };
}

export function validatePassword(password) {
  if (!password) {
    return 'Please enter a password.';
  }
  const { isValid } = checkPasswordCriteria(password);
  if (!isValid) {
    return 'Password must have at least 8 characters, an uppercase letter, a lowercase letter and a digit.';
  }
  return null;
}

export function formatPhoneDigits(value = '') {
  // Keeps only digits, limits to 9 digits, formats as XX XXX XXXX
  const digits = value.replace(/\D/g, '').slice(0, 9);
  if (digits.length <= 2) return digits;
  if (digits.length <= 5) return `${digits.slice(0, 2)} ${digits.slice(2)}`;
  return `${digits.slice(0, 2)} ${digits.slice(2, 5)} ${digits.slice(5)}`;
}

export function normalizePhoneForSubmission(formattedPhone, prefix = '+94') {
  const digits = formattedPhone.replace(/\D/g, '');
  if (!digits) return '';
  return `${prefix}${digits}`;
}

export function maskEmail(email = '') {
  if (!email || !email.includes('@')) return email;
  const [localPart, domain] = email.split('@');
  if (localPart.length <= 2) {
    return `${localPart[0]}***@${domain}`;
  }
  return `${localPart[0]}***${localPart[localPart.length - 1]}@${domain}`;
}
