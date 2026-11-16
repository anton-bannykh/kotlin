# StringConcatenationTypeNarrowing

Makes string concatenation pass `String` values instead of `Any?`: where an object is appended to a `StringBuilder` or
added to a string, the `toString()` call is moved to the call site.

**Why:** `StringBuilder.append(Any?)` calls `toString()` on its argument somewhere inside the standard library, where
nothing is known about the argument. At the call site the type is known, so the whole-program optimizations can resolve the
`toString()` call to one function ([devirtualization](../optimizations/devirtualization.md)), and
[escape analysis](../optimizations/escape-analysis.md) gets a more precise picture.

```kotlin
class Point(val x: Int)
fun f(p: Point, q: Point?) = "p=$p q=$q"

// before (string templates are already StringBuilder appends at this point)
val sb = StringBuilder()
sb.append("p=")
sb.append(p)                                       // append(Any?)
sb.append(" q=")
sb.append(q)                                       // append(Any?)
sb.toString()

// after
val sb = StringBuilder()
sb.append("p=")
sb.append(p.toString())                            // append(String?)
sb.append(" q=")
sb.append(if (q === null) null else q.toString())  // append(String?) prints "null" for null itself
sb.toString()
```

**Know this**
- `String.plus(Any?)` is handled the same way: it becomes the internal `String.plusImpl(String)`, with the argument
  converted by `toString()`, or replaced by `"null"` if it is null.
- Arguments that have their own `append` overload (`Int`, `Char`, ...) were never `append(Any?)` calls and are not
  touched.

**Source:** [StringConcatenationTypeNarrowing.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/StringConcatenationTypeNarrowing.kt)
· **Runs:** after inlining, only with `-opt`, right after the shared `StringConcatenationLowering`.
