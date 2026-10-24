// WITH_STDLIB
// IGNORE_KLIB_RUNTIME_ERRORS_WITH_CUSTOM_SECOND_STAGE: Native:2.4
// KT-90351: `==` on a type parameter of an inline function keeps the generic `equals()` semantics
// for floating-point values: all NaNs are equal to each other, and -0.0 is not equal to 0.0.

inline fun <T> eq(a: T, b: T) = a == b

fun box(): String {
    val a = Double.NaN                          // 0x7FF8000000000000
    val b = Double.fromBits(0xFFF80000L shl 32) // 0xFFF8000000000000 (also NaN)
    if (!eq(a, b)) return "Fail: T = Double"
    if (!eq<Double?>(a, b)) return "Fail: T = Double?"
    if (!eq(Float.NaN, Float.fromBits(0xFFC00000.toInt()))) return "Fail: T = Float"

    if (eq(-0.0, 0.0)) return "Fail: T = Double, -0.0 == 0.0"
    if (eq(-0.0f, 0.0f)) return "Fail: T = Float, -0.0f == 0.0f"
    if (!eq(1.5, 1.5)) return "Fail: T = Double, 1.5 != 1.5"
    if (eq(1.5, 2.5)) return "Fail: T = Double, 1.5 == 2.5"
    return "OK"
}
