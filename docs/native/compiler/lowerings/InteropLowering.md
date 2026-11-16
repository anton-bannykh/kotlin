# InteropLowering

Replaces every use of a declaration from a cinterop library (a C function, a C global, an Objective-C method or constructor)
with a call to a generated **bridge**: a small C function (the **C stub**) plus an `external` Kotlin function bound to it.

**Why:** a function such as `add` in a cinterop klib has no body, only annotations saying which C symbol it stands for.
The compiler writes a trivial C function that makes the real call and lets clang compile it, so the C side of the call is
handled by a C compiler. See [cinterop.md](../../interop/cinterop.md).

```kotlin
// before        (mylib is a cinterop library made from:  int add(int, int);  int apply(int (*f)(int), int);)
fun callC(x: Int): Int = add(x, 2)
fun callback(x: Int): Int = apply(staticCFunction { v: Int -> v * 2 }, x)

// after
fun callC(x: Int): Int = interopCallMarker({    // marks "all bridges of one interop call"; removed by the name inventor
    @KotlinToCBridge(language = "C", library = "mylib", impl = """
        extern const int (*␀target2␀)(int, int) __asm("knifunptr_mylib_add_0");   // pointer to add, exported by the library
        int ␀knbridge1␀(int p1, int p2) { return ␀target2␀(p1, p2); }""")          // the C stub, as text
    @FilterExceptions(mode = "terminate")       // a foreign exception escaping from C terminates the program
    external fun ␀knbridge1␀(p0: Int, p1: Int): Int                              // Kotlin side: declared, bound to the stub
    ␀knbridge1␀(x, 2)
})
fun callback(x: Int): Int = interopCallMarker({
    external fun ␀knbridge1␀(p0: CPointer<CFunction<(Int) -> Int>>?, p1: Int): Int     // bridge to apply, as above
    ␀knbridge1␀({
        fun anonymous(v: Int): Int = v * 2                                        // the lambda, now a plain function
        @CToKotlinBridge fun ␀knbridge1␀(p0: Int): Int = anonymous(p0)            // Kotlin function that C may call
        @KotlinToCBridge(impl = "int ␀kncfun2␀(int p1) { return ␀knbridge1␀(p1); }")   // C function that calls it
        external fun ␀kncfun2␀(): Int
        ::␀kncfun2␀                             // staticCFunction is now the raw address of that C function
    }, x)
})
```

**Know this**
- The names are placeholders between NUL characters (`␀` above). Unique names are assigned later, by
  [InteropBridgesNameInventor](InteropBridgesNameInventor.md), which also hands the C text to clang.
- Objective-C works the same way (not shown: it needs macOS). A method call gets a stub written in Objective-C that sends
  `@selector(...)` to the receiver. A Kotlin class that extends an Objective-C class also gets extra functions, for example for
  `@ObjCAction` methods, so that the Objective-C runtime can call into it.

**Source:** [InteropLowering.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/InteropLowering.kt),
[CBridgeGen.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/cgen/CBridgeGen.kt) (what a bridge looks like)
· **Runs:** after inlining, always.
