import { describe, expect, it } from 'vitest';
import {
  adultDateOfBirth,
  collect,
  digits,
  digitsBetween,
  email,
  isValid,
  matches,
  maxLength,
  minLength,
  normalisePhone,
  password,
  PASSWORD_RULES,
  required,
  rwandanPhone,
} from './validation';

describe('required', () => {
  it('rejects empty and whitespace-only values', () => {
    expect(required('', 'Name')).toBe('Name is required.');
    expect(required('   ', 'Name')).toBe('Name is required.');
  });

  it('accepts a value', () => {
    expect(required('Nicaise', 'Name')).toBeUndefined();
  });
});

describe('minLength / maxLength', () => {
  it('measures the trimmed value', () => {
    expect(minLength('  a  ', 2, 'Name')).toBe('Name must be at least 2 characters.');
    expect(minLength('  ab  ', 2, 'Name')).toBeUndefined();
    expect(maxLength('abcd', 3, 'Name')).toBe('Name must be 3 characters or fewer.');
  });
});

describe('matches', () => {
  it('compares exactly', () => {
    expect(matches('a', 'a', 'no match')).toBeUndefined();
    expect(matches('a', 'A', 'no match')).toBe('no match');
  });
});

describe('email', () => {
  it('accepts ordinary addresses', () => {
    for (const value of ['a@b.co', 'nicaise.kirezi@example.rw', 'x+tag@sub.domain.com']) {
      expect(email(value), value).toBeUndefined();
    }
  });

  it('rejects malformed addresses', () => {
    for (const value of ['', 'no-at-sign', 'a@b', 'a b@c.com', '@b.com', 'a@.com']) {
      expect(email(value), value).toBeDefined();
    }
  });
});

describe('rwandanPhone', () => {
  it('accepts the spellings a customer might type', () => {
    for (const value of [
      '0781234567',
      '078 123 4567',
      '+250781234567',
      '250781234567',
      '+250 78 123 4567',
      '0721234567',
    ]) {
      expect(rwandanPhone(value), value).toBeUndefined();
    }
  });

  it('rejects numbers that are not Rwandan mobiles', () => {
    for (const value of [
      '',
      '078123456',
      '07812345678',
      '0881234567',
      'abcdefghij',
      '+44781234567',
    ]) {
      expect(rwandanPhone(value), value).toBeDefined();
    }
  });
});

describe('normalisePhone', () => {
  it('normalises every accepted spelling to one form', () => {
    for (const value of ['0781234567', '078 123 4567', '+250781234567', '250781234567']) {
      expect(normalisePhone(value), value).toBe('+250781234567');
    }
  });
});

describe('digits', () => {
  it('requires an exact length of digits', () => {
    expect(digits('1234567890123456', 16, 'ID')).toBeUndefined();
    expect(digits('123', 16, 'ID')).toBe('ID must be 16 digits.');
    expect(digits('12345678901234ab', 16, 'ID')).toBe('ID must contain digits only.');
    expect(digits('', 16, 'ID')).toBe('ID is required.');
  });

  it('ignores spacing', () => {
    expect(digits('1234 5678 9012 3456', 16, 'ID')).toBeUndefined();
  });
});

describe('digitsBetween', () => {
  it('accepts a range', () => {
    expect(digitsBetween('1234567890', 10, 16, 'Account')).toBeUndefined();
    expect(digitsBetween('1234567890123456', 10, 16, 'Account')).toBeUndefined();
    expect(digitsBetween('123456789', 10, 16, 'Account')).toBe(
      'Account must be between 10 and 16 digits.',
    );
  });
});

describe('adultDateOfBirth', () => {
  function isoYearsAgo(years: number, dayOffset = 0): string {
    const date = new Date();
    date.setFullYear(date.getFullYear() - years);
    date.setDate(date.getDate() + dayOffset);
    return date.toISOString().slice(0, 10);
  }

  it('accepts an adult', () => {
    expect(adultDateOfBirth(isoYearsAgo(30))).toBeUndefined();
  });

  it('rejects someone under the minimum age', () => {
    expect(adultDateOfBirth(isoYearsAgo(17))).toBe('You must be at least 18 to register.');
  });

  it('accepts someone who turned 18 yesterday and rejects one who turns 18 tomorrow', () => {
    expect(adultDateOfBirth(isoYearsAgo(18, -1))).toBeUndefined();
    expect(adultDateOfBirth(isoYearsAgo(18, 1))).toBeDefined();
  });

  it('rejects a future date and an unparseable one', () => {
    expect(adultDateOfBirth(isoYearsAgo(-1))).toBeDefined();
    expect(adultDateOfBirth('not-a-date')).toBe('Enter a valid date.');
    expect(adultDateOfBirth('')).toBe('Date of birth is required.');
  });
});

describe('password', () => {
  it('accepts a password meeting every rule', () => {
    expect(password('CorrectHorse1Battery')).toBeUndefined();
  });

  it('rejects one that misses a rule', () => {
    for (const value of ['short1A', 'alllowercase123', 'ALLUPPERCASE123', 'NoDigitsAtAllHere']) {
      expect(password(value), value).toBeDefined();
    }
  });

  it('exposes rules that agree with the validator', () => {
    const good = 'CorrectHorse1Battery';
    expect(PASSWORD_RULES.every((rule) => rule.test(good))).toBe(true);
  });

  it('does not demand a special character', () => {
    // Length beats forced symbol substitution; the rule set should reflect that.
    expect(password('CorrectHorse1Battery')).toBeUndefined();
  });
});

describe('collect / isValid', () => {
  interface Form {
    a: string;
    b: string;
  }

  it('keeps only failures', () => {
    const errors = collect<Form>([
      ['a', undefined],
      ['b', 'B is wrong.'],
    ]);
    expect(errors).toEqual({ b: 'B is wrong.' });
    expect(isValid(errors)).toBe(false);
  });

  it('reports valid when nothing failed', () => {
    expect(isValid(collect<Form>([['a', undefined]]))).toBe(true);
  });
});
