[Kotlin/Native guide](../../README.md) › [compiler](../README.md) › [lowerings](README.md) › boxing

# Boxing, bridges and value classes

*Skip this page if you know where box/unbox calls are inserted and why an override may need a bridge function.*

An `Int`, a `Double` or a [value class][valueclass] is a raw machine value; an `Any`, a nullable type or a generic `T` is a
pointer to a heap object (→ [object-model.md](../../runtime/object-model.md)). These lowerings make every crossing between
the two explicit, and with `-opt` remove the crossings that turn out to be unnecessary. In the order they run:

`BridgesBuilding → GenericCallsReturnTypeEraser → Autoboxing → (devirtualization) → RedundantCoercionsCleaner`
`→ UnboxInlineLowering`

```kotlin
value class Meters(val v: Double)
interface Source<T> { fun next(): T }
class Counter : Source<Int> { var n = 0; override fun next(): Int = n++ }
fun sum(s: Source<Int>): Int = s.next() + 1
```

## BridgesBuilding

Adds a **bridge** function when a method overrides one whose machine-level signature is different. A caller that only
knows `Source<T>` expects `next()` to return a reference, but `Counter.next()` returns a raw `Int`. The bridge has the
signature of the overridden method, converts, and calls the real method.

```kotlin
// after
class Counter : Source<Int> {
    var n = 0
    override fun next(): Int = n++                // unchanged; used when the caller knows it has a Counter
    @TransparentForDebugger                       // the debugger steps through it
    open fun `$<bridge-UN>next`(): Any? {         // same signature as Source<T>.next() after erasure
        return super<Counter>.next()              // non-virtual call of the real method;
    }                                             // Autoboxing then wraps it: <Int-box>(super<Counter>.next())
}
```

**Know this**
- The bridge, not the real method, goes into the virtual table slot of the overridden method. The letters in its name say
  what differs, return value first, then each parameter including `this`: `U` this method has an unboxed value there, `B`
  a boxed one, `D` the overridden method returns `Unit`, `C` a cast is needed, `N` nothing.
- A value class gets bridges for `toString`, `hashCode` and `equals`: `this` is the raw value in its own methods but a
  boxed object when called through `Any`. Overrides of `Collection.contains`, `List.indexOf`, `Map.get` and a few
  similar methods also get a check of the argument's type: a wrong type returns `false`, `-1` or `null` instead of failing.
- **WorkersBridgesBuilding** does the same for the job function passed to `Worker.execute`, which the runtime calls as
  `(Any?) -> Any?`.

**Source:** [BridgesBuilding.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/BridgesBuilding.kt)
(when a bridge is needed: [IrUtils.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/ir/IrUtils.kt))
· **Runs:** after inlining, always.

## Autoboxing

Wherever a primitive or a value class meets `Any`, a nullable type or a generic, inserts a call to `<T-box>` or
`<T-unbox>`. Right before it, **GenericCallsReturnTypeEraser** prepares calls whose return type is a type parameter: the
frontend types `s.next()` as `Int`, but the compiled `Source<T>.next()` returns `Any?`. The eraser gives the call that type
and puts an implicit cast back to `Int` around it. The cast generates no code by itself; Autoboxing turns it into an unbox.

```kotlin
// before
fun sum(s: Source<Int>): Int = s.next() /*as Int*/ + 1  // s.next(): Any?; the cast was added by the eraser
fun boxing(x: Int): Any = x
fun nullable(x: Int): Int? = x
fun boxMeters(m: Meters): Any = m

// after
fun sum(s: Source<Int>): Int = <Int-unbox>(s.next() as Int?) + 1    // reads the value out of the object
fun boxing(x: Int): Any = <Int-box>(x)                  // allocates an object holding the Int (small values are cached)
fun nullable(x: Int): Int? = <Int-box>(x)               // Int? is a reference too: null must be representable
fun boxMeters(m: Meters): Any = <Meters-box>(m)         // m itself is just a Double

fun <Meters-box>(value: Meters): Any {                  // generated for each value class
    val box = createUninitializedInstance<Meters>(); box.v = value; return box
}
fun <Meters-unbox>(value: Any): Meters = value.v
```

**Know this**
- A value class is represented by its single property: `Meters` is a `Double`, and `Meters(1.0)` becomes `1.0` (plus a
  call of a generated function that runs the `init` blocks). Inside the class, reading `this.v` becomes
  `reinterpret<Meters, Double>(this)`, an intrinsic that changes the static type and generates no code. With `-opt`,
  **InlineClassPropertyAccessorsLowering** later replaces the getter call itself (`m.v`) with the same `reinterpret`;
  computed properties stay calls.
- Whether the `as Int?` in `sum` is checked at run time depends on the build
  (→ [type-checks.md](../optimizations/type-checks.md#casts-caused-by-generics)). A constant that needs a box becomes a
  constant object in the binary: `boxing(1)` allocates nothing.

**Source:** [Autoboxing.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/Autoboxing.kt),
[GenericCallsReturnTypeEraser.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/GenericCallsReturnTypeEraser.kt)
· **Runs:** after inlining, always.

## RedundantCoercionsCleaner

Removes a box that is immediately unboxed again (or the other way round). Such pairs appear after
[devirtualization](../optimizations/devirtualization.md): when it replaces the virtual `s.next()` with a direct call to
`Counter.next()`, which returns a raw `Int`, it has to box the result to keep the IR consistent. Box and unbox now meet.

```kotlin
// before (after devirtualization; Twice is a second implementation of Source<Int>)
fun sum(s: Source<Int>): Int =
    <Int-unbox>(when (getObjectTypeInfo(s)) {
        Counter::class -> <Int-box>(super<Counter>.next())      // direct call returns Int; boxed to match the old call
        else -> <Int-box>(super<Twice>.next())
    }) + 1

// after
fun sum(s: Source<Int>): Int =
    when (getObjectTypeInfo(s)) {
        Counter::class -> super<Counter>.next()                 // no allocation left
        else -> super<Twice>.next()
    } + 1
```

**Know this**
- The outer coercion is pushed into the branches of a `when` and into the returns of an inlined block. If only some
  branches cancel, the others get the coercion instead: `box(when { a -> unbox(x); b -> y })` becomes
  `when { a -> x; b -> box(y) }`.
- With `-opt`, **UnboxInlineLowering** then turns the unbox calls that are left into a read of the box's field:
  `<Int-unbox>(a as Int)` becomes `(a as Int).value`. Boxing is not inlined: `<Int-box>` may take the object from a cache.

**Source:** [RedundantCoercionsCleaner.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/RedundantCoercionsCleaner.kt),
[UnboxInlineLowering.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/UnboxInlineLowering.kt)
· **Runs:** right after devirtualization. The cleaner runs in every build, but devirtualization, its main source of work,
only with `-opt`.

[valueclass]: https://kotlinlang.org/docs/inline-classes.html
