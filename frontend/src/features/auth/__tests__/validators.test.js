// @vitest-environment jsdom
import { afterEach, describe, expect, it } from 'vitest';
import { cleanup } from '@testing-library/react';
import {
  checkPasswordCriteria,
  formatPhoneDigits,
  maskEmail,
  normalizePhoneForSubmission,
  validateEmail,
  validatePassword,
  validatePhone,
} from '../utils/validators';

describe('validators', () => {
  afterEach(() => {
    cleanup();
  });

  describe('validateEmail', () => {
    it('accepts valid email addresses', () => {
      expect(validateEmail('user@example.com')).toBeNull();
      expect(validateEmail('kasun@example.lk')).toBeNull();
    });

    it('rejects invalid or empty email addresses', () => {
      expect(validateEmail('')).toBe('Please enter your email address.');
      expect(validateEmail('invalid-email')).toBe('Please enter a valid email address.');
      expect(validateEmail('missing@domain')).toBe('Please enter a valid email address.');
    });
  });

  describe('validatePhone', () => {
    it('accepts valid 9-digit phones', () => {
      expect(validatePhone('77 123 4567')).toBeNull();
      expect(validatePhone('771234567')).toBeNull();
    });

    it('rejects invalid phone lengths or empty', () => {
      expect(validatePhone('')).toBe('Please enter your phone number.');
      expect(validatePhone('12345')).toBe('Please enter a valid 9-digit phone number.');
    });
  });

  describe('checkPasswordCriteria', () => {
    it('accurately counts criteria', () => {
      const empty = checkPasswordCriteria('');
      expect(empty.validCount).toBe(0);
      expect(empty.isValid).toBe(false);

      const lowerOnly = checkPasswordCriteria('abc');
      expect(lowerOnly.validCount).toBe(1);
      expect(lowerOnly.hasLowercase).toBe(true);
      expect(lowerOnly.isValid).toBe(false);

      const lengthAndLower = checkPasswordCriteria('abcdefgh');
      expect(lengthAndLower.validCount).toBe(2);
      expect(lengthAndLower.hasMinLength).toBe(true);
      expect(lengthAndLower.hasLowercase).toBe(true);
      expect(lengthAndLower.isValid).toBe(false);

      const threeRules = checkPasswordCriteria('Abcdefgh');
      expect(threeRules.validCount).toBe(3);
      expect(threeRules.isValid).toBe(false);

      const res = checkPasswordCriteria('Weak1');
      expect(res.hasMinLength).toBe(false);
      expect(res.hasUppercase).toBe(true);
      expect(res.hasLowercase).toBe(true);
      expect(res.hasDigit).toBe(true);
      expect(res.validCount).toBe(3);
      expect(res.isValid).toBe(false);

      const strong = checkPasswordCriteria('Abcdefg1');
      expect(strong.validCount).toBe(4);
      expect(strong.isValid).toBe(true);

      const strongPass = checkPasswordCriteria('StrongPass1');
      expect(strongPass.validCount).toBe(4);
      expect(strongPass.isValid).toBe(true);
    });
  });

  describe('validatePassword', () => {
    it('returns error message for weak password', () => {
      expect(validatePassword('short')).toContain('Password must have at least 8 characters');
    });

    it('returns null for valid password', () => {
      expect(validatePassword('ValidPass123')).toBeNull();
    });
  });

  describe('formatPhoneDigits & normalizePhoneForSubmission', () => {
    it('formats 9 digits with spaces', () => {
      expect(formatPhoneDigits('771234567')).toBe('77 123 4567');
      expect(formatPhoneDigits('7712')).toBe('77 12');
    });

    it('normalizes to international format with prefix', () => {
      expect(normalizePhoneForSubmission('77 123 4567', '+94')).toBe('+94771234567');
    });
  });

  describe('maskEmail', () => {
    it('masks local part of email', () => {
      expect(maskEmail('kasun@example.lk')).toBe('k***n@example.lk');
      expect(maskEmail('ab@example.com')).toBe('a***@example.com');
    });
  });
});
