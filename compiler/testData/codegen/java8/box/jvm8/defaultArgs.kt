// JVM_TARGET: 1.8

interface Z {
    fun test(s: String = "OK"): String {
        return s
    }
}

class Test: Z

fun box(): String {
    return Test().test()
}