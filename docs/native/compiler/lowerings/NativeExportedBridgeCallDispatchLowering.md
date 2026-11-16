# NativeExportedBridgeCallDispatchLowering

Inside a Swift export bridge function marked `@ExportedBridge(..., nonVirtualTargetMethod = "name")`, turns the call of
the method `name` from a virtual call into a direct call of that exact implementation, as `super.name()` would be.

**Why:** Swift export lets a Swift class inherit from a Kotlin class (see [export.md](../../interop/export.md)). For such a class the
virtual-table entries of the Kotlin object are redirected to the Swift methods. When Swift code then needs the inherited
Kotlin implementation (a `super` call, or a method it did not override), an ordinary virtual call would land in Swift
again and recurse forever. The bridge must skip the virtual table.

```kotlin
open class Base { open fun greet(name: String) = "hi $name" }

// before: two bridges with the same body
@ExportedBridge("Base_greet")
fun Base_greet(self: Base, name: String): String = self.greet(name)
@ExportedBridge("Base_greet_direct", nonVirtualTargetMethod = "greet")
fun Base_greet_direct(self: Base, name: String): String = self.greet(name)

// after
fun Base_greet(self: Base, name: String): String = self.greet(name)                 // virtual call, unchanged
fun Base_greet_direct(self: Base, name: String): String = self.super<Base>.greet(name)   // always Base.greet
```

**Know this**
- "Call this method of another object non-virtually" cannot be written in Kotlin source. That is why the bridge generator
  emits a normal call plus the annotation argument, and this lowering fixes the IR.
- The same annotation is used on the bridge that runs the constructor of an abstract or sealed Kotlin class for a Swift
  subclass. There the constructor call becomes a delegating constructor call (the IR form of `super(...)`). A plain
  constructor call of an abstract class would be replaced by a linkage error when the klib is loaded.
- A call of an abstract method is left virtual: there is no implementation to call directly.

**Source:** [NativeExportedBridgeCallDispatchLowering.kt](../../../../compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/NativeExportedBridgeCallDispatchLowering.kt)
· **Runs:** in the first stage, always, before the IR is written to the klib.
