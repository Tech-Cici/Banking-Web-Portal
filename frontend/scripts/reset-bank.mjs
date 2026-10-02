/*
 * EMPTIES THE LOCAL BANK, FROM A TERMINAL.
 *
 * WHY THIS IS NOT A BUTTON ANY MORE. It was one, on the staff overview — the first screen
 * an administrator or a manager sees — labelled "Delete every customer and application"
 * under a paragraph naming build profiles. Moving it to a developer-only page fixed who
 * meets it, and left the real problem: a control that deletes every customer in the
 * database does not belong inside the bank's own portal at all. Hiding it behind a flag
 * still ships the component, and one misconfigured build is the whole distance between
 * hidden and live.
 *
 * A terminal command cannot be reached by clicking, cannot be reached by a customer, and
 * cannot be shipped. It is also the right shape for the job: this is something a developer
 * does to their own machine between test runs, not a feature of a banking product.
 *
 * THE SERVER IS STILL THE CONTROL. The endpoint refuses outside the dev, h2 and test
 * profiles and requires staff credentials, so this script cannot reach production however
 * it is pointed. What moving it out of the UI removes is the chance of somebody finding
 * the button, not the protection itself.
 *
 *     npm run reset-bank
 *
 * Reads API_URL, STAFF_EMAIL and STAFF_PASSWORD from the environment, with the local
 * development defaults below.
 */

const API = process.env.API_URL ?? 'http://localhost:8080/api/v1';
const EMAIL = process.env.STAFF_EMAIL ?? 'admin@zigama.local';
const PASSWORD = process.env.STAFF_PASSWORD ?? 'ZigamaStaff1';

const authorization = 'Basic ' + Buffer.from(`${EMAIL}:${PASSWORD}`).toString('base64');

/*
 * NAMES THE TARGET BEFORE DOING ANYTHING. The one mistake this script can make is being
 * pointed at a database somebody cared about, so the host is printed rather than assumed —
 * and the profile check on the server is what makes the mistake survivable.
 */
console.log(`Emptying the bank at ${API}`);
console.log(`  as ${EMAIL}`);

let response;
try {
  response = await fetch(`${API}/admin/dev/reset`, {
    method: 'POST',
    headers: { Authorization: authorization },
  });
} catch (cause) {
  console.error(`\nCould not reach the API at ${API}.`);
  console.error('Is it running? Start it with: ./mvnw spring-boot:run');
  console.error(String(cause instanceof Error ? cause.message : cause));
  process.exit(1);
}

if (response.status === 401 || response.status === 403) {
  console.error(`\nThe API refused those staff credentials (${response.status}).`);
  console.error('Set STAFF_EMAIL and STAFF_PASSWORD if yours are not the seeded ones.');
  process.exit(1);
}

if (!response.ok) {
  const body = await response.text();
  console.error(`\nThe API refused the reset (${response.status}).`);
  /*
   * The likeliest cause by a wide margin, and worth saying rather than making somebody
   * read a stack trace: the service is running on a profile that does not allow this.
   * That is the protection working.
   */
  console.error('If this is not a dev, h2 or test profile, that refusal is correct.');
  console.error(body.slice(0, 400));
  process.exit(1);
}

console.log('\nDone. Every application, customer, sign-in, payee, request and message is gone.');
console.log('Staff logins are kept — deleting those would lock you out of the staff portal.');
