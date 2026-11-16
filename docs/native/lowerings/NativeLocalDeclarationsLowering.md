# NativeLocalDeclarationsLowering

Makes local functions, local classes and anonymous objects independent of the function they are declared in: every local
variable they capture becomes an explicit parameter (for a function) or a constructor parameter plus a field (for a class).
Two lowerings before it invent names, and one after it moves the declarations out; this page covers all four.

**Why:** code generation only knows top-level functions and classes with fields. A function cannot read the local variables
of another function, so a captured variable has to be passed in.

```kotlin
// before
fun count(prefix: String, items: List<String>): Int {
    var n = 0                          // already a heap box here: SharedVariablesLowering ran earlier
    fun visit(s: String) { if (s.startsWith(prefix)) n++ }
    class Counter(val step: Int) { fun bump() { n += step } }
    for (i in items) visit(i)
    Counter(2).bump()
    return n
}

// after NativeLocalDeclarationsLowering + LocalDeclarationPopupLowering
fun count(prefix: String, items: List<String>): Int {
    val n = SharedVariableBoxInt(0)
    for (i in items) `count$visit`(prefix, n, i)       // captured values are passed at every call
    `count$Counter`(n, step = 2).bump()
    return n.element
}
private fun `count$visit`(`$prefix`: String, n: SharedVariableBoxInt, s: String) {
    if (s.startsWith(`$prefix`)) n.element++
}
private class `count$Counter`(`$n`: SharedVariableBoxInt, val step: Int) {
    private val `$n` = `$n`                            // captured value stored in a field
    fun bump() { this.`$n`.element += step }
}
```

The four steps, in the order they run:

1. `NativeInventNamesForLocalClasses` names local classes and anonymous objects after their container: `count$Counter`,
   `count$o$1` for `val o = object : ... {}`. An anonymous object's invented name is hidden from reflection.
2. `NativeKlibInventNamesForLocalFunctions` chooses names for local functions: `count$visit`, and `count$visit$0` for a
   second `visit` in the same function. It only records the name; the function is renamed in step 3.
3. `NativeLocalDeclarationsLowering` adds the parameters and fields. The declarations are still nested.
4. `LocalDeclarationPopupLowering` *(shared)* moves them to the enclosing class or file.

**Know this:** all four are shared code; the Native classes only fix the naming scheme and the place in the phase list.
Lambdas are not handled here: [NativeFunctionReferenceLowering](NativeFunctionReferenceLowering.md) made them classes
earlier.

**Source:** [NativeLowerLocalDeclarations.kt](../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/NativeLowerLocalDeclarations.kt),
[LocalDeclarationsLowering.kt](../../../compiler/ir/backend.common/src/org/jetbrains/kotlin/backend/common/lower/LocalDeclarationsLowering.kt) (shared)
· **Runs:** after inlining, always.
