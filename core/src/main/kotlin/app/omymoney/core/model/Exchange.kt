@file:kotlinx.serialization.UseSerializers(LocalDateSerializer::class)

package app.omymoney.core.model

import java.time.LocalDate
import kotlinx.serialization.Serializable

@Serializable
data class ExchangeSnapshot(val accountId: String, val amount: Money, val rate: String, val asOf: LocalDate)

@Serializable
data class ManualExchangeRate(val source: CurrencySpec, val target: CurrencySpec, val rate: String, val asOf: LocalDate)

data class AssetValuation(val total: Money?, val missingCurrencies: Set<CurrencySpec>, val ratesUsed: List<ManualExchangeRate>)
