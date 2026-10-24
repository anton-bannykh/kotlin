// TARGET_BACKEND: NATIVE
// LANGUAGE: +CollectionLiterals +CompanionBlocks
// KT-82852: collection literals are resolved to `Array.of(...)`, which can be spread into variadic C calls like `arrayOf(...)`.
// MODULE: cinterop
// FILE: kt82852.def
---
#include <stdarg.h>

static int sum(int count, ...) {
    va_list args;
    va_start(args, count);
    int result = 0;
    for (int i = 0; i < count; i++) result = result * 10 + va_arg(args, int);
    va_end(args);
    return result;
}

// MODULE: main(cinterop)
// FILE: main.kt
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, ExperimentalCollectionLiteralsApi::class)

import kotlin.test.*
import kt82852.*

fun box(): String {
    assertEquals(123, sum(3, *arrayOf(1, 2, 3)))
    assertEquals(456, sum(3, *[4, 5, 6]))
    assertEquals(789, sum(3, *Array.of(7, 8, 9)))
    assertEquals(1234, sum(4, 1, *[2, 3], 4))
    assertEquals(567, sum(3, *arrayOf(5, *[6, 7])))
    assertEquals(0, sum(0, *[]))
    return "OK"
}
