package app.omymoney.core.ledger

import app.omymoney.core.model.*

object PostingCalculator {
    val outgoingKinds = setOf(BillKind.EXPENSE, BillKind.REIMBURSEMENT, BillKind.INTEREST_EXPENSE)
    val incomingKinds = setOf(BillKind.INCOME, BillKind.REFUND, BillKind.REIMBURSED, BillKind.INTEREST_INCOME)
    val transferKinds = setOf(BillKind.TRANSFER, BillKind.CREDIT_PAYMENT, BillKind.DEBT_BORROW, BillKind.DEBT_LEND, BillKind.DEBT_REPAY, BillKind.DEBT_COLLECT)
    val supportedKinds = outgoingKinds + incomingKinds + transferKinds + BillKind.ADJUSTMENT
    fun allowsUnassignedAccounts(draft: TransactionDraft) = draft.source == BillSource.ACCESSIBILITY &&
        !draft.sourcePackage.isNullOrBlank() && !draft.ruleId.isNullOrBlank() && draft.recognizedAt != null &&
        draft.kind in setOf(BillKind.EXPENSE, BillKind.INCOME, BillKind.TRANSFER, BillKind.CREDIT_PAYMENT)
    fun categoryKind(kind: BillKind?) = when (kind) {
        BillKind.REIMBURSEMENT, BillKind.INTEREST_EXPENSE -> BillKind.EXPENSE
        BillKind.INTEREST_INCOME -> BillKind.INCOME
        BillKind.EXPENSE, BillKind.INCOME -> kind
        else -> null
    }
    fun calculate(draft: TransactionDraft): List<Posting> {
        val unassignedAllowed = allowsUnassignedAccounts(draft)
        val errors = buildList {
            if (draft.bookId.isBlank()) add(FieldError("bookId", "required", "A book is required"))
            if (draft.submissionId.isBlank()) add(FieldError("submissionId", "required", "A submission ID is required"))
            if (draft.kind == null) add(FieldError("kind", "required", "A transaction kind is required"))
            else if (draft.kind !in supportedKinds) {
                add(FieldError("kind", "unsupported_kind", "This transaction kind is not supported yet"))
            }
            if (draft.amount == null || draft.amount.minor <= 0) {
                add(FieldError("amount", "positive_amount", "Amount must be greater than zero"))
            }
            if (draft.occurredAt == null || draft.timeSource == TimeSource.UNKNOWN) {
                add(FieldError("occurredAt", "unconfirmed_time", "Transaction time must be confirmed"))
            }
            if (!unassignedAllowed && (draft.kind in outgoingKinds || draft.kind in transferKinds) && draft.fromAccountId.isNullOrBlank()) {
                add(FieldError("fromAccountId", "required", "A paying account is required"))
            }
            if (!unassignedAllowed && (draft.kind in incomingKinds || draft.kind in transferKinds) && draft.toAccountId.isNullOrBlank()) {
                add(FieldError("toAccountId", "required", "A receiving account is required"))
            }
            if (draft.kind in transferKinds && draft.fromAccountId != null && draft.fromAccountId == draft.toAccountId) {
                add(FieldError("toAccountId", "same_account", "Transfer accounts must be different"))
            }
            if (draft.kind in outgoingKinds && draft.toAccountId != null) add(FieldError("toAccountId", "unexpected_account", "An outgoing bill cannot have a receiving account"))
            if (draft.kind in incomingKinds && draft.fromAccountId != null) add(FieldError("fromAccountId", "unexpected_account", "An incoming bill cannot have a paying account"))
            addAll(ExchangeRules.validate(draft))
            if (draft.kind == BillKind.ADJUSTMENT) {
                if (draft.fromAccountId.isNullOrBlank() == draft.toAccountId.isNullOrBlank()) {
                    add(FieldError("fromAccountId", "adjustment_account", "An adjustment requires exactly one account"))
                }
                if (draft.note.isBlank()) add(FieldError("note", "adjustment_reason", "An adjustment reason is required"))
            }
        }
        if (errors.isNotEmpty()) throw DraftValidationException(errors)
        val amount = requireNotNull(draft.amount)
        val base = when (draft.kind) {
            in outgoingKinds -> listOfNotNull(draft.fromAccountId?.let { Posting(it, -amount) })
            in incomingKinds -> listOfNotNull(draft.toAccountId?.let { Posting(it, amount) })
            in transferKinds -> listOfNotNull(
                draft.fromAccountId?.let { Posting(it, -amount) },
                draft.toAccountId?.let { Posting(it, amount) },
            )
            BillKind.ADJUSTMENT -> listOf(if (draft.fromAccountId != null) Posting(draft.fromAccountId, -amount)
                else Posting(requireNotNull(draft.toAccountId), amount))
            else -> error("Kind validated above")
        }
        return base.map { posting ->
            val actual = ExchangeRules.accountAmount(draft, posting.accountId)
            val signed = if (posting.amount.minor < 0) -actual else actual
            Posting(posting.accountId, if (posting.accountId == ExchangeRules.feeAccount(draft) && draft.fee != null) signed - draft.fee else signed)
        }
    }
}
