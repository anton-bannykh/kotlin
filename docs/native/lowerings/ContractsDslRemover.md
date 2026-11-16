# ContractsDslRemover

Meant to delete the classes of the contracts DSL (the stdlib declarations marked `@ContractsDsl`, such as
`ContractBuilder`) so that they are not compiled into the binary. It works on declarations, not on `contract { }` calls.

**Why:** a contract is information for the frontend only. Nothing of it is needed at run time.

```kotlin
// source
fun requireNotNull2(x: Any?) {
    contract { returns() implies (x != null) }
    if (x == null) throw Error()
}

// the IR that lowerings receive: the `contract { }` call is already gone
fun requireNotNull2(x: Any?) {
    if (x == null) throw Error()
}

// ContractBuilder.kt in the stdlib, before and after ContractsDslRemover: no difference today
@ContractsDsl interface ContractBuilder { ... }
```

**Know this**
- With the K2 frontend, `contract { }` calls are dropped when the IR is built, before any lowering. No lowering has to
  remove them.
- In the current code this lowering changes nothing. For an annotated class it returns `null` to the list helper
  `transformFlat`, where `null` means "keep the element", so the class stays. In the dump above `ContractBuilder` is still
  there after the phase. Do not rely on this lowering to remove anything.

**Source:** [ContractsDslRemover.kt](../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/ContractsDslRemover.kt)
· **Runs:** after inlining, always.
