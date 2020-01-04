/*
 * Copyright 2010-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js

import org.jetbrains.kotlin.backend.common.*
import org.jetbrains.kotlin.backend.common.lower.*
import org.jetbrains.kotlin.backend.common.lower.inline.FunctionInlining
import org.jetbrains.kotlin.backend.common.lower.loops.ForLoopsLowering
import org.jetbrains.kotlin.backend.common.phaser.*
import org.jetbrains.kotlin.ir.backend.js.lower.*
import org.jetbrains.kotlin.ir.backend.js.lower.calls.CallsLowering
import org.jetbrains.kotlin.ir.backend.js.lower.coroutines.JsSuspendFunctionsLowering
import org.jetbrains.kotlin.ir.backend.js.lower.inline.CopyInlineFunctionBodyLowering
import org.jetbrains.kotlin.ir.backend.js.lower.inline.RemoveInlineFunctionsWithReifiedTypeParametersLowering
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrBodyBase
import org.jetbrains.kotlin.ir.declarations.impl.IrDeclarationBase
import org.jetbrains.kotlin.ir.declarations.impl.IrFileImpl
import org.jetbrains.kotlin.ir.util.fileOrNull

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

sealed class Lowering(val name: String) {

    abstract val modulePhase: SameTypeNamedPhaseWrapper<JsIrBackendContext, IrModuleFragment>
}

class DeclarationLowering(
    name: String,
    description: String,
    prerequisite: Set<AnyNamedPhase> = emptySet(),
    private val factory: (JsIrBackendContext) -> DeclarationTransformer
) : Lowering(name) {
    fun declarationTransformer(context: JsIrBackendContext): DeclarationTransformer {
        return factory(context)
    }

    override val modulePhase = makeJsModulePhase(factory, name, description, prerequisite)
}

class BodyLowering(
    name: String,
    description: String,
    prerequisite: Set<AnyNamedPhase> = emptySet(),
    private val factory: (JsIrBackendContext) -> BodyLoweringPass
) : Lowering(name) {
    fun bodyLowering(context: JsIrBackendContext): BodyLoweringPass {
        return factory(context)
    }

    override val modulePhase = makeJsModulePhase(factory, name, description, prerequisite)
}

class ModuleLowering(
    name: String,
    description: String,
    prerequisite: Set<AnyNamedPhase> = emptySet(),
    op: (JsIrBackendContext, IrModuleFragment) -> Unit
) : Lowering(name) {

    override val modulePhase = makeCustomJsModulePhase(op, description, name, prerequisite)
}

private fun makeDeclarationTransformerPhase(
    lowering: (JsIrBackendContext) -> DeclarationTransformer,
    name: String,
    description: String,
    prerequisite: Set<Lowering> = emptySet()
) = DeclarationLowering(name, description, prerequisite.map { it.modulePhase }.toSet(), lowering)

private fun makeBodyLoweringPhase(
    lowering: (JsIrBackendContext) -> BodyLoweringPass,
    name: String,
    description: String,
    prerequisite: Set<Lowering> = emptySet()
) = BodyLowering(name, description, prerequisite.map { it.modulePhase }.toSet(), lowering)

private fun makeModuleLoweringPhase(
    op: (JsIrBackendContext, IrModuleFragment) -> Unit,
    description: String,
    name: String,
    prerequisite: Set<Lowering> = emptySet()
) = ModuleLowering(name, description, prerequisite.map { it.modulePhase }.toSet(), op)

private val validateIrBeforeLowering = makeModuleLoweringPhase(
    { context, module -> validationCallback(context, module) },
    name = "ValidateIrBeforeLowering",
    description = "Validate IR before lowering"
)

private val validateIrAfterLowering = makeModuleLoweringPhase(
    { context, module -> validationCallback(context, module) },
    name = "ValidateIrAfterLowering",
    description = "Validate IR after lowering"
)

private val moveBodilessDeclarationsToSeparatePlacePhase = makeDeclarationTransformerPhase(
    ::MoveBodilessDeclarationsToSeparatePlaceLowering,
    name = "MoveBodilessDeclarationsToSeparatePlaceLowering",
    description = "Move bodiless declarations to a separate place"
)

private val expectDeclarationsRemovingPhase = makeDeclarationTransformerPhase(
    ::ExpectDeclarationsRemoveLowering,
    name = "ExpectDeclarationsRemoving",
    description = "Remove expect declaration from module fragment"
)

private val lateinitNullableFieldsPhase = makeDeclarationTransformerPhase(
    ::NullableFieldsForLateinitCreationLowering,
    name = "LateinitNullableFields",
    description = "Create nullable fields for lateinit properties"
)

private val lateinitDeclarationLoweringPhase = makeDeclarationTransformerPhase(
    ::NullableFieldsDeclarationLowering,
    name = "LateinitDeclarations",
    description = "Reference nullable fields from properties and getters + insert checks"
)

private val lateinitUsageLoweringPhase = makeBodyLoweringPhase(
    ::LateinitUsageLowering,
    name = "LateinitUsage",
    description = "Insert checks for lateinit field references"
)

private val stripTypeAliasDeclarationsPhase = makeDeclarationTransformerPhase(
    { StripTypeAliasDeclarationsLowering() },
    name = "StripTypeAliasDeclarations",
    description = "Strip typealias declarations"
)

// TODO make all lambda-related stuff work with IrFunctionExpression and drop this phase
private val provisionalFunctionExpressionPhase = makeBodyLoweringPhase(
    { ProvisionalFunctionExpressionLowering() },
    name = "FunctionExpression",
    description = "Transform IrFunctionExpression to a local function reference"
)

private val arrayConstructorPhase = makeBodyLoweringPhase(
    ::ArrayConstructorLowering,
    name = "ArrayConstructor",
    description = "Transform `Array(size) { index -> value }` into a loop"
)

private val functionInliningPhase = makeBodyLoweringPhase(
    ::FunctionInlining,
    name = "FunctionInliningPhase",
    description = "Perform function inlining",
    prerequisite = setOf(expectDeclarationsRemovingPhase)
)

private val copyInlineFunctionBodyLoweringPhase = makeDeclarationTransformerPhase(
    ::CopyInlineFunctionBodyLowering,
    name = "CopyInlineFunctionBody",
    description = "Copy inline function body",
    prerequisite = setOf(functionInliningPhase)
)

private val removeInlineFunctionsWithReifiedTypeParametersLoweringPhase = makeDeclarationTransformerPhase(
    { RemoveInlineFunctionsWithReifiedTypeParametersLowering() },
    name = "RemoveInlineFunctionsWithReifiedTypeParametersLowering",
    description = "Remove Inline functions with reified parameters from context",
    prerequisite = setOf(functionInliningPhase)
)

private val throwableSuccessorsLoweringPhase = makeBodyLoweringPhase(
    ::ThrowableLowering,
    name = "ThrowableLowering",
    description = "Link kotlin.Throwable and JavaScript Error together to provide proper interop between language and platform exceptions"
)

private val tailrecLoweringPhase = makeBodyLoweringPhase(
    ::TailrecLowering,
    name = "TailrecLowering",
    description = "Replace `tailrec` callsites with equivalent loop"
)

private val enumClassConstructorLoweringPhase = makeDeclarationTransformerPhase(
    ::EnumClassConstructorLowering,
    name = "EnumClassConstructorLowering",
    description = "Transform Enum Class into regular Class"
)

private val enumClassConstructorBodyLoweringPhase = makeBodyLoweringPhase(
    ::EnumClassConstructorBodyTransformer,
    name = "EnumClassConstructorBodyLowering",
    description = "Transform Enum Class into regular Class"
)


private val enumEntryInstancesLoweringPhase = makeDeclarationTransformerPhase(
    ::EnumEntryInstancesLowering,
    name = "EnumEntryInstancesLowering",
    description = "Create instance variable for each enum entry initialized with `null`",
    prerequisite = setOf(enumClassConstructorLoweringPhase)
)

private val enumEntryInstancesBodyLoweringPhase = makeBodyLoweringPhase(
    ::EnumEntryInstancesBodyLowering,
    name = "EnumEntryInstancesBodyLowering",
    description = "Insert enum entry field initialization into correxposnding class constructors",
    prerequisite = setOf(enumEntryInstancesLoweringPhase)
)


private val enumClassCreateInitializerLoweringPhase = makeDeclarationTransformerPhase(
    ::EnumClassCreateInitializerLowering,
    name = "EnumClassCreateInitializerLowering",
    description = "Create initializer for enum entries",
    prerequisite = setOf(enumClassConstructorLoweringPhase)
)

private val enumEntryCreateGetInstancesFunsLoweringPhase = makeDeclarationTransformerPhase(
    ::EnumEntryCreateGetInstancesFunsLowering,
    name = "EnumEntryCreateGetInstancesFunsLowering",
    description = "Create enumEntry_getInstance functions",
    prerequisite = setOf(enumClassConstructorLoweringPhase)
)

private val enumSyntheticFunsLoweringPhase = makeDeclarationTransformerPhase(
    ::EnumSyntheticFunctionsLowering,
    name = "EnumSyntheticFunctionsLowering",
    description = "Implement `valueOf` and `values`",
    prerequisite = setOf(enumClassConstructorLoweringPhase)
)

private val enumUsageLoweringPhase = makeBodyLoweringPhase(
    ::EnumUsageLowering,
    name = "EnumUsageLowering",
    description = "Replace enum access with invocation of corresponding function",
    prerequisite = setOf(enumEntryCreateGetInstancesFunsLoweringPhase)
)

private val enumEntryRemovalLoweringPhase = makeDeclarationTransformerPhase(
    ::EnumClassRemoveEntriesLowering,
    name = "EnumEntryRemovalLowering",
    description = "Replace enum entry with corresponding class",
    prerequisite = setOf(enumUsageLoweringPhase)
)

private val sharedVariablesLoweringPhase = makeBodyLoweringPhase(
    ::SharedVariablesLowering,
    name = "SharedVariablesLowering",
    description = "Box captured mutable variables"
)

private val returnableBlockLoweringPhase = makeBodyLoweringPhase(
    ::ReturnableBlockLowering,
    name = "ReturnableBlockLowering",
    description = "Replace returnable block with do-while loop",
    prerequisite = setOf(functionInliningPhase)
)

private val forLoopsLoweringPhase = makeBodyLoweringPhase(
    ::ForLoopsLowering,
    name = "ForLoopsLowering",
    description = "For loops lowering"
)

private val localDelegatedPropertiesLoweringPhase = makeBodyLoweringPhase(
    { LocalDelegatedPropertiesLowering() },
    name = "LocalDelegatedPropertiesLowering",
    description = "Transform Local Delegated properties"
)

private val localDeclarationsLoweringPhase = makeBodyLoweringPhase(
    ::LocalDeclarationsLowering,
    name = "LocalDeclarationsLowering",
    description = "Move local declarations into nearest declaration container",
    prerequisite = setOf(sharedVariablesLoweringPhase, localDelegatedPropertiesLoweringPhase)
)

private val localClassExtractionPhase = makeBodyLoweringPhase(
    ::LocalClassPopupLowering,
    name = "LocalClassExtractionPhase",
    description = "Move local declarations into nearest declaration container",
    prerequisite = setOf(localDeclarationsLoweringPhase)
)

private val innerClassesLoweringPhase = makeDeclarationTransformerPhase(
    ::InnerClassesLowering,
    name = "InnerClassesLowering",
    description = "Capture outer this reference to inner class"
)

private val innerClassesMemberBodyLoweringPhase = makeBodyLoweringPhase(
    ::InnerClassesMemberBodyLowering,
    name = "InnerClassesMemberBody",
    description = "Replace `this` with 'outer this' field references",
    prerequisite = setOf(innerClassesLoweringPhase)
)

private val innerClassConstructorCallsLoweringPhase = makeBodyLoweringPhase(
    ::InnerClassConstructorCallsLowering,
    name = "InnerClassConstructorCallsLowering",
    description = "Replace inner class constructor invocation"
)

private val suspendFunctionsLoweringPhase = makeBodyLoweringPhase(
    ::JsSuspendFunctionsLowering,
    name = "SuspendFunctionsLowering",
    description = "Transform suspend functions into CoroutineImpl instance and build state machine"
)

private val suspendLambdasRemovalLoweringPhase = makeDeclarationTransformerPhase(
    { RemoveSuspendLambdas() },
    name = "RemoveSuspendLambdas",
    description = "Remove suspend lambdas"
)

private val privateMembersLoweringPhase = makeDeclarationTransformerPhase(
    ::PrivateMembersLowering,
    name = "PrivateMembersLowering",
    description = "Extract private members from classes"
)

private val privateMemberUsagesLoweringPhase = makeBodyLoweringPhase(
    ::PrivateMemberBodiesLowering,
    name = "PrivateMemberUsagesLowering",
    description = "Rewrite the private member usages"
)

private val callableReferenceLoweringPhase = makeBodyLoweringPhase(
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

private val defaultArgumentStubGeneratorPhase = makeDeclarationTransformerPhase(
    ::JsDefaultArgumentStubGenerator,
    name = "DefaultArgumentStubGenerator",
    description = "Generate synthetic stubs for functions with default parameter values"
)

private val defaultArgumentPatchOverridesPhase = makeDeclarationTransformerPhase(
    ::DefaultParameterPatchOverridenSymbolsLowering,
    name = "DefaultArgumentsPatchOverrides",
    description = "Patch overrides for fake override dispatch functions",
    prerequisite = setOf(defaultArgumentStubGeneratorPhase)
)

private val defaultParameterInjectorPhase = makeBodyLoweringPhase(
    { context -> DefaultParameterInjector(context, skipExternalMethods = true) },
    name = "DefaultParameterInjector",
    description = "Replace callsite with default parameters with corresponding stub function",
    prerequisite = setOf(callableReferenceLoweringPhase, innerClassesLoweringPhase)
)

private val defaultParameterCleanerPhase = makeDeclarationTransformerPhase(
    ::DefaultParameterCleaner,
    name = "DefaultParameterCleaner",
    description = "Clean default parameters up"
)

private val jsDefaultCallbackGeneratorPhase = makeBodyLoweringPhase(
    ::JsDefaultCallbackGenerator,
    name = "JsDefaultCallbackGenerator",
    description = "Build binding for super calls with default parameters"
)

private val varargLoweringPhase = makeBodyLoweringPhase(
    ::VarargLowering,
    name = "VarargLowering",
    description = "Lower vararg arguments",
    prerequisite = setOf(callableReferenceLoweringPhase)
)

private val propertiesLoweringPhase = makeDeclarationTransformerPhase(
    { context -> PropertiesLowering(context, skipExternalProperties = true, generateAnnotationFields = true) },
    name = "PropertiesLowering",
    description = "Move fields and accessors out from its property"
)

private val primaryConstructorLoweringPhase = makeDeclarationTransformerPhase(
    ::PrimaryConstructorLowering,
    name = "PrimaryConstructorLowering",
    description = "Creates primary constructor if it doesn't exist",
    prerequisite = setOf(enumClassConstructorLoweringPhase)
)

private val delegateToPrimaryConstructorLoweringPhase = makeBodyLoweringPhase(
    { DelegateToSyntheticPrimaryConstructor() },
    name = "DelegateToSyntheticPrimaryConstructor",
    description = "Delegates to synthetic primary constructor",
    prerequisite = setOf(primaryConstructorLoweringPhase)
)

private val annotationConstructorLowering = makeDeclarationTransformerPhase(
    ::AnnotationConstructorLowering,
    name = "AnnotationConstructorLowering",
    description = "Generate annotation constructor body"
)

private val initializersLoweringPhase = makeBodyLoweringPhase(
    ::InitializersLowering,
    name = "InitializersLowering",
    description = "Merge init block and field initializers into [primary] constructor",
    prerequisite = setOf(enumClassConstructorLoweringPhase, primaryConstructorLoweringPhase, annotationConstructorLowering)
)

private val initializersCleanupLoweringPhase = makeDeclarationTransformerPhase(
    ::InitializersCleanupLowering,
    name = "InitializersCleanupLowering",
    description = "Remove non-static anonymous initializers and field init expressions",
    prerequisite = setOf(initializersLoweringPhase)
)

private val multipleCatchesLoweringPhase = makeBodyLoweringPhase(
    ::MultipleCatchesLowering,
    name = "MultipleCatchesLowering",
    description = "Replace multiple catches with single one"
)

private val bridgesConstructionPhase = makeDeclarationTransformerPhase(
    ::BridgesConstruction,
    name = "BridgesConstruction",
    description = "Generate bridges",
    prerequisite = setOf(suspendFunctionsLoweringPhase)
)

private val typeOperatorLoweringPhase = makeBodyLoweringPhase(
    ::TypeOperatorLowering,
    name = "TypeOperatorLowering",
    description = "Lower IrTypeOperator with corresponding logic",
    prerequisite = setOf(bridgesConstructionPhase, removeInlineFunctionsWithReifiedTypeParametersLoweringPhase)
)

private val secondaryConstructorLoweringPhase = makeDeclarationTransformerPhase(
    ::SecondaryConstructorLowering,
    name = "SecondaryConstructorLoweringPhase",
    description = "Generate static functions for each secondary constructor",
    prerequisite = setOf(innerClassesLoweringPhase)
)

private val secondaryFactoryInjectorLoweringPhase = makeBodyLoweringPhase(
    ::SecondaryFactoryInjectorLowering,
    name = "SecondaryFactoryInjectorLoweringPhase",
    description = "Replace usage of secondary constructor with corresponding static function",
    prerequisite = setOf(innerClassesLoweringPhase)
)

private val inlineClassDeclarationLoweringPhase = makeDeclarationTransformerPhase(
    { InlineClassLowering(it).inlineClassDeclarationLowering },
    name = "InlineClassDeclarationLowering",
    description = "Handle inline class declarations"
)

private val inlineClassUsageLoweringPhase = makeBodyLoweringPhase(
    { InlineClassLowering(it).inlineClassUsageLowering },
    name = "InlineClassUsageLowering",
    description = "Handle inline class usages"
)

private val autoboxingTransformerPhase = makeBodyLoweringPhase(
    ::AutoboxingTransformer,
    name = "AutoboxingTransformer",
    description = "Insert box/unbox intrinsics"
)

private val createFieldInitializerFunction = makeDeclarationTransformerPhase(
    ::CreateIrFieldInitializerFunction,
    name = "CreateIrFieldInitializerFunction",
    description = "Create initializer functions for fields"
)

private val blockDecomposerLoweringPhase = makeBodyLoweringPhase(
    ::JsBlockDecomposerLowering,
    name = "BlockDecomposerLowering",
    description = "Transform statement-like-expression nodes into pure-statement to make it easily transform into JS",
    prerequisite = setOf(typeOperatorLoweringPhase, suspendFunctionsLoweringPhase, createFieldInitializerFunction)
)

private val classReferenceLoweringPhase = makeBodyLoweringPhase(
    ::ClassReferenceLowering,
    name = "ClassReferenceLowering",
    description = "Handle class references"
)

private val primitiveCompanionLoweringPhase = makeBodyLoweringPhase(
    ::PrimitiveCompanionLowering,
    name = "PrimitiveCompanionLowering",
    description = "Replace common companion object access with platform one"
)

private val constLoweringPhase = makeBodyLoweringPhase(
    ::ConstLowering,
    name = "ConstLowering",
    description = "Wrap Long and Char constants into constructor invocation"
)

private val callsLoweringPhase = makeBodyLoweringPhase(
    ::CallsLowering,
    name = "CallsLowering",
    description = "Handle intrinsics"
)

//private val testGenerationPhase = makeJsModulePhase(
//    ::TestGenerator, // TODO
//    name = "TestGenerationLowering",
//    description = "Generate invocations to kotlin.test suite and test functions"
//)

private val staticMembersLoweringPhase = makeDeclarationTransformerPhase(
    ::StaticMembersLowering,
    name = "StaticMembersLowering",
    description = "Move static member declarations to top-level"
)

private val objectDeclarationLoweringPhase = makeDeclarationTransformerPhase(
    ::ObjectDeclarationLowering,
    name = "ObjectDeclarationLowering",
    description = "Create lazy object instance generator functions"
)

private val objectUsageLoweringPhase = makeBodyLoweringPhase(
    ::ObjectUsageLowering,
    name = "ObjectUsageLowering",
    description = "Transform IrGetObjectValue into instance generator call"
)

val loweringList = listOf<Lowering>(
//    moveBodilessDeclarationsToSeparatePlacePhase,
//    scriptRemoveReceiverLowering, // TODO
    validateIrBeforeLowering,
//    testGenerationPhase, // TODO
    expectDeclarationsRemovingPhase,
    stripTypeAliasDeclarationsPhase,
    arrayConstructorPhase,
    functionInliningPhase,
    copyInlineFunctionBodyLoweringPhase,
//    createScriptFunctionsPhase, // TODO
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
    staticMembersLoweringPhase,
    validateIrAfterLowering
)

val jsPhases = namedIrModulePhase(
    name = "IrModuleLowering",
    description = "IR module lowering",
    lower = loweringList.drop(1).fold(loweringList[0].modulePhase) { acc: CompilerPhase<JsIrBackendContext, IrModuleFragment, IrModuleFragment>, lowering ->
        acc.then(lowering.modulePhase)
    }
)

class MutableController(val context: JsIrBackendContext): NoopController() {

    override fun lazyLower(declaration: IrDeclaration) {
        if (declaration is IrDeclarationBase<*> && currentStage - 1 > declaration.loweredUpTo) {
            val stageNonInclusive = currentStage

            while (declaration.loweredUpTo + 1 < stageNonInclusive) {
                val i = declaration.loweredUpTo + 1
                val parentBefore = withStage(i) { declaration.parent }
//                if (parentBefore !is IrDeclarationContainer) {
//                    break
//                }

                withStage(i) {
                    val fileBefore = declaration.fileOrNull as? IrFileImpl
                    // TODO a better way to skip declarations in external package fragments
                    if (declaration.removedOn > i && fileBefore != null && fileBefore.symbol !in context.externalPackageFragmentSymbols) {
                        val lowering = loweringList[i - 1]
                        if (lowering is DeclarationLowering) {

                            val result = restrictTo(declaration) { lowering.declarationTransformer(context).transformFlat(declaration) }
                            if (result != null) {
                                result.forEach {
                                    it.loweredUpTo = i
                                    it.parent = parentBefore
                                }

                                if (parentBefore is IrDeclarationContainer) {
                                    stageController.unrestrictDeclarationListsAccess {

                                        var index = -1
                                        parentBefore.declarations.forEachIndexed { i, v ->
                                            if (index == -1 && v == declaration) {
                                                index = i
                                            }
                                        }

                                        if (index != -1) {
                                            parentBefore.declarations.removeAt(index)
                                            parentBefore.declarations.addAll(index, result)
                                        } else {
                                            parentBefore.declarations.addAll(result)
                                        }

                                        if (declaration.parent == parentBefore && declaration !in result) {
                                            declaration.removedOn = currentStage
                                        }
                                    }
                                }
                            }
                        }
                    }
                    declaration.loweredUpTo = i
                }
            }
        }
    }


    override fun lowerBody(body: IrBodyBase<*>) {
        if (body.loweredUpTo + 1 < currentStage) {
            for (i in (body.loweredUpTo + 1) until currentStage) {
                withStage(i) {
                    val declaration = body.container
                    val fileBefore = declaration.fileOrNull as? IrFileImpl
                    if (fileBefore != null) {
                        val lowering = loweringList[i - 1]

                        if (lowering is BodyLowering) {
                            stageController.bodyLowering {
                                lowering.bodyLowering(context).lower(body, declaration)
                            }
                        }
                    }
                    body.loweredUpTo = i
                }
            }
        }
    }
}

private var IrDeclaration.loweredUpTo: Int
    get() = (this as? IrDeclarationBase<*>)?.loweredUpTo ?: 0
    set(v) {
        (this as? IrDeclarationBase<*>)?.loweredUpTo = v
    }