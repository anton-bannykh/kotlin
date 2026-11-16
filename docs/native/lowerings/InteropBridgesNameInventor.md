# InteropBridgesNameInventor

Gives the bridges created by [InteropLowering](InteropLowering.md) their final, unique names, in the Kotlin declarations and
in the C text alike, and hands the C text over to be compiled by clang.

**Why:** bridge names become symbols in the binary, so they must be unique across the whole program. InteropLowering only
numbers the bridges within one interop call (`knbridge1`, `target2`, …), so the same names repeat from call to call.

```kotlin
// before: placeholder names between NUL characters (shown as ␀), wrapped in a marker
fun callC(x: Int): Int = interopCallMarker({
    @KotlinToCBridge(language = "C", library = "mylib", impl = """
        extern const int (*␀target2␀)(int, int) __asm("knifunptr_mylib_add_0");
        int ␀knbridge1␀(int p1, int p2) { return ␀target2␀(p1, p2); }""")
    external fun ␀knbridge1␀(p0: Int, p1: Int): Int
    ␀knbridge1␀(x, 2)
})

// after: PFX is a long hex string, see below
fun callC(x: Int): Int = {                                  // the marker is gone
    @KotlinToCBridge(language = "C", library = "mylib", impl = """
        extern const int (*_PFX_1_target2)(int, int) __asm("knifunptr_mylib_add_0");
        int _PFX_1_knbridge1(int p1, int p2) { return _PFX_1_target2(p1, p2); }""")
    @SymbolName("_PFX_1_knbridge1")                         // binds the Kotlin declaration to the C function
    external fun _PFX_1_knbridge1(p0: Int, p1: Int): Int
    _PFX_1_knbridge1(x, 2)
}
```

**Know this**
- A name is `_<prefix>_<n>_<placeholder>`. The prefix is the module name plus the file path, written as hex bytes, so it is
  unique per file. `n` counts the interop calls in the file. This is why bridge symbols look like `_36636437…_1_knbridge1`.
- A bridge in the other direction (C calls Kotlin, for example from `staticCFunction`) gets `@ExportForCppRuntime` with the
  new name instead of `@SymbolName`.
- This lowering has a side effect outside the IR: every C stub is registered for compilation, and the cinterop library named
  in the bridge is recorded as a dependency of the file.

**Source:** [InteropBridgesNameInventor.kt](../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/InteropBridgesNameInventor.kt),
[CStubsManager.kt](../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/CStubsManager.kt) (compiles the stubs)
· **Runs:** after inlining, always.
