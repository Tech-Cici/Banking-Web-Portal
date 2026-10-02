import type { ReactElement } from 'react';
import { Select } from './Select';
import type { Account } from '@/types/banking';
import { formatBalanceDto } from '@/utils/money';

interface AccountSelectorProps {
  readonly label: string;
  readonly accounts: readonly Account[];
  readonly value: string;
  readonly onChange: (accountId: string) => void;
  /** Hide accounts that cannot be debited. Set for a "from" field, clear for a "to". */
  readonly debitOnly?: boolean;
  /** Excluded from the list, e.g. the account already chosen on the other side. */
  readonly excludeId?: string;
  readonly hint?: string | undefined;
  readonly error?: string | undefined;
  readonly disabled?: boolean;
}

/**
 * Picks one of the customer's own accounts.
 *
 * The balance is in the option text because choosing a source account without seeing
 * what is in it is guesswork, and a customer who has to go back and check has already
 * lost their place in the form.
 *
 * `debitOnly` removes accounts the API says cannot be debited — a dormant or frozen
 * account. Offering one and failing at the last step wastes the whole form, and the
 * server would refuse it anyway.
 */
export function AccountSelector({
  label,
  accounts,
  value,
  onChange,
  debitOnly = false,
  excludeId,
  hint,
  error,
  disabled = false,
}: AccountSelectorProps): ReactElement {
  const usable = accounts.filter(
    (account) => (!debitOnly || account.debitAllowed) && account.id !== excludeId,
  );

  return (
    <Select
      label={label}
      hint={hint}
      error={error}
      disabled={disabled}
      value={value}
      options={usable.map((account) => ({
        value: account.id,
        label: `${account.nickname} ${account.maskedNumber} — ${formatBalanceDto(account.availableBalance)}`,
      }))}
      onChange={(event) => {
        onChange(event.target.value);
      }}
    />
  );
}
