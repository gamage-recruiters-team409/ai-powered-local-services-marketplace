// @vitest-environment jsdom
import { afterEach, describe, expect, it } from 'vitest';
import { cleanup } from '@testing-library/react';
import {
  checkPasswordCriteria,
  countLetters,
  formatPhoneDigits,
  maskEmail,
  normalizeFullName,
  normalizePhoneForSubmission,
  validateEmail,
  validateFullName,
  validatePassword,
  validatePhone,
} from '../utils/validators';

describe('validators', () => {
  afterEach(() => {
    cleanup();
  });

  describe('validateFullName', () => {
    it('returns error when name is empty or whitespace', () => {
      expect(validateFullName('')).toBe('Please enter your full name.');
      expect(validateFullName('   ', { requiredMessage: 'Name is required' })).toBe('Name is required');
    });

    it('returns error when name is less than 2 characters or letters', () => {
      expect(validateFullName('A')).toEqual(expect.stringContaining('at least 2'));
      expect(validateFullName('A.')).toBe('Full name can only contain letters, spaces, apostrophes, hyphens and dots.');
    });

    it('returns error when name exceeds 100 characters', () => {
      expect(validateFullName('A'.repeat(101))).toEqual(expect.stringContaining('100 characters'));
    });

    it('accepts valid English and Sri Lankan names', () => {
      expect(validateFullName('Kalana Dinuja')).toBeNull();
      expect(validateFullName('Anne-Marie')).toBeNull();
      expect(validateFullName("O'Brien")).toBeNull();
      expect(validateFullName('D’Angelo')).toBeNull();
      expect(validateFullName('A. B. Perera')).toBeNull();
      expect(validateFullName('කමල් පෙරේරා')).toBeNull();
      expect(validateFullName('கமல் பெரேரா')).toBeNull();
      expect(validateFullName('Li')).toBeNull();
      expect(validateFullName('Silva K')).toBeNull();
    });

    it('rejects names with digits, symbols, or invalid punctuation placement', () => {
      const err = 'Full name can only contain letters, spaces, apostrophes, hyphens and dots.';
      expect(validateFullName('kalana test 14444')).toBe(err);
      expect(validateFullName('John_Doe')).toBe(err);
      expect(validateFullName('.John')).toBe(err);
      expect(validateFullName('John.')).toBe(err);
      expect(validateFullName('-John')).toBe(err);
      expect(validateFullName("John'")).toBe(err);
      expect(validateFullName('😀 Kalana')).toBe(err);
      expect(validateFullName('Kalana @ Dinuja')).toBe(err);
    });
  });

  describe('normalizeFullName', () => {
    it('trims and collapses multiple spaces', () => {
      expect(normalizeFullName('')).toBe('');
      expect(normalizeFullName('   ')).toBe('');
      expect(normalizeFullName('  Kalana    Dinuja  ')).toBe('Kalana Dinuja');
      expect(normalizeFullName('Anne   Marie   Perera')).toBe('Anne Marie Perera');
    });
  });

  describe('countLetters', () => {
    it('counts unicode letters', () => {
      expect(countLetters('AB')).toBe(2);
      expect(countLetters('A.1')).toBe(1);
      expect(countLetters('කමල්')).toBeGreaterThanOrEqual(2);
    });
  });

  describe('validateEmail', () => {
    it('accepts valid email addresses', () => {
      expect(validateEmail('user@example.com')).toBeNull();
      expect(validateEmail('kasun@example.lk')).toBeNull();
      expect(validateEmail('test@example.lk')).toBeNull();
    });

    it('rejects invalid or empty email addresses', () => {
      expect(validateEmail('')).toBe('Please enter your email address.');
      expect(validateEmail('invalid-email')).toBe('Please enter a valid email address.');
      expect(validateEmail('missing@domain')).toBe('Please enter a valid email address.');
    });

    it('rejects email exceeding 254 characters', () => {
      const longEmail = 'a'.repeat(245) + '@example.lk';
      expect(validateEmail(longEmail)).toEqual(expect.stringContaining('254 characters'));
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

    it('checks 72-byte max limit', () => {
      const criteriaValid = checkPasswordCriteria('ValidPass123!');
      expect(criteriaValid.isValid).toBe(true);
      expect(criteriaValid.hasMaxLength).toBe(true);

      const criteriaLong = checkPasswordCriteria('Aa1!' + 'b'.repeat(70));
      expect(criteriaLong.isValid).toBe(false);
      expect(criteriaLong.hasMaxLength).toBe(false);
    });
  });

  describe('validatePassword', () => {
    it('returns error message for weak password', () => {
      expect(validatePassword('short')).toContain('Password must have at least 8 characters');
    });

    it('returns null for valid password', () => {
      expect(validatePassword('ValidPass123')).toBeNull();
      expect(validatePassword('ValidPass123!')).toBeNull();
    });

    it('rejects password exceeding 72 bytes', () => {
      const longPw = 'Aa1!' + 'b'.repeat(70);
      expect(validatePassword(longPw)).toEqual(expect.stringContaining('72 characters'));
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
