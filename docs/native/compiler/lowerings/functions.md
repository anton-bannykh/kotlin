[Kotlin/Native guide](../../README.md) › [compiler](../README.md) › [lowerings](README.md) › functions

# Functions: default arguments, varargs, constructors

*Skip this page if you know the `foo$default` bit-mask scheme and that a constructor call is "allocate, then call `<init>`".*

Code generation knows one kind of callable: a function that receives all its arguments and ends in a `return`. These
independent lowerings bring omitted arguments, `vararg` lists and constructors down to that. They run in this order.

## NativeDefaultArgumentStubGenerator

Three lowerings implement default arguments. For a function with defaults they generate a second function, `foo$default`,
with one extra `Int` parameter: a bit mask saying which arguments the caller left out. The missing values must be computed
on the callee's side, because a default expression may use private members or other parameters.

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
2. `NativeDefaultParameterCleaner` removes the default expressions from the original function. It leaves a dummy default
   value behind; the injector recognizes "has defaults" by that, also when the callee is in another file that has
   already been lowered.
3. `NativeDefaultParameterInjector` rewrites every call that omits an argument. A call that passes everything still calls
   the original function directly.

**Know this**
- The value passed in place of an omitted argument is never read. Native passes the zero of the machine type: `null` for
  references (which is why `name` is `String?` in the stub), `0`, `false`, a null pointer.
- The logic is shared with the other backends; the Native classes supply the zero values.

**Source:** [DefaultArgumentStubGenerator.kt](../../../../compiler/ir/backend.common/src/org/jetbrains/kotlin/backend/common/lower/DefaultArgumentStubGenerator.kt) (shared, all three),
[NativeDefaultParameterInjector.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/NativeDefaultParameterInjector.kt)
· **Runs:** after inlining, always.

## VarargInjectionLowering

Replaces every `vararg` argument list with code that builds the array. The callee simply receives an array, so the
caller has to allocate and fill it.

```kotlin
fun f(vararg xs: String) = xs.size

// before                      // after
f(x, y)                        f({ val array = Array<String>(2)
                                   array.setWithoutBoundCheck(0, x)
                                   array.setWithoutBoundCheck(1, y)
                                   array })

f(x, *more)                    f({ val array = Array<String>(1 + more.size)     // size: plain elements + each spread
                                   var index = 0
                                   array.setWithoutBoundCheck(index, x); index += 1
                                   val length = more.size
                                   more.copyInto(array, index, 0, length); index += length
                                   array })

f("a", "b")                    f(<constant array ["a", "b"]>.copyOf())          // all elements are constants
```

**Know this**
- When all elements are compile-time constants, the array is a constant object in the binary and each call only copies it.
  The copy is needed because the callee may modify the array. For the same reason a spread always copies, even `f(*more)`
  alone.
- All elements are evaluated into temporaries first, left to right, before the array is allocated.

**Source:** [VarargLowering.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/VarargLowering.kt)
· **Runs:** after inlining, always.

## ConstructorsLowering

Splits object creation into its two real steps. A constructor becomes an ordinary function `<init>` that takes the new
object as `this`, and `Foo(args)` becomes "allocate an empty object, then call `<init>` on it".

```kotlin
// before
class Point(val x: Int, val y: Int) {
    constructor(x: Int) : this(x, 0)
}
fun makePoint(): Point = Point(1)

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
```

**Know this**
- A superclass constructor call becomes the same kind of plain call. `initInstance(obj, Foo(args))`, which earlier
  lowerings use to construct into an already allocated object ([enums](enums.md#enumclasslowering),
  [objects](../../features/initialization.md)), becomes `obj.<init>(args)`. Value classes are skipped: they have no
  allocation, and [Autoboxing](boxing.md#autoboxing) handles their constructors.
- Right after it, **ReturnsInsertionLowering** appends `return theUnitInstance()` (an intrinsic standing for the `Unit`
  object) to every function that returns `Unit`, including the new `<init>` functions, so that every path ends in an
  explicit return. It does so unconditionally, even after a body that already ends with `return` or `throw`.

**Source:** [ConstructorsLowering.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/ConstructorsLowering.kt),
[ReturnsInsertionLowering.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/ReturnsInsertionLowering.kt)
· **Runs:** after inlining, always.
