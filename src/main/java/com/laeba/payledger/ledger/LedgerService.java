package com.laeba.payledger.ledger;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only way money moves. Other features (payments, refunds) build a {@link Posting}
 * and call {@link #post}; nothing else writes to the ledger tables.
 */
@Service
public class LedgerService {

	private final AccountRepository accounts;
	private final LedgerTransactionRepository transactions;
	private final LedgerEntryRepository entries;

	public LedgerService(AccountRepository accounts, LedgerTransactionRepository transactions,
			LedgerEntryRepository entries) {
		this.accounts = accounts;
		this.transactions = transactions;
		this.entries = entries;
	}

	@Transactional
	public Account openAccount(String code, AccountType type, String currency) {
		return accounts.save(Account.open(code, type, currency));
	}

	/**
	 * Records a posting atomically. Joins the caller's transaction if there is one, so a
	 * payment status change and its ledger entries commit or roll back together.
	 */
	@Transactional
	public LedgerTransaction post(Posting posting) {
		checkAccounts(posting);

		LedgerTransaction txn = transactions.save(LedgerTransaction.of(posting));
		List<LedgerEntry> lines = posting.legs().stream()
				.map(leg -> LedgerEntry.of(txn.getId(), leg, txn.getCreatedAt()))
				.toList();
		entries.saveAll(lines);
		return txn;
	}

	@Transactional(readOnly = true)
	public long balanceOf(UUID accountId) {
		Account account = accounts.findById(accountId)
				.orElseThrow(() -> new LedgerException("unknown account " + accountId));
		long debits = entries.sumForAccount(accountId, Direction.DEBIT);
		long credits = entries.sumForAccount(accountId, Direction.CREDIT);
		return account.getType().balance(debits, credits);
	}

	@Transactional(readOnly = true)
	public List<LedgerEntry> entriesOf(UUID transactionId) {
		return entries.findByTransactionId(transactionId);
	}

	/** Fail with a clear message before touching the database; the DB trigger is the backstop. */
	private void checkAccounts(Posting posting) {
		Set<UUID> ids = posting.legs().stream().map(Posting.Leg::accountId).collect(Collectors.toSet());
		Map<UUID, Account> found = accounts.findAllById(ids).stream()
				.collect(Collectors.toMap(Account::getId, Function.identity()));
		for (UUID id : ids) {
			Account account = found.get(id);
			if (account == null) {
				throw new LedgerException("unknown account " + id);
			}
			if (!account.getCurrency().equals(posting.currency())) {
				throw new LedgerException("account " + account.getCode() + " is in "
						+ account.getCurrency() + ", posting is in " + posting.currency());
			}
		}
	}
}
