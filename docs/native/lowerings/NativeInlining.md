# NativePrivateFunctionInlining, NativeAllFunctionInlining

Replace every call of an `inline` function with a copy of the function's body. Lambdas passed to it are pasted into that
copy, so no function object is created for them.

**Why:** this is what `inline` means, and `reified` type parameters and non-local `return` only work this way. Afterwards
only a few compiler intrinsics such as `typeOf` are still called as inline functions; they have their own lowerings.

```kotlin
// before
private inline fun twice(block: (Int) -> Unit) { block(0); block(1) }
fun a() { twice { println(it) } }
fun b(list: List<String>) { list.forEach { println(it) } }      // forEach: a public inline function in the stdlib klib

// after NativePrivateFunctionInlining: only `a` changed
fun a() {
    { val it = 0; println(it) }                 // the body of `twice`; each `block(...)` became the lambda's body
    { val it = 1; println(it) }
}

// after NativeAllFunctionInlining
fun b(list: List<String>) {
    val iterator = list.iterator()              // the body of `forEach`, loaded from the stdlib klib
    while (iterator.hasNext()) {
        val element: Any? = iterator.next()
        val it: String = element /* implicit cast */
        println(it)
    }
}
```

**Why two phases:** a private inline function can only be called from its own file, so inlining it is a per-file job like
any other lowering. A non-private one is copied into other files and klibs. Before that, its body must be free of calls to
private inline functions (hence the first phase), and the private declarations it still uses need accessors
(`SyntheticAccessorLowering` runs in between). The second phase reads bodies from other files, so it starts only when
every file has finished all earlier lowerings.

**Know this**
- The body of an inline function from another klib is loaded from that klib when the first call is met, and the lowerings
  that precede inlining are applied to it on the spot.
- Each pasted body stays wrapped in a marker (`IrInlinedFunctionBlock`) that records which function it came from.
- The same inliner also runs in the first stage, before the klib is written: within a module from language version 2.4,
  across modules from 2.5. With 2.5, the default in this repository, the example arrives in the second stage already
  inlined and these two phases change nothing in it. The dumps above were made with `-language-version 2.3`.

**Source:** [NativeIrInliner.kt](../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/NativeIrInliner.kt),
[FunctionInlining.kt](../../../compiler/ir/ir.inline/src/org/jetbrains/kotlin/ir/inline/FunctionInlining.kt) (the shared inliner)
· **Runs:** always; the last steps of the "before inlining" list.
