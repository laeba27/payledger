package com.laeba.payledger.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * One debit or credit line. {@code @Immutable} stops Hibernate from ever issuing an
 * UPDATE for it; the database trigger blocks UPDATE/DELETE from anywhere else.
 */
@Entity
@Immutable
@Table(name = "ledger_entries")
public class LedgerEntry {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(nullable = false)
	private UUID transactionId;

	@Column(nullable = false)
	private UUID accountId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Direction direction;

	@Column(nullable = false)
	private long amountCents;

	@Column(nullable = false)
	private Instant createdAt;

	protected LedgerEntry() {
		// for JPA
	}

	static LedgerEntry of(UUID transactionId, Posting.Leg leg, Instant createdAt) {
		LedgerEntry entry = new LedgerEntry();
		entry.transactionId = transactionId;
		entry.accountId = leg.accountId();
		entry.direction = leg.direction();
		entry.amountCents = leg.amountCents();
		entry.createdAt = createdAt;
		return entry;
	}

	public UUID getId() {
		return id;
	}

	public UUID getTransactionId() {
		return transactionId;
	}

	public UUID getAccountId() {
		return accountId;
	}

	public Direction getDirection() {
		return direction;
	}

	public long getAmountCents() {
		return amountCents;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
