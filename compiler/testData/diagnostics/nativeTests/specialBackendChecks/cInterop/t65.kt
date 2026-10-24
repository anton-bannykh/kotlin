// RUN_PIPELINE_TILL: FRONTEND
// LANGUAGE: +CollectionLiterals +CompanionBlocks
// OPT_IN: kotlinx.cinterop.ExperimentalForeignApi, kotlin.ExperimentalCollectionLiteralsApi
// WITH_PLATFORM_LIBS
// KT-82852: collection literals are resolved to `Array.of(...)`, which must be accepted like `arrayOf(...)`.
import platform.posix.*

fun literal() = printf("%d %d", *[1, 2])
fun arrayDotOf() = printf("%d %d", *Array.of(1, 2))
fun nested() = printf("%d %d", *arrayOf(*Array.of(1, 2)))
fun notALiteral(s: Array<Any?>) = printf("%d %d", *Array.of(<!VARIADIC_C_SPREAD_IS_SUPPORTED_ONLY_FOR_ARRAYOF!>*s<!>))
