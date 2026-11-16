# UnboxInlineLowering

Replaces a call to an unbox function with a direct read of the field that holds the value in the box.

**Why:** `<Int-unbox>` is a one-line function that returns a field of the box object. Reading the field in place saves the
call, for the unbox calls that [RedundantCoercionsCleaner](RedundantCoercionsCleaner.md) could not remove.

```kotlin
value class Meters(val v: Double)

// before
fun unboxInt(a: Any): Int = <Int-unbox>(a as Int)
fun unboxUInt(a: Any): UInt = <UInt-unbox>(a as UInt)
fun unboxMeters(a: Any): Meters = <Meters-unbox>(a as Meters)

// after
fun unboxInt(a: Any): Int = (a as Int).value            // boxes of primitives keep the value in a field `value`
fun unboxUInt(a: Any): UInt = (a as UInt).data          // value classes: the field of their single property
fun unboxMeters(a: Any): Meters = (a as Meters).v
```

**Know this**
- Here `a as Int` has the *boxed* `Int` in mind: an object with a header and one field. The field read yields the raw value.
  → [object-model.md](../../runtime/object-model.md)
- Boxing is not inlined this way: `<Int-box>` may take the object from a cache or allocate it.
- An unbox function whose body is not a plain field read, or whose result is nullable, is left as a call.

**Source:** [UnboxInlineLowering.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/UnboxInlineLowering.kt)
· **Runs:** after devirtualization and [RedundantCoercionsCleaner](RedundantCoercionsCleaner.md); `-opt` only.
