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

/** One money movement. Its entries are stored in {@link LedgerEntry}. */
@Entity
@Immutable
@Table(name = "ledger_transactions")
public class LedgerTransaction {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private TransactionType type;

	private UUID referenceId;

	@Column(nullable = false)
	private String currency;

	private String description;

	@Column(nullable = false)
	private Instant createdAt;

	protected LedgerTransaction() {
		// for JPA
	}

	static LedgerTransaction of(Posting posting) {
		LedgerTransaction txn = new LedgerTransaction();
		txn.type = posting.type();
		txn.referenceId = posting.referenceId();
		txn.currency = posting.currency();
		txn.description = posting.description();
		txn.createdAt = Instant.now();
		return txn;
	}

	public UUID getId() {
		return id;
	}

	public TransactionType getType() {
		return type;
	}

	public UUID getReferenceId() {
		return referenceId;
	}

	public String getCurrency() {
		return currency;
	}

	public String getDescription() {
		return description;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
