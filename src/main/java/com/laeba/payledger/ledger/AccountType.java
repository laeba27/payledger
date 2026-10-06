package com.laeba.payledger.ledger;

/**
 * The five standard account types. Each has a "normal balance": the side that
 * increases it. An ASSET (money owed to us) grows with debits; a LIABILITY
 * (money we owe) grows with credits.
 */
public enum AccountType {
	ASSET(Direction.DEBIT),
	EXPENSE(Direction.DEBIT),
	LIABILITY(Direction.CREDIT),
	EQUITY(Direction.CREDIT),
	REVENUE(Direction.CREDIT);

	private final Direction normalBalance;

	AccountType(Direction normalBalance) {
		this.normalBalance = normalBalance;
	}

	public Direction normalBalance() {
		return normalBalance;
	}

	/** Balance as a positive number when the account holds its "normal" kind of money. */
	public long balance(long debitCents, long creditCents) {
		return normalBalance == Direction.DEBIT
				? Math.subtractExact(debitCents, creditCents)
				: Math.subtractExact(creditCents, debitCents);
	}
}
