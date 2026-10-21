package app.omymoney.core.ledger

import app.omymoney.core.model.*
import java.math.BigDecimal
import java.math.BigInteger

// Both values use the account's minor currency unit; interest never reduces principal.
data class DebtPosition(val principal: Long, val interest: Long)

object DebtRules {
    val sourceKinds = setOf(BillKind.DEBT_BORROW, BillKind.DEBT_LEND)
    val principalKinds = setOf(BillKind.DEBT_REPAY, BillKind.DEBT_COLLECT)
    val interestKinds = setOf(BillKind.INTEREST_EXPENSE, BillKind.INTEREST_INCOME)
    val linkedKinds = principalKinds + interestKinds
    val debtAccountKinds = setOf(AccountKind.LIABILITY, AccountKind.RECEIVABLE)
    fun principalAccount(draft: TransactionDraft): String? = when (draft.kind) {
        BillKind.DEBT_BORROW, BillKind.DEBT_COLLECT -> draft.fromAccountId
        BillKind.DEBT_LEND, BillKind.DEBT_REPAY -> draft.toAccountId
        else -> null
    }

    fun position(accountId: String, bills: List<Bill>): DebtPosition {
        val sources = bills.filter { it.kind in sourceKinds && principalAccount(it.toDraft()) == accountId }.distinctBy { it.id }
        val ids = sources.map { it.id }.toSet()
        val children = bills.filter { it.linkedBillId in ids }.distinctBy { it.id }
        fun sum(rows: List<Bill>) = rows.fold(BigInteger.ZERO) { total, bill -> total + BigInteger.valueOf(bill.amount.minor) }
        return DebtPosition(BigDecimal(sum(sources) - sum(children.filter { it.kind in principalKinds })).longValueExact(),
            BigDecimal(sum(children.filter { it.kind in interestKinds })).longValueExact())
    }

    fun validate(draft: TransactionDraft, bills: List<Bill>): List<FieldError> {
        val proposed = bills.filterNot { it.id == draft.id }.map { it.toDraft() } + draft
        val byId = proposed.filter { it.id != null }.associateBy { it.id }
        val affected = proposed.filter { it === draft || (draft.id != null && it.linkedBillId == draft.id) }
        return buildList {
            affected.filter { it.kind in linkedKinds }.forEach { child ->
                val expected = if (child.kind in setOf(BillKind.DEBT_REPAY, BillKind.INTEREST_EXPENSE)) BillKind.DEBT_BORROW else BillKind.DEBT_LEND
                val source = child.linkedBillId?.let(byId::get)
                if (source == null || source === child || source.kind != expected || source.bookId != child.bookId ||
                    source.amount?.currency != child.amount?.currency ||
                    (child.kind in principalKinds && principalAccount(child) != principalAccount(source))) {
                    add(FieldError("linkedBillId", "invalid_link", "A compatible source and unchanged principal account are required"))
                }
            }
            val ids = affected.filter { it.kind in principalKinds }.mapNotNull { it.linkedBillId }.toSet() +
                listOfNotNull(draft.id.takeIf { draft.kind in sourceKinds })
            ids.forEach { id ->
                val source = byId[id] ?: return@forEach
                val total = proposed.filter { it.kind in principalKinds && it.linkedBillId == id }
                    .fold(BigInteger.ZERO) { sum, bill -> sum + BigInteger.valueOf(bill.amount?.minor ?: 0) }
                if (total > BigInteger.valueOf(source.amount?.minor ?: 0)) {
                    add(FieldError("amount", "exceeds_remaining", "Principal settlements exceed the source amount"))
                }
            }
        }.distinctBy { it.field to it.code }
    }

    fun validateAccounts(draft: TransactionDraft, accounts: List<AssetAccount>): List<FieldError> {
        val byId = accounts.associateBy { it.id }
        val from = byId[draft.fromAccountId]
        val to = byId[draft.toAccountId]
        val valid = (draft.fromAccountId == null || (from != null && acceptsAccount(draft.kind, true, from))) &&
            (draft.toAccountId == null || (to != null && acceptsAccount(draft.kind, false, to)))
        return if (valid) emptyList() else listOf(FieldError("fromAccountId", "account_role", "Select accounts compatible with this transaction type"))
    }

    fun acceptsAccount(kind: BillKind?, from: Boolean, account: AssetAccount): Boolean = when {
        kind == BillKind.DEBT_BORROW && from || kind == BillKind.DEBT_REPAY && !from -> account.kind == AccountKind.LIABILITY
        kind == BillKind.DEBT_LEND && !from || kind == BillKind.DEBT_COLLECT && from -> account.kind == AccountKind.RECEIVABLE
        kind == BillKind.CREDIT_PAYMENT && !from -> account.kind == AccountKind.CREDIT
        kind == BillKind.ADJUSTMENT -> true
        else -> account.kind !in debtAccountKinds
    }
}
