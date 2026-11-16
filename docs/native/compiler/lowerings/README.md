# Lowerings

A **lowering** rewrites Kotlin IR into simpler Kotlin IR. Each one removes a language feature (enums, lambdas, `suspend`,
default arguments, …) by expressing it through plainer constructs. After the last lowering the IR contains only what LLVM code
generation understands: classes with fields, functions, calls, branches, loops and `try`/`catch`.

```kotlin
// what you write                      // what code generation sees (roughly)
enum class Color { RED, BLUE }         class Color : Enum<Color> { static val $VALUES: Array<Color> ... }
val f = { x: Int -> x + 1 }            class main$f$0 : Function1<Int, Int> { fun invoke(x: Int) = x + 1 }
fun greet(name: String = "you")        fun greet$default(name: String?, mask: Int) { ... }
```

## When they run

Almost all lowerings run in the second stage (klibs → binary), one file at a time, in a fixed order. The inliner splits them
into two lists:

```mermaid
flowchart LR
    K["IR from klibs"] --> A["before inlining<br/>(10 lowerings)"] --> I["inline all<br/>functions"] --> B["after inlining<br/>(~70 lowerings)"] --> O["whole-program<br/>optimizations"] --> C["LLVM IR"]
    O -.- D["a few cleanup lowerings<br/>run between optimizations"]
```

- **Per file.** A lowering sees one file and may only change that file. Other files may be at a different step.
- **The inliner is the exception.** It copies function bodies across files, so every file is first brought to the same point
  (everything before inlining is done), and only then inlining starts.
- **A few lowerings run in the first stage** (sources → klib), before the IR is written to the klib. Their result is stored in
  the klib, so there are few of them. Inlining is one: with the current default language version, inline functions are
  already inlined in the klib, and the second-stage inliner only has work left for klibs built with an older one.

To see what a lowering does to your code, dump the IR around it:
`-Xphases-to-dump-before=EnumClassLowering -Xphases-to-dump-after=EnumClassLowering`. The phase name is the class name.

## All lowerings

In the order they run. A linked name has its own page with a before/after example. *Shared* lowerings are common to all
backends and live in [backend.common](../../../../compiler/ir/backend.common/src/org/jetbrains/kotlin/backend/common/lower/);
the others are Native-specific and live in
[backend/konan/lower](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/).
The ordered lists themselves are in
[NativeLoweringPhases.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/driver/phases/NativeLoweringPhases.kt).

### Before inlining

| Lowering | What it does |
|---|---|
| [TestProcessor](TestProcessor.md) | Generates test-suite classes that register the file's `@Test` functions with the test runner |
| UpgradeCallableReferences *(shared)* | Brings lambdas and `::references` to one uniform IR shape |
| [NativeAssertionWrapperLowering](NativeAssertionWrapperLowering.md) | Wraps `assert(...)` so it can be switched on or off when the binary is built |
| LateinitLowering *(shared)* | Adds the "has it been initialized?" check to `lateinit` reads |
| SharedVariablesLowering *(shared)* | Puts a local `var` that a lambda modifies into a heap box |
| LocalClassesInInlineLambdasLowering *(shared)* | Moves local classes out of inline lambdas so inlining does not duplicate them |
| ArrayConstructorLowering *(shared)* | `Array(n) { i -> ... }` becomes a loop |
| [NativePrivateFunctionInlining](NativeInlining.md) | Inlines private inline functions |
| OuterThisInInlineFunctionsSpecialAccessorLowering *(shared)* | Adds an accessor for the outer `this` used inside an inline function |
| SyntheticAccessorLowering *(shared)* | Adds accessors for private members that an inline function exposes to other files |
| [NativeAllFunctionInlining](NativeInlining.md) | Inlines all remaining inline functions, across files and klibs |
| [SpecialObjCValidationLowering](SpecialObjCValidationLowering.md) | Reports suspend calls inside `autoreleasepool { }` |

### After inlining

| Lowering | What it does |
|---|---|
| ConstEvaluationLowering *(shared)* | Evaluates constant expressions at compile time |
| [ReifiedFunctionLowering](ReifiedFunctionLowering.md) | Replaces the leftover bodies of `reified` inline functions with a throw |
| [TypeOfProcessingLowering](TypeOfProcessingLowering.md) | `typeOf<T>()` becomes a constant `KType` |
| [NativeSharedVariablesPrimitiveBoxSpecializationLowering](NativeSharedVariablesPrimitiveBoxSpecializationLowering.md) | Uses a specialized box for captured `var`s of primitive type |
| [InteropLowering](InteropLowering.md) | Calls to C and Objective-C, and Kotlin subclasses of ObjC classes, become bridges and C stubs |
| [SpecialInteropIntrinsicsLowering](SpecialInteropIntrinsicsLowering.md) | The cinterop `typeOf<T>()` behind `sizeOf<T>()` and `alloc<T>()` becomes the companion object of `T` |
| [TestsInitializer, TestsDumper](TestProcessor.md) | Register the generated test suites at startup; write the list of tests to a file (the latter only with `-tr`) |
| ExpectDeclarationsRemoveLowering *(shared)* | Removes `expect` declarations |
| StripTypeAliasDeclarationsLowering *(shared)* | Removes `typealias` declarations |
| [NativeAssertionRemoverLowering](NativeAssertionWrapperLowering.md) | Keeps or removes assertions depending on `-enable-assertions` |
| [VolatileFieldsLowering](VolatileFieldsLowering.md) | Adds compare-and-set, exchange and add intrinsics to `@Volatile` properties and makes the stdlib atomics call them |
| DelegatedPropertyOptimizationLowering *(shared)* | Simplifies trivial property delegation |
| [PropertyReferenceLowering](PropertyReferenceLowering.md) | `::prop` becomes a `KProperty` object |
| [NativeFunctionReferenceLowering](NativeFunctionReferenceLowering.md) | Lambdas and `::function` references become classes implementing `FunctionN` |
| [NativeSingleAbstractMethodLowering](NativeSingleAbstractMethodLowering.md) | A function value converted to a `fun interface` is wrapped in a generated adapter class |
| [PostInlineLowering](PostInlineLowering.md) | `Foo::class` becomes a constant, `x::class` a `KClassImpl` built from the object's type info, `immutableBlobOf(...)` a constant blob |
| [ContractsDslRemover](ContractsDslRemover.md) | Meant to remove the contracts DSL classes (`@ContractsDsl`) from the stdlib; currently changes nothing |
| [NativeAnnotationImplementationLowering](NativeAnnotationImplementationLowering.md) | Creating an annotation instance becomes creating a generated implementation class |
| RangeContainsLowering *(shared)* | `x in a..b` becomes `a <= x && x <= b` |
| [EnumConstructorsLowering](EnumConstructorsLowering.md) | Adds the `name` and `ordinal` parameters to enum constructors |
| InitializersLowering *(shared)* | Moves property initializers and `init` blocks into constructors |
| [InteropBridgesNameInventor](InteropBridgesNameInventor.md) | Gives interop bridges unique names and registers their C stubs for compilation |
| [NativeInventNamesForLocalClasses, NativeKlibInventNamesForLocalFunctions](NativeLocalDeclarationsLowering.md) | Choose names like `outer$Local` for local classes, anonymous objects and local functions |
| LocalDelegatedPropertiesLowering *(shared)* | A local `val x by d` becomes a variable plus `getValue` calls |
| [NativeLocalDeclarationsLowering](NativeLocalDeclarationsLowering.md) | Local functions and classes get their captured variables as explicit parameters and fields |
| LocalDeclarationPopupLowering *(shared)* | Moves those local declarations up to the enclosing class or file |
| [NativeTailrecLowering](NativeTailrecLowering.md) | `tailrec` functions become loops |
| [NativeFinallyBlocksLowering](NativeFinallyBlocksLowering.md) | Copies each `finally` block onto every way out of its `try` |
| [ComputeTypesPass](ComputeTypesPass.md) | Recomputes precise expression types that inlining made too general (runs twice) |
| [NativeForLoopsLowering](NativeForLoopsLowering.md) | `for` over ranges, progressions and arrays becomes a counting loop without an iterator; array accesses proven in range skip the bounds check |
| FlattenStringConcatenationLowering, StringConcatenationLowering *(shared)* | String templates and `+` become `StringBuilder` appends |
| [StringConcatenationTypeNarrowing](StringConcatenationTypeNarrowing.md) | Calls `toString()` up front so appends take a `String` (`-opt` only) |
| [NativeDefaultArgumentStubGenerator, …Cleaner, …Injector](NativeDefaultArguments.md) | Default arguments: a `foo$default` stub with a bit mask, and callers that pass the mask |
| [InnerClassLowering](InnerClassLowering.md) | An `inner class` gets a field holding the outer instance |
| [DataClassOperatorsLowering](DataClassOperatorsLowering.md) | In a data class's generated `toString`/`hashCode`, array properties get `contentToString()`/`contentHashCode()` |
| IfNullExpressionsFusionLowering *(shared)* | Simplifies chains of `?.` and `?:` |
| [StaticCallableReferenceOptimization](StaticCallableReferenceOptimization.md) | A lambda, function reference or property reference that captures nothing becomes a constant object |
| [NativeEnumWhenLowering](NativeEnumWhenLowering.md) | `when` over an enum compares ordinals |
| [EnumClassLowering](EnumClassLowering.md) | An enum class becomes a class with a static array of instances |
| [EnumUsageLowering](EnumUsageLowering.md) | `Color.RED`, `enumValues<T>()` and friends become calls into that class |
| [VarargInjectionLowering](VarargInjectionLowering.md) | `vararg` arguments become an explicitly built array |
| KotlinNothingValueExceptionLowering *(shared)* | Throws if a call declared to return `Nothing` returns anyway |
| [NativeSuspendFunctionsLowering](NativeSuspendFunctionsLowering.md) | A `suspend` function with a suspend call that is not a tail call becomes a state-machine class |
| AddContinuationToNonLocalSuspendFunctionsLowering *(shared)* | Adds the hidden `Continuation` parameter to suspend functions |
| [NativeAddContinuationToFunctionCallsLowering](NativeAddContinuationToFunctionCallsLowering.md) | Passes the continuation at every suspend call |
| AddFunctionSupertypeToSuspendFunctionLowering *(shared)* | Makes `SuspendFunctionN` objects also implement `FunctionN+1` |
| [CoroutinesLivenessAnalysis, …Fallback](CoroutinesLivenessAnalysis.md) | Finds which variables are alive at each suspension point |
| ExpressionBodyTransformer *(shared)* | `fun f() = x` becomes `fun f() { return x }` |
| [ObjectClassLowering](ObjectClassLowering.md) | An `object` becomes a static instance field with a getter |
| [StaticInitializersLowering](StaticInitializersLowering.md) | Initializers of globals move into lazy init functions, called on first access |
| [RemoveCastsFromNothingLowering](RemoveCastsFromNothingLowering.md) | Removes casts and type checks applied to expressions of type `Nothing` |
| [CastsOptimization](CastsOptimization.md) | Marks casts that are proven to succeed so that no run-time check is generated (runs when generic casts are checked: debug builds by default) |
| [TypeOperatorLowering](TypeOperatorLowering.md) | `as?` becomes an `is` check; `as T` is retargeted to the erased type (the upper bound of `T`) |
| [BuiltinOperatorLowering](BuiltinOperatorLowering.md) | `==`, `!!` and the "no `when` branch matched" error become null checks, `equals` calls or primitive comparisons |
| [BridgesBuilding, WorkersBridgesBuilding](BridgesBuilding.md) | Adds bridge functions where an override's machine-level signature differs from the overridden one |
| [ExportCachesAbiVisitor, ImportCachesAbiTransformer](CachesAbiLowering.md) | Accessors for internals that code in another cache needs (the export half only when building a cache) |
| [GenericCallsReturnTypeEraser](GenericCallsReturnTypeEraser.md) | A call gets the type its callee really returns (`T` becomes `Any?`), with an implicit cast back to the expected type |
| [Autoboxing](Autoboxing.md) | Inserts explicit box/unbox where a primitive or value class meets `Any` or a generic |
| [ConstructorsLowering](ConstructorsLowering.md) | `Foo(args)` becomes "allocate, then call the constructor as a function" |
| [ReturnsInsertionLowering](ReturnsInsertionLowering.md) | Adds the explicit `return` at the end of `Unit` functions |
| [CastsLowering](CastsLowering.md) | Remaining `as` casts become calls to a runtime check function (without `-opt`; with `-opt` code generation compiles the check inline) |

### Between the whole-program optimizations

| Lowering | What it does |
|---|---|
| PropertyAccessorInlineLowering *(shared)* | Replaces calls to trivial property accessors with field reads (`-opt` only) |
| [InlineClassPropertyAccessorsLowering](InlineClassPropertyAccessorsLowering.md) | A call to the getter of a value class's underlying property becomes a no-op reinterpretation (`-opt` only) |
| [RedundantCoercionsCleaner](RedundantCoercionsCleaner.md) | Removes `box(unbox(x))` pairs |
| [UnboxInlineLowering](UnboxInlineLowering.md) | Turns unbox calls into reads of the box's field (`-opt` only) |
| [CoroutinesVarSpillingLowering](CoroutinesVarSpillingLowering.md) | Saves live variables into the coroutine object before a suspension and restores them after |

### First stage (stored in the klib)

| Lowering | What it does |
|---|---|
| [NativeExportedBridgeCallDispatchLowering](NativeExportedBridgeCallDispatchLowering.md) | In Swift-export bridges that ask for it, makes the call of the target method non-virtual |
| TestProcessor, UpgradeCallableReferences, NativeAssertionWrapperLowering | Same as above; run here when the corresponding language feature is on |
| Pre-serialization inliner, TailrecCheckerLowering *(shared)* | Inlining of `inline` functions before serialization; `tailrec` validation |
