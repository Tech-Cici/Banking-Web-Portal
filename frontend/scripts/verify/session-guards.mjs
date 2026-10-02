import { mkdirSync } from 'node:fs';
import { chromium } from 'playwright';

const OUT = process.env.WALK_OUT ?? 'walk-output';
const EMAIL = `guard${Date.now()}@example.rw`;
const CHOSEN = 'Umutekano2026!';
const API = 'http://localhost:8080/api/v1';
const b64 = (s) => 'Basic ' + Buffer.from(s).toString('base64');
const STAFF = b64('admin@zigama.local:ZigamaStaff1');
const MGR = b64('manager@zigama.local:ZigamaStaff1');

mkdirSync(OUT, { recursive: true });
const out = [];
const say = (m) => { console.log(m); out.push(m); };

async function staff(path, method = 'GET', who = STAFF) {
  const r = await fetch(`${API}${path}`, { method, headers: { Authorization: who } });
  const t = await r.text();
  if (!r.ok) throw new Error(`${method} ${path} -> ${r.status} ${t.slice(0,200)}`);
  return t ? JSON.parse(t) : null;
}
async function codeFor(email) {
  // Filtered to the verification messages, and to the LATEST by sentAt. Taking the last
  // element of the whole list picked up the registration code once the account also had
  // creation and approval notices, which then burned a real attempt against the counter.
  const ob = await staff('/admin/outbox');
  const mine = ob
    .filter((m) => m.to.toLowerCase() === email.toLowerCase())
    .filter((m) => m.kind === 'EMAIL_VERIFICATION')
    .sort((a, c) => new Date(a.sentAt ?? 0) - new Date(c.sentAt ?? 0));
  const latest = mine[mine.length - 1];
  if (!latest) throw new Error('no verification message for ' + email);
  const hit = /\b(\d{6})\b/.exec(latest.body);
  if (!hit) throw new Error('no code in: ' + latest.body.slice(0, 120));
  return hit[1];
}

const browser = await chromium.launch({ executablePath: '/opt/pw-browsers/chromium' });
const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
const shot = (n) => page.screenshot({ path: `${OUT}/g-${n}.png`, fullPage: true });

try {
  /* ---- get an approved account with a temporary password, via the API ---- */
  const start = await (await fetch(`${API}/registration/personal/start`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ accountNumber: '1234567890', nationalId: '1199570099999999',
      dateOfBirth: '1990-01-01', phone: '0781999888', email: EMAIL }),
  })).json();

  const verified = await (await fetch(`${API}/registration/personal/verify`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ challengeId: start.challengeId, code: await codeFor(EMAIL) }),
  })).json();

  await fetch(`${API}/registration/personal/complete`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ verificationToken: verified.verificationToken }),
  });

  const apps = await staff('/admin/applications');
  const mine = apps.find((a) => a.email.toLowerCase() === EMAIL.toLowerCase());
  const created = await staff(`/admin/applications/${mine.id}/create-account`, 'POST');
  await staff(`/admin/customers/${created.customer.id}/approve`, 'POST', MGR);
  const temporary = created.temporaryPassword;

  /* ---------- 1. temporary password + typed URL ---------- */
  await page.goto('http://localhost:5173/login', { waitUntil: 'networkidle' });
  await page.waitForTimeout(800);
  await page.getByLabel(/email|identifier|username/i).first().fill(EMAIL);
  await page.getByLabel(/password/i).first().fill(temporary);
  await page.getByRole('button', { name: /sign in|log in|continue/i }).first().click();
  await page.waitForTimeout(2500);
  say(`--  temporary password lands on ${page.url().replace('http://localhost:5173','')}`);

  await page.goto('http://localhost:5173/dashboard', { waitUntil: 'networkidle' });
  await page.waitForTimeout(2000);
  await shot('01-typed-dashboard');
  const at = page.url().replace('http://localhost:5173','');
  const text = await page.locator('body').innerText();
  const showsDashboard = /Your accounts|Quick actions|Recent activity/.test(text);
  say(showsDashboard
    ? `!! FAILED: typing /dashboard on a temporary password showed the dashboard`
    : `ok  typing /dashboard on a temporary password is refused — sent to ${at}`);

  /* ---------- 2. finish the change, then sign out ---------- */
  await page.goto('http://localhost:5173/auth/new-password', { waitUntil: 'networkidle' });
  await page.waitForTimeout(1200);
  const pw = page.locator('input[type="password"]');
  await pw.nth(0).fill(temporary);
  await pw.nth(1).fill(CHOSEN);
  if (await pw.count() > 2) await pw.nth(2).fill(CHOSEN);
  await page.getByRole('button', { name: /change|save|set|continue|update/i }).first().click();
  await page.waitForTimeout(3000);
  say(`--  after the change: ${page.url().replace('http://localhost:5173','')}`);

  await page.getByRole('button', { name: /sign out/i }).first().click();
  await page.waitForTimeout(2500);
  say(`--  after signing out: ${page.url().replace('http://localhost:5173','')}`);

  await page.goto('http://localhost:5173/dashboard', { waitUntil: 'networkidle' });
  await page.waitForTimeout(2000);
  const afterOut = await page.locator('body').innerText();
  say(/Your accounts|Quick actions/.test(afterOut)
    ? '!! FAILED: the dashboard is still reachable after signing out'
    : `ok  after signing out, /dashboard is refused — sent to ${page.url().replace('http://localhost:5173','')}`);
  await shot('02-after-signout');

  /* ---------- 3. settled password now requires the code ---------- */
  await page.goto('http://localhost:5173/login', { waitUntil: 'networkidle' });
  await page.waitForTimeout(800);
  await page.getByLabel(/email|identifier|username/i).first().fill(EMAIL);
  await page.getByLabel(/password/i).first().fill(CHOSEN);
  await page.getByRole('button', { name: /sign in|log in|continue/i }).first().click();
  await page.waitForTimeout(2500);
  await shot('03-otp-step');

  const otpText = await page.locator('body').innerText();
  const asksForCode = /code/i.test(otpText) && !/Your accounts/.test(otpText);
  say(asksForCode
    ? 'ok  a settled password asks for the one-time code instead of opening the portal'
    : '!! FAILED: the password alone opened the portal');

  const masked = /\b[a-z]\*+@/.exec(otpText);
  if (masked) say(`ok  the delivery hint is masked: ${masked[0]}...`);

  await page.getByLabel(/code/i).first().fill(await codeFor(EMAIL));
  await page.getByRole('button', { name: /verify|confirm|continue|sign in/i }).first().click();
  await page.waitForTimeout(3000);
  await shot('04-after-otp');
  const finalText = await page.locator('body').innerText();
  say(/Your accounts|Quick actions/.test(finalText)
    ? `ok  the code completed the sign-in — ${page.url().replace('http://localhost:5173','')}`
    : `!! the code did not complete the sign-in — ${page.url().replace('http://localhost:5173','')}`);

  // Second sign-in, so there IS a previous one to report now.
  say(/first sign-in/i.test(finalText)
    ? '--  still shows "first sign-in" on the second visit'
    : (/Last sign-in/i.test(finalText) ? 'ok  now reports the previous sign-in' : '--  no sign-in line found'));
} catch (e) {
  say('!! threw: ' + e.message);
  await shot('99-failure');
} finally {
  console.log('\n--- summary ---');
  console.log(out.join('\n'));
  await browser.close();
}
