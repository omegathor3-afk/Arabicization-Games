package com.omega.arabify

/**
 * CLDR Arabic plural categories (cdlr 44+): zero, one, two, few, many, other.
 * This is the selection algorithm - which category a count falls in - used
 * whenever the app picks a plural form at runtime or writes gettext msgstr
 * slots. It does NOT inflect nouns: real dual/zero-form morphology needs a
 * morphological engine, which the honest coverage matrix lists as out of
 * scope; callers map categories onto the translated texts they have.
 */
internal object ArabicPluralRules {

    enum class Category { ZERO, ONE, TWO, FEW, MANY, OTHER }

    /** CLDR rule: n=0 zero; n=1 one; n=2 two; n%100 3..10 few; 11..99 many; else other. */
    fun category(n: Long): Category {
        val mod = Math.floorMod(n, 100)
        return when {
            n == 0L -> Category.ZERO
            n == 1L -> Category.ONE
            n == 2L -> Category.TWO
            mod in 3..10 -> Category.FEW
            mod in 11..99 -> Category.MANY
            else -> Category.OTHER
        }
    }

    /**
     * Gettext msgstr index for a category under
     * "nplurals=6; plural=(n==0 ? 0 : n==1 ? 1 : n==2 ? 2 : n%100>=3 && n%100<=10 ? 3 : n%100>=11 && n%100<=99 ? 4 : 5)".
     */
    fun gettextIndex(category: Category): Int = when (category) {
        Category.ZERO -> 0
        Category.ONE -> 1
        Category.TWO -> 2
        Category.FEW -> 3
        Category.MANY -> 4
        Category.OTHER -> 5
    }

    /**
     * Which available text a slot gets when we have only singular and plural
     * translations (no inflection): ONE takes the singular; every other
     * category takes the plural. ZERO/TWO would ideally get "لا + اسم" / dual
     * forms - flagged in the report as a known limit, never silently faked.
     */
    fun pickText(category: Category, singular: String, plural: String): String =
        if (category == Category.ONE) singular else plural
}
