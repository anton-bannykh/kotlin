/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js

import org.jetbrains.kotlin.backend.common.CommonBackendContext
import org.jetbrains.kotlin.backend.common.atMostOne
import org.jetbrains.kotlin.backend.common.ir.Ir
import org.jetbrains.kotlin.backend.common.ir.Symbols
import org.jetbrains.kotlin.backend.common.ir.addChild
import org.jetbrains.kotlin.builtins.PrimitiveType
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.incremental.components.NoLookupLocation
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.backend.js.utils.OperatorNames
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.descriptors.IrBuiltIns
import org.jetbrains.kotlin.ir.symbols.*
import org.jetbrains.kotlin.ir.types.IrDynamicType
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.classifierOrFail
import org.jetbrains.kotlin.ir.types.impl.IrDynamicTypeImpl
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.scopes.MemberScope
import org.jetbrains.kotlin.types.Variance

class JsIrBackendContext(
    val module: ModuleDescriptor,
    override val irBuiltIns: IrBuiltIns,
    val symbolTable: SymbolTable,
    val irModuleFragment: IrModuleFragment,
    override val configuration: CompilerConfiguration,
    val stageController: MutableController
) : CommonBackendContext {

    init {
        stageController.context = this
    }

    override val builtIns = module.builtIns

    override var inVerbosePhase: Boolean = false

    private val data = ContextData(irModuleFragment)

    fun contextData(module: IrModuleFragment): ContextData {
        return data
    }

    fun contextData(declaration: IrDeclaration): ContextData {
        declaration.module
        return data
    }

    private val IrDeclaration.contextData
        get() = this.module.let { data }

    val externalPackageFragments
        get() = listOf(data.externalPackageFragment)

    val externalPackageFragmentsForIntrinsics get() = listOf(data.externalPackageFragmentForIntrinsics)

    val bodilessBuiltInsPackageFragments: List<IrPackageFragment>
        get() = listOf(data.bodilessBuiltInsPackageFragment)

    val packageLevelJsModules get() = ArrayList(data.packageLevelJsModules.values)

    val declarationLevelJsModules get() = data.declarationLevelJsModules as List<IrDeclarationWithName>

    val implicitDeclarationFile get() = data.implicitDeclarationFile

    val hasTests get() = data.hasTests

    val testContainers get() = listOf(data.testContainer)

    override val sharedVariablesManager get() = data.sharedVariablesManager

    override val declarationFactory get() = data.declarationFactory

    companion object {
        val KOTLIN_PACKAGE_FQN = FqName.fromSegments(listOf("kotlin"))

        private val INTRINSICS_PACKAGE_NAME = Name.identifier("intrinsics")
        private val COROUTINE_SUSPENDED_NAME = Name.identifier("COROUTINE_SUSPENDED")
        private val COROUTINE_CONTEXT_NAME = Name.identifier("coroutineContext")
        private val COROUTINE_IMPL_NAME = Name.identifier("CoroutineImpl")
        private val CONTINUATION_NAME = Name.identifier("Continuation")
        // TODO: what is more clear way reference this getter?
        private val CONTINUATION_CONTEXT_GETTER_NAME = Name.special("<get-context>")

        private val CONTINUATION_CONTEXT_PROPERTY_NAME = Name.identifier("context")
        private val REFLECT_PACKAGE_FQNAME = KOTLIN_PACKAGE_FQN.child(Name.identifier("reflect"))
        private val JS_PACKAGE_FQNAME = KOTLIN_PACKAGE_FQN.child(Name.identifier("js"))
        private val JS_INTERNAL_PACKAGE_FQNAME = JS_PACKAGE_FQNAME.child(Name.identifier("internal"))
        private val COROUTINE_PACKAGE_FQNAME_12 = FqName.fromSegments(listOf("kotlin", "coroutines", "experimental"))
        private val COROUTINE_PACKAGE_FQNAME_13 = FqName.fromSegments(listOf("kotlin", "coroutines"))
        private val COROUTINE_PACKAGE_FQNAME = COROUTINE_PACKAGE_FQNAME_13
        private val COROUTINE_INTRINSICS_PACKAGE_FQNAME = COROUTINE_PACKAGE_FQNAME.child(INTRINSICS_PACKAGE_NAME)

        // TODO: due to name clash those weird suffix is required, remove it once `MemberNameGenerator` is implemented
        private val COROUTINE_SUSPEND_OR_RETURN_JS_NAME = "suspendCoroutineUninterceptedOrReturnJS"
        private val GET_COROUTINE_CONTEXT_NAME = "getCoroutineContext"

        val callableClosureOrigin = object : IrDeclarationOriginImpl("CALLABLE_CLOSURE_DECLARATION") {}
    }

    private val internalPackage = module.getPackage(JS_PACKAGE_FQNAME)

    private val coroutinePackage = module.getPackage(COROUTINE_PACKAGE_FQNAME)
    private val coroutineIntrinsicsPackage = module.getPackage(COROUTINE_INTRINSICS_PACKAGE_FQNAME)

    val enumEntryToGetInstanceFunction get() = data.enumEntryToGetInstanceFunction
    val enumEntryExternalToInstanceField get() = data.enumEntryExternalToInstanceField
    val callableReferencesCache get() = data.callableReferencesCache
    val secondaryConstructorToFactoryCache get() = data.secondaryConstructorToFactoryCache
    val inlineClassTransformedFunctionsCache get() = data.inlineClassTransformedFunctionsCache
    val pendingThrowableSuperUsages get() = data.pendingThrowableSuperUsages


    val bridgeToBridgeInfoMapping get() = data.bridgeToBridgeInfoMapping

    val intrinsics = JsIntrinsics(irBuiltIns, this)

    override val internalPackageFqn = JS_PACKAGE_FQNAME

    private val operatorMap = referenceOperators()

    private fun primitivesWithImplicitCompanionObject(): List<Name> {
        val numbers = PrimitiveType.NUMBER_TYPES
            .filter { it.name != "LONG" && it.name != "CHAR" } // skip due to they have own explicit companions
            .map { it.typeName }

        return numbers + listOf(Name.identifier("String"))
    }

    val dynamicType: IrDynamicType = IrDynamicTypeImpl(null, emptyList(), Variance.INVARIANT)

    fun getOperatorByName(name: Name, type: IrSimpleType) = operatorMap[name]?.get(type.classifier)

    var coroutinesLoaded: Boolean = false
        private set

    override val ir = object : Ir<JsIrBackendContext>(this, irModuleFragment) {
        override val symbols = object : Symbols<JsIrBackendContext>(this@JsIrBackendContext, symbolTable.lazyWrapper) {
            override val ThrowNullPointerException =
                symbolTable.referenceSimpleFunction(getFunctions(kotlinPackageFqn.child(Name.identifier("THROW_NPE"))).single())

            override val ThrowNoWhenBranchMatchedException =
                symbolTable.referenceSimpleFunction(getFunctions(kotlinPackageFqn.child(Name.identifier("noWhenBranchMatchedException"))).single())

            override val ThrowTypeCastException =
                symbolTable.referenceSimpleFunction(getFunctions(kotlinPackageFqn.child(Name.identifier("THROW_CCE"))).single())

            override val ThrowUninitializedPropertyAccessException =
                symbolTable.referenceSimpleFunction(getFunctions(FqName("kotlin.throwUninitializedPropertyAccessException")).single())

            override val defaultConstructorMarker =
                symbolTable.referenceClass(context.getJsInternalClass("DefaultConstructorMarker"))

            override val stringBuilder
                get() = TODO("not implemented")
            override val copyRangeTo: Map<ClassDescriptor, IrSimpleFunctionSymbol>
                get() = TODO("not implemented")
            override val coroutineImpl by lazy {
                coroutinesLoaded = true
                symbolTable.referenceClass(findClass(coroutinePackage.memberScope, COROUTINE_IMPL_NAME))
            }
            override val coroutineSuspendedGetter by lazy {
                symbolTable.referenceSimpleFunction(
                    coroutineIntrinsicsPackage.memberScope.getContributedVariables(
                        COROUTINE_SUSPENDED_NAME,
                        NoLookupLocation.FROM_BACKEND
                    ).filterNot { it.isExpect }.single().getter!!
                )
            }

            override val getContinuation by lazy { symbolTable.referenceSimpleFunction(getJsInternalFunction("getContinuation")) }
        }

        override val defaultParameterDeclarationsCache: MutableMap<IrFunction, IrFunction>
            get() = data.defaultParameterDeclarationsCache

        override fun shouldGenerateHandlerParameterForDefaultBodyFun() = true
    }

    // classes forced to be loaded

    val primitiveClassesObject by lazy { getIrClass(FqName("kotlin.reflect.js.internal.PrimitiveClasses")) }

    val throwableClass by lazy { getIrClass(JsIrBackendContext.KOTLIN_PACKAGE_FQN.child(Name.identifier("Throwable"))) }

    val primitiveCompanionObjects by lazy {
        primitivesWithImplicitCompanionObject().associateWith {
            getIrClass(JS_INTERNAL_PACKAGE_FQNAME.child(Name.identifier("${it.identifier}CompanionObject")))
        }
    }

    val continuationClass by lazy {
        symbolTable.referenceClass(
            coroutinePackage.memberScope.getContributedClassifier(
                CONTINUATION_NAME,
                NoLookupLocation.FROM_BACKEND
            ) as ClassDescriptor
        )
    }


    // Top-level functions forced to be loaded

    val coroutineSuspendOrReturn by lazy { symbolTable.referenceSimpleFunction(getJsInternalFunction(COROUTINE_SUSPEND_OR_RETURN_JS_NAME)) }
    val coroutineGetContext: IrSimpleFunctionSymbol
        get() {
            val contextGetter =
                continuationClass.owner.declarations.filterIsInstance<IrSimpleFunction>().atMostOne { it.name == CONTINUATION_CONTEXT_GETTER_NAME }
                    ?: continuationClass.owner.declarations.filterIsInstance<IrProperty>().atMostOne { it.name == CONTINUATION_CONTEXT_PROPERTY_NAME }?.getter!!
            return contextGetter.symbol
        }

    val coroutineGetContextJs by lazy { symbolTable.referenceSimpleFunction(getJsInternalFunction(GET_COROUTINE_CONTEXT_NAME)) }

    val coroutineContextProperty: PropertyDescriptor
        get() {
            val vars = coroutinePackage.memberScope.getContributedVariables(
                COROUTINE_CONTEXT_NAME,
                NoLookupLocation.FROM_BACKEND
            )
            return vars.single()
        }

    val captureStackSymbol = symbolTable.referenceSimpleFunction(getJsInternalFunction("captureStack"))
    val newThrowableSymbol = symbolTable.referenceSimpleFunction(getJsInternalFunction("newThrowable"))

    val throwISEymbol = symbolTable.referenceSimpleFunction(getFunctions(kotlinPackageFqn.child(Name.identifier("THROW_ISE"))).single())

    val suiteFun = getFunctions(FqName("kotlin.test.suite")).singleOrNull()?.let { symbolTable.referenceSimpleFunction(it) }
    val testFun = getFunctions(FqName("kotlin.test.test")).singleOrNull()?.let { symbolTable.referenceSimpleFunction(it) }

    val coroutineImplLabelPropertyGetter by lazy { ir.symbols.coroutineImpl.getPropertyGetter("state")!!.owner }
    val coroutineImplLabelPropertySetter by lazy { ir.symbols.coroutineImpl.getPropertySetter("state")!!.owner }
    val coroutineImplResultSymbolGetter by lazy { ir.symbols.coroutineImpl.getPropertyGetter("result")!!.owner }
    val coroutineImplResultSymbolSetter by lazy { ir.symbols.coroutineImpl.getPropertySetter("result")!!.owner }
    val coroutineImplExceptionPropertyGetter by lazy { ir.symbols.coroutineImpl.getPropertyGetter("exception")!!.owner }
    val coroutineImplExceptionPropertySetter by lazy { ir.symbols.coroutineImpl.getPropertySetter("exception")!!.owner }
    val coroutineImplExceptionStatePropertyGetter by lazy { ir.symbols.coroutineImpl.getPropertyGetter("exceptionState")!!.owner }
    val coroutineImplExceptionStatePropertySetter by lazy { ir.symbols.coroutineImpl.getPropertySetter("exceptionState")!!.owner }

    val primitiveClassProperties by lazy {
        primitiveClassesObject.owner.declarations.filterIsInstance<IrProperty>()
    }

    val primitiveClassFunctionClass by lazy {
        primitiveClassesObject.owner.declarations
            .filterIsInstance<IrSimpleFunction>()
            .find { it.name == Name.identifier("functionClass") }!!
    }

    val throwableConstructors by lazy { throwableClass.owner.declarations.filterIsInstance<IrConstructor>().map { it.symbol } }
    val defaultThrowableCtor by lazy { throwableConstructors.single { it.owner.valueParameters.size == 0 } }

    private fun referenceOperators(): Map<Name, MutableMap<IrClassifierSymbol, IrSimpleFunctionSymbol>> {
        val primitiveIrSymbols = irBuiltIns.primitiveIrTypes.map { it.classifierOrFail as IrClassSymbol }

        return OperatorNames.ALL.map { name ->
            // TODO to replace KotlinType with IrType we need right equals on IrType
            name to primitiveIrSymbols.fold(mutableMapOf<IrClassifierSymbol, IrSimpleFunctionSymbol>()) { m, s ->
                val function = s.owner.declarations.filterIsInstance<IrSimpleFunction>().singleOrNull { it.name == name }
                function?.let { m.put(s, it.symbol) }
                m
            }
        }.toMap()
    }

    private fun findClass(memberScope: MemberScope, name: Name): ClassDescriptor =
        memberScope.getContributedClassifier(name, NoLookupLocation.FROM_BACKEND) as ClassDescriptor

    private fun findFunctions(memberScope: MemberScope, name: Name): List<SimpleFunctionDescriptor> =
        memberScope.getContributedFunctions(name, NoLookupLocation.FROM_BACKEND).toList()

    internal fun getJsInternalClass(name: String): ClassDescriptor =
        findClass(internalPackage.memberScope, Name.identifier(name))

    internal fun getClass(fqName: FqName): ClassDescriptor =
        findClass(module.getPackage(fqName.parent()).memberScope, fqName.shortName())

    private fun getIrClass(fqName: FqName): IrClassSymbol = symbolTable.referenceClass(getClass(fqName))

    internal fun getJsInternalFunction(name: String): SimpleFunctionDescriptor =
        findFunctions(internalPackage.memberScope, Name.identifier(name)).single()

    fun getFunctions(fqName: FqName): List<SimpleFunctionDescriptor> =
        findFunctions(module.getPackage(fqName.parent()).memberScope, fqName.shortName())

    override fun log(message: () -> String) {
        /*TODO*/
        if (inVerbosePhase) print(message())
    }

    override fun report(element: IrElement?, irFile: IrFile?, message: String, isError: Boolean) {
        /*TODO*/
        print(message)
    }
}
