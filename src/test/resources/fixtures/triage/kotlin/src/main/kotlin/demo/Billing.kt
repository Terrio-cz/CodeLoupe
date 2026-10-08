package demo

class Billing {
    /** Total with the discount applied. */
    fun total(amount: Int): Int {
        val discount = discountRate(amount)
        return amount - discount
    }

    fun net(amount: Int): Int = amount - discountRate(amount)

    fun tax(amount: Int): Int = amount * taxRate / 100

    fun label(amount: Int): String {
        val text: String = amount
        return "total: " + text
    }
}
