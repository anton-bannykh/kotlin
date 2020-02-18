// IGNORE_BACKEND: JVM, JVM_IR, JS_IR

fun interface Base {
    fun invoke(): String
}

fun interface I : Base

fun interface Proxy : I {

    override fun invoke(): String = invokeInt().toString()

    fun invokeInt(): Int
}

fun runBase(b: Base) = b.invoke()

fun runI(i: I) = i.invoke()

fun runProxy(p: Proxy) = p.invoke()

fun box(): String {

    if (runI { "i" } != "i") return "fail1"

    if (runProxy { 10 } != "10") return "fail2"

    return runBase { "OK" }
}