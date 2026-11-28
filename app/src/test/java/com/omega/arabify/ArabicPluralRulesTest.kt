package com.omega.arabify

import com.omega.arabify.ArabicPluralRules.Category
import org.junit.Assert.*
import org.junit.Test

class ArabicPluralRulesTest {
    @Test
    fun cldrCategories() {
        assertEquals(Category.ZERO, ArabicPluralRules.category(0))
        assertEquals(Category.ONE, ArabicPluralRules.category(1))
        assertEquals(Category.TWO, ArabicPluralRules.category(2))
        assertEquals(Category.FEW, ArabicPluralRules.category(3))
        assertEquals(Category.FEW, ArabicPluralRules.category(10))
        assertEquals(Category.MANY, ArabicPluralRules.category(11))
        assertEquals(Category.MANY, ArabicPluralRules.category(99))
        assertEquals(Category.OTHER, ArabicPluralRules.category(100))
        assertEquals(Category.OTHER, ArabicPluralRules.category(102)) // CLDR: two is n=2 exactly
        assertEquals(Category.FEW, ArabicPluralRules.category(103))
        assertEquals(Category.OTHER, ArabicPluralRules.category(200))
    }

    @Test
    fun gettextIndicesFollowCldrOrder() {
        assertEquals(0, ArabicPluralRules.gettextIndex(Category.ZERO))
        assertEquals(1, ArabicPluralRules.gettextIndex(Category.ONE))
        assertEquals(2, ArabicPluralRules.gettextIndex(Category.TWO))
        assertEquals(3, ArabicPluralRules.gettextIndex(Category.FEW))
        assertEquals(4, ArabicPluralRules.gettextIndex(Category.MANY))
        assertEquals(5, ArabicPluralRules.gettextIndex(Category.OTHER))
    }

    @Test
    fun textPickIsSingularOnlyForOne() {
        assertEquals("كتاب", ArabicPluralRules.pickText(Category.ONE, "كتاب", "كتب"))
        assertEquals("كتب", ArabicPluralRules.pickText(Category.FEW, "كتاب", "كتب"))
        assertEquals("كتب", ArabicPluralRules.pickText(Category.ZERO, "كتاب", "كتب"))
    }
}
