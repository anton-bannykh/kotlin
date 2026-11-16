# VolatileFieldsLowering

For every `@Volatile` property, adds member functions for the atomic operations on its field (compare-and-set, exchange, add)
and rewrites the stdlib's atomic intrinsics, which take a property reference, into calls of those functions.

**Why:** the stdlib atomics (`kotlin.concurrent.atomics.AtomicInt` and friends) are ordinary classes with one `@Volatile`
field, written as `this::value.compareAndSetField(expected, new)`. That must not create a real property reference. After
this lowering each operation is a call that codegen turns into a single atomic instruction on the field.

```kotlin
// before
class AtomicInt(@Volatile var value: Int) {                             // from the stdlib, shortened
    fun load(): Int = this::value.atomicGetField()
    fun compareAndSet(expected: Int, new: Int): Boolean = this::value.compareAndSetField(expected, new)
    fun fetchAndAdd(delta: Int): Int = this::value.getAndAddField(delta)
}
class Job { @Volatile var done: Boolean = false }

// after
class AtomicInt(@Volatile var value: Int) {
    @TypedIntrinsic("COMPARE_AND_SET")      external fun `<compareAndSet-value>`(expectedValue: Int, newValue: Int): Boolean
    @TypedIntrinsic("COMPARE_AND_EXCHANGE") external fun `<compareAndExchange-value>`(expectedValue: Int, newValue: Int): Int
    @TypedIntrinsic("GET_AND_SET")          external fun `<getAndSet-value>`(value: Int): Int
    @TypedIntrinsic("GET_AND_ADD")          external fun `<getAndAdd-value>`(value: Int): Int     // integer fields only
    fun load(): Int = this.value                                        // the ordinary getter
    fun compareAndSet(expected: Int, new: Int): Boolean = this.`<compareAndSet-value>`(expected, new)
    fun fetchAndAdd(delta: Int): Int = this.`<getAndAdd-value>`(delta)
}
class Job { @Volatile var done: Byte = if (false) 1 else 0 }            // plus the same intrinsic functions
```

**Know this**
- Plain reads and writes of a `@Volatile` field are not touched here. Codegen sees the annotation and emits an atomic load
  or store. That is why `atomicGetField` can simply become the getter.
- A `@Volatile` `Boolean` field is stored as a `Byte`; every read and write of it is converted on the spot.
- The intrinsics only work when the receiver is visibly a reference to a `@Volatile` property (`this::value`). Anything
  else is replaced by a call that throws `IllegalArgumentException` at run time; it is not a compile error.

**Source:** [VolatileFieldsLowering.kt](../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/VolatileFieldsLowering.kt)
· **Runs:** after inlining, always, before [PropertyReferenceLowering](PropertyReferenceLowering.md), which would
otherwise turn `this::value` into an object.
