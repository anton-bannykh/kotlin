// WITH_STDLIB
// WORKS_WHEN_VALUE_CLASS
// IGNORE_KLIB_RUNTIME_ERRORS_WITH_CUSTOM_SECOND_STAGE: Native:2.4
// KT-90351: the default `equals` of a value class compares its underlying floating-point value
// like `Double.equals`: all NaNs are equal to each other, and -0.0 is not equal to 0.0.

OPTIONAL_JVM_INLINE_ANNOTATION
value class D(val value: Double)

OPTIONAL_JVM_INLINE_ANNOTATION
value class F(val value: Float)

fun box(): String {
    val a = D(Double.NaN)                          // 0x7FF8000000000000
    val b = D(Double.fromBits(0xFFF80000L shl 32)) // 0xFFF8000000000000 (also NaN)
    if (a != b) return "Fail: D(NaN) != D(other NaN)"
    if (D(-0.0) == D(0.0)) return "Fail: D(-0.0) == D(0.0)"
    if (F(Float.NaN) != F(Float.fromBits(0xFFC00000.toInt()))) return "Fail: F(NaN) != F(other NaN)"
    if (F(-0.0f) == F(0.0f)) return "Fail: F(-0.0f) == F(0.0f)"
    return "OK"
}
