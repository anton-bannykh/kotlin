# StaticCallableReferenceOptimization

Replaces the creation of a lambda, function reference or property reference object that captures nothing with a
**constant object**: an instance laid out in the binary's data at compile time.

**Why:** such an object has no state, so every evaluation of `{ x -> x + 1 }` would allocate an identical object. A constant
costs no allocation and no work for the garbage collector.

```kotlin
// before (lambdas and references are already top-level classes at this point)
fun refs(delta: Int, p: Person) {
    val capturing = `refs$capturing$1`(delta)                          // has a constructor argument
    val plain     = `refs$plain$1`()                                   // { x: Int -> x + 1 }
    val ref       = `refs$ref$$FUNCTION_REFERENCE_FOR$twice$5`()       // ::twice
    val boundRef  = `refs$boundRef$$FUNCTION_REFERENCE_FOR$toString$6`(p)
    val unbound   = KProperty1Impl<Person, String>("name", null, `refs$unbound$$FUNCTION_REFERENCE_FOR$<get-name>$1`())
}

// after
fun refs(delta: Int, p: Person) {
    val capturing = `refs$capturing$1`(delta)                          // unchanged: allocated on every call
    val plain     = <constant refs$plain$1>
    val ref       = <constant refs$ref$$FUNCTION_REFERENCE_FOR$twice$5>
    val boundRef  = `refs$boundRef$$FUNCTION_REFERENCE_FOR$toString$6`(p)   // unchanged: holds p
    val unbound   = <constant KProperty1Impl("name", null, <constant …FUNCTION_REFERENCE_FOR$<get-name>$1>)>
}
```

**Know this**
- The rule for function classes is "the constructor has no parameters". For a `KProperty` object it is "all constructor
  arguments are themselves constants", which holds when the getter and setter references became constants first.
- An object that captures something (`capturing`, `boundRef`) is still allocated each time the expression is evaluated.

**Source:** [StaticCallableReferenceOptimization.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/StaticCallableReferenceOptimization.kt)
· **Runs:** after inlining, always (not only with `-opt`), after local classes were moved to the top level.
