// RUN_PIPELINE_TILL: FRONTEND
// LANGUAGE: +CollectionLiterals +CompanionBlocks
// OPT_IN: kotlinx.cinterop.ExperimentalForeignApi, kotlin.ExperimentalCollectionLiteralsApi
// WITH_PLATFORM_LIBS
// KT-82852: collection literals are resolved to `Array.of(...)`, which must be accepted like `arrayOf(...)`.
import platform.posix.*

fun literal() = printf("%d %d", <!VARIADIC_C_SPREAD_IS_SUPPORTED_ONLY_FOR_ARRAYOF!>*[1, 2]<!>)
fun arrayDotOf() = printf("%d %d", <!VARIADIC_C_SPREAD_IS_SUPPORTED_ONLY_FOR_ARRAYOF!>*Array.of(1, 2)<!>)
fun nested() = printf("%d %d", *arrayOf(<!VARIADIC_C_SPREAD_IS_SUPPORTED_ONLY_FOR_ARRAYOF!>*Array.of(1, 2)<!>))
fun notALiteral(s: Array<Any?>) = printf("%d %d", <!VARIADIC_C_SPREAD_IS_SUPPORTED_ONLY_FOR_ARRAYOF!>*Array.of(*s)<!>)
