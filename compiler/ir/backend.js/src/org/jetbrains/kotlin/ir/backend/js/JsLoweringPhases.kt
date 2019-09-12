/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js

import org.jetbrains.kotlin.backend.common.*
import org.jetbrains.kotlin.backend.common.phaser.*
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.backend.js.lower.*
import org.jetbrains.kotlin.ir.backend.js.lower.calls.CallsLowering
import org.jetbrains.kotlin.ir.backend.js.lower.common.*
import org.jetbrains.kotlin.ir.backend.js.lower.coroutines.JsSuspendFunctionsLowering
import org.jetbrains.kotlin.ir.backend.js.lower.inline.*
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrBodyBase
import org.jetbrains.kotlin.ir.declarations.impl.IrDeclarationBase
import org.jetbrains.kotlin.ir.declarations.impl.IrFileImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrPropertyImpl
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
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

sealed class Lowering(val name: String)

class DeclarationLowering(name: String,
                          private val factory: (JsIrBackendContext) -> DeclarationTransformer) : Lowering(name) {
    fun declarationTransformer(context: JsIrBackendContext): DeclarationTransformer {
        return factory(context)
    }
}

class BodyLowering(name: String,
                   private val factory: (JsIrBackendContext) -> BodyLoweringPass) : Lowering(name) {
    fun bodyLowering(context: JsIrBackendContext): BodyLoweringPass {
        return factory(context)
    }
}

private fun makeJsModulePhase(
    lowering: (JsIrBackendContext) -> DeclarationTransformer,
    name: String,
    description: String,
    prerequisite: Set<Any?> = emptySet()
) = DeclarationLowering(name, lowering)

private fun makeBodyLoweringPhase(
    lowering: (JsIrBackendContext) -> BodyLoweringPass,
    name: String,
    description: String,
    prerequisite: Set<Any?> = emptySet()
) = BodyLowering(name, lowering)

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

private val lateinitLoweringPhase = makeBodyLoweringPhase(
    { context -> LateinitLowering(context) },
    name = "LateinitLowering",
    description = "Insert checks for lateinit field references"
)

private val functionInliningPhase = makeBodyLoweringPhase(
    { context -> FunctionInlining(context) },
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

private val copyInlineFunctionBody = makeJsModulePhase(
    { context -> CopyInlineFunctionBody(context) },
    name = "CopyInlineFunctionBody",
    description = "Copy inline function body, so that the original version is saved in the history",
    prerequisite = setOf(removeInlineFunctionsLoweringPhase)
)

private val throwableSuccessorsLoweringPhase = makeJsModulePhase(
    { context -> ThrowableSuccessorsLowering(context) },
    name = "ThrowableSuccessorsLowering",
    description = "Link kotlin.Throwable and JavaScript Error together to provide proper interop between language and platform exceptions"
)

private val throwableSuccessorsBodyLoweringPhase = makeBodyLoweringPhase(
    { context -> ThrowableSuccessorsBodyLowering(context) },
    name = "ThrowableSuccessorsLowering",
    description = "Link kotlin.Throwable and JavaScript Error together to provide proper interop between language and platform exceptions"
)

private val tailrecLoweringPhase = makeBodyLoweringPhase(
    { context -> TailrecLowering(context) },
    name = "TailrecLowering",
    description = "Replace `tailrec` callsites with equivalent loop"
)

private val unitMaterializationLoweringPhase = makeBodyLoweringPhase(
    { context -> UnitMaterializationLowering(context) },
    name = "UnitMaterializationLowering",
    description = "Insert Unit object where it is supposed to be",
    prerequisite = setOf(tailrecLoweringPhase)
)

private val enumClassConstructorLoweringPhase = makeJsModulePhase(
    { context -> EnumClassConstructorLowering(context) },
    name = "EnumClassConstructorLowering",
    description = "Transform Enum Class into regular Class"
)

private val enumClassConstructorBodyLoweringPhase = makeBodyLoweringPhase(
    { context -> EnumClassConstructorBodyTransformer(context) },
    name = "EnumClassConstructorBodyLowering",
    description = "Transform Enum Class into regular Class"
)


private val enumEntryInstancesLoweringPhase = makeJsModulePhase(
    { context -> EnumEntryInstancesLowering(context) },
    name = "EnumEntryInstancesLowering",
    description = "Create instance variable for each enum entry initialized with `null`",
    prerequisite = setOf(enumClassConstructorLoweringPhase)
)

private val enumClassCreateInitializerLoweringPhase = makeJsModulePhase(
    { context -> EnumClassCreateInitializerLowering(context) },
    name = "EnumClassCreateInitializerLowering",
    description = "Create initializer for enum entries",
    prerequisite = setOf(enumClassConstructorLoweringPhase)
)

private val enumEntryCreateGetInstancesFunsLoweringPhase = makeJsModulePhase(
    { context -> EnumEntryCreateGetInstancesFunsLowering(context) },
    name = "EnumEntryCreateGetInstancesFunsLowering",
    description = "Create enumEntry_getInstance functions",
    prerequisite = setOf(enumClassConstructorLoweringPhase)
)

private val enumSyntheticFunsLoweringPhase = makeJsModulePhase(
    { context -> EnumSyntheticFunctionsLowering(context) },
    name = "EnumSyntheticFunctionsLowering",
    description = "Implement `valueOf` and `values`",
    prerequisite = setOf(enumClassConstructorLoweringPhase)
)

private val enumUsageLoweringPhase = makeBodyLoweringPhase(
    { context -> EnumUsageLowering(context) },
    name = "EnumUsageLowering",
    description = "Replace enum access with invocation of corresponding function",
    prerequisite = setOf(enumEntryCreateGetInstancesFunsLoweringPhase)
)

private val enumEntryRemovalLoweringPhase = makeJsModulePhase(
    { context -> EnumClassRemoveEntriesLowering(context) },
    name = "EnumEntryRemovalLowering",
    description = "Replace enum entry with corresponding class",
    prerequisite = setOf(enumUsageLoweringPhase)
)

private val sharedVariablesLoweringPhase = makeBodyLoweringPhase(
    { context -> SharedVariablesLowering(context) },
    name = "SharedVariablesLowering",
    description = "Box captured mutable variables"
)

private val returnableBlockLoweringPhase = makeBodyLoweringPhase(
    { context -> ReturnableBlockLowering(context) },
    name = "ReturnableBlockLowering",
    description = "Replace returnable block with do-while loop",
    prerequisite = setOf(functionInliningPhase)
)

private val localDelegatedPropertiesLoweringPhase = makeBodyLoweringPhase(
    { context -> LocalDelegatedPropertiesLowering() },
    name = "LocalDelegatedPropertiesLowering",
    description = "Transform Local Delegated properties"
)

private val localDeclarationsLoweringPhase = makeBodyLoweringPhase(
    { context -> LocalDeclarationsLowering(context) },
    name = "LocalDeclarationsLowering",
    description = "Move local declarations into nearest declaration container",
    prerequisite = setOf(sharedVariablesLoweringPhase, localDelegatedPropertiesLoweringPhase)
)

private val localClassExtractionPhase = makeBodyLoweringPhase(
    { context -> LocalClassPopupLowering(context) },
    name = "LocalClassExtractionPhase",
    description = "Move local declarations into nearest declaration container",
    prerequisite = setOf(localDeclarationsLoweringPhase)
)

private val innerClassesDeclarationLoweringPhase = makeJsModulePhase(
    { context -> InnerClassesDeclarationLowering(context) },
    name = "InnerClassesDeclarationLowering",
    description = "Capture outer this reference to inner class"
)

private val innerClassesConstructorBodyLoweringPhase = makeBodyLoweringPhase(
    { context -> InnerClassesMemberBodyLowering(context) },
    name = "innerClassesConstructorBodyLoweringPhase",
    description = "Capture outer this reference to inner class",
    prerequisite = setOf(innerClassesDeclarationLoweringPhase)
)

private val innerClassConstructorCallsLoweringPhase = makeBodyLoweringPhase(
    { context -> InnerClassConstructorCallsLowering(context) },
    name = "InnerClassConstructorCallsLowering",
    description = "Replace inner class constructor invocation"
)

private val suspendFunctionsLoweringPhase = makeBodyLoweringPhase(
    { context -> JsSuspendFunctionsLowering(context) },
    name = "SuspendFunctionsLowering",
    description = "Transform suspend functions into CoroutineImpl instance and build state machine",
    prerequisite = setOf(unitMaterializationLoweringPhase)
)

private val suspendLambdasRemovingPhase = makeJsModulePhase(
    { context -> RemoveSuspendLambdas() },
    name = "SuspendLambdasRemovalLowering",
    description = "Remove suspend lambdas as they are unused",
    prerequisite = setOf(suspendFunctionsLoweringPhase)
)


private val privateMembersLoweringPhase = makeJsModulePhase(
    { context -> PrivateMembersLowering(context) },
    name = "PrivateMembersLowering",
    description = "Extract private members from classes"
)

private val privateMembersBodyLoweringPhase = makeBodyLoweringPhase(
    { context -> PrivateMemberBodiesLowering(context) },
    name = "PrivateMembersBodyLowering",
    description = "Fill extracted private members bodies"
)

private val callableReferenceLoweringPhase = makeBodyLoweringPhase(
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
    { context -> JsDefaultArgumentStubGenerator(context) },
    name = "DefaultArgumentStubGenerator",
    description = "Generate synthetic stubs for functions with default parameter values"
)

private val defaultParameterPatchOverridenSymbolsPhase = makeJsModulePhase(
    { context -> DefaultParameterPatchOverridenSymbolsLowering(context) },
    name = "DefaultParameterPatchOverridenSymbolsLowering",
    description = "Restore overrides for dispatch functions"
)

private val defaultParameterInjectorPhase = makeBodyLoweringPhase(
    { context -> DefaultParameterInjector(context, skipExternalMethods = true) },
    name = "DefaultParameterInjector",
    description = "Replace callsite with default parameters with corresponding stub function",
    prerequisite = setOf(callableReferenceLoweringPhase, innerClassesDeclarationLoweringPhase)
)

private val defaultParameterCleanerPhase = makeJsModulePhase(
    { context -> DefaultParameterCleaner(context) },
    name = "DefaultParameterCleaner",
    description = "Clean default parameters up"
)

private val jsDefaultCallbackGeneratorPhase = makeBodyLoweringPhase(
    { context -> JsDefaultCallbackGenerator(context) },
    name = "JsDefaultCallbackGenerator",
    description = "Build binding for super calls with default parameters"
)

private val varargLoweringPhase = makeBodyLoweringPhase(
    { context -> VarargLowering(context) },
    name = "VarargLowering",
    description = "Lower vararg arguments",
    prerequisite = setOf(callableReferenceLoweringPhase)
)

private val propertiesLoweringPhase = makeJsModulePhase(
    { context -> PropertiesLowering(context, skipExternalProperties = true) },
    name = "PropertiesLowering",
    description = "Move fields and accessors out from its property"
)

private val initializersLoweringPhase = makeBodyLoweringPhase(
    { context -> InitializersBodyLowering(context, JsLoweredDeclarationOrigin.CLASS_STATIC_INITIALIZER, false) },
    name = "InitializersLowering",
    description = "Merge init block and field initializers into [primary] constructor",
    prerequisite = setOf(enumClassConstructorLoweringPhase)
)

private val removeAnonymousInitializers = makeJsModulePhase(
    { context -> RemoveAnonymousInitializers(context) },
    name = "InitializersLowering",
    description = "Merge init block and field initializers into [primary] constructor",
    prerequisite = setOf(initializersLoweringPhase)
)

private val multipleCatchesLoweringPhase = makeBodyLoweringPhase(
    { context -> MultipleCatchesLowering(context) },
    name = "MultipleCatchesLowering",
    description = "Replace multiple catches with single one"
)

private val bridgesConstructionPhase = makeJsModulePhase(
    { context -> BridgesConstruction(context) },
    name = "BridgesConstruction",
    description = "Generate bridges",
    prerequisite = setOf(suspendFunctionsLoweringPhase)
)

private val typeOperatorLoweringPhase = makeBodyLoweringPhase(
    { context -> TypeOperatorLowering(context) },
    name = "TypeOperatorLowering",
    description = "Lower IrTypeOperator with corresponding logic",
    prerequisite = setOf(bridgesConstructionPhase, removeInlineFunctionsLoweringPhase)
)

private val secondaryConstructorLoweringPhase = makeJsModulePhase(
    { context -> SecondaryConstructorLowering(context) },
    name = "SecondaryConstructorLoweringPhase",
    description = "Generate static functions for each secondary constructor",
    prerequisite = setOf(innerClassesDeclarationLoweringPhase)
)

private val secondaryFactoryInjectorLoweringPhase = makeBodyLoweringPhase(
    { context -> SecondaryFactoryInjectorLowering(context) },
    name = "SecondaryFactoryInjectorLoweringPhase",
    description = "Replace usage of secondary constructor with corresponding static function",
    prerequisite = setOf(innerClassesDeclarationLoweringPhase)
)

private val inlineClassDeclarationsLoweringPhase = makeJsModulePhase(
    { context -> InlineClassLowering(context).inlineClassDeclarationLowering },
    name = "InlineClassDeclarationsLowering",
    description = "Handle inline classes declarations"
)

private val inlineClassUsageLoweringPhase = makeBodyLoweringPhase(
    { context -> InlineClassLowering(context).inlineClassUsageLowering },
    name = "InlineClassUsageLowering",
    description = "Handle inline classes usages"
)


private val autoboxingTransformerPhase = makeBodyLoweringPhase(
    { context -> AutoboxingTransformer(context) },
    name = "AutoboxingTransformer",
    description = "Insert box/unbox intrinsics"
)

private val fieldInitializerCreationPhase = makeJsModulePhase(
    { context -> CreateIrFieldInitializerFunction(context) },
    name = "fieldInitializerCreationPhase",
    description = "Transform statement-like-expression nodes into pure-statement to make it easily transform into JS",
    prerequisite = setOf(typeOperatorLoweringPhase, suspendFunctionsLoweringPhase)
)


private val blockDecomposerLoweringPhase = makeBodyLoweringPhase(
    { context -> BlockDecomposerLowering(context) },
    name = "BlockDecomposerLowering",
    description = "Transform statement-like-expression nodes into pure-statement to make it easily transform into JS",
    prerequisite = setOf(typeOperatorLoweringPhase, suspendFunctionsLoweringPhase, fieldInitializerCreationPhase)
)

private val classReferenceLoweringPhase = makeBodyLoweringPhase(
    { context -> ClassReferenceLowering(context) },
    name = "ClassReferenceLowering",
    description = "Handle class references"
)

private val primitiveCompanionLoweringPhase = makeBodyLoweringPhase(
    { context -> PrimitiveCompanionLowering(context) },
    name = "PrimitiveCompanionLowering",
    description = "Replace common companion object access with platform one"
)

private val constLoweringPhase = makeBodyLoweringPhase(
    { context -> ConstLowering(context) },
    name = "ConstLowering",
    description = "Wrap Long and Char constants into constructor invocation"
)

private val callsLoweringPhase = makeBodyLoweringPhase(
    { context -> CallsLowering(context) },
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

private val perFilePhaseList = listOf(
    expectDeclarationsRemovingPhase, // OK
    moveBodilessDeclarationsToSeparatePlacePhase, // Needs to detect @JsModule and @JsQualifier. TODO: should become obsolete
    functionInliningPhase, // OK
    removeInlineFunctionsLoweringPhase, // OK
    copyInlineFunctionBody, // OK
    lateinitLoweringPhase, // OK
    tailrecLoweringPhase, // OK
    enumClassConstructorLoweringPhase, // OK
    enumClassConstructorBodyLoweringPhase, // OK

    sharedVariablesLoweringPhase, // OK
    localDelegatedPropertiesLoweringPhase, // OK
    localDeclarationsLoweringPhase, // OK

    localClassExtractionPhase,

    innerClassesDeclarationLoweringPhase, // OK
    innerClassesConstructorBodyLoweringPhase, // OK
    innerClassConstructorCallsLoweringPhase, // OK

    propertiesLoweringPhase, // OK
    initializersLoweringPhase, // OK
    removeAnonymousInitializers, // OK
    // Common prefix ends
    enumEntryInstancesLoweringPhase,
    enumClassCreateInitializerLoweringPhase,
    enumEntryCreateGetInstancesFunsLoweringPhase,
    enumSyntheticFunsLoweringPhase,
    enumUsageLoweringPhase, // OK
    enumEntryRemovalLoweringPhase, // OK

    returnableBlockLoweringPhase, // OK
    unitMaterializationLoweringPhase, // OK
    suspendFunctionsLoweringPhase, // OK
    suspendLambdasRemovingPhase, // OK
    privateMembersLoweringPhase, // OK
    privateMembersBodyLoweringPhase, // OK
    callableReferenceLoweringPhase, // OK -- creates new declarations from bodies

    defaultArgumentStubGeneratorPhase, // Copies type parameter annotations
    defaultParameterPatchOverridenSymbolsPhase, // Copies type parameter annotations
    defaultParameterInjectorPhase, // OK
    jsDefaultCallbackGeneratorPhase, // OK
    defaultParameterCleanerPhase, // OK

    throwableSuccessorsLoweringPhase, // OK
    throwableSuccessorsBodyLoweringPhase, // OK
    varargLoweringPhase, // OK
    multipleCatchesLoweringPhase, // OK
    bridgesConstructionPhase, // TODO Reads @JsName
    typeOperatorLoweringPhase, // OK

    secondaryConstructorLoweringPhase, // OK
    secondaryFactoryInjectorLoweringPhase, // OK

    classReferenceLoweringPhase, // OK

    inlineClassDeclarationsLoweringPhase, // OK
    inlineClassUsageLoweringPhase, // OK

    autoboxingTransformerPhase, // OK
    fieldInitializerCreationPhase, // OK
    blockDecomposerLoweringPhase, // OK
    primitiveCompanionLoweringPhase, // OK
    constLoweringPhase, // OK
    callsLoweringPhase, // OK
    staticMembersLoweringPhase // OK
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

fun generateTests(context: JsIrBackendContext, data: ContextData, moduleFragments: List<IrModuleFragment>, phaseConfig: PhaseConfig) {
    context.stageController.withInitialIr {
        val generator = TestGenerator(context, data)
        moduleFragments.forEach {
            it.files.forEach {
                generator.lower(it)
            }
        }
        data.implicitDeclarationFile.loweredUpTo = 0
    }
    context.stageController.invokeTopLevel(phaseConfig, data.irModuleFragment, moduleFragments - data.irModuleFragment)
//    stageController.lowerUpTo(context.implicitDeclarationFile, perFilePhaseList.size + 1)
}

class MutableController : StageController {
    init {
        stageController = this
    }

    override var currentStage: Int = 0
        private set

    override fun <T> withStage(stage: Int, fn: () -> T): T {
        val previousStage = currentStage
        currentStage = stage
        val result = try {
            fn()
        } finally {
            currentStage = previousStage
        }
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

    fun <T> withBodies(fn: () -> T): T {
        val previousBodies = bodiesEnabled
        bodiesEnabled = true

        return try {
            fn()
        } finally {
            bodiesEnabled = previousBodies
        }
    }

    private fun lowerUpTo(declaration: IrDeclaration, stageNonInclusive: Int) {
        while (declaration.loweredUpTo + 1 < stageNonInclusive) {
            val i = declaration.loweredUpTo + 1
            withStage(i) {
                lazyLowerIteration++
                val topLevelDeclaration = declaration.topLevel

                if (topLevelDeclaration is IrDeclarationBase<*> && topLevelDeclaration.loweredUpTo < i - 1) {
                    error("WTF?")
                }

                if (topLevelDeclaration.loweredUpTo == i - 1 && topLevelDeclaration.parent is IrFile) {
                    val fileBefore = topLevelDeclaration.parent as IrFileImpl

                    val removedOn = (topLevelDeclaration as? IrDeclarationBase<*>)?.removedOn ?: Int.MAX_VALUE

                    if (removedOn > i) {
                        if (frozen) {
                            error("frozen! ${topLevelDeclaration.name.asString()} in ${fileBefore.fileEntry.name}")
                        }

                        val lowering = perFilePhaseList[i - 1]

                        if (lowering is DeclarationLowering) {
                            val result = withoutBodies { lowering.declarationTransformer(context).runPostfix().transformFlat(topLevelDeclaration) }

                            actualLoweringInvocations++

                            topLevelDeclaration.loweredUpTo = i
                            if (result != null) {
                                result.forEach {
                                    it.loweredUpTo = i
                                    it.parent = fileBefore
                                }

                                fileBefore.declarations.remove(topLevelDeclaration)

                                fileBefore.declarations += result

                                if (topLevelDeclaration is IrDeclarationBase<*> && topLevelDeclaration.parent == fileBefore && topLevelDeclaration !in result) {
                                    topLevelDeclaration.removedOn = currentStage
                                }
                            }
                        }
                    }
                }

                declaration.loweredUpTo = i//Math.max(i, topLevelDeclaration.loweredUpTo)
            }
        }
    }

    private var IrDeclaration.loweredUpTo: Int
        get() = (this as? IrDeclarationBase<*>)?.loweredUpTo ?: 0
        set(v) {
            (this as? IrDeclarationBase<*>)?.loweredUpTo = v
        }

    // TODO Special API to check only top level declarations are added?
    override fun <T> withInitialIr(block: () -> T): T {
        return withStage(0) {
            block()
        }
    }

    private var frozen = false

    fun invokeTopLevel(phaseConfig: PhaseConfig, moduleFragment: IrModuleFragment, dependencyModules: List<IrModuleFragment>): Set<IrDeclaration> {
        val start = System.currentTimeMillis()

//        for (stage in 1..perFilePhaseList.size) {
//            for (module in dependencyModules + moduleFragment) {
//                for (file in module.files) {
//                    lowerUpTo(file, stage + 1)
//                }
//            }
//        }

        jsPhases.invokeToplevel(phaseConfig, context, moduleFragment)

        val afterMain = System.currentTimeMillis()
        mainTime += afterMain - start

        currentStage = perFilePhaseList.size + 1

        val usefulDeclarations = usefulDeclarations(moduleFragment, context, this)

        // Load bodies
        for (decl in usefulDeclarations) {
            decl.accept(object : IrElementVisitorVoid {
                override fun visitElement(element: IrElement) {
                    element.acceptChildren(this, null)
                }

                override fun visitBody(body: IrBody) {
                    if (body is IrBodyBase<*> && body.loweredUpTo + 1 < currentStage) {
                        withBodies {
                            lowerBody(body)
                        }
                    }
                }
            }, null)
        }

        val afterDce = System.currentTimeMillis()
        dceTime += afterDce - afterMain

//        while (true) {
//            var changed = false
//
//            ArrayList(context.symbolTable.unboundClasses).forEach {
//                tryLoad(it)
//            }
//            ArrayList(context.symbolTable.unboundConstructors).forEach {
//                tryLoad(it)
//            }
//            ArrayList(context.symbolTable.unboundEnumEntries).forEach {
//                tryLoad(it)
//            }
//            ArrayList(context.symbolTable.unboundFields).forEach {
//                tryLoad(it)
//            }
//            ArrayList(context.symbolTable.unboundSimpleFunctions).forEach {
//                tryLoad(it)
//            }
//            ArrayList(context.symbolTable.unboundProperties).forEach {
//                tryLoad(it)
//            }
//            ArrayList(context.symbolTable.unboundTypeParameters).forEach {
//                tryLoad(it)
//            }
//
//            for (file in moduleFragment.files + dependencyModules.flatMap { it.files }) {
//                for (decl in ArrayList(file.declarations)) {
//                    if (decl.loweredUpTo < currentStage - 1) {
//                        lazyLower(decl)
//                        changed = true
//                    }
//                }
//            }
//            if (!changed) break
//        }

        finishingTime += System.currentTimeMillis() - afterDce

        return usefulDeclarations
    }

    fun invokeLowerings(file: IrFile) {
        lowerUpTo(file, perFilePhaseList.size + 1)
    }

    fun freeze() {
        frozen = true
    }

    private fun <T> withoutBodies(fn: () -> T): T {
        val previousBodies = bodiesEnabled
        bodiesEnabled = false

        return try {
            fn()
        } finally {
            bodiesEnabled = previousBodies
        }
    }

    override fun lazyLower(declaration: IrDeclaration) {
        // TODO other declarations
        if (declaration is IrDeclarationBase<*> && currentStage - 1 > declaration.loweredUpTo) {
            lazyLowerCalls++
            lowerUpTo(declaration, currentStage)
        }
    }

    private val IrDeclaration.correspondingProperty: IrProperty?
        get() = when (this) {
            is IrSimpleFunction -> correspondingPropertySymbol
            is IrField -> correspondingPropertySymbol
            else -> null
        }?.owner?.let {
            (it as? IrPropertyImpl)?.let {
                if (currentStage >= it.removedOn) null else it
            }
        }

    private val IrDeclaration.topLevel: IrDeclaration
        get() = (correspondingProperty ?: parent).let {
            if (it is IrDeclaration) it.topLevel else this
        }

    override fun lazyLower(file: IrFile) {
        lowerUpTo(file, currentStage)
    }

    override fun lowerBody(body: IrBodyBase<*>) {
        if (body.loweredUpTo + 1 < stageController.currentStage) {
            if (frozen) {
                error("Frozen! Cannot lazy lower body")
            }
            for (i in (body.loweredUpTo + 1) until stageController.currentStage) {
                withStage(i) {
                    val declaration = body.container
                    val fileBefore = declaration.fileOrNull as? IrFileImpl
                    if (fileBefore != null) {
                        val lowering = perFilePhaseList[i - 1]

                        if (lowering is BodyLowering) {
                            lowering.bodyLowering(context).lower(body, declaration)
                        }
                    }
                    body.loweredUpTo = i
                }
            }
        }
    }

    var deserializer: IrDeserializer? = null

    var dependencyGenerator: ExternalDependenciesGenerator? = null

    val loaded = ArrayDeque<IrDeclaration>()

    var freezeLoading = false

    override fun tryLoad(symbol: IrSymbol) {
        if (frozen) {
            error("Cannot load after freeze")
        }

        // Cannot throw due to LazyIr
        if (freezeLoading) {
            return
        }

        dependencyGenerator?.let { generator ->
            withBodies {
                //                generator.loadSymbol(symbol)
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
        stageController = NoopController(currentStage)
    }
}
