# NativeForLoopsLowering

Rewrites `for` loops over ranges, progressions (`until`, `downTo`, `step`, `indices`, ...) and arrays into a plain
counting loop over an integer variable.

**Why:** the frontend expresses every `for` through an iterator object: `iterator()`, `hasNext()`, `next()`. For a range
or an array that means an allocation and several calls per iteration, where a counter and a comparison are enough.

```kotlin
// before: what the frontend produces for `for (i in 0 until n) s += i`
val iterator = (0 until n).iterator()
while (iterator.hasNext()) { val i = iterator.next(); s += i }

// after
var inductionVariable = 0
if (inductionVariable < n) do {
    val i = inductionVariable
    inductionVariable += 1
    s += i
} while (inductionVariable < n)
```

```kotlin
// before: `for (x in a) s += x` with `a: IntArray`
val iterator = a.iterator()
while (iterator.hasNext()) { val x = iterator.next(); s += x }

// after
val indexedObject = a
var inductionVariable = 0
val last = indexedObject.size
while (inductionVariable < last) {
    val x = indexedObject.getWithoutBoundCheck(inductionVariable)   // the index is known to be in range
    inductionVariable += 1
    s += x
}
```

**Know this**
- The Native-specific part is **bounds-check elimination**. The shared `ForLoopsLowering` builds the loop; Native plugs in a
  body transformer that replaces `a[i]` with `getWithoutBoundCheck` (and `a[i] = v` with `setWithoutBoundCheck`) when the
  loop bounds prove the index valid, for example inside `for (i in a.indices)`.
- A closed range `1..n` ends with the test `i != n` instead of `inductionVariable <= n`, so it also works when `n` is
  `Int.MAX_VALUE`.
- Loops over other things, such as a `List`, keep their iterator.

**Source:** [ForLoopsLowering.kt](../../../compiler/ir/backend.common/src/org/jetbrains/kotlin/backend/common/lower/loops/ForLoopsLowering.kt) (shared),
[BCEForLoopBodyTransformer.kt](../../../compiler/ir/backend.common/src/org/jetbrains/kotlin/backend/common/lower/loops/BCEForLoopBodyTransformer.kt)
· **Runs:** after inlining, always.
