# InlineClassPropertyAccessorsLowering

Replaces a call to the getter of a value class's underlying property with `reinterpret`, an intrinsic that changes the
static type of a value and generates no code.

**Why:** a value class is represented by its single property, so `m.v` is `m` itself seen as a `Double`. Without this
lowering it would still be a call to `<get-v>`.

```kotlin
value class Meters(val v: Double) {
    val twice: Double get() = v * 2
}

// before
fun len(m: Meters): Double = m.<get-v>() + 1.0

// after
fun len(m: Meters): Double = reinterpret<Meters, Double>(m) + 1.0      // same bits, different type; no call
```

**Know this**
- Only the property with a backing field is affected. A computed property such as `twice` stays an ordinary call.
- It runs only with `-opt`. In a debug build `m.v` remains a call to the getter, whose body is the same `reinterpret`
  (put there by [Autoboxing](Autoboxing.md)).
- It is the value-class counterpart of the shared `PropertyAccessorInlineLowering`, which runs just before it and turns
  calls to trivial accessors of ordinary classes into field reads.

**Source:** [InlineClassPropertyAccessorsLowering.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/InlineClassPropertyAccessorsLowering.kt)
· **Runs:** after all per-file lowerings, before the whole-program optimizations; `-opt` only.
