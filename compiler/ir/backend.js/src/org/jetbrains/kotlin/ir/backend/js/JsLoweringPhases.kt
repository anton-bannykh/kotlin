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
import org.jetbrains.kotlin.ir.backend.js.lower.inline.CopyInlineFunctionBody
import org.jetbrains.kotlin.ir.backend.js.lower.inline.FunctionInlining
import org.jetbrains.kotlin.ir.backend.js.lower.inline.RemoveInlineFunctionsLowering
import org.jetbrains.kotlin.ir.backend.js.lower.inline.ReturnableBlockLowering
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrDeclarationBase
import org.jetbrains.kotlin.ir.declarations.impl.IrFileImpl
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
    lowering: (JsIrBackendContext, ContextData) -> DeclarationTransformer,
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
    { context, data -> MoveBodilessDeclarationsToSeparatePlaceLowering(context, data) },
    name = "MoveBodilessDeclarationsToSeparatePlace",
    description = "Move `external` and `built-in` declarations into separate place to make the following lowerings do not care about them"
)

private val expectDeclarationsRemovingPhase = makeJsModulePhase(
    { context, _ -> ExpectDeclarationsRemoving(context) },
    name = "ExpectDeclarationsRemoving",
    description = "Remove expect declaration from module fragment"
)

private val expectDeclarationsBodyRemappingPhase = makeJsModulePhase(
    { context, _ -> ExpectDeclarationDefaultValueRemapping(context).toDeclarationTransformer() },
    name = "ExpectDeclarationsBodyRemapping",
    description = "Remove expect declaration from module fragment"
)

private val lateinitLoweringPhase = makeJsModulePhase(
    { context, _ -> LateinitLowering(context).toDeclarationTransformer() },
    name = "LateinitLowering",
    description = "Insert checks for lateinit field references"
)

private val functionInliningPhase = makeJsModulePhase(
    { context, _ -> FunctionInlining(context).toDeclarationTransformer() },
    name = "FunctionInliningPhase",
    description = "Perform function inlining",
    prerequisite = setOf(expectDeclarationsRemovingPhase)
)

private val removeInlineFunctionsLoweringPhase = makeJsModulePhase(
    { context, _ -> RemoveInlineFunctionsLowering(context).runPostfix() },
    name = "RemoveInlineFunctionsLowering",
    description = "Remove Inline functions with reified parameters from context",
    prerequisite = setOf(functionInliningPhase)
)

private val copyInlineFunctionBody = makeJsModulePhase(
    { context, _ -> CopyInlineFunctionBody(context).toDeclarationTransformer() },
    name = "CopyInlineFunctionBody",
    description = "Copy inline function body, so that the original version is saved in the history",
    prerequisite = setOf(removeInlineFunctionsLoweringPhase)
)

private val throwableSuccessorsLoweringPhase = makeJsModulePhase(
    { context, _ -> ThrowableSuccessorsLowering(context) },
    name = "ThrowableSuccessorsLowering",
    description = "Link kotlin.Throwable and JavaScript Error together to provide proper interop between language and platform exceptions"
)

private val throwableSuccessorsBodyLoweringPhase = makeJsModulePhase(
    { context, _ -> ThrowableSuccessorsBodyLowering(context).toDeclarationTransformer() },
    name = "ThrowableSuccessorsLowering",
    description = "Link kotlin.Throwable and JavaScript Error together to provide proper interop between language and platform exceptions"
)

private val tailrecLoweringPhase = makeJsModulePhase(
    { context, _ -> TailrecLowering(context).toDeclarationTransformer() },
    name = "TailrecLowering",
    description = "Replace `tailrec` callsites with equivalent loop"
)

private val unitMaterializationLoweringPhase = makeJsModulePhase(
    { context, _ -> UnitMaterializationLowering(context).toDeclarationTransformer() },
    name = "UnitMaterializationLowering",
    description = "Insert Unit object where it is supposed to be",
    prerequisite = setOf(tailrecLoweringPhase)
)

private val enumClassConstructorLoweringPhase = makeJsModulePhase(
    { context, _ -> EnumClassConstructorLowering(context).runPostfix() },
    name = "EnumClassConstructorLowering",
    description = "Transform Enum Class into regular Class"
)

private val enumClassConstructorBodyLoweringPhase = makeJsModulePhase(
    { context, _ -> EnumClassConstructorBodyTransformer(context).toDeclarationTransformer() },
    name = "EnumClassConstructorBodyLowering",
    description = "Transform Enum Class into regular Class"
)


private val enumClassLoweringPhase = makeJsModulePhase(
    { context, _ -> EnumClassLowering(context).runPostfix() },
    name = "EnumClassLowering",
    description = "Transform Enum Class into regular Class",
    prerequisite = setOf(enumClassConstructorLoweringPhase)
)

private val enumClassBodyLoweringPhase = makeJsModulePhase(
    { context, _ -> EnumClassBodyTransformer(context).toDeclarationTransformer() },
    name = "EnumClassBodyLowering",
    description = "Transform Enum Class into regular Class",
    prerequisite = setOf(enumClassLoweringPhase)
)

private val enumUsageLoweringPhase = makeJsModulePhase(
    { context, _ -> EnumUsageLowering(context).toDeclarationTransformer() },
    name = "EnumUsageLowering",
    description = "Replace enum access with invocation of corresponding function",
    prerequisite = setOf(enumClassLoweringPhase)
)

private val enumEntryRemovalLoweringPhase = makeJsModulePhase(
    { context, _ -> EnumClassRemoveEntriesLowering(context).runPostfix() },
    name = "EnumEntryRemovalLowering",
    description = "Replace enum entry with corresponding class",
    prerequisite = setOf(enumUsageLoweringPhase)
)

private val sharedVariablesLoweringPhase = makeJsModulePhase(
    { context, data -> SharedVariablesLowering(context, data).toDeclarationTransformer() },
    name = "SharedVariablesLowering",
    description = "Box captured mutable variables"
)

private val returnableBlockLoweringPhase = makeJsModulePhase(
    { context, _ -> ReturnableBlockLowering(context).toDeclarationTransformer() },
    name = "ReturnableBlockLowering",
    description = "Replace returnable block with do-while loop",
    prerequisite = setOf(functionInliningPhase)
)

private val localDelegatedPropertiesLoweringPhase = makeJsModulePhase(
    { context, _ -> LocalDelegatedPropertiesLowering().toDeclarationTransformer() },
    name = "LocalDelegatedPropertiesLowering",
    description = "Transform Local Delegated properties"
)

private val localDeclarationsLoweringPhase = makeJsModulePhase(
    { context, _ -> LocalDeclarationsLowering(context).toDeclarationTransformer() },
    name = "LocalDeclarationsLowering",
    description = "Move local declarations into nearest declaration container",
    prerequisite = setOf(sharedVariablesLoweringPhase, localDelegatedPropertiesLoweringPhase)
)

private val localClassExtractionPhase = makeJsModulePhase(
    { context, _ -> LocalClassPopupLowering(context).toDeclarationTransformer() },
    name = "LocalClassExtractionPhase",
    description = "Move local declarations into nearest declaration container",
    prerequisite = setOf(localDeclarationsLoweringPhase)
)

private val innerClassesDeclarationLoweringPhase = makeJsModulePhase(
    { context, data -> InnerClassesDeclarationLowering(context, data).toDeclarationTransformer() },
    name = "InnerClassesDeclarationLowering",
    description = "Capture outer this reference to inner class"
)

private val innerClassesConstructorBodyLoweringPhase = makeJsModulePhase(
    { context, data -> InnerClassesMemberBodyLowering(context, data).toDeclarationTransformer() },
    name = "innerClassesConstructorBodyLoweringPhase",
    description = "Capture outer this reference to inner class",
    prerequisite = setOf(innerClassesDeclarationLoweringPhase)
)

private val innerClassConstructorCallsLoweringPhase = makeJsModulePhase(
    { context, data -> InnerClassConstructorCallsLowering(context, data).toDeclarationTransformer() },
    name = "InnerClassConstructorCallsLowering",
    description = "Replace inner class constructor invocation"
)

private val suspendFunctionsLoweringPhase = makeJsModulePhase(
    { context, _ -> JsSuspendFunctionsLowering(context).toDeclarationTransformer() },
    name = "SuspendFunctionsLowering",
    description = "Transform suspend functions into CoroutineImpl instance and build state machine",
    prerequisite = setOf(unitMaterializationLoweringPhase)
)

private val suspendLambdasRemovingPhase = makeJsModulePhase(
    { context, _ -> RemoveSuspendLambdas().runPostfix() },
    name = "SuspendLambdasRemovalLowering",
    description = "Remove suspend lambdas as they are unused",
    prerequisite = setOf(suspendFunctionsLoweringPhase)
)


private val privateMembersLoweringPhase = makeJsModulePhase(
    { context, _ -> PrivateMembersLowering(context).toDeclarationTransformer() },
    name = "PrivateMembersLowering",
    description = "Extract private members from classes"
)

private val privateMembersBodyLoweringPhase = makeJsModulePhase(
    { context, _ -> PrivateMemberBodiesLowering(context).toDeclarationTransformer() },
    name = "PrivateMembersBodyLowering",
    description = "Fill extracted private members bodies"
)

private val callableReferenceLoweringPhase = makeJsModulePhase(
    { context, _ -> CallableReferenceLowering(context).toDeclarationTransformer() },
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
    { context, _ -> DefaultArgumentStubGenerator(context).runPostfix() },
    name = "DefaultArgumentStubGenerator",
    description = "Generate synthetic stubs for functions with default parameter values"
)

private val defaultArgumentStubBodyGeneratorPhase = makeJsModulePhase(
    { context, _ -> JsDefaultArgumentStubGenerator(context).toDeclarationTransformer() },
    name = "DefaultArgumentStubBodyGenerator",
    description = "Generate synthetic stubs for functions with default parameter values"
)

private val defaultParameterInjectorPhase = makeJsModulePhase(
    { context, _ -> DefaultParameterInjector(context, skipExternalMethods = true).toDeclarationTransformer() },
    name = "DefaultParameterInjector",
    description = "Replace callsite with default parameters with corresponding stub function",
    prerequisite = setOf(callableReferenceLoweringPhase, innerClassesDeclarationLoweringPhase)
)

private val defaultParameterCleanerPhase = makeJsModulePhase(
    { context, _ -> DefaultParameterCleaner(context).runPostfix() },
    name = "DefaultParameterCleaner",
    description = "Clean default parameters up"
)

private val jsDefaultCallbackGeneratorPhase = makeJsModulePhase(
    { context, _ -> JsDefaultCallbackGenerator(context).toDeclarationTransformer() },
    name = "JsDefaultCallbackGenerator",
    description = "Build binding for super calls with default parameters"
)

private val varargLoweringPhase = makeJsModulePhase(
    { context, _ -> VarargLowering(context).toDeclarationTransformer() },
    name = "VarargLowering",
    description = "Lower vararg arguments",
    prerequisite = setOf(callableReferenceLoweringPhase)
)

private val propertiesLoweringPhase = makeJsModulePhase(
    { context, _ -> PropertiesLowering(context, skipExternalProperties = true) },
    name = "PropertiesLowering",
    description = "Move fields and accessors out from its property"
)

private val initializersLoweringPhase = makeJsModulePhase(
    { context, _ -> InitializersBodyLowering(context, JsLoweredDeclarationOrigin.CLASS_STATIC_INITIALIZER, false).toDeclarationTransformer() },
    name = "InitializersLowering",
    description = "Merge init block and field initializers into [primary] constructor",
    prerequisite = setOf(enumClassConstructorLoweringPhase)
)

private val removeAnonymousInitializers = makeJsModulePhase(
    { context, _ -> RemoveAnonymousInitializers(context).toDeclarationTransformer() },
    name = "InitializersLowering",
    description = "Merge init block and field initializers into [primary] constructor",
    prerequisite = setOf(initializersLoweringPhase)
)

private val multipleCatchesLoweringPhase = makeJsModulePhase(
    { context, _ -> MultipleCatchesLowering(context).toDeclarationTransformer() },
    name = "MultipleCatchesLowering",
    description = "Replace multiple catches with single one"
)

private val bridgesConstructionPhase = makeJsModulePhase(
    { context, _ -> BridgesConstruction(context).toDeclarationTransformer() },
    name = "BridgesConstruction",
    description = "Generate bridges",
    prerequisite = setOf(suspendFunctionsLoweringPhase)
)

private val bridgesBodyConstructionPhase = makeJsModulePhase(
    { context, _ -> BridgesBodyConstruction(context).toDeclarationTransformer() },
    name = "BridgesBodyConstruction",
    description = "Generate bridges body",
    prerequisite = setOf(suspendFunctionsLoweringPhase)
)

private val typeOperatorLoweringPhase = makeJsModulePhase(
    { context, _ -> TypeOperatorLowering(context).toDeclarationTransformer() },
    name = "TypeOperatorLowering",
    description = "Lower IrTypeOperator with corresponding logic",
    prerequisite = setOf(bridgesConstructionPhase, removeInlineFunctionsLoweringPhase)
)

private val secondaryConstructorLoweringPhase = makeJsModulePhase(
    { context, _ -> SecondaryConstructorLowering(context).toDeclarationTransformer() },
    name = "SecondaryConstructorLoweringPhase",
    description = "Generate static functions for each secondary constructor",
    prerequisite = setOf(innerClassesDeclarationLoweringPhase)
)

private val secondaryConstructorBodyLoweringPhase = makeJsModulePhase(
    { context, _ -> SecondaryConstructorBodyLowering(context).toDeclarationTransformer() },
    name = "SecondaryConstructorLoweringPhase",
    description = "Generate static functions for each secondary constructor",
    prerequisite = setOf(innerClassesDeclarationLoweringPhase, secondaryConstructorLoweringPhase)
)


private val secondaryFactoryInjectorLoweringPhase = makeJsModulePhase(
    { context, _ -> SecondaryFactoryInjectorLowering(context).toDeclarationTransformer() },
    name = "SecondaryFactoryInjectorLoweringPhase",
    description = "Replace usage of secondary constructor with corresponding static function",
    prerequisite = setOf(innerClassesDeclarationLoweringPhase)
)

private val inlineClassDeclarationsLoweringPhase = makeJsModulePhase(
    { context, _ ->
        InlineClassLowering(context).inlineClassDeclarationLowering.toDeclarationTransformer()
    },
    name = "InlineClassDeclarationsLowering",
    description = "Handle inline classes declarations"
)

private val inlineClassDeclarationBodyLoweringPhase = makeJsModulePhase(
    { context, _ ->
        InlineClassLowering(context).inlineClassDeclarationBodyLowering.toDeclarationTransformer()
    },
    name = "InlineClassDeclarationBodyLowering",
    description = "Handle inline classes declaration bodies"
)

private val inlineClassUsageLoweringPhase = makeJsModulePhase(
    { context, _ ->
        InlineClassLowering(context).inlineClassUsageLowering.toDeclarationTransformer()
    },
    name = "InlineClassUsageLowering",
    description = "Handle inline classes usages"
)


private val autoboxingTransformerPhase = makeJsModulePhase(
    { context, _ -> AutoboxingTransformer(context).toDeclarationTransformer() },
    name = "AutoboxingTransformer",
    description = "Insert box/unbox intrinsics"
)

private val fieldInitializerCreationPhase = makeJsModulePhase(
    { context, _ -> CreateIrFieldInitializerFunction(context).runPostfix() },
    name = "fieldInitializerCreationPhase",
    description = "Transform statement-like-expression nodes into pure-statement to make it easily transform into JS",
    prerequisite = setOf(typeOperatorLoweringPhase, suspendFunctionsLoweringPhase)
)


private val blockDecomposerLoweringPhase = makeJsModulePhase(
    { context, _ -> BlockDecomposerLowering(context).toDeclarationTransformer() },
    name = "BlockDecomposerLowering",
    description = "Transform statement-like-expression nodes into pure-statement to make it easily transform into JS",
    prerequisite = setOf(typeOperatorLoweringPhase, suspendFunctionsLoweringPhase, fieldInitializerCreationPhase)
)

private val classReferenceLoweringPhase = makeJsModulePhase(
    { context, _ -> ClassReferenceLowering(context).toDeclarationTransformer() },
    name = "ClassReferenceLowering",
    description = "Handle class references"
)

private val primitiveCompanionLoweringPhase = makeJsModulePhase(
    { context, _ -> PrimitiveCompanionLowering(context).toDeclarationTransformer() },
    name = "PrimitiveCompanionLowering",
    description = "Replace common companion object access with platform one"
)

private val constLoweringPhase = makeJsModulePhase(
    { context, _ -> ConstLowering(context).toDeclarationTransformer() },
    name = "ConstLowering",
    description = "Wrap Long and Char constants into constructor invocation"
)

private val callsLoweringPhase = makeJsModulePhase(
    { context, _ -> CallsLowering(context).toDeclarationTransformer() },
    name = "CallsLowering",
    description = "Handle intrinsics"
)

//private val testGenerationPhase = makeJsModulePhase(
//    ::TestGenerator,
//    name = "TestGenerationLowering",
//    description = "Generate invocations to kotlin.test suite and test functions"
//)

private val staticMembersLoweringPhase = makeJsModulePhase(
    { context, _ -> StaticMembersLowering(context) },
    name = "StaticMembersLowering",
    description = "Move static member declarations to top-level"
)

private enum class LoweringType(val bodiesEnabled: Boolean, val canModifyDeclarations: Boolean) {
    DeclarationLowering(bodiesEnabled = false, canModifyDeclarations = true),
    BodyLowering(bodiesEnabled = true, canModifyDeclarations = false),
    FIX_ME(bodiesEnabled = true, canModifyDeclarations = true)
}

private val perFilePhaseList = listOf(
    expectDeclarationsRemovingPhase to LoweringType.DeclarationLowering, // OK
    expectDeclarationsBodyRemappingPhase to LoweringType.BodyLowering, // OK
    moveBodilessDeclarationsToSeparatePlacePhase to LoweringType.FIX_ME, // Needs to detect @JsModule and @JsQualifier. TODO: should become obsolete
    functionInliningPhase to LoweringType.BodyLowering, // OK
    removeInlineFunctionsLoweringPhase to LoweringType.DeclarationLowering, // OK
    copyInlineFunctionBody to LoweringType.BodyLowering, // OK
    lateinitLoweringPhase to LoweringType.BodyLowering, // OK
    tailrecLoweringPhase to LoweringType.BodyLowering, // OK
    enumClassConstructorLoweringPhase to LoweringType.DeclarationLowering, // OK
    enumClassConstructorBodyLoweringPhase to LoweringType.BodyLowering, // OK

    sharedVariablesLoweringPhase to LoweringType.BodyLowering, // OK
    localDelegatedPropertiesLoweringPhase to LoweringType.BodyLowering, // OK
    localDeclarationsLoweringPhase to LoweringType.BodyLowering, // OK

    localClassExtractionPhase to LoweringType.BodyLowering,

    innerClassesDeclarationLoweringPhase to LoweringType.DeclarationLowering, // OK
    innerClassesConstructorBodyLoweringPhase to LoweringType.BodyLowering, // OK
    innerClassConstructorCallsLoweringPhase to LoweringType.BodyLowering, // OK

    propertiesLoweringPhase to LoweringType.DeclarationLowering, // OK
    initializersLoweringPhase to LoweringType.BodyLowering, // OK
    removeAnonymousInitializers to LoweringType.DeclarationLowering, // OK
    // Common prefix ends
    enumClassLoweringPhase to LoweringType.DeclarationLowering, // OK
    enumClassBodyLoweringPhase to LoweringType.BodyLowering, // OK
    enumUsageLoweringPhase to LoweringType.BodyLowering, // OK
    enumEntryRemovalLoweringPhase to LoweringType.DeclarationLowering, // OK

    returnableBlockLoweringPhase to LoweringType.BodyLowering, // OK
    unitMaterializationLoweringPhase to LoweringType.BodyLowering, // OK
    suspendFunctionsLoweringPhase to LoweringType.BodyLowering, // OK
    suspendLambdasRemovingPhase to LoweringType.DeclarationLowering, // OK
    privateMembersLoweringPhase to LoweringType.DeclarationLowering, // OK
    privateMembersBodyLoweringPhase to LoweringType.BodyLowering, // OK
    callableReferenceLoweringPhase to LoweringType.BodyLowering, // OK -- creates new declarations from bodies

    defaultArgumentStubGeneratorPhase to LoweringType.FIX_ME, // Copies type parameter annotations
    defaultArgumentStubBodyGeneratorPhase to LoweringType.BodyLowering, // OK
    defaultParameterInjectorPhase to LoweringType.BodyLowering, // OK
    jsDefaultCallbackGeneratorPhase to LoweringType.BodyLowering, // OK
    defaultParameterCleanerPhase to LoweringType.DeclarationLowering, // OK

    throwableSuccessorsLoweringPhase to LoweringType.DeclarationLowering, // OK
    throwableSuccessorsBodyLoweringPhase to LoweringType.BodyLowering, // OK
    varargLoweringPhase to LoweringType.BodyLowering, // OK
    multipleCatchesLoweringPhase to LoweringType.BodyLowering, // OK
    bridgesConstructionPhase to LoweringType.FIX_ME, // TODO Reads @JsName
    bridgesBodyConstructionPhase to LoweringType.BodyLowering, // OK
    typeOperatorLoweringPhase to LoweringType.BodyLowering, // OK

    secondaryConstructorLoweringPhase to LoweringType.DeclarationLowering, // OK
    secondaryConstructorBodyLoweringPhase to LoweringType.BodyLowering, // OK
    secondaryFactoryInjectorLoweringPhase to LoweringType.BodyLowering, // OK

    classReferenceLoweringPhase to LoweringType.BodyLowering, // OK

    inlineClassDeclarationsLoweringPhase to LoweringType.DeclarationLowering, // OK
    inlineClassDeclarationBodyLoweringPhase to LoweringType.BodyLowering, // OK
    inlineClassUsageLoweringPhase to LoweringType.BodyLowering, // OK

    autoboxingTransformerPhase to LoweringType.BodyLowering, // OK
    fieldInitializerCreationPhase to LoweringType.DeclarationLowering, // OK
    blockDecomposerLoweringPhase to LoweringType.BodyLowering, // OK
    primitiveCompanionLoweringPhase to LoweringType.BodyLowering, // OK
    constLoweringPhase to LoweringType.BodyLowering, // OK
    callsLoweringPhase to LoweringType.BodyLowering, // OK
    staticMembersLoweringPhase to LoweringType.DeclarationLowering // OK
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

    private fun <T> withStage(stage: Int, fn: () -> T): T {
        val previousStage = currentStage
        currentStage = stage
        val result = fn()
        currentStage = previousStage
        return result
    }

    lateinit var context: JsIrBackendContext

    lateinit var dataMap: Map<ModuleDescriptor, ContextData>

//    private val fileToModule = mutableMapOf<IrPackageFragment, ModuleDescriptor>()

    // TODO is this needed at all?
    private fun lowerUpTo(file: IrFile, stageNonInclusive: Int) {
        val loweredUpTo = (file as? IrFileImpl)?.loweredUpTo ?: 0
        for (i in loweredUpTo + 1 until stageNonInclusive) {
            if (frozen) error("frozen!")
            withStage(i) {
                ArrayList(file.declarations).forEach {
                    lowerUpTo(it, i + 1, true)
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

    private fun lowerUpTo(declaration: IrDeclaration, stageNonInclusive: Int, skipCheck: Boolean = false) {
        while (declaration.loweredUpTo + 1 < stageNonInclusive) {
            val i = declaration.loweredUpTo + 1
            withStage(i) {
                lazyLowerIteration++
                val topLevelDeclaration = declaration.topLevel

                if (topLevelDeclaration is IrDeclarationBase<*> && topLevelDeclaration.loweredUpTo < i - 1) {
                    error("WTF?")
                }

//                if (topLevelDeclaration is IrProperty && topLevelDeclaration.name.asString() == "coroutineContext") {
//                    println("!!!")
//                }

                if (topLevelDeclaration.loweredUpTo == i - 1 && topLevelDeclaration.parent is IrFile) {
                    val fileBefore = topLevelDeclaration.parent as IrFileImpl

                    if (skipCheck || topLevelDeclaration in fileBefore.declarations) {
                        if (frozen) {
                            error("frozen! ${topLevelDeclaration.name.asString()} in ${fileBefore.fileEntry.name}")
                        }

                        val module = fileBefore.symbol.descriptor.containingDeclaration
                        val data = dataMap[module]!!

                        val (lowering, loweringType) = perFilePhaseList[i - 1]

                        val result = if (loweringType.bodiesEnabled)
                            withBodies { lowering(context, data).transformFlat(topLevelDeclaration) }
                        else
                            lowering(context, data).transformFlat(topLevelDeclaration)

                        actualLoweringInvocations++

                        topLevelDeclaration.loweredUpTo = i
                        if (result != null) {
                            result.forEach {
                                it.loweredUpTo = i
                                it.parent = fileBefore
                            }

                            fileBefore.declarations.remove(topLevelDeclaration)

                            fileBefore.declarations += result
                        }
                    }
                }

                declaration.loweredUpTo = Math.max(i, topLevelDeclaration.loweredUpTo)
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
//        val generator = dependencyGenerator
//        try {
//            dependencyGenerator = null
            return withStage(0) {
                block()
            }
//        } finally {
//            dependencyGenerator = generator
//        }
    }

    private var frozen = false

    fun invokeTopLevel(phaseConfig: PhaseConfig, moduleFragment: IrModuleFragment, dependencyModules: List<IrModuleFragment>) {
//        for (module in listOf(moduleFragment) + dependencyModules) {
//            for (file in module.files) {
//                fileToModule[file] = module.descriptor
//            }
//        }

        val start = System.currentTimeMillis()

        for (stage in 1..perFilePhaseList.size) {
            for (module in dependencyModules + moduleFragment) {
                for (file in module.files) {
                    lowerUpTo(file, stage + 1)
                }
            }
        }

//        jsPhases.invokeToplevel(phaseConfig, context, moduleFragment)

        mainTime += System.currentTimeMillis() - start


        currentStage = perFilePhaseList.size + 1

        while (true) {
            var changed = false

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

            for (file in moduleFragment.files + dependencyModules.flatMap { it.files }) {
                for (decl in ArrayList(file.declarations)) {
                    if (decl.loweredUpTo < perFilePhaseList.size) {
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
        if (declaration is IrDeclarationBase<*> && currentStage - 1 > declaration.loweredUpTo) {
            lazyLowerCalls++
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

    var deserializer: IrDeserializer? = null

    var dependencyGenerator: ExternalDependenciesGenerator? = null

    val loaded = ArrayDeque<IrDeclaration>()

    override fun tryLoad(symbol: IrSymbol) {
//        if (frozen) {
//            error("Cannot load after freeze")
//        }

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
        stageController = NoopController()
    }
}
