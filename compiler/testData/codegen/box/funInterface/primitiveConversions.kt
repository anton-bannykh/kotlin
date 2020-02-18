
fun interface CharToAny {
    fun invoke(c: Char): Any
}

fun foo(c: CharToAny): Any = c.invoke('O')

fun box(): String {

    if (foo { it } !is Char) return "fail"

    return "OK"
}