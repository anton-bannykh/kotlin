/*
 * Copyright 2010-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js

import org.jetbrains.kotlin.backend.common.ClassLoweringPass
import org.jetbrains.kotlin.backend.common.DeclarationContainerLoweringPass
import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.common.lower.*
import org.jetbrains.kotlin.backend.common.lower.inline.FunctionInlining
import org.jetbrains.kotlin.backend.common.lower.loops.ForLoopsLowering
import org.jetbrains.kotlin.backend.common.phaser.*
import org.jetbrains.kotlin.backend.common.runOnFilePostfix
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

private val moveBodilessDeclarationsToSeparatePlacePhase = makeJsModulePhase(
    ::MoveBodilessDeclarationsToSeparatePlaceLowering,
    name = "MoveBodilessDeclarationsToSeparatePlaceLowering",
    description = "Move bodiless declarations to a separate place"
)

private val expectDeclarationsRemovingPhase = makeJsModulePhase(
    ::ExpectDeclarationsRemoveLowering,
    name = "ExpectDeclarationsRemoving",
    description = "Remove expect declaration from module fragment"
)

private val lateinitNullableFieldsPhase = makeJsModulePhase(
    ::NullableFieldsForLateinitCreationLowering,
    name = "LateinitNullableFields",
    description = "Create nullable fields for lateinit properties"
)

private val lateinitDeclarationLoweringPhase = makeJsModulePhase(
    ::NullableFieldsDeclarationLowering,
    name = "LateinitDeclarations",
    description = "Reference nullable fields from properties and getters + insert checks"
)

private val lateinitUsageLoweringPhase = makeJsModulePhase(
    ::LateinitUsageLowering,
    name = "LateinitUsage",
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

private val copyInlineFunctionBodyLoweringPhase = makeJsModulePhase(
    ::CopyInlineFunctionBodyLowering,
    name = "CopyInlineFunctionBody",
    description = "Copy inline function body",
    prerequisite = setOf(functionInliningPhase)
)

private val removeInlineFunctionsWithReifiedTypeParametersLoweringPhase = makeJsModulePhase(
    { RemoveInlineFunctionsWithReifiedTypeParametersLowering() },
    name = "RemoveInlineFunctionsWithReifiedTypeParametersLowering",
    description = "Remove Inline functions with reified parameters from context",
    prerequisite = setOf(functionInliningPhase)
)

private val throwableSuccessorsLoweringPhase = makeJsModulePhase(
    ::ThrowableLowering,
    name = "ThrowableLowering",
    description = "Link kotlin.Throwable and JavaScript Error together to provide proper interop between language and platform exceptions"
)

private val tailrecLoweringPhase = makeJsModulePhase(
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

private val enumEntryInstancesBodyLoweringPhase = makeJsModulePhase(
    ::EnumEntryInstancesBodyLowering,
    name = "EnumEntryInstancesBodyLowering",
    description = "Insert enum entry field initialization into correxposnding class constructors",
    prerequisite = setOf(enumEntryInstancesLoweringPhase)
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

private val forLoopsLoweringPhase = makeJsModulePhase(
    ::ForLoopsLowering,
    name = "ForLoopsLowering",
    description = "For loops lowering"
)

private val localDelegatedPropertiesLoweringPhase = makeJsModulePhase(
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

private val localClassExtractionPhase = makeJsModulePhase(
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
    name = "InnerClassesMemberBody",
    description = "Replace `this` with 'outer this' field references",
    prerequisite = setOf(innerClassesLoweringPhase)
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

private val suspendLambdasRemovalLoweringPhase = makeJsModulePhase(
    { RemoveSuspendLambdas() },
    name = "RemoveSuspendLambdas",
    description = "Remove suspend lambdas"
)

private val privateMembersLoweringPhase = makeJsModulePhase(
    ::PrivateMembersLowering,
    name = "PrivateMembersLowering",
    description = "Extract private members from classes"
)

private val privateMemberUsagesLoweringPhase = makeJsModulePhase(
    ::PrivateMemberBodiesLowering,
    name = "PrivateMemberUsagesLowering",
    description = "Rewrite the private member usages"
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

private val defaultArgumentPatchOverridesPhase = makeJsModulePhase(
    ::DefaultParameterPatchOverridenSymbolsLowering,
    name = "DefaultArgumentsPatchOverrides",
    description = "Patch overrides for fake override dispatch functions",
    prerequisite = setOf(defaultArgumentStubGeneratorPhase)
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

private val varargLoweringPhase = makeJsModulePhase(
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

private val primaryConstructorLoweringPhase = makeJsModulePhase(
    ::PrimaryConstructorLowering,
    name = "PrimaryConstructorLowering",
    description = "Creates primary constructor if it doesn't exist",
    prerequisite = setOf(enumClassConstructorLoweringPhase)
)

private val delegateToPrimaryConstructorLoweringPhase = makeJsModulePhase(
    { DelegateToSyntheticPrimaryConstructor() },
    name = "DelegateToSyntheticPrimaryConstructor",
    description = "Delegates to synthetic primary constructor",
    prerequisite = setOf(primaryConstructorLoweringPhase)
)

private val annotationConstructorLowering = makeJsModulePhase(
    ::AnnotationConstructorLowering,
    name = "AnnotationConstructorLowering",
    description = "Generate annotation constructor body"
)

private val initializersLoweringPhase = makeJsModulePhase(
    ::InitializersLowering,
    name = "InitializersLowering",
    description = "Merge init block and field initializers into [primary] constructor",
    prerequisite = setOf(enumClassConstructorLoweringPhase, primaryConstructorLoweringPhase, annotationConstructorLowering)
)

private val initializersCleanupLoweringPhase = makeJsModulePhase(
    ::InitializersCleanupLowering,
    name = "InitializersCleanupLowering",
    description = "Remove non-static anonymous initializers and field init expressions",
    prerequisite = setOf(initializersLoweringPhase)
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

private val inlineClassDeclarationLoweringPhase = makeJsModulePhase(
    { InlineClassLowering(it).inlineClassDeclarationLowering },
    name = "InlineClassDeclarationLowering",
    description = "Handle inline class declarations"
)

private val inlineClassUsageLoweringPhase = makeJsModulePhase(
    { InlineClassLowering(it).inlineClassUsageLowering },
    name = "InlineClassUsageLowering",
    description = "Handle inline class usages"
)

private val autoboxingTransformerPhase = makeJsModulePhase(
    ::AutoboxingTransformer,
    name = "AutoboxingTransformer",
    description = "Insert box/unbox intrinsics"
)

private val createFieldInitializerFunction = makeJsModulePhase(
    ::CreateIrFieldInitializerFunction,
    name = "CreateIrFieldInitializerFunction",
    description = "Create initializer functions for fields"
)

private val blockDecomposerLoweringPhase = makeJsModulePhase(
    ::JsBlockDecomposerLowering,
    name = "BlockDecomposerLowering",
    description = "Transform statement-like-expression nodes into pure-statement to make it easily transform into JS",
    prerequisite = setOf(typeOperatorLoweringPhase, suspendFunctionsLoweringPhase, createFieldInitializerFunction)
)

private val classReferenceLoweringPhase = makeJsModulePhase(
    ::ClassReferenceLowering,
    name = "ClassReferenceLowering",
    description = "Handle class references"
)

private val primitiveCompanionLoweringPhase = makeJsModulePhase(
    ::PrimitiveCompanionLowering,
    name = "PrimitiveCompanionLowering",
    description = "Replace common companion object access with platform one"
)

private val constLoweringPhase = makeJsModulePhase(
    ::ConstLowering,
    name = "ConstLowering",
    description = "Wrap Long and Char constants into constructor invocation"
)

private val callsLoweringPhase = makeJsModulePhase(
    ::CallsLowering,
    name = "CallsLowering",
    description = "Handle intrinsics"
)

private val testGenerationPhase = makeJsModulePhase(
    ::TestGenerator, // TODO
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
    moveBodilessDeclarationsToSeparatePlacePhase,
    scriptRemoveReceiverLowering,
    validateIrBeforeLowering,
    testGenerationPhase,
    expectDeclarationsRemovingPhase,
    stripTypeAliasDeclarationsPhase,
    arrayConstructorPhase,
    functionInliningPhase,
    copyInlineFunctionBodyLoweringPhase,
    createScriptFunctionsPhase,
    provisionalFunctionExpressionPhase,
    lateinitNullableFieldsPhase,
    lateinitDeclarationLoweringPhase,
    lateinitUsageLoweringPhase,
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
    primaryConstructorLoweringPhase,
    delegateToPrimaryConstructorLoweringPhase,
    annotationConstructorLowering,
    initializersLoweringPhase,
    initializersCleanupLoweringPhase,
    // Common prefix ends
    enumEntryInstancesLoweringPhase,
    enumEntryInstancesBodyLoweringPhase,
    enumClassCreateInitializerLoweringPhase,
    enumEntryCreateGetInstancesFunsLoweringPhase,
    enumSyntheticFunsLoweringPhase,
    enumUsageLoweringPhase,
    enumEntryRemovalLoweringPhase,
    suspendFunctionsLoweringPhase,
    suspendLambdasRemovalLoweringPhase,
    returnableBlockLoweringPhase,
    forLoopsLoweringPhase,
    privateMembersLoweringPhase,
    privateMemberUsagesLoweringPhase,
    callableReferenceLoweringPhase,
    defaultArgumentStubGeneratorPhase,
    defaultArgumentPatchOverridesPhase,
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
    inlineClassDeclarationLoweringPhase,
    inlineClassUsageLoweringPhase,
    autoboxingTransformerPhase,
    createFieldInitializerFunction,
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
    lower = phaseList.drop(1).fold(phaseList[0]) { acc: CompilerPhase<JsIrBackendContext, IrModuleFragment, IrModuleFragment>, phase -> acc.then(phase) }
)