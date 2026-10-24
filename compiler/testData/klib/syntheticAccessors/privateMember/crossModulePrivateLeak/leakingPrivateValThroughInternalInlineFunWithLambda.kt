// KT-54330
// MODULE: lib
// FILE: A.kt
package test

class A {
    private val x = "OK"

    internal inline fun foo(p: (String) -> Unit) {
        p(x)
    }
}

// MODULE: main()(lib)
// FILE: main.kt
import test.*

fun box(): String {
    var result = "fail"
    A().foo { result = it }
    return result
}
