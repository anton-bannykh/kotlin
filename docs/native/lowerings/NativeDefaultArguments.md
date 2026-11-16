# NativeDefaultArgumentStubGenerator, NativeDefaultParameterCleaner, NativeDefaultParameterInjector

Three lowerings that implement default arguments. For a function with defaults they generate a second function,
`foo$default`, with one extra `Int` parameter: a bit mask saying which arguments the caller left out. Calls that omit
arguments are redirected to it.

**Why:** a machine-level call always passes all arguments. Someone has to compute the missing ones, and it must be the
callee's side: the default expression may use private members, or other parameters.

```kotlin
// before
fun greet(name: String = "you", times: Int = name.length): String = name.repeat(times)
fun main() { greet(); greet("me") }

// after
fun greet(name: String, times: Int): String = name.repeat(times)          // the function itself is unchanged

fun `greet$default`(name: String?, times: Int, `$mask0`: Int): String {    // 1. the stub generator adds this
    val name = if ((`$mask0` and 1) != 0) "you" else name                  // bit i set: argument i was omitted
    val times = if ((`$mask0` and 2) != 0) name.length else times          // sees the already chosen `name`
    return greet(name, times)
}
fun main() {
    `greet$default`(null, 0, `$mask0` = 3)                                 // 3. the injector rewrites the calls
    `greet$default`("me", 0, `$mask0` = 2)
}
```

1. `NativeDefaultArgumentStubGenerator` creates the `$default` function. A constructor gets a second constructor with the
   mask and an extra marker parameter that keeps the two signatures apart.
2. `NativeDefaultParameterCleaner` removes the default expressions from the original function.
3. `NativeDefaultParameterInjector` rewrites every call that omits an argument. A call that passes everything still calls the
   original function directly.

**Know this**
- The value passed in place of an omitted argument is never read. Native passes the zero of the machine type: `null` for
  references (which is why `name` is `String?` in the stub), `0`, `false`, a null pointer.
- The cleaner leaves a dummy default value on the parameter instead of nothing. The injector recognizes "has defaults" by
  that, also when the callee is in another file that has already been lowered.
- The logic is shared with the other backends (`DefaultArgumentStubGenerator.kt`); the Native classes supply the zero
  values and also generate stubs for inline functions.

**Source:** [DefaultArgumentStubGenerator.kt](../../../compiler/ir/backend.common/src/org/jetbrains/kotlin/backend/common/lower/DefaultArgumentStubGenerator.kt) (shared, all three),
[NativeDefaultParameterInjector.kt](../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/NativeDefaultParameterInjector.kt)
· **Runs:** after inlining, always.
