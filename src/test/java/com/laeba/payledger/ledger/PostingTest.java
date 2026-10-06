package com.laeba.payledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PostingTest {

	private final UUID a = UUID.randomUUID();
	private final UUID b = UUID.randomUUID();
	private final UUID c = UUID.randomUUID();

	@Test
	void transferCreatesOneDebitAndOneCredit() {
		Posting posting = Posting.transfer(TransactionType.CAPTURE, null, "USD", a, b, 5_000, "coffee");

		assertThat(posting.legs()).containsExactly(
				new Posting.Leg(a, Direction.DEBIT, 5_000),
				new Posting.Leg(b, Direction.CREDIT, 5_000));
	}

	@Test
	void allowsSplitPostingsWhenTotalsMatch() {
		// e.g. a $100 capture where $97 goes to the merchant and $3 is our fee
		Posting posting = new Posting(TransactionType.CAPTURE, null, "USD", "split", List.of(
				new Posting.Leg(a, Direction.DEBIT, 10_000),
				new Posting.Leg(b, Direction.CREDIT, 9_700),
				new Posting.Leg(c, Direction.CREDIT, 300)));

		assertThat(posting.legs()).hasSize(3);
	}

	@Test
	void rejectsUnbalancedPosting() {
		assertThatThrownBy(() -> new Posting(TransactionType.ADJUSTMENT, null, "USD", null, List.of(
				new Posting.Leg(a, Direction.DEBIT, 5_000),
				new Posting.Leg(b, Direction.CREDIT, 4_999))))
				.isInstanceOf(LedgerException.class)
				.hasMessageContaining("unbalanced");
	}

	@Test
	void rejectsSingleLeg() {
		assertThatThrownBy(() -> new Posting(TransactionType.ADJUSTMENT, null, "USD", null, List.of(
				new Posting.Leg(a, Direction.DEBIT, 5_000))))
				.isInstanceOf(LedgerException.class)
				.hasMessageContaining("at least 2");
	}

	@ParameterizedTest
	@ValueSource(longs = { 0, -1, -5_000 })
	void rejectsNonPositiveAmounts(long cents) {
		assertThatThrownBy(() -> new Posting.Leg(a, Direction.DEBIT, cents))
				.isInstanceOf(LedgerException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = { "usd", "US", "USDX", "" })
	void rejectsBadCurrencyCodes(String currency) {
		assertThatThrownBy(() -> Posting.transfer(TransactionType.CAPTURE, null, currency, a, b, 100, null))
				.isInstanceOf(LedgerException.class);
	}

	@Test
	void detectsOverflowInsteadOfWrappingAround() {
		assertThatThrownBy(() -> new Posting(TransactionType.ADJUSTMENT, null, "USD", null, List.of(
				new Posting.Leg(a, Direction.DEBIT, Long.MAX_VALUE),
				new Posting.Leg(b, Direction.DEBIT, 1),
				new Posting.Leg(c, Direction.CREDIT, 1))))
				.isInstanceOf(ArithmeticException.class);
	}

	@Test
	void legsCannotBeChangedAfterCreation() {
		Posting posting = Posting.transfer(TransactionType.CAPTURE, null, "USD", a, b, 100, null);

		assertThatThrownBy(() -> posting.legs().add(new Posting.Leg(c, Direction.DEBIT, 1)))
				.isInstanceOf(UnsupportedOperationException.class);
	}
}
