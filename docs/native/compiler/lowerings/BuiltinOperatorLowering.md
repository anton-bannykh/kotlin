# BuiltinOperatorLowering

Replaces the built-in operators that have no function behind them (`==`, `!!`, the "no `when` branch matched" error) with
explicit null checks, calls and primitive comparisons.

**Why:** in the IR, `a == b` is a call to a placeholder `EQEQ` that means "compare the Kotlin way". What that is depends
on the operand types: compare two machine values, call `equals`, or handle `null` first. This lowering picks the right
one, so code generation does not have to.

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
- `equals` is only called when the left side is not null, and `null == null` is true without any call.
- Floating-point `==` on statically known `Double`/`Float` follows IEEE 754 (`NaN != NaN`), which is not what
  `Double.equals` does. That is why it gets its own intrinsic, `ieee754Equals`.
- A value class with the default `equals` is compared through its underlying value, without boxing.
- `===` on two primitives or value classes is treated like `==` (`i === j` becomes `i.equals(j)`), as on the JVM.

**Source:** [BuiltinOperatorLowering.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/BuiltinOperatorLowering.kt)
· **Runs:** after inlining, always, right after [TypeOperatorLowering](TypeOperatorLowering.md).
