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

/**
 * A named bucket of money. Accounts have no balance column: the balance is always
 * derived from the ledger entries, so it can never drift from the history.
 */
@Entity
@Table(name = "accounts")
public class Account {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(nullable = false, unique = true)
	private String code;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private AccountType type;

	@Column(nullable = false)
	private String currency;

	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	protected Account() {
		// for JPA
	}

	public static Account open(String code, AccountType type, String currency) {
		Account account = new Account();
		account.code = code;
		account.type = type;
		account.currency = currency;
		account.createdAt = Instant.now();
		return account;
	}

	public UUID getId() {
		return id;
	}

	public String getCode() {
		return code;
	}

	public AccountType getType() {
		return type;
	}

	public String getCurrency() {
		return currency;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
