# BridgesBuilding

Adds a **bridge** function to a class when one of its methods overrides a method whose machine-level signature is different.
The bridge has the signature of the overridden method, converts, and calls the real method.

**Why:** a generic `T` is passed as an object reference, an `Int` as a raw 32-bit value. A caller that only knows
`Source<T>` expects `next()` to return a reference, but `Counter.next()` returns a raw `Int`. Something must sit in between.

```kotlin
// before
interface Source<T> { fun next(): T }
class Counter : Source<Int> {
    var n = 0
    override fun next(): Int = n++
}

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

**WorkersBridgesBuilding** does the same for the job function passed to `Worker.execute`, which the runtime calls as
`(Any?) -> Any?`: `{ it + 1 }` on an `Int` gets a bridge `(it: Any?): Any?` and the call site passes the bridge instead.

**Know this**
- The bridge, not the real method, is what the class puts into the virtual table slot of the overridden method.
  → [object-model.md](../../runtime/object-model.md)
- The letters in the name say what differs, return value first, then each parameter including `this`: `U` this method has
  an unboxed value there, `B` a boxed one, `D` the overridden method returns `Unit`, `C` a cast is needed, `N` nothing.
- A value class gets bridges for `toString`, `hashCode` and `equals`, because `this` is the raw value in its own methods
  but a boxed object when called through `Any`.
- Overrides of `Collection.contains`, `MutableCollection.remove`, `List.indexOf`, `Map.get`, `Map.containsKey` and a few
  similar methods also get a check of the argument's type, in the bridge or in the method itself: a wrong type returns
  `false`, `-1` or `null` instead of failing.

**Source:** [BridgesBuilding.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/BridgesBuilding.kt)
(when a bridge is needed: [IrUtils.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/ir/IrUtils.kt))
· **Runs:** after inlining, always · **Next:** [Autoboxing](Autoboxing.md) fills in the conversions.
