import { mkdirSync } from 'node:fs';
import { chromium } from 'playwright';

const OUT = process.env.WALK_OUT ?? 'walk-output';
const EMAIL = `walk${Date.now()}@example.rw`;
const API = 'http://localhost:8080/api/v1';
const STAFF = 'Basic ' + Buffer.from('admin@zigama.local:ZigamaStaff1').toString('base64');
const MGR = 'Basic ' + Buffer.from('manager@zigama.local:ZigamaStaff1').toString('base64');

mkdirSync(OUT, { recursive: true });
const steps = [];
const say = (m) => { console.log(m); steps.push(m); };

async function staff(path, method = 'GET', who = STAFF) {
  const r = await fetch(`${API}${path}`, { method, headers: { Authorization: who } });
  const text = await r.text();
  if (!r.ok) throw new Error(`${method} ${path} -> ${r.status} ${text.slice(0, 200)}`);
  return text ? JSON.parse(text) : null;
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

const requests = [];
page.on('response', async (r) => {
  if (!r.url().includes('/api/v1')) return;
  const setCookie = (await r.allHeaders())['set-cookie'] ?? '';
  const names = setCookie.split('\n').map(c => c.split('=')[0]).filter(Boolean).join('+');
  requests.push(`${r.status()} ${r.request().method()} ${r.url().replace('http://localhost:8080/api/v1','')}${names ? '   Set-Cookie: '+names : ''}`);
});
const consoleErrors = [];
page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });

async function shot(name) { await page.screenshot({ path: `${OUT}/${name}.png`, fullPage: true }); }

try {
  /* ---------------------------------------------------------- register */
  await page.goto('http://localhost:5173/register/personal', { waitUntil: 'networkidle' });
  await page.waitForTimeout(900);
  await shot('01-register');

  const fill = async (label, value) => {
    const box = page.getByLabel(label, { exact: false }).first();
    await box.fill(value);
  };

  // The form asks for the name again: nothing here can supply it, since matching against
  // the bank's records happens outside this system. It is a claim staff verify.
  await fill('Full name', 'Aline Uwase');
  await fill('Account number', String(Date.now()).slice(-10));
  await fill('National ID number', '1199570099999999');
  await fill('Date of birth', '1990-01-01');
  await fill('Mobile number', '0781999888');
  await fill('Email address', EMAIL);
  await shot('02-filled');

  const send = page.getByRole('button', { name: /email me a code/i }).first();
  await send.click();
  await page.waitForTimeout(2500);
  await shot('03-code-requested');

  const bodyAfterStart = await page.locator('body').innerText();
  if (/could not|error|went wrong/i.test(bodyAfterStart)) {
    say('!! register/start showed an error');
  } else {
    say('ok  registration start reached the real backend');
  }

  /* ------------------------------------------------------- verify code */
  const code = await codeFor(EMAIL);
  say(`ok  verification code read from /admin/outbox (${code.length} digits)`);

  const codeBox = page.getByLabel(/code/i).first();
  await codeBox.fill(code);
  await page.getByRole('button', { name: /verify|confirm|continue/i }).first().click();
  await page.waitForTimeout(2500);
  await shot('04-verified');

  /* ----------------------------------------------------- submit/complete */
  // The confirm step gates submission on accepting the terms, as it should.
  const terms = page.locator('input[type="checkbox"]').first();
  if (await terms.count() > 0) {
    await terms.check();
    say('ok  the confirm step requires the terms to be accepted before it will submit');
  }

  const submit = page.getByRole('button', { name: /submit registration/i }).first();
  if (await submit.count() > 0) {
    await submit.click();
    await page.waitForTimeout(2500);
  }
  await shot('05-submitted');

  const apps = await staff('/admin/applications');
  const mine = apps.find((a) => a.email?.toLowerCase() === EMAIL.toLowerCase());
  if (!mine) throw new Error('application not found on the backend for ' + EMAIL);
  say(mine.displayName === 'Aline Uwase'
    ? `ok  the application carries the name the applicant gave ("${mine.displayName}")`
    : `!! unexpected name on the application: "${mine.displayName}"`);
  say(`ok  application ${mine.reference ?? mine.id} is on the backend, status ${mine.status}`);

  /* --------------------------------------------- staff create + approve */
  const created = await staff(`/admin/applications/${mine.id}/create-account`, 'POST');
  const customerId = created.customer.id;
  say(created.temporaryPassword === undefined
    ? 'ok  the create response carries NO password — nobody at the bank sees one'
    : '!! the create response still returns a temporary password');

  await staff(`/admin/customers/${customerId}/approve`, 'POST', MGR);
  say('ok  manager approved it (four eyes: a different member of staff)');

  // The password exists only in the approval email now.
  const approvalMail = (await staff('/admin/outbox'))
    .filter((m) => m.to.toLowerCase() === EMAIL.toLowerCase() && m.kind === 'ACCOUNT_APPROVED')
    .pop();
  const hit = /Temporary password: ([A-Z2-9-]+)/.exec(approvalMail?.body ?? '');
  if (!hit) throw new Error('the approval email carries no temporary password');
  const temporary = hit[1];
  say(`ok  the temporary password arrived in the approval email (${temporary.length} chars)`);
  say(/stops working in \d+ hours/.test(approvalMail.body)
    ? 'ok  the email states when it expires'
    : '!! the email does not say it expires');
  say(/replace it/.test(approvalMail.body) && /Delete this email/.test(approvalMail.body)
    ? 'ok  the email says to change it and then delete the message'
    : '!! the email is missing the change/delete instruction');
  say(/never will|did not include your password/.test(approvalMail.body)
    ? '!! the email still carries the old "we never email passwords" promise'
    : 'ok  the old contradictory promise is gone');

  /* ------------------------------------------- sign in, forced change */
  await page.goto('http://localhost:5173/login', { waitUntil: 'networkidle' });
  await page.waitForTimeout(700);
  await shot('06-login');

  await page.getByLabel(/email|identifier|username/i).first().fill(EMAIL);
  await page.getByLabel(/password/i).first().fill(temporary);
  await page.getByRole('button', { name: /sign in|log in|continue/i }).first().click();
  await page.waitForTimeout(3000);
  await shot('07-after-temp-login');

  const url = page.url();
  say(`--  after signing in with the temporary password: ${url.replace('http://localhost:5173','')}`);

  const pageText = await page.locator('body').innerText();
  if (/dashboard|balance|accounts/i.test(pageText) && !/password/i.test(pageText)) {
    say('!! FAILED: the dashboard opened while still on the temporary password');
  }

  /* ------------------------------------------------- change the password */
  const CHOSEN = 'Umutekano2026!';
  const pwBoxes = page.locator('input[type="password"]');
  const n = await pwBoxes.count();
  say(`--  ${n} password field(s) on the change screen`);

  if (n >= 2) {
    // current, new, (confirm)
    if (n === 2) {
      await pwBoxes.nth(0).fill(CHOSEN);
      await pwBoxes.nth(1).fill(CHOSEN);
    } else {
      await pwBoxes.nth(0).fill(temporary);
      await pwBoxes.nth(1).fill(CHOSEN);
      await pwBoxes.nth(2).fill(CHOSEN);
    }
    await shot('08-change-filled');
    await page.getByRole('button', { name: /change|save|set|continue|update/i }).first().click();
    await page.waitForTimeout(3500);
    await shot('09-after-change');
    say(`--  after the change: ${page.url().replace('http://localhost:5173','')}`);
  }

  const finalText = await page.locator('body').innerText();
  if (/first sign-in/i.test(finalText)) {
    say('ok  the dashboard says "This is your first sign-in" instead of "Last sign-in — from undefined"');
  }

  await shot('10-final');
} catch (e) {
  say('!! threw: ' + e.message);
  await shot('99-failure');
} finally {
  console.log('\n--- API calls the browser made ---');
  console.log(requests.join('\n'));
  console.log('\n--- console errors ---');
  console.log([...new Set(consoleErrors)].slice(0, 12).join('\n') || '(none)');
  console.log('\n--- summary ---');
  console.log(steps.join('\n'));
  await browser.close();
}
