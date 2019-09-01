/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js

import org.jetbrains.kotlin.builtins.PrimitiveType
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.types.isLong
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.findDeclaration
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi2ir.findSingleFunction

class JsLibraryIntrinsics(val context: JsIrBackendContext) {

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

    val isNumberSymbol = getInternalFunction("isNumber")
    val isComparableSymbol = getInternalFunction("isComparable")
    val isCharSequenceSymbol = getInternalFunction("isCharSequence")

    val isPrimitiveArray =
        mapOf(
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
    val jsGetObjectHashCode = getInternalFunction("getObjectHashCode")
    val jsToString = getInternalFunction("toString")
    val jsAnyToString = getInternalFunction("anyToString")
    val jsCompareTo = getInternalFunction("compareTo")
    val jsEquals = getInternalFunction("equals")

    val jsImul = getInternalFunction("imul")

    // Coroutines

    val jsCoroutineContext = context.symbolTable.referenceSimpleFunction(context.coroutineContextProperty.getter!!)

    val jsGetContinuation = getInternalFunction("getContinuation")
    val jsGetKClass = getInternalWithoutPackage("getKClass")
    val jsGetKClassFromExpression = getInternalWithoutPackage("getKClassFromExpression")
    val jsClass = getInternalFunction("jsClass")

    val jsNumberRangeToNumber = getInternalFunction("numberRangeToNumber")
    val jsNumberRangeToLong = getInternalFunction("numberRangeToLong")

    val longClassSymbol = getInternalClassWithoutPackage("kotlin.Long")

    val longToDouble =
        context.symbolTable.referenceSimpleFunction(
            context.getClass(FqName("kotlin.Long")).unsubstitutedMemberScope.findSingleFunction(
                Name.identifier("toDouble")
            )
        )

    val longToFloat =
        context.symbolTable.referenceSimpleFunction(
            context.getClass(FqName("kotlin.Long")).unsubstitutedMemberScope.findSingleFunction(
                Name.identifier("toFloat")
            )
        )

    val longCompareToLong: IrSimpleFunction =
        longClassSymbol.owner.findDeclaration<IrSimpleFunction> {
            it.name == Name.identifier("compareTo") && it.valueParameters[0].type.isLong()
        }!!

    val charClassSymbol = getInternalClassWithoutPackage("kotlin.Char")
    val charConstructor = charClassSymbol.constructors.single().owner

    val uByteClassSymbol by lazy { getInternalClassWithoutPackage("kotlin.UByte") }
    val uShortClassSymbol by lazy { getInternalClassWithoutPackage("kotlin.UShort") }
    val uIntClassSymbol by lazy { getInternalClassWithoutPackage("kotlin.UInt") }
    val uLongClassSymbol by lazy { getInternalClassWithoutPackage("kotlin.ULong") }


    val returnIfSuspended = getInternalFunction("returnIfSuspended")
    val getContinuation = getInternalFunction("getContinuation")

    // Arrays:
    val array = context.symbolTable.referenceClass(context.builtIns.array)

    val primitiveArrays = PrimitiveType.values()
        .associate { context.symbolTable.referenceClass(context.builtIns.getPrimitiveArrayClassDescriptor(it)) to it }

    val jsArray = getInternalFunction("arrayWithFun")
    val jsFillArray = getInternalFunction("fillArrayFun")


    val jsArrayIteratorFunction = getInternalFunction("arrayIterator")

    val jsPrimitiveArrayIteratorFunctions =
        PrimitiveType.values().associate { it to getInternalFunction("${it.typeName.asString().toLowerCase()}ArrayIterator") }


    val primitiveToSizeConstructor =
        PrimitiveType.values().associate { type ->
            type to (context.intrinsics.primitiveToSizeConstructor[type] ?: getInternalFunction("${type.typeName.asString().toLowerCase()}Array"))
        }

    val primitiveToLiteralConstructor =
        PrimitiveType.values().associate { type ->
            type to (context.intrinsics.primitiveToLiteralConstructor[type] ?: getInternalFunction("${type.typeName.asString().toLowerCase()}ArrayOf"))
        }

    val arrayConcat = getInternalWithoutPackage("arrayConcat")

    val primitiveArrayConcat = getInternalWithoutPackage("primitiveArrayConcat")

    // TODO move to IntrinsifyCallsLowering
    val doNotIntrinsifyAnnotationSymbol = context.symbolTable.referenceClass(context.getJsInternalClass("DoNotIntrinsify"))

    // TODO move CharSequence-related stiff to IntrinsifyCallsLowering
    val charSequenceClassSymbol = context.symbolTable.referenceClass(context.getClass(FqName("kotlin.CharSequence")))

    val charSequenceLengthPropertyGetterSymbol =
        charSequenceClassSymbol.owner.declarations.filterIsInstance<IrProperty>().first { it.name.asString() == "length" }.getter!!.symbol


    val charSequenceGetFunctionSymbol =
        charSequenceClassSymbol.owner.declarations.filterIsInstance<IrFunction>().single { it.name.asString() == "get" }.symbol

    val charSequenceSubSequenceFunctionSymbol =
        charSequenceClassSymbol.owner.declarations.filterIsInstance<IrFunction>().single { it.name.asString() == "subSequence" }.symbol


    val jsCharSequenceGet = getInternalFunction("charSequenceGet")
    val jsCharSequenceLength = getInternalFunction("charSequenceLength")
    val jsCharSequenceSubSequence = getInternalFunction("charSequenceSubSequence")

    val jsBoxIntrinsic = getInternalFunction("boxIntrinsic")
    val jsUnboxIntrinsic = getInternalFunction("unboxIntrinsic")

    // Helpers:

    private fun getInternalFunction(name: String) =
        context.symbolTable.referenceSimpleFunction(context.getJsInternalFunction(name))

    private fun getInternalWithoutPackage(name: String) =
        context.symbolTable.referenceSimpleFunction(context.getFunctions(FqName(name)).single())

    private fun getInternalClassWithoutPackage(fqName: String) =
        context.symbolTable.referenceClass(context.getClass(FqName(fqName)))

}