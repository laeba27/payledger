package com.laeba.payledger.ledger;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A request to record one balanced money movement. Validated on construction, so an
 * invalid posting can't exist in memory; the database re-checks the same rules at commit.
 */
public record Posting(TransactionType type, UUID referenceId, String currency, String description,
		List<Leg> legs) {

	public record Leg(UUID accountId, Direction direction, long amountCents) {

		public Leg {
			Objects.requireNonNull(accountId, "accountId");
			Objects.requireNonNull(direction, "direction");
			if (amountCents <= 0) {
				throw new LedgerException("amount must be positive, was " + amountCents);
			}
		}
	}

	public Posting {
		Objects.requireNonNull(type, "type");
		if (currency == null || !currency.matches("[A-Z]{3}")) {
			throw new LedgerException("currency must be a 3-letter ISO code, was " + currency);
		}
		legs = List.copyOf(legs);
		if (legs.size() < 2) {
			throw new LedgerException("a posting needs at least 2 legs, had " + legs.size());
		}
		long debits = 0;
		long credits = 0;
		for (Leg leg : legs) {
			if (leg.direction() == Direction.DEBIT) {
				debits = Math.addExact(debits, leg.amountCents());
			}
			else {
				credits = Math.addExact(credits, leg.amountCents());
			}
		}
		if (debits != credits) {
			throw new LedgerException("posting is unbalanced: debits " + debits + ", credits " + credits);
		}
	}

	/** The common case: move {@code amountCents} from one account to another. */
	public static Posting transfer(TransactionType type, UUID referenceId, String currency,
			UUID debitAccountId, UUID creditAccountId, long amountCents, String description) {
		return new Posting(type, referenceId, currency, description, List.of(
				new Leg(debitAccountId, Direction.DEBIT, amountCents),
				new Leg(creditAccountId, Direction.CREDIT, amountCents)));
	}
}
