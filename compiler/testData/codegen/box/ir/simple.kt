// KJS_WITH_FULL_RUNTIME
//
//abstract class Base(val fn: () -> String)
//
//open class Outer {
//    val outerO = "O"
//
//    fun test(): Base {
//        val localK = "K"
//        class Local : Base({ outerO + localK })
//
//        return Local()
//    }
//}

//fun box() = Outer().test().fn().also {
//
//    val e = Error("12")
//
//    if (e !is Throwable) return "f1"
//    if (e !is Error) return "f2"
//
//    if (!js("e instanceof Error").unsafeCast<Boolean>()) return "sad"
//
//}

sealed class A {
    class B: A()
    class C: A()
    class D: A()
}

fun box(): String {
    A.B()

    return "OK"
}

//class Test(val ok: String)
//
//open class B(val s: String) {
//    constructor(a: Int): this("Int: $a")
//
//    constructor(b: Byte): this("String: $b")
//}
//
//class A: B {
//    constructor(a: Int): super(a)
//
//    constructor(b: Byte): super(b)
//}
//
//fun foo() {
//    var a = 10
//    a = 20
//
//    for (i in 10 downTo 1) {
//        a = a * i
//    }
//
//}
//
//fun box() = Test("OK").ok.also {
//
//    foo()
//
//    if (A(10).s != "Int: 10") return "f1"
//
//    if (A(10.toByte()).s != "String: 10") return "f2"
//
//
//}