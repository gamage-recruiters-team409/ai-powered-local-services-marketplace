export const EMAIL_REGEX = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
export const FULL_NAME_REGEX = /^[\p{L}\p{M}][\p{L}\p{M} .'’-]*[\p{L}\p{M}]$/u;

export function countLetters(str = '') {
  if (!str) return 0;
  const matches = str.match(/\p{L}/gu);
  return matches ? matches.length : 0;
}

export function normalizeFullName(name = '') {
  if (!name) return '';
  return name.trim().replace(/\s+/g, ' ');
}

export function validateFullName(name, { requiredMessage = 'Please enter your full name.' } = {}) {
  if (!name || !name.trim()) {
    return requiredMessage;
  }
  const trimmed = name.trim();
  if (trimmed.length < 2) {
    return 'Full name must be at least 2 characters';
  }
  if (trimmed.length > 100) {
    return 'Full name must be 100 characters or fewer';
  }
  if (!FULL_NAME_REGEX.test(trimmed) || countLetters(trimmed) < 2) {
    return 'Full name can only contain letters, spaces, apostrophes, hyphens and dots.';
  }
  return null;
}

export function validateEmail(email) {
  if (!email || !email.trim()) {
    return 'Please enter your email address.';
  }
  const trimmed = email.trim();
  if (trimmed.length > 254) {
    return 'Email must not exceed 254 characters.';
  }
  if (!EMAIL_REGEX.test(trimmed)) {
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
  const hasMaxLength = new TextEncoder().encode(password).length <= 72 && password.length <= 72;
  const hasUppercase = /[A-Z]/.test(password);
  const hasLowercase = /[a-z]/.test(password);
  const hasDigit = /\d/.test(password);

  const validCount = [hasMinLength, hasUppercase, hasLowercase, hasDigit].filter(Boolean).length;
  const isValid = validCount === 4 && hasMaxLength;

  return {
    hasMinLength,
    hasMaxLength,
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
  if (new TextEncoder().encode(password).length > 72 || password.length > 72) {
    return 'Password must not exceed 72 characters.';
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
