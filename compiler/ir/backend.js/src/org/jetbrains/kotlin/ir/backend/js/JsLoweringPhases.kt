/*
 * Copyright 2010-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js

import org.jetbrains.kotlin.backend.common.*
import org.jetbrains.kotlin.backend.common.lower.*
import org.jetbrains.kotlin.backend.common.lower.inline.FunctionInlining
import org.jetbrains.kotlin.backend.common.phaser.*
import org.jetbrains.kotlin.ir.backend.js.ir.JsIrBuilder
import org.jetbrains.kotlin.ir.backend.js.lower.*
import org.jetbrains.kotlin.ir.backend.js.lower.calls.CallsLowering
import org.jetbrains.kotlin.ir.backend.js.lower.coroutines.JsSuspendFunctionsLowering
import org.jetbrains.kotlin.ir.backend.js.lower.inline.CopyInlineFunctionBodyLowering
import org.jetbrains.kotlin.ir.backend.js.lower.inline.RemoveInlineFunctionsWithReifiedTypeParametersLowering
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment


private fun DeclarationContainerLoweringPass.runOnFilesPostfix(files: Iterable<IrFile>) = files.forEach { runOnFilePostfix(it) }

private fun ClassLoweringPass.runOnFilesPostfix(moduleFragment: IrModuleFragment) = moduleFragment.files.forEach { runOnFilePostfix(it) }

private fun makeJsModulePhase(
    lowering: (JsIrBackendContext) -> FileLoweringPass,
    name: String,
    description: String,
    prerequisite: Set<AnyNamedPhase> = emptySet()
) = makeIrModulePhase<JsIrBackendContext>(lowering, name, description, prerequisite, actions = setOf(validationAction, defaultDumper))

private fun makeJsBodyLoweringPhase(
    lowering: (JsIrBackendContext) -> BodyLoweringPass,
    name: String,
    description: String,
    prerequisite: Set<AnyNamedPhase> = emptySet()
) = makeJsModulePhase(
    { context -> lowering(context).toDeclarationTransformer().toFileLoweringPass() },
    name,
    description,
    prerequisite
)


private fun makeCustomJsModulePhase(
    op: (JsIrBackendContext, IrModuleFragment) -> Unit,
    description: String,
    name: String,
    prerequisite: Set<AnyNamedPhase> = emptySet()
) = namedIrModulePhase(
    name,
    description,
    prerequisite,
    actions = setOf(defaultDumper, validationAction),
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

private val moveBodilessDeclarationsToSeparatePlace = makeJsModulePhase(
    ::MoveBodilessDeclarationsToSeparatePlaceLowering,
    name = "MoveBodilessDeclarationsToSeparatePlaceLowering",
    description = "Move bodiless declarations to a separate place"
)

private val validateIrBeforeLowering = makeCustomJsModulePhase(
    { context, module -> validationCallback(context, module) },
    name = "ValidateIrBeforeLowering",
    description = "Validate IR before lowering"
)

private val validateIrAfterLowering = makeCustomJsModulePhase(
    { context, module -> validationCallback(context, module) },
    name = "ValidateIrAfterLowering",
    description = "Validate IR after lowering"
)

private val expectDeclarationsRemovingPhase = makeJsModulePhase(
    ::ExpectDeclarationsRemoveLowering,
    name = "ExpectDeclarationsRemoving",
    description = "Remove expect declaration from module fragment"
)

private val lateinitPropertiesLoweringPhase = makeJsModulePhase(
    ::LateinitPropertiesLowering,
    name = "LateinitPropertiesLowering",
    description = "Insert checks for lateinit field references"
)

private val lateinitBodyLoweringPhase = makeJsModulePhase(
    ::LateinitBodyLowering,
    name = "LateinitBodyLowering",
    description = "Insert checks for lateinit field references"
)

private val stripTypeAliasDeclarationsPhase = makeJsModulePhase(
    { StripTypeAliasDeclarationsLowering() },
    name = "StripTypeAliasDeclarations",
    description = "Strip typealias declarations"
)

// TODO make all lambda-related stuff work with IrFunctionExpression and drop this phase
private val provisionalFunctionExpressionPhase = makeJsModulePhase(
    { ProvisionalFunctionExpressionLowering() },
    name = "FunctionExpression",
    description = "Transform IrFunctionExpression to a local function reference"
)

private val arrayConstructorPhase = makeJsModulePhase(
    ::ArrayConstructorLowering,
    name = "ArrayConstructor",
    description = "Transform `Array(size) { index -> value }` into a loop"
)

private val functionInliningPhase = makeJsModulePhase(
    ::FunctionInlining,
    name = "FunctionInliningPhase",
    description = "Perform function inlining",
    prerequisite = setOf(expectDeclarationsRemovingPhase)
)

private val removeInlineFunctionsWithReifiedTypeParametersLoweringPhase = makeJsModulePhase(
    { RemoveInlineFunctionsWithReifiedTypeParametersLowering() },
    name = "RemoveInlineFunctionsWithReifiedTypeParametersLowering",
    description = "Remove Inline functions with reified parameters from context",
    prerequisite = setOf(functionInliningPhase)
)

private val copyInlineFunctionBodyLoweringPhase = makeJsModulePhase(
    ::CopyInlineFunctionBodyLowering,
    name = "CopyInlineFunctionBody",
    description = "Copy inline function body",
    prerequisite = setOf(functionInliningPhase)
)

private val throwableSuccessorsLoweringPhase = makeJsModulePhase(
    ::ThrowableLowering,
    name = "ThrowableLowering",
    description = "Link kotlin.Throwable and JavaScript Error together to provide proper interop between language and platform exceptions"
)

private val tailrecLoweringPhase = makeJsBodyLoweringPhase(
    ::TailrecLowering,
    name = "TailrecLowering",
    description = "Replace `tailrec` callsites with equivalent loop"
)

private val enumClassConstructorLoweringPhase = makeJsModulePhase(
    ::EnumClassConstructorLowering,
    name = "EnumClassConstructorLowering",
    description = "Transform Enum Class into regular Class"
)

private val enumClassConstructorBodyLoweringPhase = makeJsModulePhase(
    ::EnumClassConstructorBodyTransformer,
    name = "EnumClassConstructorBodyLowering",
    description = "Transform Enum Class into regular Class"
)


private val enumEntryInstancesLoweringPhase = makeJsModulePhase(
    ::EnumEntryInstancesLowering,
    name = "EnumEntryInstancesLowering",
    description = "Create instance variable for each enum entry initialized with `null`",
    prerequisite = setOf(enumClassConstructorLoweringPhase)
)

private val enumClassCreateInitializerLoweringPhase = makeJsModulePhase(
    ::EnumClassCreateInitializerLowering,
    name = "EnumClassCreateInitializerLowering",
    description = "Create initializer for enum entries",
    prerequisite = setOf(enumClassConstructorLoweringPhase)
)

private val enumEntryCreateGetInstancesFunsLoweringPhase = makeJsModulePhase(
    ::EnumEntryCreateGetInstancesFunsLowering,
    name = "EnumEntryCreateGetInstancesFunsLowering",
    description = "Create enumEntry_getInstance functions",
    prerequisite = setOf(enumClassConstructorLoweringPhase)
)

private val enumSyntheticFunsLoweringPhase = makeJsModulePhase(
    ::EnumSyntheticFunctionsLowering,
    name = "EnumSyntheticFunctionsLowering",
    description = "Implement `valueOf` and `values`",
    prerequisite = setOf(enumClassConstructorLoweringPhase)
)

private val enumUsageLoweringPhase = makeJsModulePhase(
    ::EnumUsageLowering,
    name = "EnumUsageLowering",
    description = "Replace enum access with invocation of corresponding function",
    prerequisite = setOf(enumEntryCreateGetInstancesFunsLoweringPhase)
)

private val enumEntryRemovalLoweringPhase = makeJsModulePhase(
    ::EnumClassRemoveEntriesLowering,
    name = "EnumEntryRemovalLowering",
    description = "Replace enum entry with corresponding class",
    prerequisite = setOf(enumUsageLoweringPhase)
)

private val sharedVariablesLoweringPhase = makeJsModulePhase(
    ::SharedVariablesLowering,
    name = "SharedVariablesLowering",
    description = "Box captured mutable variables"
)

private val returnableBlockLoweringPhase = makeJsModulePhase(
    ::ReturnableBlockLowering,
    name = "ReturnableBlockLowering",
    description = "Replace returnable block with do-while loop",
    prerequisite = setOf(functionInliningPhase)
)

private val localDelegatedPropertiesLoweringPhase = makeJsBodyLoweringPhase(
    { LocalDelegatedPropertiesLowering() },
    name = "LocalDelegatedPropertiesLowering",
    description = "Transform Local Delegated properties"
)

private val localDeclarationsLoweringPhase = makeJsModulePhase(
    ::LocalDeclarationsLowering,
    name = "LocalDeclarationsLowering",
    description = "Move local declarations into nearest declaration container",
    prerequisite = setOf(sharedVariablesLoweringPhase, localDelegatedPropertiesLoweringPhase)
)

private val localClassExtractionPhase = makeJsBodyLoweringPhase(
    ::LocalClassPopupLowering,
    name = "LocalClassExtractionPhase",
    description = "Move local declarations into nearest declaration container",
    prerequisite = setOf(localDeclarationsLoweringPhase)
)

private val innerClassesLoweringPhase = makeJsModulePhase(
    ::InnerClassesLowering,
    name = "InnerClassesLowering",
    description = "Capture outer this reference to inner class"
)

private val innerClassesMemberBodyLoweringPhase = makeJsModulePhase(
    ::InnerClassesMemberBodyLowering,
    name = "InnerClassesMemberBodyLowering",
    description = "Capture outer this reference to inner class"
)

private val innerClassConstructorCallsLoweringPhase = makeJsModulePhase(
    ::InnerClassConstructorCallsLowering,
    name = "InnerClassConstructorCallsLowering",
    description = "Replace inner class constructor invocation"
)

private val suspendFunctionsLoweringPhase = makeJsModulePhase(
    ::JsSuspendFunctionsLowering,
    name = "SuspendFunctionsLowering",
    description = "Transform suspend functions into CoroutineImpl instance and build state machine"
)

private val suspendLambdasRemovingPhase = makeJsModulePhase(
    { _ -> RemoveSuspendLambdas() },
    name = "RemoveSuspendLambdasLowering",
    description = "Remove suspend lambdas"
)

private val privateMembersLoweringPhase = makeJsModulePhase(
    ::PrivateMembersLowering,
    name = "PrivateMembersLowering",
    description = "Extract private members from classes"
)

private val privateMembersBodyLoweringPhase = makeJsModulePhase(
    ::PrivateMemberBodiesLowering,
    name = "PrivateMembersBodyLowering",
    description = "Redirect private member accesses to statics"
)

private val callableReferenceLoweringPhase = makeJsModulePhase(
    ::CallableReferenceLowering,
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
    ::JsDefaultArgumentStubGenerator,
    name = "DefaultArgumentStubGenerator",
    description = "Generate synthetic stubs for functions with default parameter values"
)

private val defaultParameterPatchOverridenSymbolsPhase = makeJsModulePhase(
    ::DefaultParameterPatchOverridenSymbolsLowering,
    name = "DefaultParameterPatchOverridenSymbolsLowering",
    description = "Set correct overrides"
)

private val defaultParameterInjectorPhase = makeJsModulePhase(
    { context -> DefaultParameterInjector(context, skipExternalMethods = true) },
    name = "DefaultParameterInjector",
    description = "Replace callsite with default parameters with corresponding stub function",
    prerequisite = setOf(callableReferenceLoweringPhase, innerClassesLoweringPhase)
)

private val defaultParameterCleanerPhase = makeJsModulePhase(
    ::DefaultParameterCleaner,
    name = "DefaultParameterCleaner",
    description = "Clean default parameters up"
)

private val jsDefaultCallbackGeneratorPhase = makeJsModulePhase(
    ::JsDefaultCallbackGenerator,
    name = "JsDefaultCallbackGenerator",
    description = "Build binding for super calls with default parameters"
)

private val varargLoweringPhase = makeJsBodyLoweringPhase(
    ::VarargLowering,
    name = "VarargLowering",
    description = "Lower vararg arguments",
    prerequisite = setOf(callableReferenceLoweringPhase)
)

private val propertiesLoweringPhase = makeJsModulePhase(
    { context -> PropertiesLowering(context, skipExternalProperties = true, generateAnnotationFields = true) },
    name = "PropertiesLowering",
    description = "Move fields and accessors out from its property"
)

private val syntheticPrimaryConstructorLoweringPhase = makeJsModulePhase(
    ::SyntheticPrimaryConstructorLowering,
    name = "SyntheticPrimaryConstructorLowering",
    description = "Creates primary constructor if it doesn't exist",
    prerequisite = setOf(enumClassConstructorLoweringPhase)
)

private val delegateToSyntheticPrimaryConstructorLoweringPhase = makeJsModulePhase(
    { DelegateToSyntheticPrimaryConstructor() },
    name = "DelegateToSyntheticPrimaryConstructor",
    description = "Delegate secondary constructors to the synthetic primary constructor if needed",
    prerequisite = setOf(syntheticPrimaryConstructorLoweringPhase)
)

private val initializersLoweringPhase = makeJsModulePhase(
    ::InitializersLowering,
    name = "InitializersLowering",
    description = "Merge init block and field initializers into [primary] constructor",
    prerequisite = setOf(enumClassConstructorLoweringPhase, delegateToSyntheticPrimaryConstructorLoweringPhase)
)

private val initializersCleanupLoweringPhase = makeJsModulePhase(
    ::InitializersCleanup,
    name = "InitializersCleanupLowering",
    description = "Remove anonymousInitBlock and field initializers",
    prerequisite = setOf(enumClassConstructorLoweringPhase, delegateToSyntheticPrimaryConstructorLoweringPhase)
)


private val multipleCatchesLoweringPhase = makeJsModulePhase(
    ::MultipleCatchesLowering,
    name = "MultipleCatchesLowering",
    description = "Replace multiple catches with single one"
)

private val bridgesConstructionPhase = makeJsModulePhase(
    ::BridgesConstruction,
    name = "BridgesConstruction",
    description = "Generate bridges",
    prerequisite = setOf(suspendFunctionsLoweringPhase)
)

private val typeOperatorLoweringPhase = makeJsModulePhase(
    ::TypeOperatorLowering,
    name = "TypeOperatorLowering",
    description = "Lower IrTypeOperator with corresponding logic",
    prerequisite = setOf(bridgesConstructionPhase, removeInlineFunctionsWithReifiedTypeParametersLoweringPhase)
)

private val secondaryConstructorLoweringPhase = makeJsModulePhase(
    ::SecondaryConstructorLowering,
    name = "SecondaryConstructorLoweringPhase",
    description = "Generate static functions for each secondary constructor",
    prerequisite = setOf(innerClassesLoweringPhase)
)

private val secondaryFactoryInjectorLoweringPhase = makeJsModulePhase(
    ::SecondaryFactoryInjectorLowering,
    name = "SecondaryFactoryInjectorLoweringPhase",
    description = "Replace usage of secondary constructor with corresponding static function",
    prerequisite = setOf(innerClassesLoweringPhase)
)

private val inlineClassLoweringPhase = makeCustomJsModulePhase(
    { context, module ->
        InlineClassLowering(context).run {
            inlineClassDeclarationLowering.runPostfix().toFileLoweringPass().lower(module)
            inlineClassUsageLowering.toDeclarationTransformer().toFileLoweringPass().lower(module)
        }
    },
    name = "InlineClassLowering",
    description = "Handle inline classes"
)

private val autoboxingTransformerPhase = makeJsBodyLoweringPhase(
    ::AutoboxingTransformer,
    name = "AutoboxingTransformer",
    description = "Insert box/unbox intrinsics"
)

private val createIrFieldInitializerFunction = makeJsModulePhase(
    ::CreateIrFieldInitializerFunction,
    name = "CreateIrFieldInitializerFunction",
    description = "Create initializer function for the decomposed field initializer",
    prerequisite = setOf(typeOperatorLoweringPhase, suspendFunctionsLoweringPhase)
)

private val blockDecomposerLoweringPhase = makeJsBodyLoweringPhase(
    { context ->
        BlockDecomposerLowering(context, { JsIrBuilder.buildCall(context.intrinsics.unreachable.symbol, context.irBuiltIns.nothingType) })
    },
    name = "BlockDecomposerLowering",
    description = "Transform statement-like-expression nodes into pure-statement to make it easily transform into JS",
    prerequisite = setOf(createIrFieldInitializerFunction)
)

private val classReferenceLoweringPhase = makeJsModulePhase(
    ::ClassReferenceLowering,
    name = "ClassReferenceLowering",
    description = "Handle class references"
)

private val primitiveCompanionLoweringPhase = makeJsBodyLoweringPhase(
    ::PrimitiveCompanionLowering,
    name = "PrimitiveCompanionLowering",
    description = "Replace common companion object access with platform one"
)

private val constLoweringPhase = makeJsBodyLoweringPhase(
    ::ConstLowering,
    name = "ConstLowering",
    description = "Wrap Long and Char constants into constructor invocation"
)

private val callsLoweringPhase = makeJsBodyLoweringPhase(
    ::CallsLowering,
    name = "CallsLowering",
    description = "Handle intrinsics"
)

private val testGenerationPhase = makeJsModulePhase(
    ::TestGenerator,
    name = "TestGenerationLowering",
    description = "Generate invocations to kotlin.test suite and test functions"
)

private val staticMembersLoweringPhase = makeJsModulePhase(
    ::StaticMembersLowering,
    name = "StaticMembersLowering",
    description = "Move static member declarations to top-level"
)

private val objectDeclarationLoweringPhase = makeJsModulePhase(
    ::ObjectDeclarationLowering,
    name = "ObjectDeclarationLowering",
    description = "Create lazy object instance generator functions"
)

private val objectUsageLoweringPhase = makeJsModulePhase(
    ::ObjectUsageLowering,
    name = "ObjectUsageLowering",
    description = "Transform IrGetObjectValue into instance generator call"
)

val phaseList = listOf(
    moveBodilessDeclarationsToSeparatePlace,
    validateIrBeforeLowering,
    testGenerationPhase,
    expectDeclarationsRemovingPhase,
    stripTypeAliasDeclarationsPhase,
    arrayConstructorPhase,
    functionInliningPhase,
    provisionalFunctionExpressionPhase,
    lateinitPropertiesLoweringPhase,
    lateinitBodyLoweringPhase,
    tailrecLoweringPhase,
    enumClassConstructorLoweringPhase,
    enumClassConstructorBodyLoweringPhase,
    sharedVariablesLoweringPhase,
    localDelegatedPropertiesLoweringPhase,
    localDeclarationsLoweringPhase,
    localClassExtractionPhase,
    innerClassesLoweringPhase,
    innerClassesMemberBodyLoweringPhase,
    innerClassConstructorCallsLoweringPhase,
    propertiesLoweringPhase,
    syntheticPrimaryConstructorLoweringPhase,
    delegateToSyntheticPrimaryConstructorLoweringPhase,
    initializersLoweringPhase,
    initializersCleanupLoweringPhase,
    // Common prefix ends
    enumEntryInstancesLoweringPhase,
    enumClassCreateInitializerLoweringPhase,
    enumEntryCreateGetInstancesFunsLoweringPhase,
    enumSyntheticFunsLoweringPhase,
    enumUsageLoweringPhase,
    enumEntryRemovalLoweringPhase,
    suspendFunctionsLoweringPhase,
    suspendLambdasRemovingPhase,
    returnableBlockLoweringPhase,
    privateMembersLoweringPhase,
    privateMembersBodyLoweringPhase,
    callableReferenceLoweringPhase,
    defaultArgumentStubGeneratorPhase,
    defaultParameterPatchOverridenSymbolsPhase,
    defaultParameterInjectorPhase,
    defaultParameterCleanerPhase,
    jsDefaultCallbackGeneratorPhase,
    removeInlineFunctionsWithReifiedTypeParametersLoweringPhase,
    throwableSuccessorsLoweringPhase,
    varargLoweringPhase,
    multipleCatchesLoweringPhase,
    bridgesConstructionPhase,
    typeOperatorLoweringPhase,
    secondaryConstructorLoweringPhase,
    secondaryFactoryInjectorLoweringPhase,
    classReferenceLoweringPhase,
    inlineClassLoweringPhase,
    autoboxingTransformerPhase,
    createIrFieldInitializerFunction,
    blockDecomposerLoweringPhase,
    primitiveCompanionLoweringPhase,
    constLoweringPhase,
    objectDeclarationLoweringPhase,
    objectUsageLoweringPhase,
    callsLoweringPhase,
    validateIrAfterLowering
)

val jsPhases = namedIrModulePhase(
    name = "IrModuleLowering",
    description = "IR module lowering",
    lower = scriptRemoveReceiverLowering then
            moveBodilessDeclarationsToSeparatePlace then
            validateIrBeforeLowering then
            testGenerationPhase then
            expectDeclarationsRemovingPhase then
            stripTypeAliasDeclarationsPhase then
            arrayConstructorPhase then
            functionInliningPhase then
            copyInlineFunctionBodyLoweringPhase then
            createScriptFunctionsPhase then
            provisionalFunctionExpressionPhase then
            lateinitPropertiesLoweringPhase then
            lateinitBodyLoweringPhase then
            tailrecLoweringPhase then
            enumClassConstructorLoweringPhase then
            enumClassConstructorBodyLoweringPhase then
            sharedVariablesLoweringPhase then
            localDelegatedPropertiesLoweringPhase then
            localDeclarationsLoweringPhase then
            localClassExtractionPhase then
            innerClassesLoweringPhase then
            innerClassesMemberBodyLoweringPhase then
            innerClassConstructorCallsLoweringPhase then
            propertiesLoweringPhase then
            syntheticPrimaryConstructorLoweringPhase then
            delegateToSyntheticPrimaryConstructorLoweringPhase then
            initializersLoweringPhase then
            initializersCleanupLoweringPhase then
            // Common prefix ends
            enumEntryInstancesLoweringPhase then
            enumClassCreateInitializerLoweringPhase then
            enumEntryCreateGetInstancesFunsLoweringPhase then
            enumSyntheticFunsLoweringPhase then
            enumUsageLoweringPhase then
            enumEntryRemovalLoweringPhase then

            suspendFunctionsLoweringPhase then
            suspendLambdasRemovingPhase then
            returnableBlockLoweringPhase then
            privateMembersLoweringPhase then
            privateMembersBodyLoweringPhase then
            callableReferenceLoweringPhase then
            defaultArgumentStubGeneratorPhase then
            defaultParameterPatchOverridenSymbolsPhase then
            defaultParameterInjectorPhase then
            defaultParameterCleanerPhase then
            jsDefaultCallbackGeneratorPhase then
            removeInlineFunctionsWithReifiedTypeParametersLoweringPhase then
            throwableSuccessorsLoweringPhase then
            varargLoweringPhase then
            multipleCatchesLoweringPhase then
            bridgesConstructionPhase then
            typeOperatorLoweringPhase then
            secondaryConstructorLoweringPhase then
            secondaryFactoryInjectorLoweringPhase then
            classReferenceLoweringPhase then
            inlineClassLoweringPhase then
            autoboxingTransformerPhase then
            createIrFieldInitializerFunction then
            blockDecomposerLoweringPhase then
            primitiveCompanionLoweringPhase then
            constLoweringPhase then
            objectDeclarationLoweringPhase then
            objectUsageLoweringPhase then
            callsLoweringPhase then
            validateIrAfterLowering
)
