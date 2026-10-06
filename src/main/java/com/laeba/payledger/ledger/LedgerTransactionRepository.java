package com.laeba.payledger.ledger;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction, UUID> {

	List<LedgerTransaction> findByReferenceIdOrderByCreatedAt(UUID referenceId);
}
