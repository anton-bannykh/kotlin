# NativeSharedVariablesPrimitiveBoxSpecializationLowering

When a captured `var` has a primitive type, replaces its generic heap box `SharedVariableBox<Int>` with the specialized
`SharedVariableBoxInt` (there is one such class per primitive type).

**Why:** a local `var` that a lambda modifies cannot stay on the stack, so the shared `SharedVariablesLowering` moves it
into a small heap object, a **box**, before inlining. That box is generic: its field has type `T`, so an `Int` in it must
itself be boxed into an object. That is two allocations and two indirections for one counter. The specialized box has a
plain `Int` field.

```kotlin
// source
fun counter(): () -> Int { var n = 0; return { n++ } }
fun appender(): () -> String { var s = ""; return { s += "a"; s } }

// before (SharedVariablesLowering has already run)
fun counter(): () -> Int {
    val n: SharedVariableBox<Int> = SharedVariableBox<Int>(element = 0)
    return { val old = n.element; n.element = old.inc(); old }
}

// after
fun counter(): () -> Int {
    val n: SharedVariableBoxInt = SharedVariableBoxInt(element = 0)     // type, constructor and accessors replaced
    return { val old = n.element; n.element = old.inc(); old }
}
fun appender(): () -> String {
    val s: SharedVariableBox<String> = SharedVariableBox<String>(element = "")      // not a primitive: unchanged
    return { s.element = s.element.plus("a"); s.element }
}
```

**Know this**
- All the logic is in the shared class `SharedVariablesPrimitiveBoxSpecializationLowering`; the Native class only passes
  the Native symbols to it.
- The box classes are ordinary Kotlin classes in the stdlib (`kotlin.internal.SharedVariableBox*`).

**Source:** [SharedVariablesPrimitiveBoxSpecializationLowering.kt](../../../../compiler/ir/backend.common/src/org/jetbrains/kotlin/backend/common/lower/SharedVariablesPrimitiveBoxSpecializationLowering.kt),
[SharedVariablesLowering.kt](../../../../compiler/ir/backend.common/src/org/jetbrains/kotlin/backend/common/lower/SharedVariablesLowering.kt)
· **Runs:** after inlining, always.
