// TARGET_BACKEND: NATIVE
// WITH_PLATFORM_LIBS

// KT-87579: a Kotlin class implementing the same Objective-C protocol imported from two different
// cinterop libraries must register the protocol only once.

// MODULE: lib1
// FILE: lib1.def
headers = lib1.h
language = Objective-C

// FILE: lib1.h
@protocol P
@end

// MODULE: lib2
// FILE: lib2.def
headers = lib2.h
language = Objective-C

// FILE: lib2.h
@protocol P
@end

// MODULE: main(lib1,lib2)
// FILE: main.kt
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

class C : platform.darwin.NSObject(), lib1.PProtocol, lib2.PProtocol

fun box(): String {
    val c = C()
    if (c !is lib1.PProtocol) return "FAIL: not lib1.PProtocol"
    if (c !is lib2.PProtocol) return "FAIL: not lib2.PProtocol"

    return "OK"
}
