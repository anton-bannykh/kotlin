// IGNORE_KLIB_RUNTIME_ERRORS_WITH_CUSTOM_SECOND_STAGE: JS:2.3
// ^^^ KT-15101 js: Same callable references are not equal

// FILE: a.kt

private fun foo() = 5
private val bar = 5

fun aFoo(): Any = ::foo
fun aBar(): Any = ::bar

fun aLocal(): Any {
    class Local {
        fun baz() = 5
    }
    return Local::baz
}

// FILE: b.kt

private fun foo() = 6
private val bar = 6

fun bFoo(): Any = ::foo
fun bBar(): Any = ::bar

fun bLocal(): Any {
    class Local {
        fun baz() = 6
    }
    return Local::baz
}

// FILE: main.kt

fun checkEqual(x: Any, y: Any) {
    if (x != y || y != x) throw AssertionError("$x and $y should be equal")
    if (x.hashCode() != y.hashCode()) throw AssertionError("$x and $y should have the same hash code")
}

fun checkNotEqual(x: Any, y: Any) {
    if (x == y || y == x) throw AssertionError("$x and $y should NOT be equal")
}

fun box(): String {
    checkEqual(aFoo(), aFoo())
    checkEqual(aBar(), aBar())
    checkEqual(aLocal(), aLocal())

    checkNotEqual(aFoo(), bFoo())
    checkNotEqual(aBar(), bBar())
    checkNotEqual(aLocal(), bLocal())

    return "OK"
}
