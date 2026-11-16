[Kotlin/Native guide](../../README.md) › [compiler](../README.md) › [lowerings](README.md) › all-lowerings

# All lowerings
*Reference material: a lookup table. For what lowerings are and how they are grouped, see [README.md](README.md).*

In the order they run. A linked name leads to its section on a theme page, with a before/after example. *Shared* lowerings are common to all
backends and live in [backend.common](../../../../compiler/ir/backend.common/src/org/jetbrains/kotlin/backend/common/lower/);
the others are Native-specific and live in
[backend/konan/lower](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/).
The ordered lists themselves are in
[NativeLoweringPhases.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/driver/phases/NativeLoweringPhases.kt).

### Before inlining

| Lowering | What it does |
|---|---|
| [TestProcessor](tests-and-assertions.md#testprocessor) | Generates test-suite classes that register the file's `@Test` functions with the test runner |
| UpgradeCallableReferences *(shared)* | Brings lambdas and `::references` to one uniform IR shape |
| [NativeAssertionWrapperLowering](tests-and-assertions.md#nativeassertionwrapperlowering) | Wraps `assert(...)` so it can be switched on or off when the binary is built |
| LateinitLowering *(shared)* | Adds the "has it been initialized?" check to `lateinit` reads |
| SharedVariablesLowering *(shared)* | Puts a local `var` that a lambda modifies into a heap box |
| LocalClassesInInlineLambdasLowering *(shared)* | Moves local classes out of inline lambdas so inlining does not duplicate them |
| ArrayConstructorLowering *(shared)* | `Array(n) { i -> ... }` becomes a loop |
| [NativePrivateFunctionInlining](inlining.md#nativeallfunctioninlining) | Inlines private inline functions |
| OuterThisInInlineFunctionsSpecialAccessorLowering *(shared)* | Adds an accessor for the outer `this` used inside an inline function |
| SyntheticAccessorLowering *(shared)* | Adds accessors for private members that an inline function exposes to other files |
| [NativeAllFunctionInlining](inlining.md#nativeallfunctioninlining) | Inlines all remaining inline functions, across files and klibs |
| [SpecialObjCValidationLowering](interop.md#specialobjcvalidationlowering) | Reports suspend calls inside `autoreleasepool { }` |

### After inlining

| Lowering | What it does |
|---|---|
| ConstEvaluationLowering *(shared)* | Evaluates constant expressions at compile time |
| [ReifiedFunctionLowering](inlining.md#reifiedfunctionlowering) | Replaces the leftover bodies of `reified` inline functions with a throw |
| [TypeOfProcessingLowering](inlining.md#typeofprocessinglowering) | `typeOf<T>()` becomes a constant `KType` |
| [NativeSharedVariablesPrimitiveBoxSpecializationLowering](classes-and-local-declarations.md#nativesharedvariablesprimitiveboxspecializationlowering) | Uses a specialized box for captured `var`s of primitive type |
| [InteropLowering](interop.md#interoplowering) | Calls to C and Objective-C, and Kotlin subclasses of ObjC classes, become bridges and C stubs |
| [SpecialInteropIntrinsicsLowering](interop.md#specialinteropintrinsicslowering) | The cinterop `typeOf<T>()` behind `sizeOf<T>()` and `alloc<T>()` becomes the companion object of `T` |
| [TestsInitializer, TestsDumper](tests-and-assertions.md#testprocessor) | Register the generated test suites at startup; write the list of tests to a file (the latter only with `-tr`) |
| ExpectDeclarationsRemoveLowering *(shared)* | Removes `expect` declarations |
| StripTypeAliasDeclarationsLowering *(shared)* | Removes `typealias` declarations |
| [NativeAssertionRemoverLowering](tests-and-assertions.md#nativeassertionwrapperlowering) | Keeps or removes assertions depending on `-enable-assertions` |
| [VolatileFieldsLowering](classes-and-local-declarations.md#volatilefieldslowering) | Adds compare-and-set, exchange and add intrinsics to `@Volatile` properties and makes the stdlib atomics call them |
| DelegatedPropertyOptimizationLowering *(shared)* | Simplifies trivial property delegation |
| [PropertyReferenceLowering](callable-references.md#propertyreferencelowering) | `::prop` becomes a `KProperty` object |
| [NativeFunctionReferenceLowering](callable-references.md#nativefunctionreferencelowering) | Lambdas and `::function` references become classes implementing `FunctionN` |
| [NativeSingleAbstractMethodLowering](callable-references.md#nativesingleabstractmethodlowering) | A function value converted to a `fun interface` is wrapped in a generated adapter class |
| [PostInlineLowering](inlining.md#postinlinelowering) | `Foo::class` becomes a constant, `x::class` a `KClassImpl` built from the object's type info, `immutableBlobOf(...)` a constant blob |
| [ContractsDslRemover](inlining.md#contractsdslremover) | Meant to remove the contracts DSL classes (`@ContractsDsl`) from the stdlib; currently changes nothing |
| [NativeAnnotationImplementationLowering](classes-and-local-declarations.md#nativeannotationimplementationlowering) | Creating an annotation instance becomes creating a generated implementation class |
| RangeContainsLowering *(shared)* | `x in a..b` becomes `a <= x && x <= b` |
| [EnumConstructorsLowering](enums.md#enumconstructorslowering) | Adds the `name` and `ordinal` parameters to enum constructors |
| InitializersLowering *(shared)* | Moves property initializers and `init` blocks into constructors |
| [InteropBridgesNameInventor](interop.md#interopbridgesnameinventor) | Gives interop bridges unique names and registers their C stubs for compilation |
| [NativeInventNamesForLocalClasses, NativeKlibInventNamesForLocalFunctions](classes-and-local-declarations.md#nativelocaldeclarationslowering) | Choose names like `outer$Local` for local classes, anonymous objects and local functions |
| LocalDelegatedPropertiesLowering *(shared)* | A local `val x by d` becomes a variable plus `getValue` calls |
| [NativeLocalDeclarationsLowering](classes-and-local-declarations.md#nativelocaldeclarationslowering) | Local functions and classes get their captured variables as explicit parameters and fields |
| LocalDeclarationPopupLowering *(shared)* | Moves those local declarations up to the enclosing class or file |
| [NativeTailrecLowering](control-flow.md#nativetailreclowering) | `tailrec` functions become loops |
| [NativeFinallyBlocksLowering](control-flow.md#nativefinallyblockslowering) | Copies each `finally` block onto every way out of its `try` |
| [ComputeTypesPass](casts-and-types.md#computetypespass) | Recomputes precise expression types that inlining made too general (runs twice) |
| [NativeForLoopsLowering](control-flow.md#nativeforloopslowering) | `for` over ranges, progressions and arrays becomes a counting loop without an iterator; array accesses proven in range skip the bounds check |
| FlattenStringConcatenationLowering, StringConcatenationLowering *(shared)* | String templates and `+` become `StringBuilder` appends |
| [StringConcatenationTypeNarrowing](casts-and-types.md#stringconcatenationtypenarrowing) | Calls `toString()` up front so appends take a `String` (`-opt` only) |
| [NativeDefaultArgumentStubGenerator, …Cleaner, …Injector](functions.md#nativedefaultargumentstubgenerator) | Default arguments: a `foo$default` stub with a bit mask, and callers that pass the mask |
| [InnerClassLowering](classes-and-local-declarations.md#innerclasslowering) | An `inner class` gets a field holding the outer instance |
| [DataClassOperatorsLowering](classes-and-local-declarations.md#dataclassoperatorslowering) | In a data class's generated `toString`/`hashCode`, array properties get `contentToString()`/`contentHashCode()` |
| IfNullExpressionsFusionLowering *(shared)* | Simplifies chains of `?.` and `?:` |
| [StaticCallableReferenceOptimization](callable-references.md#staticcallablereferenceoptimization) | A lambda, function reference or property reference that captures nothing becomes a constant object |
| [NativeEnumWhenLowering](enums.md#nativeenumwhenlowering) | `when` over an enum compares ordinals |
| [EnumClassLowering](enums.md#enumclasslowering) | An enum class becomes a class with a static array of instances |
| [EnumUsageLowering](enums.md#enumusagelowering) | `Color.RED`, `enumValues<T>()` and friends become calls into that class |
| [VarargInjectionLowering](functions.md#vararginjectionlowering) | `vararg` arguments become an explicitly built array |
| KotlinNothingValueExceptionLowering *(shared)* | Throws if a call declared to return `Nothing` returns anyway |
| [NativeSuspendFunctionsLowering](../../features/coroutines.md#nativesuspendfunctionslowering) | A `suspend` function with a suspend call that is not a tail call becomes a state-machine class |
| AddContinuationToNonLocalSuspendFunctionsLowering *(shared)* | Adds the hidden `Continuation` parameter to suspend functions |
| [NativeAddContinuationToFunctionCallsLowering](../../features/coroutines.md#nativeaddcontinuationtofunctioncallslowering) | Passes the continuation at every suspend call |
| AddFunctionSupertypeToSuspendFunctionLowering *(shared)* | Makes `SuspendFunctionN` objects also implement `FunctionN+1` |
| [CoroutinesLivenessAnalysis, …Fallback](../../features/coroutines.md#coroutineslivenessanalysis) | Finds which variables are alive at each suspension point |
| ExpressionBodyTransformer *(shared)* | `fun f() = x` becomes `fun f() { return x }` |
| [ObjectClassLowering](../../features/initialization.md#objectclasslowering) | An `object` becomes a static instance field with a getter |
| [StaticInitializersLowering](../../features/initialization.md#staticinitializerslowering) | Initializers of globals move into lazy init functions, called on first access |
| [RemoveCastsFromNothingLowering](casts-and-types.md#castsoptimization) | Removes casts and type checks applied to expressions of type `Nothing` |
| [CastsOptimization](casts-and-types.md#castsoptimization) | Marks casts that are proven to succeed so that no run-time check is generated (runs when generic casts are checked: debug builds by default) |
| [TypeOperatorLowering](casts-and-types.md#typeoperatorlowering) | `as?` becomes an `is` check; `as T` is retargeted to the erased type (the upper bound of `T`) |
| [BuiltinOperatorLowering](casts-and-types.md#builtinoperatorlowering) | `==`, `!!` and the "no `when` branch matched" error become null checks, `equals` calls or primitive comparisons |
| [BridgesBuilding, WorkersBridgesBuilding](boxing.md#bridgesbuilding) | Adds bridge functions where an override's machine-level signature differs from the overridden one |
| [ExportCachesAbiVisitor, ImportCachesAbiTransformer](../caches.md#cachesabilowering) | Accessors for internals that code in another cache needs (the export half only when building a cache) |
| [GenericCallsReturnTypeEraser](boxing.md#autoboxing) | A call gets the type its callee really returns (`T` becomes `Any?`), with an implicit cast back to the expected type |
| [Autoboxing](boxing.md#autoboxing) | Inserts explicit box/unbox where a primitive or value class meets `Any` or a generic |
| [ConstructorsLowering](functions.md#constructorslowering) | `Foo(args)` becomes "allocate, then call the constructor as a function" |
| [ReturnsInsertionLowering](functions.md#constructorslowering) | Adds the explicit `return` at the end of `Unit` functions |
| [CastsLowering](casts-and-types.md#castslowering) | Remaining `as` casts become calls to a runtime check function (without `-opt`; with `-opt` code generation compiles the check inline) |

### Between the whole-program optimizations

| Lowering | What it does |
|---|---|
| PropertyAccessorInlineLowering *(shared)* | Replaces calls to trivial property accessors with field reads (`-opt` only) |
| [InlineClassPropertyAccessorsLowering](boxing.md#autoboxing) | A call to the getter of a value class's underlying property becomes a no-op reinterpretation (`-opt` only) |
| [RedundantCoercionsCleaner](boxing.md#redundantcoercionscleaner) | Removes `box(unbox(x))` pairs |
| [UnboxInlineLowering](boxing.md#redundantcoercionscleaner) | Turns unbox calls into reads of the box's field (`-opt` only) |
| [CoroutinesVarSpillingLowering](../../features/coroutines.md#coroutinesvarspillinglowering) | Saves live variables into the coroutine object before a suspension and restores them after |

### First stage (stored in the klib)

| Lowering | What it does |
|---|---|
| [NativeExportedBridgeCallDispatchLowering](interop.md#nativeexportedbridgecalldispatchlowering) | In Swift-export bridges that ask for it, makes the call of the target method non-virtual |
| TestProcessor, UpgradeCallableReferences, NativeAssertionWrapperLowering | Same as above; run here when the corresponding language feature is on |
| Pre-serialization inliner, TailrecCheckerLowering *(shared)* | Inlining of `inline` functions before serialization; `tailrec` validation |
