# Casts and types

*Skip this page if you know how `as`, `as?`, implicit casts and `==` are rewritten in the IR before code generation.*

After inlining the IR is full of casts: explicit `as`, smart casts, and implicit casts where generic code was erased. Four
lowerings remove the ones that cannot fail and bring the rest to one form:
`ComputeTypesPass → CastsOptimization → TypeOperatorLowering → CastsLowering`. The last two sections use static types to
pick a cheaper operation for `==` and for string templates. How a check works at run time, and in which builds implicit
casts are checked at all, is in [type-checks.md](../optimizations/type-checks.md). In the samples `Dog` is an `Animal`.

## ComputeTypesPass

Recomputes the types of local variables and expressions from the values that actually flow into them, and removes the
implicit casts that this makes redundant. The inliner erases generics: when `same<Dog>(d)` is inlined, its parameter
`x: T` becomes a variable of type `Any?`, which costs a cast back to `Dog` and may force boxing.

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
- It runs **twice**: soon after inlining, and again just before `CastsOptimization`, to help that analysis and to fix
  types that lowerings in between left imprecise.
- The analysis is local to one function body. A variable written from several places gets their common superclass;
  interfaces are never narrowed, because that would need the whole class hierarchy.

**Source:** [ComputeTypesPass.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/optimizations/ComputeTypesPass.kt)
· **Runs:** after inlining, always, after `NativeFinallyBlocksLowering` and after `StaticInitializersLowering`.

## CastsOptimization

Finds casts and type checks whose outcome is already known from the surrounding code, and marks or folds them so that no
run-time check is generated. Without it, in builds that check implicit casts, every smart cast would cost a real check.

```kotlin
fun smart(a: Any): String = if (a is Dog) a.bark() else ""

// before
fun smart(a: Any): String = when {
    a is Dog -> (a /*as Dog*/).bark()                   // implicit cast from the smart cast
    else -> ""
}

// after
fun smart(a: Any): String = when {
    a is Dog -> noCastNeeded { a /*as Dog*/ }.bark()    // proven by the `is` above: marked, will not be checked
    else -> ""
}
```

**Know this**
- It runs only when `genericSafeCasts` is on (debug builds by default); otherwise there is nothing to save. The analysis
  is per function and follows facts like "`a` is `Dog`" through branches, loops and `try`.
- `noCastNeeded` is a block with the origin `NO_CAST_NEEDED`. [Autoboxing](boxing.md#autoboxing), which turns implicit
  casts into checked ones, skips such blocks.
- Just before it, **RemoveCastsFromNothingLowering** drops every type operator whose argument has type `Nothing`, such as
  `error("no") as String` after `error` was inlined: the cast can never run.

**Source:** [CastsOptimization.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/optimizations/CastsOptimization.kt),
[RemoveCastsFromNothingLowering.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/RemoveCastsFromNothingLowering.kt)
· **Runs:** after the second `ComputeTypesPass`; `RemoveCastsFromNothingLowering` always.

## TypeOperatorLowering

Rewrites `as?` into an `is` check, and gives `as T` a class instead of a type parameter as its target, because at run
time an object only knows its class, not its type arguments.

```kotlin
// before
fun safe(a: Any): Dog? = a as? Dog
fun <T : Animal> bounded(a: Any?): T = a as T
fun <T> generic(a: Any?): T = a as T

// after
fun safe(a: Any): Dog? = when {
    a is Dog -> noCastNeeded { a /*as Dog*/ }        // implicit cast; the `is` just proved it, so it is never checked
    else -> null
}
fun <T : Animal> bounded(a: Any?): T = a as Animal  // T erased to its upper bound: this is all that can be checked
fun <T> generic(a: Any?): T = a as Any?             // upper bound is Any?: this cast can never fail
```

**Know this**
- `generic<String>(42)` therefore returns normally. What happens next in the caller depends on the build
  (→ [type-checks.md](../optimizations/type-checks.md#casts-caused-by-generics)).
- `a as Dog` and `a is Dog` are left unchanged. A cast to an Objective-C forward declaration (`@class Foo;`) is checked
  against `Any` only: there is no class to compare with.

**Source:** [TypeOperatorLowering.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/TypeOperatorLowering.kt)
· **Runs:** after inlining, always.

## CastsLowering

Turns every remaining `as` into a call to `downcast`, a small standard library function that checks and throws. A cast to
a superclass is dropped or reduced to a null check. Compiled inline, each cast is a null test, a type check and a call
that throws; debug builds have so many casts that this made the LLVM IR much bigger and slowed LLVM down.

```kotlin
// before
fun plain(a: Any): Dog = a as Dog
fun up(d: Dog?): Animal = d as Animal
fun first(dogs: List<Dog>): Dog = dogs.first() as Dog?      // was an implicit cast; Autoboxing made it a real one

// after
fun plain(a: Any): Dog = downcast<Dog>(a, Dog::class, nullable = false)
fun up(d: Dog?): Animal = checkNotNull<Animal>(d)            // Dog is an Animal: only null can go wrong
fun first(dogs: List<Dog>): Dog = downcast<Dog?>(dogs.first(), Dog::class, nullable = true)
```

**Know this**
- It runs only without `-opt`. With `-opt` the casts stay in the IR, and code generation compiles the same cases inline.
- `is` is always compiled by code generation, and so are casts to Objective-C classes.

**Source:** [CastsLowering.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/CastsLowering.kt);
the inline version is `evaluateCast` in
[IrToBitcode.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/llvm/IrToBitcode.kt)
· **Runs:** last in the list, after [Autoboxing](boxing.md#autoboxing); only without `-opt`.

## BuiltinOperatorLowering

Replaces the built-in operators that have no function behind them (`==`, `!!`, the "no `when` branch matched" error) with
explicit null checks, calls and primitive comparisons. In the IR, `a == b` is a call to a placeholder `EQEQ`; what it
means depends on the operand types.

```kotlin
// before                                   // after
i == j              // Int, Int             areEqualByValue(i, j)               // compares the two machine values
a == null           // Dog?                 a === null                          // pointer comparison
a == b              // Dog?, Dog?           if (a === null) b === null else a.equals(b)
x == y              // Double, Double?      if (y !== null) ieee754Equals(x, y) else false
a!!                 // Dog?                 { val tmp = a; if (tmp === null) ThrowNullPointerException(); tmp }
a === b             // references           a === b                             // unchanged
```

**Know this**
- Floating-point `==` on statically known `Double`/`Float` follows IEEE 754 (`NaN != NaN`), which is not what
  `Double.equals` does. That is why it gets its own intrinsic, `ieee754Equals`.
- A value class with the default `equals` is compared through its underlying value, without boxing. `===` on two
  primitives or value classes is treated like `==` (`i === j` becomes `i.equals(j)`), as on the JVM.

**Source:** [BuiltinOperatorLowering.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/BuiltinOperatorLowering.kt)
· **Runs:** after inlining, always, right after `TypeOperatorLowering`.

## StringConcatenationTypeNarrowing

Where an object is appended to a `StringBuilder` or added to a string, moves the `toString()` call to the call site.
There the argument's type is known, so [devirtualization](../optimizations/devirtualization.md) can resolve `toString()`
to one function and [escape analysis](../optimizations/escape-analysis.md) gets a more precise picture.

```kotlin
class Point(val x: Int)
fun f(p: Point, q: Point?) = "p=$p q=$q"

// before (string templates are already StringBuilder appends)      // after
sb.append("p=")                                                     sb.append("p=")
sb.append(p)                    // append(Any?)                     sb.append(p.toString())             // append(String?)
sb.append(" q=")                                                    sb.append(" q=")
sb.append(q)                    // append(Any?)                     sb.append(if (q === null) null else q.toString())
```

**Know this**
- `append(String?)` prints `"null"` for null itself. `String.plus(Any?)` is handled the same way: it becomes the internal
  `String.plusImpl(String)`.
- Arguments that have their own `append` overload (`Int`, `Char`, ...) were never `append(Any?)` calls and are not touched.

**Source:** [StringConcatenationTypeNarrowing.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/StringConcatenationTypeNarrowing.kt)
· **Runs:** after inlining, only with `-opt`, right after the shared `StringConcatenationLowering`.
