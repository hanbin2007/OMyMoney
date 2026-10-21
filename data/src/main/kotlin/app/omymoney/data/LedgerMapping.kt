package app.omymoney.data

import app.omymoney.core.model.*
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.encodeToString

internal fun BookEntity.model() = Book(id, name, CurrencySpec(currencyCode, currencyScale), ZoneId.of(zoneId), sortOrder, archived)
internal fun Book.entity() = BookEntity(id, name, currency.code, currency.scale, zoneId.id, sortOrder, archived)
internal fun CategoryEntity.model() = Category(id, bookId, name, BillKind.valueOf(kind), parentId, sortOrder, archived)
internal fun Category.entity() = CategoryEntity(id, bookId, name, kind.name, parentId, sortOrder, archived)
internal fun AccountEntity.model(): AssetAccount {
    val currency = CurrencySpec(currencyCode, currencyScale)
    return AssetAccount(id, name, currency, Money(openingMinor, currency), Money(balanceMinor, currency),
        AccountKind.valueOf(kind), groupName, sortOrder, archived, cardLastFour, LedgerJson.format.decodeFromString(aliasesJson),
        creditTermsJson?.let { LedgerJson.format.decodeFromString<CreditTerms>(it) })
}
internal fun AssetAccount.entity() = AccountEntity(id, name, currency.code, currency.scale, openingBalance.minor,
    balance.minor, kind.name, group, sortOrder, archived, cardLastFour, LedgerJson.format.encodeToString(aliases),
    creditTerms?.let { LedgerJson.format.encodeToString(it) })

internal fun TransactionDraft.recorded(id: String, revision: Long, savedAt: Instant): Bill = Bill(
    id = id, revision = revision, savedAt = savedAt, submissionId = submissionId, bookId = bookId,
    kind = requireNotNull(kind), amount = requireNotNull(amount), occurredAt = requireNotNull(occurredAt),
    timeSource = timeSource, zoneId = zoneId, fromAccountId = fromAccountId, toAccountId = toAccountId,
    categoryId = categoryId, note = note, tags = tags, attachments = attachments,
    recognizedAt = recognizedAt, linkedBillId = linkedBillId, fee = fee, exchange = exchange,
    excludeStats = excludeStats, excludeBudget = excludeBudget, source = source,
    sourcePackage = sourcePackage, ruleId = ruleId, ruleVersion = ruleVersion, payment = payment,
)

internal fun Bill.entity(deletedAt: Instant? = null): BillEntity = BillEntity(
    id = id, revision = revision, bookId = bookId, kind = kind.name,
    occurredAtSeconds = occurredAt.epochSecond, occurredAtNanos = occurredAt.nano,
    fromAccountId = fromAccountId, toAccountId = toAccountId, categoryId = categoryId,
    note = note, payloadJson = LedgerJson.encodeBill(this), deletedAt = deletedAt?.toString(),
)

internal fun Posting.entity(billId: String, position: Int) = PostingEntity(
    billId, position, accountId, amount.minor, amount.currency.code, amount.currency.scale,
)

internal fun PostingEntity.model() = Posting(accountId, Money(minor, CurrencySpec(currencyCode, currencyScale)))
internal fun DeletionEntity.token() = UndoToken(id, billId, Instant.parse(expiresAt))

internal fun ExchangeRateEntity.model() = ManualExchangeRate(CurrencySpec(sourceCode, sourceScale),
    CurrencySpec(targetCode, targetScale), rate, java.time.LocalDate.parse(asOf))
internal fun ManualExchangeRate.entity() = ExchangeRateEntity(source.code, source.scale, target.code, target.scale, rate, asOf.toString())
