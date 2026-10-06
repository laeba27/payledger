package com.laeba.payledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AccountTypeTest {

	@Test
	void assetGrowsWithDebits() {
		// cardholder spent $50 then got $20 refunded: they owe us $30
		assertThat(AccountType.ASSET.balance(5_000, 2_000)).isEqualTo(3_000);
	}

	@Test
	void liabilityGrowsWithCredits() {
		// we owe the merchant $50, minus a $20 refund: we owe them $30
		assertThat(AccountType.LIABILITY.balance(2_000, 5_000)).isEqualTo(3_000);
	}

	@Test
	void normalBalancesFollowAccountingRules() {
		assertThat(AccountType.ASSET.normalBalance()).isEqualTo(Direction.DEBIT);
		assertThat(AccountType.EXPENSE.normalBalance()).isEqualTo(Direction.DEBIT);
		assertThat(AccountType.LIABILITY.normalBalance()).isEqualTo(Direction.CREDIT);
		assertThat(AccountType.EQUITY.normalBalance()).isEqualTo(Direction.CREDIT);
		assertThat(AccountType.REVENUE.normalBalance()).isEqualTo(Direction.CREDIT);
	}
}
