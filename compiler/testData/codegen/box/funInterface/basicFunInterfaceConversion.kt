// EXPECTED_REACHABLE_NODES: 1309
// !LANGUAGE: +NewInference +FunctionalInterfaceConversion +SamConversionPerArgument +SamConversionForKotlinFunctions
// IGNORE_BACKEND_FIR: JVM_IR

fun interface Foo {
    fun invoke(): String
}

fun foo(f: Foo) = f.invoke()

fun box(): String {
    return foo { "OK" }
}