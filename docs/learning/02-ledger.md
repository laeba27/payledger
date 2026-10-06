# Slice 2: Accounts + double-entry ledger

**Goal:** build the part of a payment system that must never be wrong: the record of where
every cent went. After this slice we have:
- **accounts**: named buckets of money,
- a **ledger** recording every money movement as balanced debits and credits,
- **rules enforced by PostgreSQL itself**: history can't be edited and every transaction must
  balance, even if the Java code has a bug.

There's nothing to install. This slice is pure design + code.

---

## 1. The big idea: double-entry bookkeeping

### The naive way (what most student projects do)
```sql
UPDATE cards SET balance = balance - 5000 WHERE id = ?;
```
Problems:
1. **No history.** Once overwritten, the old value is gone. Why is the balance 3,000? No one knows.
2. **Lost updates.** Two requests read 10,000, both write 5,000, and one payment vanishes.
3. **Can't reconcile.** Money "appears" or "disappears" with no trace. Auditors and regulators
   don't accept this.

### The ledger way (what banks have done for 500 years)
Money is never created or destroyed; it **moves** from one account to another. Every movement is a
**transaction** with at least two **entries**:

```
Transaction T1: "capture $50 coffee payment"
┌───────────────────────┬───────────┬────────┐
│ account               │ direction │ amount │
├───────────────────────┼───────────┼────────┤
│ card:abc:receivable   │ DEBIT     │  5000  │  ← cardholder now owes us $50
│ merchant:cafe:payable │ CREDIT    │  5000  │  ← we now owe the café $50
└───────────────────────┴───────────┴────────┘
                     total debits = total credits  ✔
```

The **balance** of an account isn't stored anywhere; it's *calculated* by adding up its entries.
So the balance and the history can never disagree.

### Debit and credit: the confusing part, made simple
"Debit" and "credit" don't mean minus and plus. They're just the **left** and **right** columns.
Whether a debit *increases* or *decreases* an account depends on the account **type**:

| Type | Meaning (for a card issuer like Amex) | Increased by | Example |
|---|---|---|---|
| ASSET | something we own / money owed **to us** | DEBIT | cardholder receivable |
| EXPENSE | money we spend | DEBIT | fraud losses |
| LIABILITY | money **we owe** others | CREDIT | merchant payable |
| EQUITY | owners' stake | CREDIT | — |
| REVENUE | money we earn | CREDIT | interchange fees |

This is called the **normal balance** of the account type. It's coded in `AccountType.java`.

### Our payment flows in ledger terms
| Event | DEBIT | CREDIT |
|---|---|---|
| Capture $50 | card receivable (ASSET ↑) | merchant payable (LIABILITY ↑) |
| Refund $20 | merchant payable (LIABILITY ↓) | card receivable (ASSET ↓) |
| Result | card owes us **$30** | we owe merchant **$30** |

A refund is **not** an edit of the capture. It's a *new* transaction going the other way.
That's what "append-only" means.

---

## 2. The database design (`V2__ledger.sql`)

```
accounts                ledger_transactions             ledger_entries
─────────               ───────────────────             ──────────────
id (PK)          ┌───── id (PK)                  ┌──── transaction_id (FK)
code (unique)    │      type   CAPTURE/REFUND/…  │     account_id (FK) ──► accounts.id
type             │      reference_id (payment)   │     direction  DEBIT/CREDIT
currency         │      currency                 │     amount_cents > 0
created_at       │      description              │     created_at
                 └──────────────────────────────-┘
```

Decisions worth explaining:
- **`amount_cents BIGINT` and always > 0.** The *direction* says which side; the amount is never
  negative. This avoids "is -50 a debit or a credit?" confusion.
- **No `balance` column on `accounts`.** The balance is derived from entries. (Later, for speed, we
  might cache it, but the entries stay the source of truth.)
- **`reference_id`** links a ledger transaction to whatever caused it (a payment). It has no foreign
  key on purpose: different things (payments, adjustments, fees) can cause postings.
- **Indexes:** `(account_id, created_at)` for "statement of an account", `transaction_id` for "entries of
  a transaction", and `reference_id` for "all postings of a payment".
- **`currency` + CHECK `^[A-Z]{3}$`:** ISO currency codes only. We never add USD to EUR.

### Rule 1: append-only (triggers)
```sql
CREATE TRIGGER ledger_entries_append_only
  BEFORE UPDATE OR DELETE ON ledger_entries
  FOR EACH ROW EXECUTE FUNCTION ledger_reject_modification();
```
A **trigger** is a function Postgres runs automatically when something happens to a table. Ours
raises an error on any `UPDATE`, `DELETE` or `TRUNCATE`. Even someone typing SQL by hand in
production can't silently rewrite history.

### Rule 2: every transaction balances (deferred constraint trigger)
```sql
CREATE CONSTRAINT TRIGGER ledger_entries_balanced
  AFTER INSERT ON ledger_entries
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION ledger_check_transaction();
```
The function checks, for the transaction being written:
1. at least **2 entries**,
2. **sum of debits = sum of credits**,
3. all accounts use the **transaction's currency**.

**Why `DEFERRABLE INITIALLY DEFERRED`?** We insert entries one at a time. Right after the first
entry, the transaction is *temporarily* unbalanced (5000 debit, 0 credit). A normal check would fail
immediately. A **deferred** trigger waits until **COMMIT**, when all entries are in, and checks then.
If it fails, the whole database transaction rolls back and nothing is saved.

A second trigger on `ledger_transactions` catches a transaction inserted with **zero** entries.

### Real output (run by hand in `psql`)
```
--- unbalanced transaction: rejected at COMMIT
ERROR:  ledger transaction aaaaaaaa-...-0002 is unbalanced: debits 5000, credits 4000

--- editing history: rejected
ERROR:  ledger_entries is append-only: UPDATE is not allowed
ERROR:  ledger_entries is append-only: DELETE is not allowed

--- balances from entries
     code      |   type    | debits | credits
 demo:card     | ASSET     |   5000 |       0
 demo:merchant | LIABILITY |      0 |    5000
```

---

## 3. The Java code (`ledger/` package)

```
ledger/
├── Direction.java                  DEBIT / CREDIT
├── AccountType.java                5 types + normal balance + balance() maths
├── TransactionType.java            CAPTURE / REFUND / ADJUSTMENT
├── LedgerException.java            our error type for ledger rule violations
├── Account.java                    @Entity for accounts
├── LedgerTransaction.java          @Entity @Immutable
├── LedgerEntry.java                @Entity @Immutable
├── Posting.java                    a validated "move money" request (record)
├── AccountRepository.java          Spring Data repositories
├── LedgerTransactionRepository.java
├── LedgerEntryRepository.java      + sum queries for balances
└── LedgerService.java              the ONLY way to write to the ledger
```

### `Posting.java`: invalid requests can't even exist
```java
public record Posting(TransactionType type, UUID referenceId, String currency,
                      String description, List<Leg> legs) {
    public Posting {               // "compact constructor": runs on every new Posting(...)
        ... legs.size() >= 2, debits == credits, currency is 3 letters ...
    }
}
```
- **Java `record`:** an immutable data class. Fields are final, and `equals`, `hashCode` and `toString` are
  generated for you.
- **Validation in the constructor:** if you hold a `Posting` object, it's *guaranteed* valid.
  This idea is often called **"make illegal states unrepresentable."**
- **`List.copyOf(legs)`:** takes an immutable copy, so nobody can add an unbalanced leg afterwards.
- **`Math.addExact`:** throws on overflow instead of silently wrapping to a negative number. With
  money, wrong-but-quiet is the worst outcome.
- **`Posting.transfer(...)`:** a helper for the common 2-leg case. Postings with more legs are
  still possible, e.g. $100 from the cardholder → $97 to the merchant + $3 fee to us.

### `AccountType.java`
```java
ASSET(Direction.DEBIT), LIABILITY(Direction.CREDIT), ...
public long balance(long debitCents, long creditCents)
```
An enum can hold data and behaviour. The normal-balance rule lives in exactly one place.

### Entities: `Account`, `LedgerTransaction`, `LedgerEntry`
- `@Entity` + `@Table`: maps the class to the table.
- `@GeneratedValue(strategy = UUID)`: Hibernate creates the UUID when saving.
- `@Enumerated(EnumType.STRING)`: stores `"DEBIT"`, not `0`. If someone reorders the enum later, the
  data doesn't silently change meaning.
- **`@Immutable`** on transactions and entries: Hibernate will never send an `UPDATE` for them.
  Together with the trigger, that gives two layers of protection.
- `protected` no-arg constructor: JPA needs it, but our code uses static factories
  (`Account.open(...)`) that set every required field.
- **`accountId` is a plain `UUID`, not a `@ManyToOne Account`.** It's simpler, has no lazy-loading
  surprises, and an entry never needs the whole account object.

### `LedgerEntryRepository.java`
```java
@Query("select coalesce(sum(e.amountCents), 0) from LedgerEntry e
        where e.accountId = :accountId and e.direction = :direction")
long sumForAccount(UUID accountId, Direction direction);
```
JPQL (queries written against Java classes, not tables). `coalesce(…, 0)` returns 0 instead of null for an
account with no entries.

### `LedgerService.java`: the single door to the ledger
```java
@Transactional
public LedgerTransaction post(Posting posting) {
    checkAccounts(posting);                         // clear Java error first
    LedgerTransaction txn = transactions.save(...); // 1 transaction row
    entries.saveAll(...);                           // N entry rows
    return txn;                                     // DB trigger checks balance at COMMIT
}
```
- **`@Transactional`:** everything inside commits together or not at all. If a payment service
  calls `post()` inside its own transaction, `post()` **joins** it, so the payment's status change
  and its ledger entries succeed or fail together. That's the property we need in slice 4.
- **Checks in two layers:**
  - **Java** (`Posting` + `checkAccounts`): fast, with friendly messages like "unknown account …".
  - **Database** (triggers): the backstop that catches anything that slips past, including manual SQL.
  This is called **defence in depth**.
- **`balanceOf`:** sum of debits, sum of credits, then `AccountType.balance()` turns them into a
  human number.

---

## 4. The tests

### Unit tests (no Docker, milliseconds)
**`PostingTest`** (13 cases):
- 2-leg transfer and 3-leg split
- rejects: unbalanced, single leg, amounts `0 / -1 / -5000`, currencies `usd / US / USDX / ""`
- overflow throws instead of wrapping around
- legs can't be modified after creation

`@ParameterizedTest` + `@ValueSource` runs one test method with many inputs.

**`AccountTypeTest`:** asset and liability balance maths, plus the normal-balance table.

### Integration tests: `LedgerIT` (real Postgres, 12 cases)
**Through the service:**
- capture $50 + refund $20 → card balance $30, merchant balance $30
- one entry is written per leg
- across the whole ledger, total debits == total credits
- unknown account and currency mismatch are rejected with clear messages

**Directly against the database (raw SQL, skipping Java):**
- unbalanced transaction → rejected at commit
- single-entry transaction → rejected
- transaction with zero entries → rejected
- mixed currencies → rejected
- `UPDATE`, `DELETE`, `TRUNCATE` → rejected

The raw-SQL tests matter most. They prove the rules hold **even when the application is
bypassed**.

**Testing trick:** because the ledger is append-only, tests can't clean up after themselves.
So each test creates **fresh accounts** with random codes and only checks those. The "whole
ledger balances" test still works, because every transaction that ever succeeded is balanced.

**Result:** 16 unit + 14 integration = **30 tests, all green**.

---

## 5. Try it yourself

```bash
docker compose up -d
./mvnw spring-boot:run        # applies V2 automatically; stop it with Ctrl+C once started
docker compose exec postgres psql -U payledger
```
Then in `psql`:
```sql
\d ledger_entries                     -- see the columns, indexes and triggers
SELECT * FROM flyway_schema_history;  -- V1 baseline, V2 ledger

-- try to break the rules:
UPDATE ledger_entries SET amount_cents = 1;   -- ERROR: append-only
```
Note: since the ledger is append-only, the only way to wipe your local data is to delete the whole
database volume: `docker compose down -v`.

Run the tests:
```bash
./mvnw test -Dtest=PostingTest       # one unit test class, instantly
./mvnw verify                        # everything
```

---

## 6. Words to know

| Term | Meaning |
|---|---|
| Double-entry | Every movement is recorded as equal debits and credits |
| Ledger | The full, ordered record of all transactions |
| Entry / leg | One debit or credit line inside a transaction |
| Normal balance | The side (debit/credit) that increases an account type |
| Append-only | Rows can be added, never changed or removed |
| Trigger | A database function that runs automatically on INSERT/UPDATE/DELETE |
| Deferred constraint | A check that runs at COMMIT instead of after each statement |
| Defence in depth | Several independent layers checking the same rule |
| Reversal | Undoing a transaction by posting the opposite one, never by deleting |
| Trial balance | Checking that total debits == total credits across the whole ledger |

---

## 7. Interview questions this slice prepares you for

**Q: Why not just keep a `balance` column?**
Overwrites lose history and can't be audited. Concurrent updates can lose money, and there's nothing to
reconcile against. With a ledger, the balance is derived from immutable entries, so every cent is
traceable.

**Q: How do you fix a wrong ledger entry?**
You never edit it. You post a *reversing* transaction (opposite directions) and then the correct one.
The mistake and its correction both stay visible, which is what auditors want.

**Q: How do you guarantee the ledger always balances?**
Two layers. The Java `Posting` record can't be built unbalanced, and a Postgres **deferred constraint
trigger** checks debits == credits for every transaction at commit. Raw SQL that bypasses the app is
rejected too, and I have integration tests that prove it.

**Q: Why is the balance trigger deferred?**
Entries are inserted one by one, so the transaction is temporarily unbalanced mid-way. Deferring the check
to COMMIT validates the final state, and if it fails, the whole transaction rolls back atomically.

**Q: Isn't summing entries slow for a big account?**
With the `(account_id, created_at)` index it's fine for a long time. At scale you'd add
**balance snapshots**: store the balance at a checkpoint, then sum only entries after it. You could also keep a
cached balance column updated in the same transaction. Either way, the entries stay the source of truth.

**Q: Why store amounts as positive numbers with a direction?**
There's no ambiguity about what a negative number means, and it matches how accountants read ledgers.

**Q: What's the trade-off of putting rules in database triggers?**
Pro: they can't be bypassed, by any app or person. Con: logic is split between Java and SQL and is
harder to unit-test. That's why the rule lives in both places and the IT tests cover the SQL. The per-row
deferred trigger also re-checks a transaction once per entry; that's fine for 2–3 legs, and we'd
revisit it for very large postings.

---

## Next: Slice 3, cardholders + cards
Issuing virtual cards: Luhn-valid card numbers shown **once**, only `last4` + a token stored,
masking, freeze/unfreeze, a global error handler and OpenAPI/Swagger docs. Each card gets its
own ledger account.
