// ISSUE: KT-82852
// TARGET_BACKEND: NATIVE
// DISABLE_NATIVE: isAppleTarget=false
// WITH_PLATFORM_LIBS
// LANGUAGE: +CollectionLiterals +CompanionBlocks

// Collection literals are resolved to `Array.of(...)`, which can be spread into variadic Objective-C calls
// like `arrayOf(...)`. A named `args = *[...]` is how such calls are disambiguated between overloads.

// FILE: main.kt
@file:OptIn(ExperimentalCollectionLiteralsApi::class)

import kotlin.test.*
import platform.Foundation.*

fun box(): String {
    assertEquals("1 2", NSString.stringWithFormat("%d %d", *arrayOf(1, 2)))
    assertEquals("3 4", NSString.stringWithFormat("%d %d", *[3, 4]))
    assertEquals("5 6", NSString.stringWithFormat(format = "%d %d", args = *[5, 6]))
    assertEquals("7 8", NSString.stringWithFormat("%d %d", *Array.of(7, 8)))
    assertEquals("9 10 11", NSString.stringWithFormat("%d %d %d", 9, *[10, 11]))
    return "OK"
}
