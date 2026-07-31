package com.lifeops.briefing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Thorough coverage of `LifeScript.kt` -- see that file's kdoc for the
 * grammar and design decisions this exercises. Organized by: arithmetic,
 * comparison, logical, ternary, precedence, variable resolution,
 * quantifiers, built-in functions, parse errors, and realistic end-to-end
 * window-condition expressions.
 */
class LifeScriptTest {

    private fun eval(expr: String, context: Map<String, Any> = emptyMap(), sets: Map<String, List<Map<String, Any>>> = emptyMap()): Any =
        evaluate(expr, context, sets)

    // -----------------------------------------------------------------
    // Arithmetic
    // -----------------------------------------------------------------

    @Test
    fun arithmetic_addition() {
        assertEquals(5.0, eval("2 + 3"))
    }

    @Test
    fun arithmetic_subtraction() {
        assertEquals(-1.0, eval("2 - 3"))
    }

    @Test
    fun arithmetic_multiplication() {
        assertEquals(6.0, eval("2 * 3"))
    }

    @Test
    fun arithmetic_division() {
        assertEquals(2.5, eval("5 / 2"))
    }

    @Test
    fun arithmetic_modulo() {
        assertEquals(1.0, eval("5 % 2"))
    }

    @Test
    fun arithmetic_decimalLiteral() {
        assertEquals(3.5, eval("1.5 + 2"))
    }

    @Test
    fun arithmetic_unaryMinus() {
        assertEquals(-5.0, eval("-5"))
        assertEquals(3.0, eval("5 + -2"))
    }

    @Test
    fun arithmetic_leftAssociative() {
        // (10 - 3) - 2 = 5, not 10 - (3 - 2) = 9
        assertEquals(5.0, eval("10 - 3 - 2"))
    }

    @Test
    fun arithmetic_onNonNumberIsTypeError() {
        assertThrowsEvaluate("'+' requires a number") { eval("true + 1") }
    }

    // -----------------------------------------------------------------
    // Comparison
    // -----------------------------------------------------------------

    @Test
    fun comparison_operators() {
        assertEquals(true, eval("3 > 2"))
        assertEquals(false, eval("3 < 2"))
        assertEquals(true, eval("3 >= 3"))
        assertEquals(true, eval("3 <= 3"))
        assertEquals(true, eval("3 == 3"))
        assertEquals(true, eval("3 != 4"))
    }

    @Test
    fun comparison_equalityAcrossIntAndDoubleContextValues() {
        // Context values passed as Kotlin Int must still compare equal to
        // a decimal-looking literal -- this is why numbers are coerced
        // uniformly to Double at evaluate time (see LifeScript's kdoc).
        assertEquals(true, eval("daysSinceLast == 4", mapOf("daysSinceLast" to 4)))
    }

    @Test
    fun comparison_stringEquality() {
        assertEquals(true, eval("status == \"active\"", mapOf("status" to "active")))
        assertEquals(false, eval("status == \"active\"", mapOf("status" to "inactive")))
    }

    @Test
    fun comparison_onBooleanIsTypeError() {
        assertThrowsEvaluate("'>' requires a number") { eval("true > false") }
    }

    // -----------------------------------------------------------------
    // Logical
    // -----------------------------------------------------------------

    @Test
    fun logical_and() {
        assertEquals(true, eval("true && true"))
        assertEquals(false, eval("true && false"))
    }

    @Test
    fun logical_or() {
        assertEquals(true, eval("false || true"))
        assertEquals(false, eval("false || false"))
    }

    @Test
    fun logical_not() {
        assertEquals(false, eval("!true"))
        assertEquals(true, eval("!false"))
        assertEquals(true, eval("!!true"))
    }

    @Test
    fun logical_onNumbersIsTypeError() {
        // Explicit case named in the design spec: "&&" on two numbers.
        assertThrowsEvaluate("'&&' requires a boolean") { eval("1 && 2") }
    }

    @Test
    fun logical_orShortCircuits_doesNotEvaluateRightSide() {
        // Right side references an undefined variable; if || evaluated it
        // eagerly this would throw. true || X must short-circuit to true.
        assertEquals(true, eval("true || undefinedVar"))
    }

    @Test
    fun logical_andShortCircuits_doesNotEvaluateRightSide() {
        assertEquals(false, eval("false && undefinedVar"))
    }

    // -----------------------------------------------------------------
    // Ternary
    // -----------------------------------------------------------------

    @Test
    fun ternary_trueBranch() {
        assertEquals(1.0, eval("true ? 1 : 2"))
    }

    @Test
    fun ternary_falseBranch() {
        assertEquals(2.0, eval("false ? 1 : 2"))
    }

    @Test
    fun ternary_nestedInFalseBranch() {
        assertEquals(3.0, eval("false ? 1 : true ? 3 : 4"))
    }

    @Test
    fun ternary_conditionMustBeBoolean() {
        assertThrowsEvaluate("'?:' requires a boolean") { eval("1 ? 2 : 3") }
    }

    // -----------------------------------------------------------------
    // Precedence matrix
    // -----------------------------------------------------------------

    @Test
    fun precedence_orBindsLooserThanAnd() {
        // a || b && c must parse as a || (b && c), not (a || b) && c.
        // a=true, b=false, c=false: a||(b&&c) = true||false = true.
        // (a||b)&&c = true&&false = false. These disagree, so this
        // distinguishes the two parses.
        assertEquals(true, eval("a || b && c", mapOf("a" to true, "b" to false, "c" to false)))
    }

    @Test
    fun precedence_andBindsTighterThanOr_secondCase() {
        // a=false, b=true, c=false: a||(b&&c) = false||false = false.
        // (a||b)&&c = true&&false = false. Same result -- add a case where
        // they diverge in the other direction to fully pin down the parse.
        assertEquals(false, eval("a || b && c", mapOf("a" to false, "b" to true, "c" to false)))
        // a=false, b=true, c=true: a||(b&&c) = false||true = true.
        assertEquals(true, eval("a || b && c", mapOf("a" to false, "b" to true, "c" to true)))
    }

    @Test
    fun precedence_andBindsTighterThanComparisonIsWrong_comparisonBindsTighter() {
        // Comparison binds tighter than &&: (2 > 1) && (3 > 2) = true.
        // If && bound tighter this would try `1 && 3` first, a type error.
        assertEquals(true, eval("2 > 1 && 3 > 2"))
    }

    @Test
    fun precedence_comparisonBindsLooserThanAdditive() {
        // 1 + 2 > 2 must parse as (1 + 2) > 2 = true, not 1 + (2 > 2) which
        // would be a type error (+ on a number and a boolean).
        assertEquals(true, eval("1 + 2 > 2"))
    }

    @Test
    fun precedence_additiveBindsLooserThanMultiplicative() {
        // 2 + 3 * 4 = 2 + 12 = 14, not (2+3)*4 = 20.
        assertEquals(14.0, eval("2 + 3 * 4"))
    }

    @Test
    fun precedence_multiplicativeBindsLooserThanUnary() {
        // -2 * 3 = -6 (unary minus binds tighter than *).
        assertEquals(-6.0, eval("-2 * 3"))
    }

    @Test
    fun precedence_notBindsTighterThanAnd() {
        // !a && b must parse as (!a) && b, not !(a && b).
        // a=true, b=false: (!a)&&b = false&&false = false.
        // !(a&&b) = !(false) = true. These disagree.
        assertEquals(false, eval("!a && b", mapOf("a" to true, "b" to false)))
    }

    @Test
    fun precedence_ternaryLowestOfAll() {
        // a || b ? 1 : 2 must parse as (a || b) ? 1 : 2, not a || (b ? 1 : 2)
        // (which would be a type error mixing boolean || number).
        assertEquals(1.0, eval("a || b ? 1 : 2", mapOf("a" to true, "b" to false)))
    }

    @Test
    fun precedence_parenthesesOverridePrecedence() {
        assertEquals(20.0, eval("(2 + 3) * 4"))
    }

    // -----------------------------------------------------------------
    // Variable resolution
    // -----------------------------------------------------------------

    @Test
    fun variables_resolveFromContext() {
        assertEquals(true, eval("sleepOk", mapOf("sleepOk" to true)))
        assertEquals(4.0, eval("daysSinceLast", mapOf("daysSinceLast" to 4)))
    }

    @Test
    fun variables_undefinedThrowsAtEvaluateTime() {
        assertThrowsEvaluate("Undefined variable 'nope'") { eval("nope") }
    }

    @Test
    fun variables_undefinedInsideLargerExpressionThrows() {
        assertThrowsEvaluate("Undefined variable 'missing'") {
            eval("sleepOk && missing", mapOf("sleepOk" to true))
        }
    }

    // -----------------------------------------------------------------
    // Quantifiers: all() / any()
    // -----------------------------------------------------------------

    private val routineSets = mapOf(
        "routines" to listOf(
            mapOf("name" to "gym", "caughtUp" to true, "isImportant" to true),
            mapOf("name" to "chores", "caughtUp" to true, "isImportant" to false),
            mapOf("name" to "social", "caughtUp" to false, "isImportant" to true),
        )
    )

    @Test
    fun quantifier_all_trueWhenEveryElementMatches() {
        assertEquals(false, eval("all(routines, r => r.caughtUp)", sets = routineSets))
    }

    @Test
    fun quantifier_all_trueCase() {
        val allCaughtUp = mapOf(
            "routines" to listOf(
                mapOf("caughtUp" to true),
                mapOf("caughtUp" to true),
            )
        )
        assertEquals(true, eval("all(routines, r => r.caughtUp)", sets = allCaughtUp))
    }

    @Test
    fun quantifier_any_trueWhenAtLeastOneMatches() {
        assertEquals(true, eval("any(routines, r => !r.caughtUp)", sets = routineSets))
    }

    @Test
    fun quantifier_any_falseWhenNoneMatch() {
        assertEquals(false, eval("any(routines, r => r.name == \"nonexistent\")", sets = routineSets))
    }

    @Test
    fun quantifier_all_emptySetIsVacuouslyTrue() {
        assertEquals(true, eval("all(routines, r => r.caughtUp)", sets = mapOf("routines" to emptyList())))
    }

    @Test
    fun quantifier_any_emptySetIsFalse() {
        assertEquals(false, eval("any(routines, r => r.caughtUp)", sets = mapOf("routines" to emptyList())))
    }

    @Test
    fun quantifier_predicateCanReferenceOuterContext() {
        // "predicate ... evaluated against ITS OWN element's variable-context
        // map merged with the outer context" -- a bare identifier not on the
        // element should fall back to the outer context.
        assertEquals(
            true,
            eval(
                "all(routines, r => sleepOk && r.caughtUp)",
                context = mapOf("sleepOk" to true),
                sets = mapOf(
                    "routines" to listOf(mapOf("caughtUp" to true), mapOf("caughtUp" to true))
                ),
            )
        )
    }

    @Test
    fun quantifier_filteredSetSelection_matchesDocExample() {
        // Mirrors the cross-routine-gating doc's illustrative shape:
        // all(routine => routine.is_important == true).caught_up -- i.e.
        // quantify only over the important routines.
        val important = eval(
            "all(routines, r => !r.isImportant || r.caughtUp)",
            sets = routineSets,
        )
        // social is important and not caughtUp, so this should be false.
        assertEquals(false, important)
    }

    @Test
    fun quantifier_unknownSetNameThrowsAtEvaluateTime() {
        assertThrowsEvaluate("Unknown routine set 'missingSet'") {
            eval("all(missingSet, r => r.caughtUp)")
        }
    }

    @Test
    fun quantifier_memberAccessOnMissingFieldThrows() {
        assertThrowsEvaluate("Field 'nope' not found in scope") {
            eval("all(routines, r => r.nope)", sets = routineSets)
        }
    }

    @Test
    fun quantifier_memberAccessOnNonMapThrowsTypeError() {
        assertThrowsEvaluate("Cannot access '.field' on a non-object value") {
            eval("(1).field")
        }
    }

    // -----------------------------------------------------------------
    // Built-in functions
    // -----------------------------------------------------------------

    @Test
    fun builtin_min() {
        assertEquals(2.0, eval("min(2, 5)"))
        assertEquals(2.0, eval("min(5, 2)"))
    }

    @Test
    fun builtin_max() {
        assertEquals(5.0, eval("max(2, 5)"))
    }

    @Test
    fun builtin_abs() {
        assertEquals(5.0, eval("abs(-5)"))
        assertEquals(5.0, eval("abs(5)"))
    }

    @Test
    fun builtin_composedWithOtherOperators() {
        assertEquals(true, eval("max(1, 2) == 2"))
    }

    @Test
    fun builtin_unknownFunctionThrowsAtEvaluateTime() {
        assertThrowsEvaluate("Unknown function 'nope'") { eval("nope(1)") }
    }

    @Test
    fun builtin_wrongArityThrowsAtEvaluateTime() {
        assertThrowsEvaluate("'abs' expects 1 argument(s), got 2") { eval("abs(1, 2)") }
    }

    // -----------------------------------------------------------------
    // Malformed syntax -> parse-time errors
    // -----------------------------------------------------------------

    @Test
    fun parseError_unclosedParen() {
        assertThrowsParse { LifeScript.parse("(1 + 2") }
    }

    @Test
    fun parseError_danglingOperator() {
        assertThrowsParse { LifeScript.parse("1 +") }
    }

    @Test
    fun parseError_missingTernaryColon() {
        assertThrowsParse { LifeScript.parse("true ? 1") }
    }

    @Test
    fun parseError_emptyExpression() {
        assertThrowsParse { LifeScript.parse("") }
    }

    @Test
    fun parseError_trailingGarbage() {
        assertThrowsParse { LifeScript.parse("1 + 2 3") }
    }

    @Test
    fun parseError_singleAmpersand() {
        assertThrowsParse { LifeScript.parse("a & b") }
    }

    @Test
    fun parseError_quantifierMissingArrow() {
        assertThrowsParse { LifeScript.parse("all(routines, r r.caughtUp)") }
    }

    @Test
    fun parseError_quantifierMissingSetName() {
        assertThrowsParse { LifeScript.parse("all(r => r.caughtUp)") }
    }

    @Test
    fun parseError_unterminatedString() {
        assertThrowsParse { LifeScript.parse("x == \"unterminated") }
    }

    // -----------------------------------------------------------------
    // Parse-once, evaluate-repeatedly
    // -----------------------------------------------------------------

    @Test
    fun parseOnce_evaluateRepeatedlyWithDifferentContexts() {
        val expr = LifeScript.parse("sleepOk && daysSinceLast >= 3")
        assertEquals(true, expr.evaluate(mapOf("sleepOk" to true, "daysSinceLast" to 5)))
        assertEquals(false, expr.evaluate(mapOf("sleepOk" to true, "daysSinceLast" to 1)))
        assertEquals(false, expr.evaluate(mapOf("sleepOk" to false, "daysSinceLast" to 5)))
    }

    // -----------------------------------------------------------------
    // Realistic end-to-end window-condition expressions
    // -----------------------------------------------------------------

    @Test
    fun endToEnd_eveningWindowCondition() {
        val expr = "!deadlineHeavy && !eveningBlocked && !gymBlocked && !dayAfterShow"

        assertTrue(
            eval(
                expr,
                mapOf(
                    "deadlineHeavy" to false,
                    "eveningBlocked" to false,
                    "gymBlocked" to false,
                    "dayAfterShow" to false,
                )
            ) as Boolean
        )

        assertFalse(
            eval(
                expr,
                mapOf(
                    "deadlineHeavy" to true,
                    "eveningBlocked" to false,
                    "gymBlocked" to false,
                    "dayAfterShow" to false,
                )
            ) as Boolean
        )
    }

    @Test
    fun endToEnd_morningWindowCondition() {
        val expr = "allowMorning && sleepOk && !priorNightBlocked && !gymBlocked && !dayAfterShow"

        assertTrue(
            eval(
                expr,
                mapOf(
                    "allowMorning" to true,
                    "sleepOk" to true,
                    "priorNightBlocked" to false,
                    "gymBlocked" to false,
                    "dayAfterShow" to false,
                )
            ) as Boolean
        )

        assertFalse(
            eval(
                expr,
                mapOf(
                    "allowMorning" to true,
                    "sleepOk" to false,
                    "priorNightBlocked" to false,
                    "gymBlocked" to false,
                    "dayAfterShow" to false,
                )
            ) as Boolean
        )
    }

    @Test
    fun endToEnd_crossRoutineGateSuppressesFriendHangoutWhileGymBehind() {
        // Mirrors the cross-routine-gating doc's motivating example:
        // don't propose a friend hangout while gym is behind target.
        val expr = "friendsCaughtUp && all(importantRoutines, r => r.caughtUp)"
        val sets = mapOf(
            "importantRoutines" to listOf(
                mapOf("name" to "gym", "caughtUp" to false),
            )
        )
        assertEquals(
            false,
            eval(expr, mapOf("friendsCaughtUp" to true), sets)
        )
    }

    // -----------------------------------------------------------------
    // Nested quantifiers: bare-identifier shadowing
    //
    // Regression coverage for a real bug: Scope.resolve walked the active
    // quantifier bindings in map-insertion order, which is
    // outermost-to-innermost, so an OUTER element's field won over the
    // inner element's field of the same name -- backwards from every
    // lexically-scoped language and from what LifeScript's own kdoc
    // describes ("its own element's map first").
    // -----------------------------------------------------------------

    private val nestedShadowSets = mapOf(
        "outer" to listOf(mapOf("flag" to true, "onlyOuter" to true)),
        "inner" to listOf(mapOf("flag" to false, "onlyInner" to true)),
    )

    @Test
    fun nestedQuantifier_innerElementFieldShadowsOuterElementField() {
        // Both elements have `flag`; the bare `flag` inside the INNER
        // predicate must be the inner element's false, not the outer's true.
        assertEquals(
            false,
            eval("all(outer, o => any(inner, i => flag))", sets = nestedShadowSets),
        )
    }

    @Test
    fun nestedQuantifier_innerShadowingHoldsInTheOtherDirectionToo() {
        // Same shape with the truth values swapped, so a hardcoded `false`
        // (or the old outer-wins behavior) can't accidentally pass.
        val sets = mapOf(
            "outer" to listOf(mapOf("flag" to false)),
            "inner" to listOf(mapOf("flag" to true)),
        )
        assertEquals(true, eval("any(outer, o => all(inner, i => flag))", sets = sets))
    }

    @Test
    fun nestedQuantifier_outerElementFieldStillVisibleWhenNotShadowed() {
        // Shadowing must not sever the scope chain: a field only the outer
        // element has is still reachable bare from the inner predicate.
        assertEquals(
            true,
            eval("all(outer, o => all(inner, i => onlyOuter && onlyInner))", sets = nestedShadowSets),
        )
    }

    @Test
    fun nestedQuantifier_qualifiedAccessReachesPastTheShadow() {
        // `o.flag` explicitly names the outer binding, so it is unaffected
        // by the inner element shadowing the bare name.
        assertEquals(
            true,
            eval("all(outer, o => all(inner, i => o.flag && !i.flag))", sets = nestedShadowSets),
        )
    }

    @Test
    fun nestedQuantifier_elementFieldStillShadowsOuterContext() {
        // The pre-existing rule (element beats outer context) is unchanged.
        assertEquals(
            true,
            eval(
                "all(s, r => flag)",
                context = mapOf("flag" to false),
                sets = mapOf("s" to listOf(mapOf("flag" to true))),
            ),
        )
    }

    // -----------------------------------------------------------------
    // Numeric coercion across the Java/JSON boundary
    //
    // Regression coverage for a real bug: the numeric check was a
    // hand-listed `is Double || is Float || is Int || is Long || is Short`,
    // so any other Number (Byte, BigDecimal, a JSON decoder's own lazy
    // Number subclass) made `==` fall through to Any.equals and return
    // *false silently* -- the one outcome LifeScript's error-handling
    // contract promises never happens.
    // -----------------------------------------------------------------

    @Test
    fun numeric_byteContextValueComparesAsANumber() {
        assertEquals(true, eval("b == 3", mapOf("b" to 3.toByte())))
        assertEquals(false, eval("b == 4", mapOf("b" to 3.toByte())))
        assertEquals(4.0, eval("b + 1", mapOf("b" to 3.toByte())))
    }

    @Test
    fun numeric_bigDecimalContextValueComparesAndComputesAsANumber() {
        assertEquals(true, eval("b == 3", mapOf("b" to java.math.BigDecimal("3"))))
        assertEquals(4.0, eval("b + 1", mapOf("b" to java.math.BigDecimal("3"))))
        assertEquals(true, eval("b > 2.5", mapOf("b" to java.math.BigDecimal("3"))))
    }

    @Test
    fun numeric_bigIntegerContextValueComparesAsANumber() {
        assertEquals(true, eval("b == 3", mapOf("b" to java.math.BigInteger("3"))))
    }

    @Test
    fun numeric_arbitraryNumberSubclassIsTreatedAsANumber() {
        // Stands in for JSON libraries' lazily-parsed Number types, which
        // are neither Int nor Double nor any other Kotlin numeric class.
        val lazyThree: Number = object : Number() {
            override fun toByte(): Byte = 3
            override fun toDouble(): Double = 3.0
            override fun toFloat(): Float = 3f
            override fun toInt(): Int = 3
            override fun toLong(): Long = 3L
            override fun toShort(): Short = 3
        }
        assertEquals(true, eval("b == 3", mapOf("b" to lazyThree)))
        assertEquals(4.0, eval("b + 1", mapOf("b" to lazyThree)))
    }

    @Test
    fun numeric_unsignedContextValueComparesAsANumber() {
        // Kotlin's unsigned types are value classes, NOT java.lang.Number.
        assertEquals(true, eval("b == 3", mapOf("b" to 3u)))
        assertEquals(true, eval("b == 3", mapOf("b" to 3uL)))
    }

    @Test
    fun numeric_elementFieldCoercionMatchesContextCoercion() {
        assertEquals(
            true,
            eval("all(s, r => r.n == 3)", sets = mapOf("s" to listOf(mapOf("n" to 3.toByte())))),
        )
    }

    @Test
    fun numeric_nonNumbersStillCompareByEquality() {
        // The widened numeric check must not make non-numbers numeric.
        assertEquals(true, eval("s == \"x\"", mapOf("s" to "x")))
        assertEquals(false, eval("s == 1", mapOf("s" to "x")))
        assertEquals(false, eval("b == 1", mapOf("b" to true)))
    }

    // -----------------------------------------------------------------
    // null values smuggled through a Map<String, Any>
    // -----------------------------------------------------------------

    @Suppress("UNCHECKED_CAST")
    private fun nullableContext(vararg pairs: Pair<String, Any?>): Map<String, Any> =
        mapOf(*pairs) as Map<String, Any>

    @Test
    fun nullContextValueRaisesTypedErrorInsteadOfEscaping() {
        // evaluate() is declared to return non-null Any; a null context
        // value used to be handed straight back through it.
        assertThrowsEvaluate("Variable 'x' is null") {
            eval("x", nullableContext("x" to null))
        }
    }

    @Test
    fun nullElementFieldRaisesTypedErrorInsteadOfNpe() {
        // This one used to surface as a raw NullPointerException out of the
        // error-message formatter, not as a LifeScriptEvaluateException.
        assertThrowsEvaluate("Field 'x' is null") {
            eval("all(s, r => r.x)", sets = mapOf("s" to listOf(nullableContext("x" to null))))
        }
    }

    // -----------------------------------------------------------------
    // Error-message quality
    // -----------------------------------------------------------------

    @Test
    fun unknownSet_withNoSetsProvidedSaysSo() {
        assertThrowsEvaluate("no sets were provided") {
            eval("all(routines, r => r.caughtUp)")
        }
    }

    @Test
    fun unknownSet_withOtherSetsListsTheAvailableOnes() {
        assertThrowsEvaluate("available sets: importantRoutines") {
            eval("all(routines, r => r.caughtUp)", sets = mapOf("importantRoutines" to emptyList()))
        }
    }

    @Test
    fun parseError_emptyStringLiteralIsNotReportedAsEndOfInput() {
        // An empty string literal is a real token with empty text; keying
        // the error message off text emptiness mislabeled it as EOF.
        try {
            LifeScript.parse("all(\"\", r => r.x)")
            fail("Expected LifeScriptParseException")
        } catch (e: LifeScriptParseException) {
            assertFalse(
                "Message should not claim end-of-input, was '${e.message}'",
                e.message!!.contains("<end of input>"),
            )
        }
    }

    @Test
    fun parseError_genuineEndOfInputIsStillReportedAsSuch() {
        try {
            LifeScript.parse("1 +")
            fail("Expected LifeScriptParseException")
        } catch (e: LifeScriptParseException) {
            assertTrue(
                "Message should mention end-of-input, was '${e.message}'",
                e.message!!.contains("<end of input>"),
            )
        }
    }

    // -----------------------------------------------------------------
    // Lexer / parser edge cases that were previously untested
    // -----------------------------------------------------------------

    @Test
    fun edge_chainedMemberAccess() {
        assertEquals(
            true,
            eval(
                "all(s, r => r.inner.deep)",
                sets = mapOf("s" to listOf(mapOf("inner" to mapOf("deep" to true)))),
            ),
        )
    }

    @Test
    fun edge_deeplyNestedParentheses() {
        assertEquals(3.0, eval("((((((((((1 + 2))))))))))"))
    }

    @Test
    fun edge_unicodeInStringLiterals() {
        assertEquals(true, eval("x == \"café 🎉\"", mapOf("x" to "café 🎉")))
    }

    @Test
    fun edge_escapedQuoteAndBackslashInStringLiterals() {
        assertEquals(true, eval("x == \"a\\\"b\"", mapOf("x" to "a\"b")))
        assertEquals(true, eval("x == \"a\\\\b\"", mapOf("x" to "a\\b")))
    }

    @Test
    fun edge_emptyStringLiteral() {
        assertEquals(true, eval("x == \"\"", mapOf("x" to "")))
    }

    @Test
    fun edge_whitespaceOnlyExpressionIsAParseError() {
        assertThrowsParse { LifeScript.parse("   ") }
        assertThrowsParse { LifeScript.parse("\t\n ") }
    }

    @Test
    fun edge_veryLongIdentifier() {
        val name = "a".repeat(500)
        assertEquals(true, eval(name, mapOf(name to true)))
    }

    @Test
    fun edge_identifierWithDigitsAndUnderscores() {
        assertEquals(true, eval("_has_tag2", mapOf("_has_tag2" to true)))
    }

    @Test
    fun edge_identifierWithAKeywordPrefixIsNotAKeyword() {
        assertEquals(true, eval("trueish", mapOf("trueish" to true)))
        assertEquals(true, eval("falsey", mapOf("falsey" to true)))
    }

    @Test
    fun edge_malformedNumbersAreParseErrors() {
        assertThrowsParse { LifeScript.parse("1.2.3") }
        assertThrowsParse { LifeScript.parse("1.") }
        assertThrowsParse { LifeScript.parse(".5") }
    }

    @Test
    fun edge_unaryMinusAppliesToMemberAccess() {
        assertEquals(
            true,
            eval("all(s, r => -r.n == -3)", sets = mapOf("s" to listOf(mapOf("n" to 3)))),
        )
    }

    @Test
    fun edge_doubleUnaryMinus() {
        assertEquals(5.0, eval("--5"))
    }

    @Test
    fun edge_nestedTernaryInTrueBranch() {
        assertEquals(2.0, eval("true ? false ? 1 : 2 : 3"))
    }

    @Test
    fun edge_quantifierNameIsUsableAsAPlainVariable() {
        // `all`/`any` are only quantifiers when immediately followed by '('.
        assertEquals(true, eval("all", mapOf("all" to true)))
    }

    @Test
    fun edge_trailingCommaInCallArgsIsAParseError() {
        assertThrowsParse { LifeScript.parse("min(1, 2,)") }
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private fun assertThrowsEvaluate(messageContains: String, block: () -> Unit) {
        try {
            block()
            fail("Expected LifeScriptEvaluateException containing '$messageContains'")
        } catch (e: LifeScriptEvaluateException) {
            assertTrue(
                "Expected message to contain '$messageContains', was '${e.message}'",
                e.message?.contains(messageContains) == true
            )
        }
    }

    private fun assertThrowsParse(block: () -> Unit) {
        try {
            block()
            fail("Expected LifeScriptParseException")
        } catch (e: LifeScriptParseException) {
            // expected
        }
    }
}
