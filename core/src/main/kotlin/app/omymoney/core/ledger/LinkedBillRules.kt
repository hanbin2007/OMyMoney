package app.omymoney.core.ledger

import app.omymoney.core.model.*
import java.math.BigDecimal
import java.math.BigInteger

data class RefundEffect(val expenseReduction: Money, val income: Money)

object LinkedBillRules {
    val settlementKinds = setOf(BillKind.REFUND, BillKind.REIMBURSED)

    // Evaluate the proposed graph, not the previous version of an edited source or settlement.
    fun validate(draft: TransactionDraft, relatedBills: List<Bill>): List<FieldError> {
        val proposed = relatedBills.filterNot { it.id == draft.id }.map { it.toDraft() } + draft
        val byId = proposed.filter { it.id != null }.associateBy { it.id }
        val affected = proposed.filter { it === draft || (draft.id != null && it.linkedBillId == draft.id) }
        return buildList {
            affected.forEach { child ->
                if (child.kind in settlementKinds) {
                    val source = child.linkedBillId?.let(byId::get)
                    val expected = if (child.kind == BillKind.REFUND) BillKind.EXPENSE else BillKind.REIMBURSEMENT
                    if (source == null || source === child || source.kind != expected || source.bookId != child.bookId ||
                        source.amount?.currency != child.amount?.currency) {
                        add(FieldError("linkedBillId", "invalid_link", "The source must be an available compatible bill in the same book and currency"))
                    }
                } else if (child.linkedBillId != null && child.kind !in DebtRules.linkedKinds) {
                    add(FieldError("linkedBillId", "unexpected_link", "This transaction kind cannot have a settlement source"))
                }
            }
            val sourceIds = affected.filter { it.kind == BillKind.REIMBURSED }.mapNotNull { it.linkedBillId }.toSet() +
                listOfNotNull(draft.id.takeIf { draft.kind == BillKind.REIMBURSEMENT })
            sourceIds.forEach { id ->
                val source = byId[id] ?: return@forEach
                val total = proposed.filter { it.kind == BillKind.REIMBURSED && it.linkedBillId == id }
                    .fold(BigInteger.ZERO) { sum, bill -> sum + BigInteger.valueOf(bill.amount?.minor ?: 0) }
                if (total > BigInteger.valueOf(source.amount?.minor ?: 0)) {
                    add(FieldError("amount", "exceeds_remaining", "Settlements cannot exceed the reimbursable amount"))
                }
            }
        }.plus(DebtRules.validate(draft, relatedBills)).distinctBy { it.field to it.code }
    }

    fun remainingReimbursement(source: Bill, settlements: List<Bill>): Money {
        require(source.kind == BillKind.REIMBURSEMENT)
        val total = settlements.filter { it.kind == BillKind.REIMBURSED && it.linkedBillId == source.id }.distinctBy { it.id }
            .fold(BigInteger.ZERO) { sum, bill ->
                require(bill.amount.currency == source.amount.currency)
                sum + BigInteger.valueOf(bill.amount.minor)
            }
        return source.amount.copy(minor = BigDecimal(BigInteger.valueOf(source.amount.minor) - total).longValueExact())
    }

    fun refundEffects(source: Bill, refunds: List<Bill>): Map<String, RefundEffect> {
        require(source.kind == BillKind.EXPENSE)
        var remaining = source.amount.minor
        return refunds.filter { it.kind == BillKind.REFUND && it.linkedBillId == source.id }.distinctBy { it.id }
            .sortedWith(compareBy({ it.occurredAt }, { it.id })).associate { refund ->
                require(refund.amount.currency == source.amount.currency && refund.amount.minor > 0)
                val reduction = minOf(remaining, refund.amount.minor)
                remaining -= reduction
                refund.id to RefundEffect(Money(reduction, source.amount.currency),
                    Money(refund.amount.minor - reduction, source.amount.currency))
            }
    }
}
