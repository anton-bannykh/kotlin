# GenericCallsReturnTypeEraser

Makes every call expression have the type its callee really returns. Where the surrounding code expected the more precise
type that the frontend had written there, an explicit implicit-cast node is put around the call.

**Why:** the frontend types `pick<String>(l)` as `String`, but the compiled `pick` returns whatever `T` erases to: `Any?`.
After this lowering a call is just a call, and the conversion from `Any?` back to `String` or `Int` is a separate node that
[Autoboxing](Autoboxing.md) can turn into a type check or an unbox.

```kotlin
fun <T> pick(list: List<T>): T = list[0]

// before                                         // (the type of each call expression is shown in the comment)
fun useGeneric(l: List<String>): Int =
    pick<String>(l).length                        // pick(...): String
fun useGenericInt(l: List<Int>): Int =
    pick<Int>(l) + 1                              // pick(...): Int
fun dropGeneric(l: List<String>) { pick<String>(l) }

// after
fun useGeneric(l: List<String>): Int =
    (pick<String>(l) /*as String*/).length        // pick(...): Any?, then an implicit cast to String
fun useGenericInt(l: List<Int>): Int =
    (pick<Int>(l) /*as Int*/) + 1                 // pick(...): Any?, then an implicit cast to Int
fun dropGeneric(l: List<String>) { pick<String>(l) }    // result unused: type changed to Any?, no cast
```

**Know this**
- Only a return type that is itself a type parameter is erased. A callee returning `List<T>` keeps that type on the call,
  and the cast to `List<String>` around it costs nothing.
- The cast is an *implicit* cast: by itself it generates no code. [Autoboxing](Autoboxing.md) decides what it becomes.

**Source:** [GenericCallsReturnTypeEraser.kt](../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/GenericCallsReturnTypeEraser.kt)
· **Runs:** after inlining, always, right before [Autoboxing](Autoboxing.md).
