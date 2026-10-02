import { describe, expect, it } from 'vitest';
import { isDisplayableReference, newCorrelationId } from './correlation';

/**
 * A reference has one job: a customer reads it down a phone line and support finds the
 * failure. Every property below exists to protect that, and the awkward ones — the bias
 * check, the ambiguous characters — are the ones that would rot silently.
 */
describe('references', () => {
  it('is short enough to say out loud', () => {
    const reference = newCorrelationId();
    expect(reference).toHaveLength(9);
    // The UUID it replaced was 36. Anything approaching that is unreadable aloud.
    expect(reference.length).toBeLessThan(12);
  });

  it('is grouped so it can be held in the head', () => {
    expect(newCorrelationId()).toMatch(/^[2-9A-HJ-NP-TV-Z]{4}-[2-9A-HJ-NP-TV-Z]{4}$/);
  });

  it('never contains a character people mistype', () => {
    // 0/O and 1/I/l are the copying mistakes that actually happen; U is out so the
    // generator cannot spell anything unfortunate.
    const ambiguous = /[OILU01]/;
    for (let i = 0; i < 2000; i += 1) {
      expect(newCorrelationId(), 'contains an ambiguous character').not.toMatch(ambiguous);
    }
  });

  it('is upper case, because it gets dictated back', () => {
    for (let i = 0; i < 200; i += 1) {
      const reference = newCorrelationId();
      expect(reference).toBe(reference.toUpperCase());
    }
  });

  it('survives the server header allow-list', () => {
    // The backend drops anything outside [A-Za-z0-9_-]{8,64} rather than echoing it,
    // which would lose traceability without any visible symptom.
    for (let i = 0; i < 200; i += 1) {
      expect(newCorrelationId()).toMatch(/^[A-Za-z0-9_-]{8,64}$/);
    }
  });

  it('does not repeat itself in normal use', () => {
    const seen = new Set<string>();
    for (let i = 0; i < 5000; i += 1) seen.add(newCorrelationId());
    expect(seen.size).toBe(5000);
  });

  it('does not favour the start of the alphabet', () => {
    /*
     * The bias test. `byte % 30` would quietly over-represent the first 16 symbols,
     * because 256 is not a multiple of 30 — a fault that produces perfectly
     * valid-looking references and would never be noticed by eye.
     */
    const counts = new Map<string, number>();
    const draws = 60_000;

    for (let i = 0; i < draws / 8; i += 1) {
      for (const character of newCorrelationId().replace('-', '')) {
        counts.set(character, (counts.get(character) ?? 0) + 1);
      }
    }

    expect(counts.size).toBe(30);

    const expected = draws / 30;
    for (const [character, count] of counts) {
      // Generous bounds: this is catching a systematic 2x skew, not sampling noise.
      expect(count, `${character} appeared ${String(count)} times`).toBeGreaterThan(expected * 0.7);
      expect(count, `${character} appeared ${String(count)} times`).toBeLessThan(expected * 1.3);
    }
  });

  describe('deciding whether to show a reference', () => {
    it('accepts our own format', () => {
      for (let i = 0; i < 100; i += 1) {
        expect(isDisplayableReference(newCorrelationId())).toBe(true);
      }
    });

    it('rejects a UUID, which is what a backend is most likely to substitute', () => {
      expect(isDisplayableReference('4b39518e-b8d2-4301-9f64-37a5a4a26638')).toBe(false);
    });

    it('rejects other things a server might echo instead', () => {
      for (const value of [
        '',
        'ABCD-EFGH-IJKL',
        'abcd-efgh',
        'ABCDEFGH',
        'AB-CD',
        'OOOO-1111',
        'trace-id-7',
        '00000000-0000-0000-0000-000000000000',
      ]) {
        expect(isDisplayableReference(value), value).toBe(false);
      }
    });
  });
});
