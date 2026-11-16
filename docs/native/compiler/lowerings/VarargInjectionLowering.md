# VarargInjectionLowering

Replaces every `vararg` argument list with code that builds the array explicitly.

**Why:** in IR, `f(1, x, *rest)` holds a special "vararg" node listing elements and spreads. Code generation has no such
node: the callee simply receives an array, so somebody has to allocate and fill it.

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
f()                            f(<constant empty array>.copyOf())
```

**Know this**
- When all elements are compile-time constants, the array is a constant object in the binary and each call only copies it.
  The copy is needed because the callee may modify the array it receives.
- A spread always copies, even `f(*more)` alone: the callee gets a fresh array, never `more` itself.
- All elements are evaluated into temporaries first, left to right, before the array is allocated.
- Primitive varargs (`vararg xs: Int`) build an `IntArray` the same way, and unsigned ones a `UIntArray`.

**Source:** [VarargLowering.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/VarargLowering.kt)
· **Runs:** after inlining, always.
