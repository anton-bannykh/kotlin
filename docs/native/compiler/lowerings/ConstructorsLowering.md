# ConstructorsLowering

Splits object creation into its two real steps. A constructor becomes an ordinary function `<init>` that takes the new
object as `this`, and `Foo(args)` becomes "allocate an empty object, then call `<init>` on it".

**Why:** code generation has no notion of a constructor. It knows how to ask the runtime for memory and how to call a
function.

```kotlin
// before
class Point(val x: Int, val y: Int) {
    constructor(x: Int) : this(x, 0)
}
fun makePoint(): Point = Point(1)
fun makeArray(n: Int) = arrayOfNulls<String>(n)

// after
class Point {
    constructor(x: Int, y: Int)                   // the constructor stays as a declaration without a body
    fun <init>(x: Int, y: Int) {                  // its body moved here; the call to Any's constructor is dropped
        this.x = x
        this.y = y
    }
    constructor(x: Int)
    fun <init>(x: Int) { this.<init>(x, 0) }      // delegation is a plain call on the same object
}
fun makePoint(): Point {
    val inst = createUninitializedInstance<Point>()     // allocation only: no constructor code runs
    inst.<init>(1)
    return inst
}
fun makeArray(n: Int) = createUninitializedArray<Array<T>>(n)   // arrays: allocation only (T: arrayOfNulls was inlined)
```

**Know this**
- A superclass constructor call becomes the same kind of plain call: `this.<init>(...)` of the superclass function.
- `initInstance(obj, Foo(args))`, which earlier lowerings use to construct into an already allocated object (see
  [EnumClassLowering](EnumClassLowering.md), [ObjectClassLowering](ObjectClassLowering.md)), becomes `obj.<init>(args)`.
- Value classes are skipped: they have no allocation. [Autoboxing](Autoboxing.md) handles their constructors.

**Source:** [ConstructorsLowering.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/ConstructorsLowering.kt)
· **Runs:** after inlining, always.
