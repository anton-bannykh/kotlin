// KT-54330
// MODULE: lib
// FILE: lib.kt
package test

private val x = "OK"

internal inline fun foo(p: (String) -> Unit) {
    p(x)
}

// MODULE: main()(lib)
// FILE: main.kt
import test.*

fun box(): String {
    var result = "fail"
    foo { result = it }
    return result
}
