# Devirtualization

A call to an `open` or interface function is a **virtual call**: which function runs depends on the class of the object, so
at run time the code looks the function up in a table attached to that class (→ [object-model.md](../object-model.md)).
Devirtualization finds the call sites where only one or a few functions can actually be the target, and replaces the lookup
with direct calls. It is the first of the [whole-program optimizations](../optimizations.md) and the one the others build on.

```kotlin
interface Shape { fun area(): Double }
class Circle(val r: Double) : Shape { override fun area() = 3.14 * r * r }
class Square(val side: Double) : Shape { override fun area() = side * side }
class Triangle(val a: Double, val h: Double) : Shape { override fun area() = a * h / 2 }    // never created

interface Logger { fun log(message: String) }
class ConsoleLogger : Logger { override fun log(message: String) = println(message) }
class SilentLogger : Logger { override fun log(message: String) {} }                        // never created

fun pick(n: Int): Shape = if (n % 2 == 0) Circle(n.toDouble()) else Square(n.toDouble())
fun describe(s: Shape): Double = s.area()
fun report(logger: Logger, s: Shape) { logger.log("area = " + describe(s)) }

fun main(args: Array<String>) { report(ConsoleLogger(), pick(args.size)) }
```

IR of an `-opt` build, before and after the phase `Devirtualization` (shortened):

```kotlin
// before
fun describe(s: Shape): Double {
    return s.area()                                     // interface call
}
fun report(logger: Logger, s: Shape) {
    logger.log("area = ".plusImpl(describe(s).toString()))      // interface call
}

// after
fun describe(s: Shape): Double {
    val typeInfo = getObjectTypeInfo(s)                 // the object's class
    return when {
        typeInfo == Circle::class -> super<Circle>.area()       // direct call to Circle.area
        else -> super<Square>.area()                    // nothing else is possible, so no second check
    }
}
fun report(logger: Logger, s: Shape) {
    super<ConsoleLogger>.log("area = ".plusImpl(describe(s).toString()))    // one candidate: a plain direct call
}
```

`super<Circle>.area()` is how the IR writes "call exactly `Circle.area` on this object, without dispatch". In LLVM IR the
difference looks like this:

```llvm
; debug build: describe calls a small dispatch function that finds the implementation in the object's interface table
  %2 = call double @"kfun:Shape#area(){}kotlin.Double"(ptr %0)

; -opt build
  %2 = call ptr @Kotlin_Any_getTypeInfo(ptr %0)                         ; load the pointer to the class description
  %3 = icmp eq ptr %2, @"kclass:Circle"                                 ; is it Circle?
  br i1 %3, label %when_case, label %when_next
when_case:
  %4 = call double @"kfun:Circle#area(){}kotlin.Double"(ptr %0)         ; direct call
when_next:
  %5 = call double @"kfun:Square#area(){}kotlin.Double"(ptr %0)         ; direct call
```

## The idea

The compiler follows objects through the [data-flow graph](../optimizations.md#the-data-flow-graph) of the whole program:

1. **Find the classes that are ever instantiated.** Starting from `main`, walk everything reachable and note each
   allocation. Here that gives `Circle`, `Square` and `ConsoleLogger`. `Triangle` and `SilentLogger` are declared but never
   created, so no call can ever land in their methods.
2. **Track where each object can go.** An allocation flows into a variable, from an argument into the callee's parameter,
   from a `return` back to the caller, into a field and out of it again. A cast or an `is` check filters what passes through.
   The result is, for every variable and parameter, the set of classes it can hold. For parameter `s` of `describe` that
   set is {`Circle`, `Square`}, because the only value that reaches it comes from `pick`.
3. **Look at each virtual call.** The set of classes of the receiver gives the set of functions that can be called.

The analysis does not look at the order of statements or at conditions. It answers "can a `Circle` ever get here?", not
"can it get here on this path?".

## What it produces

Depending on how many different functions can be the target of a call site:

| Candidates | The call becomes |
|---|---|
| 1 | A direct call |
| 2 or 3 | A `when` over the object's class with a direct call in each branch. The last branch has no check |
| more | Stays virtual |
| 0 | Code that throws: no object of a suitable class is ever created, so the call cannot happen |

## Why it matters

- **Speed of the call itself.** A direct call avoids the table lookup and an indirect jump.
- **Inlining.** LLVM cannot inline a call through a pointer loaded from a table. It can inline a direct call, and getters
  and other tiny functions then disappear completely. This is usually the bigger win.
- **The other analyses become precise.** [Escape analysis](escape-analysis.md) must know what a callee does with its
  arguments. For a direct call it knows; for a virtual call with many possible targets it has to assume the worst.
  [Dead code elimination](dce.md) uses the same information to drop methods that no call can reach, like `Triangle.area`.

## Things to know

- **More than three candidates: the call stays virtual.** The limit keeps the chain of class checks short and the code
  small. For example, with `open class Animal` and subclasses `Dog`, `Puppy`, `Cat` all instantiated and all overriding
  `sound()`, a call `animal.sound()` has four candidates and is not touched.
- **The analysis is whole-function, not per-path.** If two unrelated lists exist in the program, one holding `Circle`s and
  one holding `Square`s, a call on an element of either list sees both classes: all objects stored into any `Array` are
  treated as one set.
- **Libraries are handled conservatively.** When the output is a framework or a library there is no `main` to start from.
  Every public function is a starting point, every public non-abstract class is assumed to be instantiated, and a field
  may hold an object of a class the compiler has never seen, stored there by the library's user. Far fewer calls can be
  devirtualized.
- **Objective-C classes are never devirtualized.** A value of such a type is treated as "unknown class".
- **It is split into two phases**, `DevirtualizationAnalysis` (computes the candidates for each call) and
  `Devirtualization` (rewrites the IR). Other steps use the candidates even for the calls that stay virtual.
- **The same program compiled in debug mode has none of this.** Every virtual call goes through the tables.

## Where the code is

- [DevirtualizationAnalysis.kt](../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/optimizations/DevirtualizationAnalysis.kt):
  the whole optimization. The analysis is the algorithm known as Variable Type Analysis; the paper is linked at the top.
  The limits (3 candidates) are the constants in `DevirtualizationUnfoldFactors`.
- [DFGBuilder.kt](../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/optimizations/DFGBuilder.kt):
  builds the data-flow graph the analysis works on.
- [LTO.kt](../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/driver/phases/LTO.kt):
  the two phases.
- [CodeGenerator.kt](../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/llvm/CodeGenerator.kt):
  how a call that stayed virtual is compiled (`getVirtualImpl`).

**Read next:** [escape-analysis.md](escape-analysis.md)
