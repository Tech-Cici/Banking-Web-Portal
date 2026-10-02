import { describe, expect, it } from 'vitest';
import { maskAccountNumber, maskEmail, maskIdentifier, maskPan, maskPhoneNumber } from './mask';

describe('maskAccountNumber', () => {
  it('matches the blueprint format', () => {
    expect(maskAccountNumber('1234567890124582')).toBe('**** 4582');
  });

  it('ignores whitespace in the input', () => {
    expect(maskAccountNumber('1234 5678 9012 4582')).toBe('**** 4582');
  });

  it('fully masks a value too short to mask partially', () => {
    expect(maskAccountNumber('482')).not.toContain('482');
    expect(maskAccountNumber('4582')).not.toContain('4582');
  });
});

describe('maskPan', () => {
  it('keeps only the last four digits', () => {
    expect(maskPan('4111111111111111')).toBe('**** **** **** 1111');
  });

  it('never reveals the BIN', () => {
    expect(maskPan('4111111111111111')).not.toContain('411111');
  });

  it('handles separators', () => {
    expect(maskPan('4111-1111-1111-1234')).toBe('**** **** **** 1234');
  });
});

describe('maskPhoneNumber', () => {
  it('keeps the last three digits', () => {
    expect(maskPhoneNumber('+250788123456')).toBe('***456');
  });

  it('fully masks a very short value', () => {
    expect(maskPhoneNumber('12')).toBe('***');
  });
});

describe('maskEmail', () => {
  it('keeps the first character and the domain', () => {
    expect(maskEmail('nicaise@example.com')).toBe('n******@example.com');
  });

  it('masks a single-character local part without revealing it', () => {
    expect(maskEmail('a@example.com')).toBe('a*@example.com');
  });

  it('masks a value that is not an email', () => {
    expect(maskEmail('notanemail')).toBe('**********');
  });
});

describe('maskIdentifier', () => {
  it('keeps the last four characters', () => {
    expect(maskIdentifier('100123456789')).toBe('********6789');
  });

  it('fully masks a short identifier', () => {
    expect(maskIdentifier('12')).toBe('****');
  });
});
