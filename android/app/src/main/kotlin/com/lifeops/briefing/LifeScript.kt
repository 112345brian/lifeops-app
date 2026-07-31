package com.lifeops.briefing

/**
 * LifeScript: a small, bounded predicate/expression language for LifeOps's
 * Routine scheduling system, deliberately designed in the spirit of
 * Liftosaur's "Liftoscript" DSL (https://www.liftosaur.com/doc/liftoscript)
 * -- a real, shipped language for workout program scripting. This is the
 * foundational engine everything else in the "cross-routine gating" design
 * (see docs/lifeops_capability_todo.md's "Recurring Items as User-Configured
 * Data" -> "Cross-routine gating" section) will read through: a routine's
 * own window condition, and a gate that reads *other* routines' exposed
 * variables, are both just LifeScript expressions.
 *
 * NOT wired into anything yet -- standalone, unused-so-far, same status as
 * `Routine.kt`'s own port. Nothing calls this file today.
 *
 * ## Why this is safe to be this expressive without becoming Turing-complete
 *
 * The cross-routine-gating doc section draws a load-bearing line: Liftoscript
 * itself has to be Turing-complete-adjacent (loops, assignment) because it
 * *writes* future program state (assigns weights to arbitrary future weeks).
 * LifeScript only ever *reads* named variables to produce one boolean or
 * number -- so it deliberately omits variable assignment, user-defined
 * functions, and open-ended recursion/loops. The only iteration construct is
 * [Kind.ALL]/[Kind.ANY], and it only ever ranges over an already-finite,
 * already-supplied `List<Map<String, Any>>` handed in by the caller at
 * evaluation time -- never a expression-computed or self-referential
 * collection. That mirrors Liftoscript's own actual constraint (`for
 * (var.i in completedReps)`: bounded iteration over a known collection, not
 * general recursion) and keeps LifeScript firmly non-Turing-complete by
 * construction.
 *
 * ## Grammar (concrete syntax this file implements)
 *
 * ```
 * expr        := ternary
 * ternary     := logicalOr ( '?' expr ':' expr )?
 * logicalOr   := logicalAnd ( '||' logicalAnd )*
 * logicalAnd  := comparison ( '&&' comparison )*
 * comparison  := additive ( ('>' | '<' | '>=' | '<=' | '==' | '!=') additive )*
 * additive    := multiplicative ( ('+' | '-') multiplicative )*
 * multiplicative := unary ( ('*' | '/' | '%') unary )*
 * unary       := ('!' | '-') unary | postfix
 * postfix     := primary ( '.' identifier )*
 * primary     := number | 'true' | 'false' | string | quantifier | call
 *              | identifier | '(' expr ')'
 * call        := identifier '(' ( expr ( ',' expr )* )? ')'
 * quantifier  := ( 'all' | 'any' ) '(' identifier ',' identifier '=>' expr ')'
 * ```
 * Precedence lowest-to-highest: ternary, `||`, `&&`, comparison, additive,
 * multiplicative, unary, postfix/primary -- as specified. Binary operators
 * at the same precedence level are left-associative (`comparison` chains
 * left-to-right like every other level here, e.g. `a < b < c` parses as
 * `(a < b) < c` -- which will then fail at evaluate time with a clear type
 * error, since `<` requires two numbers and the left side is now a boolean;
 * this is a deliberate consequence of uniform left-associativity, not a
 * special case).
 *
 * ## Design decisions made where the spec left room for judgment
 *
 * - **Quantifier set-selection syntax**: `all(setName, x => predicate)` /
 *   `any(setName, x => predicate)`. `setName` is a bare identifier naming
 *   one of the caller-supplied `sets` collections (the `sets` parameter of
 *   [LifeScriptExpr.evaluate] / [evaluate]) -- e.g. `all(routines, r =>
 *   r.isImportant == true)`. This was chosen over an implicit/unnamed
 *   single-set model because the cross-routine-gating design explicitly
 *   allows *multiple* named routine sets to exist (not just "the" routine
 *   set), and naming the set explicitly keeps the grammar uniform with
 *   ordinary function-call syntax (`name(args...)`) rather than inventing a
 *   second call shape.
 * - **Bound-variable member access**: `x.fieldName` reads `fieldName` out of
 *   the bound element's own `Map<String, Any>` context (the per-element
 *   entry from the `sets` list the quantifier is iterating). Implemented as
 *   a general postfix `.identifier` on any expression, not special-cased to
 *   quantifier variables, since the only values in this language that are
 *   ever `Map`-shaped are quantifier-bound variables -- member access on
 *   anything else (a plain number/boolean/string) is a clear evaluate-time
 *   type error.
 * - **Predicate scope inside a quantifier**: per the spec ("predicate ...
 *   evaluated against ITS OWN element's variable-context map merged with the
 *   outer context"), a bare identifier inside `x => predicate` resolves
 *   against the element's own map first, falling back to the outer context
 *   -- so `all(routines, r => sleepOk && r.caughtUp)` can reference both the
 *   outer `sleepOk` and the per-element `r.caughtUp` in the same predicate.
 *   The bound variable name itself (`r`) is additionally available as a
 *   whole-map value, solely for `.field` access.
 * - **String literals**: the formal literal list above (integers, decimals,
 *   booleans, identifiers) doesn't mention strings, but section 1 of the
 *   spec says context values can themselves be strings, and the
 *   cross-routine-gating doc's own illustrative example (`has_tag ==
 *   "fitness"`) compares a variable against a string constant. Without a
 *   string literal there would be no way to write that comparison at all,
 *   so double-quoted string literals (`"fitness"`, no escape sequences
 *   beyond `\"` and `\\`) are added as a deliberate, minimal extension.
 * - **Numeric representation**: all numbers (literals and context/element
 *   values) are coerced to `Double` at evaluation time, uniformly. This
 *   avoids a whole class of Int/Double-mismatch bugs (e.g. a caller passing
 *   `daysSinceLast` as a Kotlin `Int` failing to `==` a literal `4`) at the
 *   cost of not preserving integer-vs-decimal identity in results --
 *   acceptable here since every documented use case is a boolean window
 *   condition, not numeric output.
 * - **`all()`/`any()` over an empty set**: vacuous truth, matching the
 *   spec's explicit instruction and Liftoscript/standard convention --
 *   `all()` of nothing is `true`, `any()` of nothing is `false`. The
 *   predicate is never evaluated in the empty case (no element to bind).
 * - **Built-in functions**: `min(a, b)`, `max(a, b)`, `abs(x)` only, per the
 *   spec's minimal starter set. Adding to this list later is expected and
 *   should be a small, local change to [callBuiltin].
 *
 * ## Error handling
 *
 * Malformed syntax raises [LifeScriptParseException] at *parse* time
 * (naming what was expected). An undefined variable reference, an unknown
 * set/function name, or a wrong-type operation (e.g. `&&` on two numbers)
 * raises [LifeScriptEvaluateException] at *evaluate* time. Neither ever
 * silently produces a wrong value or degrades to null/zero -- matching this
 * codebase's existing "fail loud" philosophy (see e.g. `web.py`'s
 * `api_next_tasks` docstring, and `Routine.kt`'s own careful handling).
 *
 * ## Usage
 *
 * Parse once, evaluate repeatedly against different contexts (a routine's
 * window condition is evaluated once per candidate day, so re-parsing the
 * same expression string every time would be wasteful):
 * ```kotlin
 * val expr = LifeScript.parse("sleepOk && !gymBlocked")
 * val dueToday = expr.evaluate(mapOf("sleepOk" to true, "gymBlocked" to false)) as Boolean
 * ```
 * Or, for a one-shot call, [evaluate] does both steps at once.
 */
object LifeScript {
    /** Parses [expression] into a reusable, repeatedly-evaluable [LifeScriptExpr]. */
    fun parse(expression: String): LifeScriptExpr {
        val tokens = Lexer(expression).tokenize()
        val parser = Parser(tokens, expression)
        val expr = parser.parseExpression()
        parser.expectEnd()
        return expr
    }
}

/**
 * Parses and evaluates [expression] in one call. Prefer [LifeScript.parse]
 * plus [LifeScriptExpr.evaluate] when the same expression will be evaluated
 * more than once, to avoid re-parsing on every call.
 */
fun evaluate(
    expression: String,
    context: Map<String, Any> = emptyMap(),
    sets: Map<String, List<Map<String, Any>>> = emptyMap(),
): Any = LifeScript.parse(expression).evaluate(context, sets)

/** Raised at parse time for malformed LifeScript syntax. */
class LifeScriptParseException(message: String) : RuntimeException(message)

/** Raised at evaluate time for an undefined variable, unknown set/function, or type error. */
class LifeScriptEvaluateException(message: String) : RuntimeException(message)

/**
 * Evaluation-time scope: the caller-supplied outer [context] and named
 * [sets], plus [locals] -- the currently-bound quantifier variable(s), each
 * mapped to its element's own `Map<String, Any>`. Not part of the public
 * API; only [LifeScriptExpr] subclasses see this.
 */
internal class Scope(
    val context: Map<String, Any>,
    val sets: Map<String, List<Map<String, Any>>>,
    val locals: Map<String, Map<String, Any>> = emptyMap(),
) {
    /** Resolves a bare identifier, per the quantifier-predicate scoping rule
     * documented on [LifeScript]: locals bind the whole-map value for
     * `.field` access; otherwise the element map of any active local
     * (falling back to [context]) is checked field-by-field, then
     * [context] itself. */
    fun resolve(name: String): Any {
        locals[name]?.let { return it }
        for (elementMap in locals.values) {
            if (elementMap.containsKey(name)) return elementMap.getValue(name)
        }
        if (context.containsKey(name)) return context.getValue(name)
        throw LifeScriptEvaluateException("Undefined variable '$name'")
    }
}

/** Parsed, reusable LifeScript expression tree. See [LifeScript] for the grammar and design notes. */
sealed class LifeScriptExpr {
    internal abstract fun eval(scope: Scope): Any

    /** Evaluates this expression against [context] (and, if it uses
     * quantifiers, the named [sets] of element contexts). May be called
     * repeatedly on the same parsed [LifeScriptExpr] with different
     * contexts. */
    fun evaluate(context: Map<String, Any> = emptyMap(), sets: Map<String, List<Map<String, Any>>> = emptyMap()): Any =
        eval(Scope(context, sets))

    internal data class NumberLiteral(val value: Double) : LifeScriptExpr() {
        override fun eval(scope: Scope): Any = value
    }

    internal data class BoolLiteral(val value: Boolean) : LifeScriptExpr() {
        override fun eval(scope: Scope): Any = value
    }

    internal data class StringLiteral(val value: String) : LifeScriptExpr() {
        override fun eval(scope: Scope): Any = value
    }

    internal data class Identifier(val name: String) : LifeScriptExpr() {
        // Normalize any numeric context/element value to Double here, so
        // *every* numeric result out of this language -- literal or
        // resolved variable -- is uniformly Double (see the "numeric
        // representation" design note on LifeScript).
        override fun eval(scope: Scope): Any = normalizeIfNumber(scope.resolve(name))
    }

    internal data class MemberAccess(val target: LifeScriptExpr, val member: String) : LifeScriptExpr() {
        override fun eval(scope: Scope): Any {
            val value = target.eval(scope)
            val map = value as? Map<*, *>
                ?: throw LifeScriptEvaluateException(
                    "Cannot access '.$member' on a non-object value ($value)"
                )
            if (!map.containsKey(member)) {
                throw LifeScriptEvaluateException("Field '$member' not found in scope")
            }
            @Suppress("UNCHECKED_CAST")
            return normalizeIfNumber((map as Map<String, Any>).getValue(member))
        }
    }

    internal data class UnaryOp(val op: String, val operand: LifeScriptExpr) : LifeScriptExpr() {
        override fun eval(scope: Scope): Any {
            val value = operand.eval(scope)
            return when (op) {
                "!" -> !asBoolean(value, "!")
                "-" -> -asNumber(value, "unary -")
                else -> throw LifeScriptEvaluateException("Unknown unary operator '$op'")
            }
        }
    }

    internal data class BinaryOp(val op: String, val left: LifeScriptExpr, val right: LifeScriptExpr) : LifeScriptExpr() {
        override fun eval(scope: Scope): Any {
            // && and || short-circuit, so their right side must not be
            // evaluated eagerly.
            if (op == "&&") return asBoolean(left.eval(scope), "&&") && asBoolean(right.eval(scope), "&&")
            if (op == "||") return asBoolean(left.eval(scope), "||") || asBoolean(right.eval(scope), "||")

            val l = left.eval(scope)
            val r = right.eval(scope)
            return when (op) {
                "+" -> asNumber(l, "+") + asNumber(r, "+")
                "-" -> asNumber(l, "-") - asNumber(r, "-")
                "*" -> asNumber(l, "*") * asNumber(r, "*")
                "/" -> asNumber(l, "/") / asNumber(r, "/")
                "%" -> asNumber(l, "%") % asNumber(r, "%")
                ">" -> asNumber(l, ">") > asNumber(r, ">")
                "<" -> asNumber(l, "<") < asNumber(r, "<")
                ">=" -> asNumber(l, ">=") >= asNumber(r, ">=")
                "<=" -> asNumber(l, "<=") <= asNumber(r, "<=")
                "==" -> valuesEqual(l, r)
                "!=" -> !valuesEqual(l, r)
                else -> throw LifeScriptEvaluateException("Unknown binary operator '$op'")
            }
        }
    }

    internal data class Ternary(
        val condition: LifeScriptExpr,
        val whenTrue: LifeScriptExpr,
        val whenFalse: LifeScriptExpr,
    ) : LifeScriptExpr() {
        override fun eval(scope: Scope): Any =
            if (asBoolean(condition.eval(scope), "?:")) whenTrue.eval(scope) else whenFalse.eval(scope)
    }

    internal data class Call(val name: String, val args: List<LifeScriptExpr>) : LifeScriptExpr() {
        override fun eval(scope: Scope): Any = callBuiltin(name, args.map { it.eval(scope) })
    }

    internal enum class Kind { ALL, ANY }

    internal data class Quantifier(
        val kind: Kind,
        val setName: String,
        val bindingVar: String,
        val predicate: LifeScriptExpr,
    ) : LifeScriptExpr() {
        override fun eval(scope: Scope): Any {
            val elements = scope.sets[setName]
                ?: throw LifeScriptEvaluateException("Unknown routine set '$setName'")
            return when (kind) {
                // Vacuous truth over an empty set: all() of nothing is
                // true, any() of nothing is false -- see LifeScript's kdoc.
                Kind.ALL -> elements.all { element ->
                    asBoolean(predicate.eval(childScope(scope, element)), "all()")
                }
                Kind.ANY -> elements.any { element ->
                    asBoolean(predicate.eval(childScope(scope, element)), "any()")
                }
            }
        }

        private fun childScope(scope: Scope, element: Map<String, Any>): Scope =
            Scope(scope.context, scope.sets, scope.locals + (bindingVar to element))
    }
}

/** Coerces a resolved value to `Double` if it's any numeric type, leaving
 * booleans/strings/maps untouched -- see the "numeric representation"
 * design note on [LifeScript]. */
private fun normalizeIfNumber(value: Any): Any = when (value) {
    is Double -> value
    is Float -> value.toDouble()
    is Int -> value.toDouble()
    is Long -> value.toDouble()
    is Short -> value.toDouble()
    else -> value
}

private fun asNumber(value: Any, opDescription: String): Double = when (value) {
    is Double -> value
    is Float -> value.toDouble()
    is Int -> value.toDouble()
    is Long -> value.toDouble()
    is Short -> value.toDouble()
    else -> throw LifeScriptEvaluateException("'$opDescription' requires a number, got ${describe(value)}")
}

private fun asBoolean(value: Any, opDescription: String): Boolean = when (value) {
    is Boolean -> value
    else -> throw LifeScriptEvaluateException("'$opDescription' requires a boolean, got ${describe(value)}")
}

private fun valuesEqual(a: Any, b: Any): Boolean {
    val aIsNumber = a is Double || a is Float || a is Int || a is Long || a is Short
    val bIsNumber = b is Double || b is Float || b is Int || b is Long || b is Short
    if (aIsNumber && bIsNumber) return asNumber(a, "==") == asNumber(b, "==")
    return a == b
}

private fun describe(value: Any): String = "${value::class.simpleName}($value)"

private fun callBuiltin(name: String, args: List<Any>): Any = when (name) {
    "min" -> {
        requireArity(name, args, 2)
        kotlin.math.min(asNumber(args[0], "min"), asNumber(args[1], "min"))
    }
    "max" -> {
        requireArity(name, args, 2)
        kotlin.math.max(asNumber(args[0], "max"), asNumber(args[1], "max"))
    }
    "abs" -> {
        requireArity(name, args, 1)
        kotlin.math.abs(asNumber(args[0], "abs"))
    }
    // Intentionally minimal, not exhaustive -- see LifeScript's kdoc.
    // Extend this `when` with new built-ins as the domain needs them.
    else -> throw LifeScriptEvaluateException("Unknown function '$name'")
}

private fun requireArity(name: String, args: List<Any>, expected: Int) {
    if (args.size != expected) {
        throw LifeScriptEvaluateException("'$name' expects $expected argument(s), got ${args.size}")
    }
}

// ---------------------------------------------------------------------
// Lexer
// ---------------------------------------------------------------------

private enum class TokenType {
    NUMBER, STRING, IDENTIFIER, TRUE, FALSE,
    PLUS, MINUS, STAR, SLASH, PERCENT,
    GT, LT, GE, LE, EQ, NE,
    AND_AND, OR_OR, BANG,
    QUESTION, COLON, ARROW,
    LPAREN, RPAREN, COMMA, DOT,
    EOF,
}

private data class Token(val type: TokenType, val text: String, val pos: Int)

private class Lexer(private val input: String) {
    private var i = 0

    fun tokenize(): List<Token> {
        val tokens = mutableListOf<Token>()
        while (true) {
            skipWhitespace()
            if (i >= input.length) {
                tokens += Token(TokenType.EOF, "", i)
                break
            }
            val start = i
            val c = input[i]
            when {
                c.isDigit() -> tokens += lexNumber()
                c == '"' -> tokens += lexString()
                c.isLetter() || c == '_' -> tokens += lexIdentifierOrKeyword()
                else -> tokens += lexOperator(start)
            }
        }
        return tokens
    }

    private fun skipWhitespace() {
        while (i < input.length && input[i].isWhitespace()) i++
    }

    private fun lexNumber(): Token {
        val start = i
        while (i < input.length && input[i].isDigit()) i++
        if (i < input.length && input[i] == '.' && i + 1 < input.length && input[i + 1].isDigit()) {
            i++
            while (i < input.length && input[i].isDigit()) i++
        }
        return Token(TokenType.NUMBER, input.substring(start, i), start)
    }

    private fun lexString(): Token {
        val start = i
        i++ // opening quote
        val sb = StringBuilder()
        while (true) {
            if (i >= input.length) {
                throw LifeScriptParseException("Unterminated string literal starting at position $start")
            }
            val c = input[i]
            if (c == '"') {
                i++
                break
            }
            if (c == '\\' && i + 1 < input.length && (input[i + 1] == '"' || input[i + 1] == '\\')) {
                sb.append(input[i + 1])
                i += 2
                continue
            }
            sb.append(c)
            i++
        }
        return Token(TokenType.STRING, sb.toString(), start)
    }

    private fun lexIdentifierOrKeyword(): Token {
        val start = i
        while (i < input.length && (input[i].isLetterOrDigit() || input[i] == '_')) i++
        val text = input.substring(start, i)
        val type = when (text) {
            "true" -> TokenType.TRUE
            "false" -> TokenType.FALSE
            else -> TokenType.IDENTIFIER
        }
        return Token(type, text, start)
    }

    private fun lexOperator(start: Int): Token {
        return when (input[i]) {
            '+' -> { i++; Token(TokenType.PLUS, "+", start) }
            '-' -> { i++; Token(TokenType.MINUS, "-", start) }
            '*' -> { i++; Token(TokenType.STAR, "*", start) }
            '/' -> { i++; Token(TokenType.SLASH, "/", start) }
            '%' -> { i++; Token(TokenType.PERCENT, "%", start) }
            '(' -> { i++; Token(TokenType.LPAREN, "(", start) }
            ')' -> { i++; Token(TokenType.RPAREN, ")", start) }
            ',' -> { i++; Token(TokenType.COMMA, ",", start) }
            '.' -> { i++; Token(TokenType.DOT, ".", start) }
            '?' -> { i++; Token(TokenType.QUESTION, "?", start) }
            ':' -> { i++; Token(TokenType.COLON, ":", start) }
            '>' -> if (i + 1 < input.length && input[i + 1] == '=') {
                i += 2; Token(TokenType.GE, ">=", start)
            } else {
                i++; Token(TokenType.GT, ">", start)
            }
            '<' -> if (i + 1 < input.length && input[i + 1] == '=') {
                i += 2; Token(TokenType.LE, "<=", start)
            } else {
                i++; Token(TokenType.LT, "<", start)
            }
            '=' -> if (i + 1 < input.length && input[i + 1] == '=') {
                i += 2; Token(TokenType.EQ, "==", start)
            } else if (i + 1 < input.length && input[i + 1] == '>') {
                i += 2; Token(TokenType.ARROW, "=>", start)
            } else {
                throw LifeScriptParseException("Unexpected character '=' at position $start (did you mean '==' or '=>'?)")
            }
            '!' -> if (i + 1 < input.length && input[i + 1] == '=') {
                i += 2; Token(TokenType.NE, "!=", start)
            } else {
                i++; Token(TokenType.BANG, "!", start)
            }
            '&' -> if (i + 1 < input.length && input[i + 1] == '&') {
                i += 2; Token(TokenType.AND_AND, "&&", start)
            } else {
                throw LifeScriptParseException("Unexpected character '&' at position $start (did you mean '&&'?)")
            }
            '|' -> if (i + 1 < input.length && input[i + 1] == '|') {
                i += 2; Token(TokenType.OR_OR, "||", start)
            } else {
                throw LifeScriptParseException("Unexpected character '|' at position $start (did you mean '||'?)")
            }
            else -> throw LifeScriptParseException("Unexpected character '${input[i]}' at position $start")
        }
    }
}

// ---------------------------------------------------------------------
// Parser
// ---------------------------------------------------------------------

private class Parser(private val tokens: List<Token>, private val source: String) {
    private var pos = 0

    private fun peek(): Token = tokens[pos]
    private fun advance(): Token = tokens[pos].also { pos++ }

    private fun check(type: TokenType): Boolean = peek().type == type

    private fun match(type: TokenType): Boolean {
        if (check(type)) {
            advance()
            return true
        }
        return false
    }

    private fun expect(type: TokenType, description: String): Token {
        if (!check(type)) {
            throw LifeScriptParseException(
                "Expected $description at position ${peek().pos}, found '${peek().text.ifEmpty { "<end of input>" }}'"
            )
        }
        return advance()
    }

    fun expectEnd() {
        if (!check(TokenType.EOF)) {
            throw LifeScriptParseException(
                "Unexpected trailing input '${peek().text}' at position ${peek().pos}"
            )
        }
    }

    fun parseExpression(): LifeScriptExpr = parseTernary()

    private fun parseTernary(): LifeScriptExpr {
        val condition = parseLogicalOr()
        if (match(TokenType.QUESTION)) {
            val whenTrue = parseExpression()
            expect(TokenType.COLON, "':' in ternary expression")
            val whenFalse = parseExpression()
            return LifeScriptExpr.Ternary(condition, whenTrue, whenFalse)
        }
        return condition
    }

    private fun parseLogicalOr(): LifeScriptExpr {
        var left = parseLogicalAnd()
        while (match(TokenType.OR_OR)) {
            left = LifeScriptExpr.BinaryOp("||", left, parseLogicalAnd())
        }
        return left
    }

    private fun parseLogicalAnd(): LifeScriptExpr {
        var left = parseComparison()
        while (match(TokenType.AND_AND)) {
            left = LifeScriptExpr.BinaryOp("&&", left, parseComparison())
        }
        return left
    }

    private fun parseComparison(): LifeScriptExpr {
        var left = parseAdditive()
        while (true) {
            val op = when (peek().type) {
                TokenType.GT -> ">"
                TokenType.LT -> "<"
                TokenType.GE -> ">="
                TokenType.LE -> "<="
                TokenType.EQ -> "=="
                TokenType.NE -> "!="
                else -> null
            } ?: break
            advance()
            left = LifeScriptExpr.BinaryOp(op, left, parseAdditive())
        }
        return left
    }

    private fun parseAdditive(): LifeScriptExpr {
        var left = parseMultiplicative()
        while (true) {
            val op = when (peek().type) {
                TokenType.PLUS -> "+"
                TokenType.MINUS -> "-"
                else -> null
            } ?: break
            advance()
            left = LifeScriptExpr.BinaryOp(op, left, parseMultiplicative())
        }
        return left
    }

    private fun parseMultiplicative(): LifeScriptExpr {
        var left = parseUnary()
        while (true) {
            val op = when (peek().type) {
                TokenType.STAR -> "*"
                TokenType.SLASH -> "/"
                TokenType.PERCENT -> "%"
                else -> null
            } ?: break
            advance()
            left = LifeScriptExpr.BinaryOp(op, left, parseUnary())
        }
        return left
    }

    private fun parseUnary(): LifeScriptExpr {
        if (match(TokenType.BANG)) return LifeScriptExpr.UnaryOp("!", parseUnary())
        if (match(TokenType.MINUS)) return LifeScriptExpr.UnaryOp("-", parseUnary())
        return parsePostfix()
    }

    private fun parsePostfix(): LifeScriptExpr {
        var expr = parsePrimary()
        while (match(TokenType.DOT)) {
            val member = expect(TokenType.IDENTIFIER, "field name after '.'")
            expr = LifeScriptExpr.MemberAccess(expr, member.text)
        }
        return expr
    }

    private fun parsePrimary(): LifeScriptExpr {
        val token = peek()
        return when (token.type) {
            TokenType.NUMBER -> {
                advance()
                LifeScriptExpr.NumberLiteral(token.text.toDouble())
            }
            TokenType.STRING -> {
                advance()
                LifeScriptExpr.StringLiteral(token.text)
            }
            TokenType.TRUE -> {
                advance()
                LifeScriptExpr.BoolLiteral(true)
            }
            TokenType.FALSE -> {
                advance()
                LifeScriptExpr.BoolLiteral(false)
            }
            TokenType.LPAREN -> {
                advance()
                val inner = parseExpression()
                expect(TokenType.RPAREN, "')' to close '('")
                inner
            }
            TokenType.IDENTIFIER -> parseIdentifierLed(token)
            else -> throw LifeScriptParseException(
                "Expected an expression at position ${token.pos}, found '${token.text.ifEmpty { "<end of input>" }}'"
            )
        }
    }

    private fun parseIdentifierLed(token: Token): LifeScriptExpr {
        advance()
        if (!check(TokenType.LPAREN)) {
            return LifeScriptExpr.Identifier(token.text)
        }
        advance() // consume '('
        return when (token.text) {
            "all", "any" -> parseQuantifierArgs(token.text)
            else -> parseCallArgs(token.text)
        }
    }

    private fun parseQuantifierArgs(kindName: String): LifeScriptExpr {
        val setNameToken = expect(TokenType.IDENTIFIER, "routine-set name as the first argument to '$kindName('")
        expect(TokenType.COMMA, "',' after the set name in '$kindName('")
        val bindingToken = expect(TokenType.IDENTIFIER, "bound variable name in '$kindName('")
        expect(TokenType.ARROW, "'=>' after the bound variable in '$kindName('")
        val predicate = parseExpression()
        expect(TokenType.RPAREN, "')' to close '$kindName('")
        val kind = if (kindName == "all") LifeScriptExpr.Kind.ALL else LifeScriptExpr.Kind.ANY
        return LifeScriptExpr.Quantifier(kind, setNameToken.text, bindingToken.text, predicate)
    }

    private fun parseCallArgs(name: String): LifeScriptExpr {
        val args = mutableListOf<LifeScriptExpr>()
        if (!check(TokenType.RPAREN)) {
            args += parseExpression()
            while (match(TokenType.COMMA)) {
                args += parseExpression()
            }
        }
        expect(TokenType.RPAREN, "')' to close '$name('")
        return LifeScriptExpr.Call(name, args)
    }
}
