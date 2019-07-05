/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js

import org.jetbrains.kotlin.backend.common.*
import org.jetbrains.kotlin.backend.common.phaser.*
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.backend.js.lower.*
import org.jetbrains.kotlin.ir.backend.js.lower.calls.CallsLowering
import org.jetbrains.kotlin.ir.backend.js.lower.common.*
import org.jetbrains.kotlin.ir.backend.js.lower.coroutines.JsSuspendFunctionsLowering
import org.jetbrains.kotlin.ir.backend.js.lower.inline.FunctionInlining
import org.jetbrains.kotlin.ir.backend.js.lower.inline.RemoveInlineFunctionsLowering
import org.jetbrains.kotlin.ir.backend.js.lower.inline.ReturnableBlockLowering
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrDeclarationBase
import org.jetbrains.kotlin.ir.declarations.impl.IrFileImpl
import org.jetbrains.kotlin.ir.declarations.name
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.util.*
import java.util.*
import kotlin.collections.ArrayList

private fun ClassLoweringPass.runOnFilesPostfix(moduleFragment: IrModuleFragment) = moduleFragment.files.forEach { runOnFilePostfix(it) }

private fun validationCallback(context: JsIrBackendContext, module: IrElement) {
    val validatorConfig = IrValidatorConfig(
        abortOnError = false,
        ensureAllNodesAreDifferent = true,
        checkTypes = false,
        checkDescriptors = false
    )
    module.accept(IrValidator(context, validatorConfig), null)
    module.accept(CheckDeclarationParentsVisitor, null)
}

private fun makeJsModulePhase(
    lowering: (JsIrBackendContext) -> DeclarationTransformer,
    name: String,
    description: String,
    prerequisite: Set<Any?> = emptySet()
) = lowering

private fun makeCustomJsModulePhase(
    op: (JsIrBackendContext, IrModuleFragment) -> Unit,
    description: String,
    name: String,
    prerequisite: Set<AnyNamedPhase> = emptySet()
) = namedIrModulePhase(
    name,
    description,
    prerequisite,
    verify = ::validationCallback,
    nlevels = 0,
    lower = object : SameTypeCompilerPhase<JsIrBackendContext, IrModuleFragment> {
        override fun invoke(
            phaseConfig: PhaseConfig,
            phaserState: PhaserState<IrModuleFragment>,
            context: JsIrBackendContext,
            input: IrModuleFragment
        ): IrModuleFragment {
            op(context, input)
            return input
        }
    }
)

private val moveBodilessDeclarationsToSeparatePlacePhase = makeJsModulePhase(
    { context -> MoveBodilessDeclarationsToSeparatePlaceLowering(context) },
    name = "MoveBodilessDeclarationsToSeparatePlace",
    description = "Move `external` and `built-in` declarations into separate place to make the following lowerings do not care about them"
)

private val expectDeclarationsRemovingPhase = makeJsModulePhase(
    { context -> ExpectDeclarationsRemoving(context) },
    name = "ExpectDeclarationsRemoving",
    description = "Remove expect declaration from module fragment"
)

private val lateinitLoweringPhase = makeJsModulePhase(
    { context -> LateinitLowering(context).toDeclarationTransformer() },
    name = "LateinitLowering",
    description = "Insert checks for lateinit field references"
)

private val functionInliningPhase = makeJsModulePhase(
    { context -> FunctionInlining(context).toDeclarationTransformer() },
    name = "FunctionInliningPhase",
    description = "Perform function inlining",
    prerequisite = setOf(expectDeclarationsRemovingPhase)
)

private val removeInlineFunctionsLoweringPhase = makeJsModulePhase(
    { context -> RemoveInlineFunctionsLowering(context) },
    name = "RemoveInlineFunctionsLowering",
    description = "Remove Inline functions with reified parameters from context",
    prerequisite = setOf(functionInliningPhase)
)

private val throwableSuccessorsLoweringPhase = makeJsModulePhase(
    { context -> ThrowableSuccessorsLowering(context) },
    name = "ThrowableSuccessorsLowering",
    description = "Link kotlin.Throwable and JavaScript Error together to provide proper interop between language and platform exceptions"
)

private val tailrecLoweringPhase = makeJsModulePhase(
    { context -> TailrecLowering(context).toDeclarationTransformer() },
    name = "TailrecLowering",
    description = "Replace `tailrec` callsites with equivalent loop"
)

private val unitMaterializationLoweringPhase = makeJsModulePhase(
    { context -> UnitMaterializationLowering(context).toDeclarationTransformer() },
    name = "UnitMaterializationLowering",
    description = "Insert Unit object where it is supposed to be",
    prerequisite = setOf(tailrecLoweringPhase)
)

private val enumClassConstructorLoweringPhase = makeJsModulePhase(
    { context -> EnumClassConstructorLowering(context).runPostfix() },
    name = "EnumClassConstructorLowering",
    description = "Transform Enum Class into regular Class"
)

private val enumClassLoweringPhase = makeJsModulePhase(
    { context -> EnumClassLowering(context).runPostfix() },
    name = "EnumClassLowering",
    description = "Transform Enum Class into regular Class",
    prerequisite = setOf(enumClassConstructorLoweringPhase)
)

private val enumUsageLoweringPhase = makeJsModulePhase(
    { context -> EnumUsageLowering(context) },
    name = "EnumUsageLowering",
    description = "Replace enum access with invocation of corresponding function",
    prerequisite = setOf(enumClassLoweringPhase)
)

private val sharedVariablesLoweringPhase = makeJsModulePhase(
    { context -> SharedVariablesLowering(context).toDeclarationTransformer() },
    name = "SharedVariablesLowering",
    description = "Box captured mutable variables"
)

private val returnableBlockLoweringPhase = makeJsModulePhase(
    { context -> ReturnableBlockLowering(context) },
    name = "ReturnableBlockLowering",
    description = "Replace returnable block with do-while loop",
    prerequisite = setOf(functionInliningPhase)
)

private val localDelegatedPropertiesLoweringPhase = makeJsModulePhase(
    { context -> LocalDelegatedPropertiesLowering().toDeclarationTransformer() },
    name = "LocalDelegatedPropertiesLowering",
    description = "Transform Local Delegated properties"
)

private val localDeclarationsLoweringPhase = makeJsModulePhase(
    { context -> LocalDeclarationsLowering(context).runPostfix() },
    name = "LocalDeclarationsLowering",
    description = "Move local declarations into nearest declaration container",
    prerequisite = setOf(sharedVariablesLoweringPhase, localDelegatedPropertiesLoweringPhase)
)

private val innerClassesLoweringPhase = makeJsModulePhase(
    { context -> InnerClassesLowering(context).toDeclarationTransformer() },
    name = "InnerClassesLowering",
    description = "Capture outer this reference to inner class"
)

private val innerClassConstructorCallsLoweringPhase = makeJsModulePhase(
    { context -> InnerClassConstructorCallsLowering(context).toDeclarationTransformer() },
    name = "InnerClassConstructorCallsLowering",
    description = "Replace inner class constructor invocation"
)

private val suspendFunctionsLoweringPhase = makeJsModulePhase(
    { context -> JsSuspendFunctionsLowering(context) },
    name = "SuspendFunctionsLowering",
    description = "Transform suspend functions into CoroutineImpl instance and build state machine",
    prerequisite = setOf(unitMaterializationLoweringPhase)
)

private val privateMembersLoweringPhase = makeJsModulePhase(
    { context -> PrivateMembersLowering(context).toDeclarationTransformer() },
    name = "PrivateMembersLowering",
    description = "Extract private members from classes"
)

private val callableReferenceLoweringPhase = makeJsModulePhase(
    { context -> CallableReferenceLowering(context) },
    name = "CallableReferenceLowering",
    description = "Handle callable references",
    prerequisite = setOf(
        suspendFunctionsLoweringPhase,
        localDeclarationsLoweringPhase,
        localDelegatedPropertiesLoweringPhase,
        privateMembersLoweringPhase
    )
)

private val defaultArgumentStubGeneratorPhase = makeJsModulePhase(
    { context -> JsDefaultArgumentStubGenerator(context).runPostfix() },
    name = "DefaultArgumentStubGenerator",
    description = "Generate synthetic stubs for functions with default parameter values"
)

private val defaultParameterInjectorPhase = makeJsModulePhase(
    { context -> DefaultParameterInjector(context, skipExternalMethods = true) },
    name = "DefaultParameterInjector",
    description = "Replace callsite with default parameters with corresponding stub function",
    prerequisite = setOf(callableReferenceLoweringPhase, innerClassesLoweringPhase)
)

// TODO use?
//private val defaultParameterCleanerPhase = makeJsModulePhase(
//    ::DefaultParameterCleaner,
//    name = "DefaultParameterCleaner",
//    description = "Clean default parameters up"
//)

private val jsDefaultCallbackGeneratorPhase = makeJsModulePhase(
    { context -> JsDefaultCallbackGenerator(context).toDeclarationTransformer() },
    name = "JsDefaultCallbackGenerator",
    description = "Build binding for super calls with default parameters"
)

private val varargLoweringPhase = makeJsModulePhase(
    { context -> VarargLowering(context).toDeclarationTransformer() },
    name = "VarargLowering",
    description = "Lower vararg arguments",
    prerequisite = setOf(callableReferenceLoweringPhase)
)

private val propertiesLoweringPhase = makeJsModulePhase(
    { context -> PropertiesLowering(context, skipExternalProperties = true) },
    name = "PropertiesLowering",
    description = "Move fields and accessors out from its property"
)

private val initializersLoweringPhase = makeJsModulePhase(
    { context -> InitializersLowering(context, JsLoweredDeclarationOrigin.CLASS_STATIC_INITIALIZER, false).toDeclarationTransformer() },
    name = "InitializersLowering",
    description = "Merge init block and field initializers into [primary] constructor",
    prerequisite = setOf(enumClassConstructorLoweringPhase)
)

private val multipleCatchesLoweringPhase = makeJsModulePhase(
    { context -> MultipleCatchesLowering(context).toDeclarationTransformer() },
    name = "MultipleCatchesLowering",
    description = "Replace multiple catches with single one"
)

private val bridgesConstructionPhase = makeJsModulePhase(
    { context -> BridgesConstruction(context).toDeclarationTransformer() },
    name = "BridgesConstruction",
    description = "Generate bridges",
    prerequisite = setOf(suspendFunctionsLoweringPhase)
)

private val typeOperatorLoweringPhase = makeJsModulePhase(
    { context -> TypeOperatorLowering(context) },
    name = "TypeOperatorLowering",
    description = "Lower IrTypeOperator with corresponding logic",
    prerequisite = setOf(bridgesConstructionPhase, removeInlineFunctionsLoweringPhase)
)

private val secondaryConstructorLoweringPhase = makeJsModulePhase(
    { context -> SecondaryConstructorLowering(context).toDeclarationTransformer() },
    name = "SecondaryConstructorLoweringPhase",
    description = "Generate static functions for each secondary constructor",
    prerequisite = setOf(innerClassesLoweringPhase)
)

private val secondaryFactoryInjectorLoweringPhase = makeJsModulePhase(
    { context -> SecondaryFactoryInjectorLowering(context).toDeclarationTransformer() },
    name = "SecondaryFactoryInjectorLoweringPhase",
    description = "Replace usage of secondary constructor with corresponding static function",
    prerequisite = setOf(innerClassesLoweringPhase)
)

private val inlineClassDeclarationsLoweringPhase = makeJsModulePhase(
    { context ->
        InlineClassLowering(context).inlineClassDeclarationLowering.toDeclarationTransformer()
    },
    name = "InlineClassDeclarationsLowering",
    description = "Handle inline classes declarations"
)

private val inlineClassUsageLoweringPhase = makeJsModulePhase(
    { context ->
        InlineClassLowering(context).inlineClassUsageLowering
    },
    name = "InlineClassUsageLowering",
    description = "Handle inline classes usages"
)


private val autoboxingTransformerPhase = makeJsModulePhase(
    { context -> AutoboxingTransformer(context) },
    name = "AutoboxingTransformer",
    description = "Insert box/unbox intrinsics"
)

private val blockDecomposerLoweringPhase = makeJsModulePhase(
    { context -> BlockDecomposerLowering(context).runPostfix() },
    name = "BlockDecomposerLowering",
    description = "Transform statement-like-expression nodes into pure-statement to make it easily transform into JS",
    prerequisite = setOf(typeOperatorLoweringPhase, suspendFunctionsLoweringPhase)
)

private val classReferenceLoweringPhase = makeJsModulePhase(
    { context -> ClassReferenceLowering(context) },
    name = "ClassReferenceLowering",
    description = "Handle class references"
)

private val primitiveCompanionLoweringPhase = makeJsModulePhase(
    { context -> PrimitiveCompanionLowering(context) },
    name = "PrimitiveCompanionLowering",
    description = "Replace common companion object access with platform one"
)

private val constLoweringPhase = makeJsModulePhase(
    { context -> ConstLowering(context).toDeclarationTransformer() },
    name = "ConstLowering",
    description = "Wrap Long and Char constants into constructor invocation"
)

private val callsLoweringPhase = makeJsModulePhase(
    { context -> CallsLowering(context).toDeclarationTransformer() },
    name = "CallsLowering",
    description = "Handle intrinsics"
)

//private val testGenerationPhase = makeJsModulePhase(
//    ::TestGenerator,
//    name = "TestGenerationLowering",
//    description = "Generate invocations to kotlin.test suite and test functions"
//)

private val staticMembersLoweringPhase = makeJsModulePhase(
    { context -> StaticMembersLowering(context) },
    name = "StaticMembersLowering",
    description = "Move static member declarations to top-level"
)

// Second value means if body access is allowed
val perFilePhaseList = listOf(
    expectDeclarationsRemovingPhase to false, // OK
    moveBodilessDeclarationsToSeparatePlacePhase to true, // Needs to detect @JsModule and @JsQualifier. TODO: read those on use only
    functionInliningPhase to true, // OK
    removeInlineFunctionsLoweringPhase to false, // OK
    lateinitLoweringPhase to true, // OK
    tailrecLoweringPhase to true, // OK
    enumClassConstructorLoweringPhase to true,
    sharedVariablesLoweringPhase to true,
    localDelegatedPropertiesLoweringPhase to true,
    localDeclarationsLoweringPhase to true,

    innerClassesLoweringPhase to true,
    innerClassConstructorCallsLoweringPhase to true, // OK

    propertiesLoweringPhase to true,
    initializersLoweringPhase to true,
    // Common prefix ends
    enumClassLoweringPhase to true,
    enumUsageLoweringPhase to true,

    returnableBlockLoweringPhase to true,
    unitMaterializationLoweringPhase to true, // OK
    suspendFunctionsLoweringPhase to true,
    privateMembersLoweringPhase to true,
    callableReferenceLoweringPhase to true,

    defaultArgumentStubGeneratorPhase to true,
    defaultParameterInjectorPhase to true,
    jsDefaultCallbackGeneratorPhase to true, // OK

    throwableSuccessorsLoweringPhase to true,
    varargLoweringPhase to true, // OK
    multipleCatchesLoweringPhase to true, // OK
    bridgesConstructionPhase to true,
    typeOperatorLoweringPhase to true,

    secondaryConstructorLoweringPhase to true,
    secondaryFactoryInjectorLoweringPhase to true, // OK

    classReferenceLoweringPhase to true,

    inlineClassDeclarationsLoweringPhase to true,
    inlineClassUsageLoweringPhase to true,

    autoboxingTransformerPhase to true,
    blockDecomposerLoweringPhase to true,
    primitiveCompanionLoweringPhase to true,
    constLoweringPhase to true, // OK
    callsLoweringPhase to true, // OK
    staticMembersLoweringPhase to false // OK
)

fun compositePhase(): CompilerPhase<JsIrBackendContext, IrFile, IrFile> {
    return object : CompilerPhase<JsIrBackendContext, IrFile, IrFile> {
        override fun invoke(
            phaseConfig: PhaseConfig,
            phaserState: PhaserState<IrFile>,
            context: JsIrBackendContext,
            input: IrFile
        ): IrFile {
            context.stageController.invokeLowerings(input)
            return input
        }
    }
}

val jsPerFileStages = performByIrFile(
    name = "IrLowerByFile",
    description = "IR Lowering by file",
    lower = compositePhase()
)

val jsPhases = namedIrModulePhase(
    name = "IrModuleLowering",
    description = "IR module lowering",
    lower = jsPerFileStages
)

fun generateTests(context: JsIrBackendContext, moduleFragment: IrModuleFragment, phaseConfig: PhaseConfig) {
    context.stageController.withInitialIr {
        val generator = TestGenerator(context)
        moduleFragment.files.forEach {
            generator.lower(it)
        }
        context.implicitDeclarationFile.loweredUpTo = 0
    }
    context.stageController.invokeTopLevel(phaseConfig, moduleFragment)
//    stageController.lowerUpTo(context.implicitDeclarationFile, perFilePhaseList.size + 1)
}

class MutableController : StageController {
    init {
        stageController = this
    }

    override var currentStage: Int = 0
        private set

    private fun <T> withStage(stage: Int, fn: () -> T): T {
        val previousStage = currentStage
        currentStage = stage
        val result = fn()
        currentStage = previousStage
        return result
    }

    lateinit var context: JsIrBackendContext

    // TODO is this needed at all?
    private fun lowerUpTo(file: IrFile, stageNonInclusive: Int) {
        val loweredUpTo = (file as? IrFileImpl)?.loweredUpTo ?: 0
        for (i in loweredUpTo + 1 until stageNonInclusive) {
            if (frozen) error("frozen!")
            withStage(i) {
                ArrayList(file.declarations).forEach {
                    lowerUpTo(it, i + 1)
                }
            }
            (file as? IrFileImpl)?.loweredUpTo = i
        }
    }

    override var bodiesEnabled = false

    private fun <T> withBodies(fn: () -> T): T {
        val previousBodies = bodiesEnabled
        bodiesEnabled = true

        return try {
            fn()
        } finally {
            bodiesEnabled = previousBodies
        }
    }

    private fun lowerUpTo(declaration: IrDeclaration, stageNonInclusive: Int) {
        val loweredUpTo = declaration.loweredUpTo
        for (i in loweredUpTo + 1 until stageNonInclusive) {
            withStage(i) {
                val topLevelDeclaration = declaration.topLevel

                if (topLevelDeclaration is IrDeclarationBase && topLevelDeclaration.loweredUpTo < i - 1) {
                    error("WTF?")
                }

//                if (topLevelDeclaration is IrProperty && topLevelDeclaration.name.asString() == "coroutineContext") {
//                    println("!!!")
//                }

                if (topLevelDeclaration.loweredUpTo == i - 1 && topLevelDeclaration.parent is IrFile) {
                    val fileBefore = topLevelDeclaration.parent as IrFileImpl

                    if (topLevelDeclaration in fileBefore.declarations) {
                        if (frozen) {
                            error("frozen! ${topLevelDeclaration.name.asString()} in ${fileBefore.fileEntry.name}")
                        }
                        val (lowering, bodiesEnabled) = perFilePhaseList[i - 1]

                        val result = if (bodiesEnabled)
                            withBodies { lowering(context).transformFlat(topLevelDeclaration) }
                        else
                            lowering(context).transformFlat(topLevelDeclaration)

                        topLevelDeclaration.loweredUpTo = i
                        if (result != null) {
                            result.forEach { it.loweredUpTo = i }

                            fileBefore.declarations.remove(topLevelDeclaration)

                            fileBefore.declarations += result
                        }
                    }
                }

                declaration.loweredUpTo = i
            }
        }
    }

    private var IrDeclaration.loweredUpTo: Int
        get() = (this as? IrDeclarationBase)?.loweredUpTo ?: 0
        set(v) {
            (this as? IrDeclarationBase)?.loweredUpTo = v
        }

    // TODO Special API to check only top level declarations are added?
    fun withInitialIr(block: () -> Unit) {
        withStage(0) {
            block()
        }
    }

    private var frozen = false

    fun invokeTopLevel(phaseConfig: PhaseConfig, moduleFragment: IrModuleFragment) {
        jsPhases.invokeToplevel(phaseConfig, context, moduleFragment)

        currentStage = perFilePhaseList.size + 1

        ArrayList(context.symbolTable.unboundClasses).forEach {
            tryLoad(it)
        }
        ArrayList(context.symbolTable.unboundConstructors).forEach {
            tryLoad(it)
        }
        ArrayList(context.symbolTable.unboundEnumEntries).forEach {
            tryLoad(it)
        }
        ArrayList(context.symbolTable.unboundFields).forEach {
            tryLoad(it)
        }
        ArrayList(context.symbolTable.unboundSimpleFunctions).forEach {
            tryLoad(it)
        }
        ArrayList(context.symbolTable.unboundProperties).forEach {
            tryLoad(it)
        }
        ArrayList(context.symbolTable.unboundTypeParameters).forEach {
            tryLoad(it)
        }

        while (true) {
            var changed = false
            for (file in moduleFragment.files) {
                for (decl in ArrayList(file.declarations)) {
                    if (decl.loweredUpTo < currentStage - 1) {
                        lazyLower(decl)
                        changed = true
                    }
                }
            }
            if (!changed) break
        }

//        while (!loaded.isEmpty()) {
//            val decl = loaded.pop()
//            lazyLower(decl)
//        }
    }

    fun invokeLowerings(file: IrFile) {
        lowerUpTo(file, perFilePhaseList.size + 1)
    }

    fun freeze() {
        frozen = true
    }

    override fun lazyLower(declaration: IrDeclaration) {
        // TODO other declarations
        if (declaration is IrDeclarationBase && currentStage > declaration.loweredUpTo) {
            lowerUpTo(declaration, currentStage)
        }
    }

    private val IrDeclaration.topLevel: IrDeclaration
        get() = parent.let {
            if (it is IrDeclaration) it.topLevel else this
        }

    override fun lazyLower(file: IrFile) {
        lowerUpTo(file, currentStage)
    }

    var dependencyGenerator: ExternalDependenciesGenerator? = null

    val loaded = ArrayDeque<IrDeclaration>()

    override fun tryLoad(symbol: IrSymbol) {
        dependencyGenerator?.let { generator ->
            withBodies {
                try {
                    dependencyGenerator = null
                    if (!symbol.isBound) {
                        withStage(0) {
                            generator.loadSymbol(symbol)
                            if (symbol.isBound) {
                                (symbol.owner as? IrDeclaration)?.let { loaded.add(it) }
                            }
                        }
                    }
                } finally {
                    dependencyGenerator = generator
                }
            }
        }
    }

    fun deinit() {
        stageController = NoopController()
    }
}
