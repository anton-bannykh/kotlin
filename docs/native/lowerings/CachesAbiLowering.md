# CachesAbiLowering (ExportCachesAbiVisitor, ImportCachesAbiTransformer)

Two lowerings that work as a pair. **ExportCachesAbiVisitor** adds public accessor functions for a few things that are
private to a library. **ImportCachesAbiTransformer** makes code outside that library use these accessors.

**Why:** a **cache** is a library compiled ahead of time into its own object code (see [caches.md](../caches.md)). Inside one
cache, private functions are not visible to the linker from outside. But lowered code sometimes needs something private
from another library: for example, a call to an inherited function resolves to the function in a private base class.
Without caches everything is one LLVM module and this is no problem. With caches the link would fail.

```kotlin
// lib.kt, compiled into its own cache
private open class Base { fun hello(): String = "hello" }
class Derived : Base()                          // Derived.hello() is really Base.hello(), a function of a private class
class Outer(val tag: String) { inner class Inner }
class Service { lateinit var name: String }

// lib.kt after ExportCachesAbiVisitor: these top-level functions are added
fun `$lib.Derived.hello_accessor`(inst: Derived): String = inst.hello()
fun `$lib.Outer.Inner.outerThis`(innerClass: Outer.Inner): Outer = innerClass.`$this$0`   // field holding the outer instance
fun `$lib.Service.name_field`(owner: Service): String? = owner.`name`                   // raw field: null if not initialized

// main.kt, compiled against the cache of lib
fun use(d: Derived) = d.hello()
// main.kt after ImportCachesAbiTransformer
fun use(d: Derived) = `$lib.Derived.hello_accessor`(inst = d)
```

**Know this**
- The accessors are not stored in the klib. Both sides create the same declaration from the same naming rule
  (`CachesAbiSupport`): the exporter gives it a body, the importer only declares it and the linker connects the two.
- There are four kinds: inherited functions whose real implementation is in a private class, the outer-instance field of an
  inner class, the backing field of a `lateinit` property, and top-level fields.
- The exporter runs only when a cache is being built. The importer always runs, and rewrites a use only when the target is
  not part of the LLVM module being generated. So in a build without caches nothing changes.

**Source:** [CachesAbiLowering.kt](../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/CachesAbiLowering.kt)
· **Runs:** after inlining, near the end. Export: only when producing a cache. Import: always.
