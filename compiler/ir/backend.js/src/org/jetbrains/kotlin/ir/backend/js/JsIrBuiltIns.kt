/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js

import org.jetbrains.kotlin.builtins.PrimitiveType
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.isLong
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.findDeclaration
import org.jetbrains.kotlin.ir.util.kotlinPackageFqn
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi2ir.findSingleFunction
import org.jetbrains.kotlin.util.capitalizeDecapitalize.toLowerCaseAsciiOnly

class JsIrBuiltIns(private val context: JsIrBackendContext) {

    // Type checks:
    val jsTypeOf = getInternalFunction("jsTypeOf")

    // Number conversions:

    val jsNumberToByte = getInternalFunction("numberToByte")
    val jsNumberToDouble = getInternalFunction("numberToDouble")
    val jsNumberToInt = getInternalFunction("numberToInt")
    val jsNumberToShort = getInternalFunction("numberToShort")
    val jsNumberToLong = getInternalFunction("numberToLong")
    val jsNumberToChar = getInternalFunction("numberToChar")
    val jsToByte = getInternalFunction("toByte")
    val jsToShort = getInternalFunction("toShort")
    val jsToLong = getInternalFunction("toLong")


    // RTTI:

    val isInterfaceSymbol = getInternalFunction("isInterface")
    val isArraySymbol = getInternalFunction("isArray")
    //    val isCharSymbol = getInternalFunction("isChar")
    val isObjectSymbol = getInternalFunction("isObject")
    val isSuspendFunctionSymbol = getInternalFunction("isSuspendFunction")

    val isNumberSymbol = getInternalFunction("isNumber")
    val isComparableSymbol = getInternalFunction("isComparable")
    val isCharSequenceSymbol = getInternalFunction("isCharSequence")

    val isPrimitiveArray = mapOf(
        PrimitiveType.BOOLEAN to getInternalFunction("isBooleanArray"),
        PrimitiveType.BYTE to getInternalFunction("isByteArray"),
        PrimitiveType.SHORT to getInternalFunction("isShortArray"),
        PrimitiveType.CHAR to getInternalFunction("isCharArray"),
        PrimitiveType.INT to getInternalFunction("isIntArray"),
        PrimitiveType.FLOAT to getInternalFunction("isFloatArray"),
        PrimitiveType.LONG to getInternalFunction("isLongArray"),
        PrimitiveType.DOUBLE to getInternalFunction("isDoubleArray")
    )


    // Enum

    val enumValueOfIntrinsic = getInternalFunction("enumValueOfIntrinsic")
    val enumValuesIntrinsic = getInternalFunction("enumValuesIntrinsic")

    // Other:

    val jsCode = getInternalFunction("js") // js("<code>")
    val jsHashCode = getInternalFunction("hashCode")
    val jsGetNumberHashCode = getInternalFunction("getNumberHashCode")
    val jsGetObjectHashCode = getInternalFunction("getObjectHashCode")
    val jsGetStringHashCode = getInternalFunction("getStringHashCode")
    val jsToString = getInternalFunction("toString")
    val jsAnyToString = getInternalFunction("anyToString")
    val jsCompareTo = getInternalFunction("compareTo")
    val jsEquals = getInternalFunction("equals")
    val jsConstruct = getInternalFunction("construct")

    val jsImul = getInternalFunction("imul")

    val jsUnreachableDeclarationLog = getInternalFunction("unreachableDeclarationLog")
    val jsUnreachableDeclarationException = getInternalFunction("unreachableDeclarationException")

    // Coroutines

    val jsCoroutineContext
        get() = context.ir.symbols.coroutineContextGetter

    val jsGetContinuation = getInternalFunction("getContinuation")
    val jsGetKClass = getInternalWithoutPackage("getKClass")
    val jsGetKClassFromExpression = getInternalWithoutPackage("getKClassFromExpression")

    val jsClassClassSymbol = getInternalClassWithoutPackage("kotlin.js.JsClass")

    val jsNumberRangeToNumber = getInternalFunction("numberRangeToNumber")
    val jsNumberRangeToLong = getInternalFunction("numberRangeToLong")

    val longClassSymbol = getInternalClassWithoutPackage("kotlin.Long")

    val longToDouble = context.symbolTable.referenceSimpleFunction(
        context.getClass(FqName("kotlin.Long")).unsubstitutedMemberScope.findSingleFunction(
            Name.identifier("toDouble")
        )
    )
    val longToFloat = context.symbolTable.referenceSimpleFunction(
        context.getClass(FqName("kotlin.Long")).unsubstitutedMemberScope.findSingleFunction(
            Name.identifier("toFloat")
        )
    )

    val longCompareToLong: IrSimpleFunction = longClassSymbol.owner.findDeclaration<IrSimpleFunction> {
        it.name == Name.identifier("compareTo") && it.valueParameters[0].type.isLong()
    }!!

    val charClassSymbol = getInternalClassWithoutPackage("kotlin.Char")
    val charConstructor = charClassSymbol.constructors.single().owner

    val stringClassSymbol = getInternalClassWithoutPackage("kotlin.String")
    val stringConstructorSymbol = stringClassSymbol.constructors.single()

    val anyClassSymbol = getInternalClassWithoutPackage("kotlin.Any")
    val anyConstructorSymbol = anyClassSymbol.constructors.single()

    val jsObjectClassSymbol = getInternalClassWithoutPackage("kotlin.js.JsObject")
    val jsObjectConstructorSymbol by context.lazy2 { jsObjectClassSymbol.constructors.single() }

    val uByteClassSymbol by context.lazy2 { getInternalClassWithoutPackage("kotlin.UByte") }
    val uShortClassSymbol by context.lazy2 { getInternalClassWithoutPackage("kotlin.UShort") }
    val uIntClassSymbol by context.lazy2 { getInternalClassWithoutPackage("kotlin.UInt") }
    val uLongClassSymbol by context.lazy2 { getInternalClassWithoutPackage("kotlin.ULong") }


    val returnIfSuspended = getInternalFunction("returnIfSuspended")
    val getContinuation = getInternalFunction("getContinuation")

    val jsEnsureNonNull = getFunctionInKotlinPackage("ensureNotNull")


    // Arrays:
    val array = context.symbolTable.referenceClass(context.irBuiltIns.builtIns.array)

    val primitiveArrays = PrimitiveType.values()
        .associate { context.symbolTable.referenceClass(context.irBuiltIns.builtIns.getPrimitiveArrayClassDescriptor(it)) to it }

    val jsArray = getInternalFunction("arrayWithFun")
    val jsFillArray = getInternalFunction("fillArrayFun")

    val jsArrayIteratorFunction = getInternalFunction("arrayIterator")

    val jsPrimitiveArrayIteratorFunctions =
        PrimitiveType.values().associate { it to getInternalFunction("${it.typeName.asString().toLowerCaseAsciiOnly()}ArrayIterator") }


    val primitiveToSizeConstructor =
        PrimitiveType.values().associate { type ->
            type to (context.intrinsics.typedArraySizeConstructors[type] ?: getInternalFunction("${type.typeName.asString().toLowerCaseAsciiOnly()}Array"))
        }

    val primitiveToLiteralConstructor =
        PrimitiveType.values().associate { type ->
            type to (context.intrinsics.typedArrayLiteralConstructors[type] ?: getInternalFunction("${type.typeName.asString().toLowerCaseAsciiOnly()}ArrayOf"))
        }


    val createKType = getInternalWithoutPackageOrNull("createKType")
    val createDynamicKType = getInternalWithoutPackageOrNull("createDynamicKType")
    val createKTypeParameter = getInternalWithoutPackageOrNull("createKTypeParameter")
    val getStarKTypeProjection = getInternalWithoutPackageOrNull("getStarKTypeProjection")
    val createCovariantKTypeProjection = getInternalWithoutPackageOrNull("createCovariantKTypeProjection")
    val createInvariantKTypeProjection = getInternalWithoutPackageOrNull("createInvariantKTypeProjection")
    val createContravariantKTypeProjection = getInternalWithoutPackageOrNull("createContravariantKTypeProjection")

    val arrayConcat = getInternalWithoutPackage("arrayConcat")

    val primitiveArrayConcat = getInternalWithoutPackage("primitiveArrayConcat")
    val taggedArrayCopy = getInternalWithoutPackage("taggedArrayCopy")

    // TODO move to IntrinsifyCallsLowering
    val doNotIntrinsifyAnnotationSymbol = context.symbolTable.referenceClass(context.getJsInternalClass("DoNotIntrinsify"))
    val jsFunAnnotationSymbol = context.symbolTable.referenceClass(context.getJsInternalClass("JsFun"))

    // TODO move CharSequence-related stiff to IntrinsifyCallsLowering
    val charSequenceClassSymbol = context.symbolTable.referenceClass(context.getClass(FqName("kotlin.CharSequence")))
    val charSequenceLengthPropertyGetterSymbol by context.lazy2 {
        with(charSequenceClassSymbol.owner.declarations) {
            filterIsInstance<IrProperty>().firstOrNull { it.name.asString() == "length" }?.getter ?:
            filterIsInstance<IrFunction>().first { it.name.asString() == "<get-length>" }
        }.symbol
    }
    val charSequenceGetFunctionSymbol by context.lazy2 {
        charSequenceClassSymbol.owner.declarations.filterIsInstance<IrFunction>().single { it.name.asString() == "get" }.symbol
    }
    val charSequenceSubSequenceFunctionSymbol by context.lazy2 {
        charSequenceClassSymbol.owner.declarations.filterIsInstance<IrFunction>().single { it.name.asString() == "subSequence" }.symbol
    }


    val jsCharSequenceGet = getInternalFunction("charSequenceGet")
    val jsCharSequenceLength = getInternalFunction("charSequenceLength")
    val jsCharSequenceSubSequence = getInternalFunction("charSequenceSubSequence")

    val jsBoxIntrinsic = getInternalFunction("boxIntrinsic")
    val jsUnboxIntrinsic = getInternalFunction("unboxIntrinsic")

    val captureStack = getInternalFunction("captureStack")


    // Helpers:

    private fun getInternalFunction(name: String) =
        context.symbolTable.referenceSimpleFunction(context.getJsInternalFunction(name))

    private fun getInternalWithoutPackage(name: String) =
        context.symbolTable.referenceSimpleFunction(context.getFunctions(FqName(name)).single())

    private fun getInternalWithoutPackageOrNull(name: String): IrSimpleFunctionSymbol? {
        val descriptor = context.getFunctions(FqName(name)).singleOrNull() ?: return null
        return context.symbolTable.referenceSimpleFunction(descriptor)
    }

    private fun getFunctionInKotlinPackage(name: String) =
        context.symbolTable.referenceSimpleFunction(context.getFunctions(kotlinPackageFqn.child(Name.identifier(name))).single())

    private fun getInternalClassWithoutPackage(fqName: String) =
        context.symbolTable.referenceClass(context.getClass(FqName(fqName)))

}