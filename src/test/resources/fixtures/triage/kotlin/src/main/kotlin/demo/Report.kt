package demo

class Report(private val billing: Billing) {
    fun render(amount: Int): String {
        val rate = discountRate(amount)
        return "rate " + rate + " total " + billing.total(amount)
    }

    fun summary(): String = billing.label(10)
}
