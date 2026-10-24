// RUN_PIPELINE_TILL: FRONTEND
// LANGUAGE: +CollectionLiterals +CompanionBlocks
// OPT_IN: kotlin.ExperimentalCollectionLiteralsApi
// WITH_PLATFORM_LIBS
// KT-82852: collection literals are resolved to `Array.of(...)`, which must be accepted like `arrayOf(...)`.
import platform.Foundation.*

fun literal() = NSString.stringWithFormat(format = "%d %d", <!VARIADIC_OBJC_SPREAD_IS_SUPPORTED_ONLY_FOR_ARRAYOF!>args = <!REDUNDANT_SPREAD_OPERATOR_IN_NAMED_FORM_IN_FUNCTION!>*<!>[1, 2]<!>)
fun arrayDotOf() = NSString.stringWithFormat("%d %d", <!VARIADIC_OBJC_SPREAD_IS_SUPPORTED_ONLY_FOR_ARRAYOF!>*Array.of(1, 2)<!>)
fun notALiteral(s: Array<Any?>) = NSString.stringWithFormat("%d %d", <!VARIADIC_OBJC_SPREAD_IS_SUPPORTED_ONLY_FOR_ARRAYOF!>*Array.of(*s)<!>)
