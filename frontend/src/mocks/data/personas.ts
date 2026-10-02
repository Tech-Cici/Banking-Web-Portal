import type { CorporateMembership } from '@/types/banking';
import { persistedArray } from './persist';

/**
 * The register of companies that bank here.
 *
 * THIS FILE USED TO HOLD THREE SEEDED USERS. They are gone. Nobody can sign in to this
 * portal without going through the real chain — register, an admin creates the login, a
 * manager approves it — because a fixture login is a way to skip exactly the steps that
 * most need testing.
 *
 * What remains is the company register, and it starts EMPTY too. A company appears here
 * when a business registration is approved, which is the only way a company exists at a
 * bank: somebody applied and somebody signed it off.
 */

export interface CorporateRecord {
  readonly id: string;
  readonly name: string;
  /** Short code the administrator shares with colleagues who need access. */
  readonly code: string;
  /** Members and their role in THIS company. */
  readonly members: CorporateMembership['role'][];
}

const store = persistedArray<CorporateRecord>('corporates');
export const CORPORATES: CorporateRecord[] = store.items;

export function corporateById(id: string): CorporateRecord | undefined {
  return CORPORATES.find((company) => company.id === id);
}

export function corporateByCode(code: string): CorporateRecord | undefined {
  const needle = code.trim().toUpperCase();
  return CORPORATES.find((company) => company.code === needle);
}

/**
 * Registers a company and returns it.
 *
 * The code is derived from the name so it is memorable to the people who have to quote
 * it, and suffixed with the year so two companies with similar names do not collide.
 */
export function registerCorporate(name: string): CorporateRecord {
  const letters = name
    .toUpperCase()
    .replace(/[^A-Z ]/g, '')
    .split(/\s+/)
    .filter(Boolean);

  const stem = (letters.length >= 2
    ? letters.map((word) => word.slice(0, 2)).join('')
    : (letters[0] ?? 'CO')
  ).slice(0, 4);

  let code = `${stem}${String(new Date().getFullYear())}`;
  let attempt = 1;
  while (CORPORATES.some((company) => company.code === code)) {
    attempt += 1;
    code = `${stem}${String(new Date().getFullYear())}${String(attempt)}`;
  }

  const company: CorporateRecord = {
    id: `corp-${crypto.randomUUID().slice(0, 8)}`,
    name,
    code,
    members: [],
  };

  CORPORATES.push(company);
  store.commit();
  return company;
}

/** The memberships a customer holds, in the shape the session exposes. */
export function membershipsFor(
  corporateId: string | undefined,
  role: CorporateMembership['role'],
): readonly CorporateMembership[] {
  if (corporateId === undefined) return [];
  const company = corporateById(corporateId);
  if (company === undefined) return [];
  return [{ id: company.id, name: company.name, code: company.code, role }];
}
