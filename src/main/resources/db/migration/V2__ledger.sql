-- Double-entry ledger.
--
-- accounts             : named buckets of money (a cardholder's receivable, a merchant's payable, ...)
-- ledger_transactions  : one money movement (e.g. "capture payment X")
-- ledger_entries       : the debit/credit lines of a transaction; debits must equal credits
--
-- The rules below are enforced by Postgres itself, so no bug in the application
-- (or a manual SQL session) can produce an unbalanced or edited ledger.

CREATE TABLE accounts (
  id          UUID         PRIMARY KEY,
  code        VARCHAR(100) NOT NULL UNIQUE,     -- human-readable, e.g. 'card:<id>:receivable'
  type        VARCHAR(16)  NOT NULL
              CHECK (type IN ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE')),
  currency    VARCHAR(3)   NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
  created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE ledger_transactions (
  id            UUID         PRIMARY KEY,
  type          VARCHAR(32)  NOT NULL,           -- CAPTURE, REFUND, ADJUSTMENT, ...
  reference_id  UUID,                            -- what caused it, e.g. a payment id
  currency      VARCHAR(3)   NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
  description   VARCHAR(200),
  created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_ledger_txn_reference ON ledger_transactions(reference_id);

CREATE TABLE ledger_entries (
  id              UUID        PRIMARY KEY,
  transaction_id  UUID        NOT NULL REFERENCES ledger_transactions(id),
  account_id      UUID        NOT NULL REFERENCES accounts(id),
  direction       VARCHAR(6)  NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
  amount_cents    BIGINT      NOT NULL CHECK (amount_cents > 0),
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_ledger_entries_account ON ledger_entries(account_id, created_at);
CREATE INDEX idx_ledger_entries_txn     ON ledger_entries(transaction_id);


-- Rule 1: the ledger is append-only. Mistakes are fixed with a new, reversing transaction.
CREATE FUNCTION ledger_reject_modification() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION USING
    ERRCODE = 'restrict_violation',
    MESSAGE = format('%s is append-only: %s is not allowed', TG_TABLE_NAME, TG_OP);
END;
$$;

CREATE TRIGGER ledger_entries_append_only
  BEFORE UPDATE OR DELETE ON ledger_entries
  FOR EACH ROW EXECUTE FUNCTION ledger_reject_modification();
CREATE TRIGGER ledger_entries_no_truncate
  BEFORE TRUNCATE ON ledger_entries
  FOR EACH STATEMENT EXECUTE FUNCTION ledger_reject_modification();

CREATE TRIGGER ledger_transactions_append_only
  BEFORE UPDATE OR DELETE ON ledger_transactions
  FOR EACH ROW EXECUTE FUNCTION ledger_reject_modification();
CREATE TRIGGER ledger_transactions_no_truncate
  BEFORE TRUNCATE ON ledger_transactions
  FOR EACH STATEMENT EXECUTE FUNCTION ledger_reject_modification();


-- Rule 2: every transaction has >= 2 entries, debits == credits, and all accounts
-- use the transaction's currency.
-- DEFERRABLE INITIALLY DEFERRED means the check runs at COMMIT, after all entries of the
-- transaction have been inserted. Checking row by row would fail on the very first entry.
CREATE FUNCTION ledger_check_transaction() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  txn_id        UUID;
  txn_currency  VARCHAR(3);
  entry_count   INT;
  debits        BIGINT;
  credits       BIGINT;
  wrong_ccy     INT;
BEGIN
  IF TG_TABLE_NAME = 'ledger_transactions' THEN
    txn_id := NEW.id;
  ELSE
    txn_id := NEW.transaction_id;
  END IF;

  SELECT currency INTO txn_currency FROM ledger_transactions WHERE id = txn_id;

  SELECT count(*),
         coalesce(sum(e.amount_cents) FILTER (WHERE e.direction = 'DEBIT'), 0),
         coalesce(sum(e.amount_cents) FILTER (WHERE e.direction = 'CREDIT'), 0),
         count(*) FILTER (WHERE a.currency <> txn_currency)
    INTO entry_count, debits, credits, wrong_ccy
    FROM ledger_entries e
    JOIN accounts a ON a.id = e.account_id
   WHERE e.transaction_id = txn_id;

  IF entry_count < 2 THEN
    RAISE EXCEPTION USING ERRCODE = 'check_violation',
      MESSAGE = format('ledger transaction %s has %s entries, needs at least 2', txn_id, entry_count);
  END IF;
  IF debits <> credits THEN
    RAISE EXCEPTION USING ERRCODE = 'check_violation',
      MESSAGE = format('ledger transaction %s is unbalanced: debits %s, credits %s', txn_id, debits, credits);
  END IF;
  IF wrong_ccy > 0 THEN
    RAISE EXCEPTION USING ERRCODE = 'check_violation',
      MESSAGE = format('ledger transaction %s has accounts not in %s', txn_id, txn_currency);
  END IF;

  RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER ledger_entries_balanced
  AFTER INSERT ON ledger_entries
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION ledger_check_transaction();

-- Also fires for a transaction row inserted with no entries at all.
CREATE CONSTRAINT TRIGGER ledger_transactions_balanced
  AFTER INSERT ON ledger_transactions
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION ledger_check_transaction();
