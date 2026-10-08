package demo

class Billing {
    val taxRate = 20

    fun discountRate(amount: Int): Int = if (amount > 100) 10 else 0

    /** Total with the discount applied. */
    fun total(amount: Int): Int {
        val discount = discountRate(amount)
        return amount - discount
    }

    fun tax(amount: Int): Int = amount * taxRate / 100

    fun label(amount: Int): String = "total: " + amount
}
