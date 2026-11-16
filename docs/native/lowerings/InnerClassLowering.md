# InnerClassLowering

Gives every `inner class` a field that holds the instance of the outer class, and rewrites each use of the outer `this`
into a read of that field.

**Why:** an inner class can use members of the object it was created in. That object is not a language-level property of
the inner class, so it needs a real field to live in.

```kotlin
// before
class Outer(val x: Int) {
    inner class Inner(val y: Int) {
        fun sum() = x + y                          // `x` means this@Outer.x
        inner class Deep { fun all() = x + y }
    }
}
fun use(o: Outer) = o.Inner(2).sum()

// after
class Outer(val x: Int) {
    inner class Inner {
        protected val `$this$0`: Outer
        constructor(y: Int) {
            this.`$this$0` = <outer>                // first statement, before the super constructor call
            super(); this.y = y
        }
        fun sum() = this.`$this$0`.x + this.y
        inner class Deep {
            protected val `$this$0`: Inner
            fun all() = this.`$this$0`.`$this$0`.x + this.`$this$0`.y    // one hop per nesting level
        }
    }
}
fun use(o: Outer) = o.Inner(2).sum()               // unchanged
```

**Know this**
- The constructor does not get an extra parameter. In IR the constructor of an inner class already has a receiver, the outer
  instance (`o` in `o.Inner(2)`, shown as `<outer>` above), and the lowering simply stores it. So call sites do not change.
- The field is assigned before the super constructor runs, so a base-class constructor that calls an overridden method
  already sees a valid outer instance.

**Source:** [InnerClassLowering.kt](../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/InnerClassLowering.kt)
· **Runs:** after inlining, always.
