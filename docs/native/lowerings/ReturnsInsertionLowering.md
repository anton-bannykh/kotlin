# ReturnsInsertionLowering

Appends `return theUnitInstance()` to the body of every function that returns `Unit`.

**Why:** in Kotlin a `Unit` function may simply run off its end. Code generation wants every path through a function to end
in an explicit return, so that it never has to work out whether the end of a body is reachable.

```kotlin
// before
fun log(s: String) {
    println(s)
}
fun logIf(s: String?) {
    if (s == null) return theUnitInstance()       // an explicit `return` already has its value
    println(s)
}

// after
fun log(s: String) {
    println(s)
    return theUnitInstance()                      // added
}
fun logIf(s: String?) {
    if (s == null) return theUnitInstance()
    println(s)
    return theUnitInstance()                      // added; it is not checked whether the end is reachable
}
```

**Know this**
- The return is added unconditionally, even after a body that already ends with `return` or `throw`.
- Constructors are covered too: by now they are `<init>` functions returning `Unit`
  (see [ConstructorsLowering](ConstructorsLowering.md)).
- `theUnitInstance()` is an intrinsic that stands for the `Unit` object; it is not a real call.

**Source:** [ReturnsInsertionLowering.kt](../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/ReturnsInsertionLowering.kt)
· **Runs:** after inlining, always.
