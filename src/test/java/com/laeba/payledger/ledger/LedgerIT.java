package com.laeba.payledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.laeba.payledger.TestcontainersConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs against real Postgres, so it tests both the Java service and the database
 * triggers. The ledger is append-only even for tests, so every test uses fresh accounts
 * instead of cleaning up.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class LedgerIT {

	@Autowired
	LedgerService ledger;

	@Autowired
	LedgerEntryRepository entries;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	TransactionTemplate tx;

	// ---- the service ----------------------------------------------------------------

	@Test
	void captureThenPartialRefundMovesBalances() {
		Account card = open(AccountType.ASSET, "USD");        // cardholder owes us
		Account merchant = open(AccountType.LIABILITY, "USD"); // we owe the merchant
		UUID paymentId = UUID.randomUUID();

		ledger.post(Posting.transfer(TransactionType.CAPTURE, paymentId, "USD",
				card.getId(), merchant.getId(), 5_000, "capture"));
		ledger.post(Posting.transfer(TransactionType.REFUND, paymentId, "USD",
				merchant.getId(), card.getId(), 2_000, "partial refund"));

		assertThat(ledger.balanceOf(card.getId())).isEqualTo(3_000);
		assertThat(ledger.balanceOf(merchant.getId())).isEqualTo(3_000);
	}

	@Test
	void postWritesOneEntryPerLeg() {
		Account card = open(AccountType.ASSET, "USD");
		Account merchant = open(AccountType.LIABILITY, "USD");

		LedgerTransaction txn = ledger.post(Posting.transfer(TransactionType.CAPTURE, null, "USD",
				card.getId(), merchant.getId(), 450, "coffee"));

		assertThat(ledger.entriesOf(txn.getId()))
				.extracting(LedgerEntry::getAccountId, LedgerEntry::getDirection, LedgerEntry::getAmountCents)
				.containsExactlyInAnyOrder(
						tuple(card.getId(), Direction.DEBIT, 450L),
						tuple(merchant.getId(), Direction.CREDIT, 450L));
	}

	@Test
	void wholeLedgerAlwaysBalances() {
		Account card = open(AccountType.ASSET, "USD");
		Account merchant = open(AccountType.LIABILITY, "USD");
		ledger.post(Posting.transfer(TransactionType.CAPTURE, null, "USD",
				card.getId(), merchant.getId(), 12_345, null));

		assertThat(entries.sumAll(Direction.DEBIT)).isEqualTo(entries.sumAll(Direction.CREDIT));
	}

	@Test
	void serviceRejectsUnknownAccount() {
		Account card = open(AccountType.ASSET, "USD");

		assertThatThrownBy(() -> ledger.post(Posting.transfer(TransactionType.CAPTURE, null, "USD",
				card.getId(), UUID.randomUUID(), 100, null)))
				.isInstanceOf(LedgerException.class)
				.hasMessageContaining("unknown account");
	}

	@Test
	void serviceRejectsCurrencyMismatch() {
		Account card = open(AccountType.ASSET, "USD");
		Account merchant = open(AccountType.LIABILITY, "EUR");

		assertThatThrownBy(() -> ledger.post(Posting.transfer(TransactionType.CAPTURE, null, "USD",
				card.getId(), merchant.getId(), 100, null)))
				.isInstanceOf(LedgerException.class)
				.hasMessageContaining("EUR");
	}

	// ---- the database backstop: raw SQL that bypasses the Java checks ----------------

	@Test
	void databaseRejectsUnbalancedTransaction() {
		Account card = open(AccountType.ASSET, "USD");
		Account merchant = open(AccountType.LIABILITY, "USD");

		assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
			UUID txn = insertTransaction("USD");
			insertEntry(txn, card.getId(), "DEBIT", 5_000);
			insertEntry(txn, merchant.getId(), "CREDIT", 4_000);
		})).hasStackTraceContaining("unbalanced");
	}

	@Test
	void databaseRejectsSingleEntryTransaction() {
		Account card = open(AccountType.ASSET, "USD");

		assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
			UUID txn = insertTransaction("USD");
			insertEntry(txn, card.getId(), "DEBIT", 5_000);
		})).hasStackTraceContaining("needs at least 2");
	}

	@Test
	void databaseRejectsTransactionWithNoEntries() {
		assertThatThrownBy(() -> tx.executeWithoutResult(status -> insertTransaction("USD")))
				.hasStackTraceContaining("needs at least 2");
	}

	@Test
	void databaseRejectsMixedCurrencies() {
		Account card = open(AccountType.ASSET, "USD");
		Account merchant = open(AccountType.LIABILITY, "EUR");

		assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
			UUID txn = insertTransaction("USD");
			insertEntry(txn, card.getId(), "DEBIT", 5_000);
			insertEntry(txn, merchant.getId(), "CREDIT", 5_000);
		})).hasStackTraceContaining("not in USD");
	}

	@Test
	void databaseRejectsEditingEntries() {
		LedgerTransaction txn = postSmallCapture();

		assertThatThrownBy(() -> jdbc.update(
				"UPDATE ledger_entries SET amount_cents = 1 WHERE transaction_id = ?", txn.getId()))
				.hasStackTraceContaining("append-only");
	}

	@Test
	void databaseRejectsDeletingEntriesAndTransactions() {
		LedgerTransaction txn = postSmallCapture();

		assertThatThrownBy(() -> jdbc.update(
				"DELETE FROM ledger_entries WHERE transaction_id = ?", txn.getId()))
				.hasStackTraceContaining("append-only");
		assertThatThrownBy(() -> jdbc.update(
				"DELETE FROM ledger_transactions WHERE id = ?", txn.getId()))
				.hasStackTraceContaining("append-only");
	}

	@Test
	void databaseRejectsTruncate() {
		assertThatThrownBy(() -> jdbc.execute("TRUNCATE ledger_entries CASCADE"))
				.hasStackTraceContaining("append-only");
	}

	// ---- helpers -----------------------------------------------------------------------

	private Account open(AccountType type, String currency) {
		return ledger.openAccount("test:" + UUID.randomUUID(), type, currency);
	}

	private LedgerTransaction postSmallCapture() {
		Account card = open(AccountType.ASSET, "USD");
		Account merchant = open(AccountType.LIABILITY, "USD");
		return ledger.post(Posting.transfer(TransactionType.CAPTURE, null, "USD",
				card.getId(), merchant.getId(), 100, null));
	}

	private UUID insertTransaction(String currency) {
		UUID id = UUID.randomUUID();
		jdbc.update("INSERT INTO ledger_transactions (id, type, currency) VALUES (?, 'ADJUSTMENT', ?)",
				id, currency);
		return id;
	}

	private void insertEntry(UUID txn, UUID account, String direction, long cents) {
		jdbc.update("""
				INSERT INTO ledger_entries (id, transaction_id, account_id, direction, amount_cents)
				VALUES (?, ?, ?, ?, ?)""", UUID.randomUUID(), txn, account, direction, cents);
	}
}
