# ComputeTypesPass

Recomputes the types of local variables and expressions from the values that actually flow into them, and removes the
implicit casts that this makes redundant.

**Why:** the inliner erases generics. When `same<Dog>(d)` is inlined, its parameter `x: T` becomes a variable of type
`Any?`, and the result is cast back to `Dog`. In debug builds such casts are checked at run time
([type-checks.md](../optimizations/type-checks.md)), and an `Any?` variable may force boxing. Giving the variable its real
type removes both.

```kotlin
inline fun <T> same(x: T): T = x
fun compute(d: Dog): String { val x = same(d); return x.bark() }

// before (after inlining)
val x: Dog = {
    val tmp0: Any? = d /*as Any?*/          // argument of the inlined call, erased to Any?
    inlined@ {
        val x: Any? = tmp0                  // the parameter `x: T`
        return@inlined x /*as Dog*/         // implicit cast back to the type the caller expects
    }
}

// after
val x: Dog = {
    val tmp0: Dog = d                       // only a Dog is ever written here
    inlined@ {
        val x: Dog = tmp0
        return@inlined x                    // cast no longer needed
    }
}
```

**Know this**
- It runs **twice**. First soon after inlining, to repair the erased types. Then again just before
  [CastsOptimization](CastsOptimization.md), to help that analysis and to fix types that lowerings in between left imprecise.
- The analysis is local to one function body. A variable written from several places gets their common superclass.
  Interfaces are never narrowed, because that would need the whole class hierarchy.
- A variable declared in source code keeps its type if the new type would change how it is boxed.

**Source:** [ComputeTypesPass.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/optimizations/ComputeTypesPass.kt)
· **Runs:** after inlining, always, twice (after [NativeFinallyBlocksLowering](NativeFinallyBlocksLowering.md) and after
[StaticInitializersLowering](StaticInitializersLowering.md)).
