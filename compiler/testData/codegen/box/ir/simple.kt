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

open class A(var a: Int) {
    constructor(): this(20)
}

open class B : A {
    constructor(t: Short): this(t.toInt() * 10)

    constructor(t: Int): super() {
        a += t
    }
}

open class C: B {
    constructor(e: Int): super(3.toShort()) {
        a += e
    }
}

class D : C {
    constructor() : super(7)
}

class A {

    console.log(new A(10));
    console.log(A.create());
    console.log(B.create(1));
    console.log(new C(4));
    console.log(new D(5));
    console.log(D.create());
}


fun box(): String {

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