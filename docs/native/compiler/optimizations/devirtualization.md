[Kotlin/Native guide](../../README.md) › [compiler](../README.md) › [optimizations](README.md) › devirtualization

# Devirtualization

*Skip this page if you know when a virtual call becomes a direct call or a `when` over classes, and when it does not.*

A call to an `open` or interface function is a **virtual call**: at run time the code looks the function up in a table
attached to the object's class (→ [object-model.md](../../runtime/object-model.md)). Devirtualization finds the call sites
where only one or a few functions can actually be the target, and replaces the lookup with direct calls. It is the first of
the [whole-program optimizations](README.md) and the one the others build on.

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
`when` is a load of the object's class pointer, a comparison with `@"kclass:Circle"` and two direct calls, where a debug
build calls the dispatch function `kfun:Shape#area(){}kotlin.Double` ([codegen.md](../codegen.md#a-virtual-call-and-an-interface-call)).

## The idea

The compiler follows objects through the [data-flow graph](README.md#the-data-flow-graph) of the whole program:

1. **Find the classes that are ever instantiated.** Starting from `main`, walk everything reachable and note each
   allocation. Here that gives `Circle`, `Square` and `ConsoleLogger`; no call can ever land in `Triangle` or `SilentLogger`.
2. **Track where each object can go.** An allocation flows into a variable, from an argument into the callee's parameter,
   from a `return` back to the caller, into a field and out of it again. A cast or an `is` check filters what passes through.
   The result is, for every variable and parameter, the set of classes it can hold. For parameter `s` of `describe` that
   set is {`Circle`, `Square`}, because the only value that reaches it comes from `pick`.
3. **Look at each virtual call.** The set of classes of the receiver gives the set of functions that can be called.

## What it produces

Depending on how many different functions can be the target of a call site:

| Candidates | The call becomes |
|---|---|
| 1 | A direct call |
| 2 or 3 | A `when` over the object's class with a direct call in each branch. The last branch has no check |
| more | Stays virtual |
| 0 | Code that throws: no object of a suitable class is ever created, so the call cannot happen |

## Why it matters

- **Inlining.** A direct call avoids the table lookup, and, the bigger win, LLVM can inline it: getters and other tiny
  functions then disappear completely.
- **The other analyses become precise.** [Escape analysis](escape-analysis.md) must know what a callee does with its
  arguments, and [dead code elimination](dce.md) uses the same information to drop methods that no call can reach, like
  `Triangle.area`.

## Things to know

- **More than three candidates: the call stays virtual.** The limit keeps the chain of class checks short. With
  `open class Animal` and subclasses `Dog`, `Puppy`, `Cat` all instantiated and all overriding `sound()`, a call
  `animal.sound()` has four candidates and is not touched.
- **The analysis ignores statement order and conditions, and is not per-path.** It answers "can a `Circle` ever get here?".
  If one list in the program holds `Circle`s and an unrelated one holds `Square`s, a call on an element of either sees both
  classes: all objects stored into any `Array` are treated as one set.
- **Libraries are handled conservatively.** When the output is a framework or a library, every public function is a
  starting point, every public non-abstract class is assumed to be instantiated, and a field may hold an object of a class
  the compiler has never seen. Far fewer calls can be devirtualized. Objective-C classes are never devirtualized.
- **It is split into two phases**, `DevirtualizationAnalysis` (computes the candidates for each call) and
  `Devirtualization` (rewrites the IR). Other steps use the candidates even for the calls that stay virtual.

## Where the code is

- [DevirtualizationAnalysis.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/optimizations/DevirtualizationAnalysis.kt):
  the whole optimization. The analysis is the algorithm known as Variable Type Analysis; the paper is linked at the top.
  The limits (3 candidates) are the constants in `DevirtualizationUnfoldFactors`.
- [CodeGenerator.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/llvm/CodeGenerator.kt):
  how a call that stayed virtual is compiled (`getVirtualImpl`).

**Read next:** [escape-analysis.md](escape-analysis.md)
