import { describe, expect, it } from 'vitest';
import { handlers, handlersExcept } from './handlers';

/**
 * What VITE_LIVE_API actually leaves out.
 *
 * Worth a test rather than a careful read, for two reasons. The mechanism relies on
 * `handler.info.path`, which is MSW's shape and not ours to guarantee — an upgrade that
 * renamed it would silently keep every handler installed, so every "live" feature would
 * quietly go back to being mocked and still appear to work. And the prefix matching has
 * to be by path segment: a `/auth` prefix that also swallowed `/authorisations` would
 * take a feature offline that nobody asked to go live.
 */

/** The path a handler is registered at, relative to the API root. */
function pathsOf(list: readonly unknown[]): readonly string[] {
  return list
    .map((handler) => (handler as { info?: { path?: unknown } }).info?.path)
    .filter((path): path is string => typeof path === 'string')
    .map((path) => path.replace('*/api/v1', ''));
}

describe('handlersExcept', () => {
  it('leaves everything installed when nothing is live', () => {
    expect(handlersExcept([])).toBe(handlers);
  });

  it('removes only the listed area', () => {
    /*
     * `/registration/personal` rather than `/registration`: the whole prefix is now
     * refused, because the backend does not implement the company endpoints under it.
     */
    const kept = pathsOf(handlersExcept(['/registration/personal']));

    expect(kept.some((path) => path.startsWith('/registration/personal'))).toBe(false);
    // And the rest of the app is untouched.
    expect(kept).toContain('/auth/login');
    expect(kept).toContain('/session');
    expect(kept).toContain('/accounts');
  });

  it('removes several areas at once', () => {
    const kept = pathsOf(handlersExcept(['/registration/personal', '/auth', '/session']));

    expect(kept.some((path) => path.startsWith('/registration/personal'))).toBe(false);
    expect(kept.some((path) => path.startsWith('/auth'))).toBe(false);
    expect(kept).not.toContain('/session');

    /*
     * The point of a per-feature switch: the surface that is still mocked stays mocked
     * while the whole onboarding chain runs against the real service.
     *
     * NOT `/transfers` any more, and that is the whole reason this line changed: its
     * handler was DELETED when transfers went live, so asserting it survives exclusion
     * would be asserting that a handler which no longer exists is still installed.
     * `/cards` is the example now — genuinely still a fixture.
     */
    expect(kept).toContain('/accounts');
    expect(kept).toContain('/cards');
    expect(kept).toContain('/health');
  });

  it('takes the staff endpoints as their own area', () => {
    const kept = pathsOf(handlersExcept(['/admin']));

    expect(kept.some((path) => path.startsWith('/admin'))).toBe(false);
    expect(kept).toContain('/session');
  });

  it('matches whole segments, not string prefixes', () => {
    /*
     * `/card` must not match `/cards`. If it did, listing one area would silently take a
     * neighbouring one live too, and a feature nobody asked to move would start calling
     * a backend endpoint that does not exist.
     *
     * The assertion is the throw: a plain string prefix would have matched `/cards`, so
     * `/card` would have been considered matched and no error raised. Getting the error
     * is the proof — and it also shows the two guards compose in the right order.
     */
    expect(() => handlersExcept(['/card'])).toThrow(/match no mocked endpoint/);

    // Whereas the real segment is accepted, and takes only its own paths.
    const kept = pathsOf(handlersExcept(['/cards']));
    expect(kept.some((path) => path.startsWith('/cards'))).toBe(false);
    expect(kept).toContain('/accounts');
  });

  it('splits an area that the backend only partly implements', () => {
    /*
     * The bug this encodes, because the symptom pointed nowhere near the cause.
     *
     * `registration` was listed whole. The backend implements /registration/personal/*
     * and /registration/otp/resend; it does not implement /registration/business or
     * /registration/join. So company registration was sent to the real service, where
     * Spring Security authorises before the dispatcher resolves a handler — an
     * unimplemented path is not a 404, it falls to the catch-all and is DENIED. Someone
     * registering a company was told "your account does not have permission to do this".
     *
     * Multi-segment prefixes are what fixes it, so they need to keep working.
     */
    const kept = pathsOf(handlersExcept(['/registration/personal', '/registration/otp']));

    expect(kept).not.toContain('/registration/personal/start');
    expect(kept).not.toContain('/registration/otp/resend');

    /*
     * Joining an existing company is still mocked, because there is still nothing real to
     * call. Company REGISTRATION is not in this list any more — it is implemented, its
     * mock handler is deleted, and there is therefore no handler left for a prefix to
     * keep or drop. That is what "live" means here: MSW passes through what it has no
     * handler for.
     */
    expect(kept).toContain('/registration/join');
    expect(kept).not.toContain('/registration/business');
    expect(kept).not.toContain('/registration/business/start');
  });

  it('refuses to send company registration to a backend that has no such endpoint', () => {
    /*
     * The other way a prefix can be wrong, and the one that actually bit.
     *
     * `registration` matches mocked endpoints perfectly well, so the spelling guard above
     * is satisfied. But the real service implements only /registration/personal/* and
     * /registration/otp/resend, and an unimplemented path there is not a 404: Spring
     * Security authorises before the dispatcher resolves a handler, so it falls to the
     * catch-all and is DENIED. Company registration failed with "Your account does not
     * have permission to do this" — a permission error for a missing feature.
     *
     * Refused at start-up rather than checked in a test, because the value normally comes
     * from a developer's own .env.local, which no committed test can see.
     */
    expect(() => handlersExcept(['/registration'])).toThrow(/does not implement them/);

    /*
     * It now names JOIN rather than business. Company registration was the path that bit,
     * and it is implemented — so the guard has to name what is actually still missing,
     * or it protects a feature that no longer needs protecting while the real gap goes
     * unmentioned.
     */
    expect(() => handlersExcept(['/registration'])).toThrow(/registration\/join/);

    // The narrower prefixes are exactly what it is asking for, and are accepted.
    expect(() =>
      handlersExcept(['/registration/personal', '/registration/otp', '/auth', '/session', '/admin']),
    ).not.toThrow();

    /*
     * COMPANY REGISTRATION MUST NOT BE LISTED ANY MORE, and the error says why.
     *
     * Its mock is deleted, so there is no handler for a prefix to remove and MSW passes
     * the request through on its own — the area is live by virtue of having no mock. The
     * spelling guard above therefore fires, which would read as a typo, so the message
     * names this case explicitly. Asserting on that text because the next person to add
     * the entry will be reading it.
     */
    expect(() =>
      handlersExcept(['/registration/personal', '/registration/otp', '/registration/business']),
    ).toThrow(/already live and the entry can simply be removed/);
  });

  it('refuses a prefix that matches nothing', () => {
    /*
     * The failure this prevents is the quiet one: a typo leaves the area mocked while the
     * developer believes they are exercising the real backend, and everything appears to
     * work because the mock answers.
     */
    expect(() => handlersExcept(['/sesssion'])).toThrow(/match no mocked endpoint/);
    expect(() => handlersExcept(['/registration/personal', '/nope'])).toThrow(/nope/);
  });
});
