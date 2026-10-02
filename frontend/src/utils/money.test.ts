import { describe, expect, it } from 'vitest';
import {
  addMoney,
  compareMoney,
  currencyScale,
  formatMoney,
  isPositiveMoney,
  isZeroMoney,
  MoneyError,
  moneyToDto,
  parseMoney,
  subtractMoney,
  sumMoney,
  toDecimalString,
  zeroMoney,
} from './money';

describe('currencyScale', () => {
  it('resolves ISO 4217 minor units', () => {
    expect(currencyScale('USD')).toBe(2);
    expect(currencyScale('RWF')).toBe(0);
    expect(currencyScale('JPY')).toBe(0);
  });

  it('rejects a malformed currency code', () => {
    expect(() => currencyScale('rwf')).toThrow(MoneyError);
    expect(() => currencyScale('RWFX')).toThrow(MoneyError);
  });
});

describe('parseMoney', () => {
  it('parses a two-decimal currency', () => {
    const money = parseMoney('100.50', 'USD');
    expect(money.minorUnits).toBe(10050n);
    expect(money.scale).toBe(2);
  });

  it('parses a zero-decimal currency', () => {
    expect(parseMoney('100000', 'RWF').minorUnits).toBe(100000n);
  });

  it('pads a short fraction', () => {
    expect(parseMoney('7.5', 'USD').minorUnits).toBe(750n);
  });

  it('parses negatives', () => {
    expect(parseMoney('-42.01', 'USD').minorUnits).toBe(-4201n);
  });

  it('rejects more decimals than the currency allows rather than rounding', () => {
    expect(() => parseMoney('100.5', 'RWF')).toThrow(MoneyError);
    expect(() => parseMoney('1.234', 'USD')).toThrow(MoneyError);
  });

  it('rejects non-decimal input', () => {
    for (const bad of ['', ' ', 'abc', 'NaN', 'Infinity', '1e5', '1,000', '1.2.3', '+5']) {
      expect(() => parseMoney(bad, 'USD'), bad).toThrow(MoneyError);
    }
  });

  it('preserves precision beyond Number.MAX_SAFE_INTEGER', () => {
    // 9007199254740993 is 2^53 + 1, which a double cannot represent.
    const money = parseMoney('9007199254740993', 'RWF');
    expect(toDecimalString(money)).toBe('9007199254740993');
  });
});

describe('toDecimalString', () => {
  it('round-trips', () => {
    for (const [amount, currency] of [
      ['0.00', 'USD'],
      ['100.50', 'USD'],
      ['-42.01', 'USD'],
      ['0', 'RWF'],
      ['100000', 'RWF'],
    ] as const) {
      expect(toDecimalString(parseMoney(amount, currency))).toBe(amount);
    }
  });

  it('renders sub-unit amounts with a leading zero', () => {
    expect(toDecimalString(parseMoney('0.07', 'USD'))).toBe('0.07');
  });
});

describe('arithmetic', () => {
  it('adds without floating-point drift', () => {
    // The canonical float failure: 0.1 + 0.2 !== 0.3.
    const total = addMoney(parseMoney('0.10', 'USD'), parseMoney('0.20', 'USD'));
    expect(toDecimalString(total)).toBe('0.30');
  });

  it('subtracts', () => {
    const result = subtractMoney(parseMoney('100.00', 'USD'), parseMoney('0.01', 'USD'));
    expect(toDecimalString(result)).toBe('99.99');
  });

  it('sums a list', () => {
    const fees = [parseMoney('500', 'RWF'), parseMoney('250', 'RWF'), parseMoney('1000', 'RWF')];
    expect(toDecimalString(sumMoney(fees, 'RWF'))).toBe('1750');
  });

  it('sums to zero for an empty list', () => {
    expect(isZeroMoney(sumMoney([], 'RWF'))).toBe(true);
  });

  it('refuses to mix currencies', () => {
    expect(() => addMoney(parseMoney('1', 'RWF'), parseMoney('1.00', 'USD'))).toThrow(MoneyError);
    expect(() => sumMoney([parseMoney('1.00', 'USD')], 'RWF')).toThrow(MoneyError);
  });

  it('compares', () => {
    const small = parseMoney('100', 'RWF');
    const large = parseMoney('200', 'RWF');
    expect(compareMoney(small, large)).toBeLessThan(0);
    expect(compareMoney(large, small)).toBeGreaterThan(0);
    expect(compareMoney(small, small)).toBe(0);
  });

  it('reports sign', () => {
    expect(isPositiveMoney(parseMoney('1', 'RWF'))).toBe(true);
    expect(isPositiveMoney(zeroMoney('RWF'))).toBe(false);
    expect(isZeroMoney(zeroMoney('RWF'))).toBe(true);
  });
});

describe('formatMoney', () => {
  it('formats RWF with the currency code and no decimals', () => {
    const formatted = formatMoney(parseMoney('100000', 'RWF'));
    expect(formatted).toContain('RWF');
    expect(formatted).toContain('100,000');
    expect(formatted).not.toContain('.00');
  });

  it('honours an explicit fraction-digit override', () => {
    // The blueprint's example panel shows RWF with two decimals, which ISO 4217 does not.
    // The override exists so that decision can be applied without touching parsing.
    expect(formatMoney(parseMoney('100000', 'RWF'), { fractionDigits: 2 })).toContain('100,000.00');
  });

  it('formats a two-decimal currency', () => {
    expect(formatMoney(parseMoney('1234.50', 'USD'), { locale: 'en-US' })).toContain('1,234.50');
  });
});

describe('moneyToDto', () => {
  it('produces the wire shape', () => {
    expect(moneyToDto(parseMoney('100.50', 'USD'))).toEqual({
      amount: '100.50',
      currency: 'USD',
    });
  });
});
