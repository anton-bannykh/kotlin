// WITH_STDLIB
// IGNORE_KLIB_RUNTIME_ERRORS_WITH_CUSTOM_SECOND_STAGE: Native:2.4
// KT-90351: `==` on a reified type parameter keeps the generic `equals()` semantics
// for floating-point values: all NaNs are equal to each other, and -0.0 is not equal to 0.0.

inline fun <reified T> eq(a: T, b: T) = a == b

fun box(): String {
    val a = Double.NaN                          // 0x7FF8000000000000
    val b = Double.fromBits(0xFFF80000L shl 32) // 0xFFF8000000000000 (also NaN)
    if (!eq(a, b)) return "Fail: reified T = Double"
    if (!eq(Float.NaN, Float.fromBits(0xFFC00000.toInt()))) return "Fail: reified T = Float"
    if (eq(-0.0, 0.0)) return "Fail: reified T = Double, -0.0 == 0.0"
    return "OK"
}
