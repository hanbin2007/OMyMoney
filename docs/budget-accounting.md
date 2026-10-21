# Local Budget Rules

Monthly and yearly budgets are independent plans for a book, in that book's base currency and timezone. The period starts at local midnight and ends before the next period's midnight. A zero limit is a valid budget. An absent or deleted plan is not a zero budget.

## Consumption

Budgets use the same economic-fact classifier intended for statistics: expenses and interest expenses consume budget; fees are separate expense facts. Refunds reduce the source category's expense up to the original nominal amount, in chronological refund order. Refund excess is income, not negative expense. Refund effects occur on the refund date. Transfers, credit-card repayments, debt principal, reimbursement advances/recoveries and balance adjustments do not consume budget except for explicitly recorded fees.

Each record's own `excludeBudget` flag applies independently. Excluding an original expense does not automatically exclude a later refund. Negative usage is therefore possible and is displayed rather than clamped. Complete active book history is classified before selecting the report period, so a refund can refer to an earlier-period expense.

Amounts already in the budget currency are used directly. Otherwise a saved actual settlement in that currency takes priority over current manual rates. Split refund settlement is proportionally allocated with HALF_EVEN rounding and its remainder assigned to surplus income. If neither nominal nor actual currency matches, an explicit directed manual rate is required. The report displays the dates of fallback rates used. Missing rates make usage, remaining and daily allowance unavailable, never zero. Bills and their historical postings are not rewritten.

## Category Limits

A parent category includes its children. A child limit must fit within a configured parent limit, and the sum of children must not exceed that parent. Only non-overlapping allocations count against the total: a parent with its own limit replaces its children in that sum. Categories without a configured parent limit contribute their own amounts. Editing a category limit never increases the total automatically.

Budgeted category kind/parent and a budgeted book's currency cannot be changed while a live affected plan exists. Category names and archival remain editable; existing archived limits can be retained, but new limits cannot select an archived category. Copies omit archived categories.

## Remaining And Copying

Remaining equals the unchanged limit minus consumption. Overspending is shown as a positive overage with an explicit label. Derived sums use BigInteger, while saved limits remain exact Money values. Daily allowance is nonnegative remaining divided down by remaining calendar days, including today. A future period uses its full day count; an ended period has no daily allowance.

Opening the current month/year can copy the immediately preceding same-kind plan only when no row exists for the current period. A persisted deletion marker prevents automatic resurrection, including after backup/restore and process restart. Explicit copying requires confirmation and replaces the selected period's entire plan. Changes to the predecessor do not update an existing copy.

The home summary is always the current month in the selected book's timezone, uses the same calculator and source flows as the budget page, and is hidden while bill filters are active. It is not a subtotal of currently visible bill rows.

## Evidence Boundaries

The original APK's static Budget and BudgetSetNew models expose monthly/yearly totals, parent/child allocations and a deleted marker. Its remote calculation and apparent automatic total growth are not reused. These independent local rules are authoritative. Core BudgetCalculatorTest and data BudgetCopyTest cover arithmetic, exclusions, month-end/cross-year boundaries, hierarchy, exact FX values, copy behavior, rollback and backup deletion markers. BudgetFlowTest exercises native creation, editing, category validation, activity recreation, home consistency and explicit copying. Physical-device and accessibility-service acceptance remain separate final gates.
