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

    val jsNumberToByte by lazy { getInternalFunction("numberToByte") }
    val jsNumberToDouble by lazy { getInternalFunction("numberToDouble") }
    val jsNumberToInt by lazy { getInternalFunction("numberToInt") }
    val jsNumberToShort by lazy { getInternalFunction("numberToShort") }
    val jsNumberToLong by lazy { getInternalFunction("numberToLong") }
    val jsNumberToChar by lazy { getInternalFunction("numberToChar") }
    val jsToByte by lazy { getInternalFunction("toByte") }
    val jsToShort by lazy { getInternalFunction("toShort") }
    val jsToLong by lazy { getInternalFunction("toLong") }


    // RTTI:

    val isInterfaceSymbol by lazy { getInternalFunction("isInterface") }
    val isArraySymbol by lazy { getInternalFunction("isArray") }
    //    val isCharSymbol = getInternalFunction("isChar")
    val isObjectSymbol by lazy { getInternalFunction("isObject") }

    val isNumberSymbol by lazy { getInternalFunction("isNumber") }
    val isComparableSymbol by lazy { getInternalFunction("isComparable") }
    val isCharSequenceSymbol by lazy { getInternalFunction("isCharSequence") }

    val isPrimitiveArray by lazy {
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
    }

    // Enum

    val enumValueOfIntrinsic by lazy { getInternalFunction("enumValueOfIntrinsic") }
    val enumValuesIntrinsic by lazy { getInternalFunction("enumValuesIntrinsic") }


    // Other:
    val jsCode by lazy { getInternalFunction("js") } // js("<code>")
    val jsHashCode by lazy { getInternalFunction("hashCode") }
    val jsGetObjectHashCode by lazy { getInternalFunction("getObjectHashCode") }
    val jsToString by lazy { getInternalFunction("toString") }
    val jsAnyToString by lazy { getInternalFunction("anyToString") }
    val jsCompareTo by lazy { getInternalFunction("compareTo") }
    val jsEquals by lazy { getInternalFunction("equals") }

    val jsImul by lazy { getInternalFunction("imul") }

    // Coroutines

    val jsCoroutineContext by lazy { context.symbolTable.referenceSimpleFunction(context.coroutineContextProperty.getter!!) }

    val jsGetContinuation by lazy { getInternalFunction("getContinuation") }
    val jsGetKClass by lazy { getInternalWithoutPackage("getKClass") }
    val jsGetKClassFromExpression by lazy { getInternalWithoutPackage("getKClassFromExpression") }
    val jsClass by lazy { getInternalFunction("jsClass") }

    val jsNumberRangeToNumber by lazy { getInternalFunction("numberRangeToNumber") }
    val jsNumberRangeToLong by lazy { getInternalFunction("numberRangeToLong") }

    val longClassSymbol by lazy { getInternalClassWithoutPackage("kotlin.Long") }

    val longToDouble by lazy {
        context.symbolTable.referenceSimpleFunction(
            context.getClass(FqName("kotlin.Long")).unsubstitutedMemberScope.findSingleFunction(
                Name.identifier("toDouble")
            )
        )
    }

    val longToFloat by lazy {
        context.symbolTable.referenceSimpleFunction(
            context.getClass(FqName("kotlin.Long")).unsubstitutedMemberScope.findSingleFunction(
                Name.identifier("toFloat")
            )
        )
    }

    val longCompareToLong: IrSimpleFunction by lazy {
        longClassSymbol.owner.findDeclaration<IrSimpleFunction> {
            it.name == Name.identifier("compareTo") && it.valueParameters[0].type.isLong()
        }!!
    }

    val charClassSymbol by lazy { getInternalClassWithoutPackage("kotlin.Char") }
    val charConstructor by lazy { charClassSymbol.constructors.single().owner }

    val uByteClassSymbol by lazy { getInternalClassWithoutPackage("kotlin.UByte") }
    val uShortClassSymbol by lazy { getInternalClassWithoutPackage("kotlin.UShort") }
    val uIntClassSymbol by lazy { getInternalClassWithoutPackage("kotlin.UInt") }
    val uLongClassSymbol by lazy { getInternalClassWithoutPackage("kotlin.ULong") }


    val returnIfSuspended by lazy { getInternalFunction("returnIfSuspended") }
    val getContinuation by lazy { getInternalFunction("getContinuation") }

    // Arrays:
    val array = context.symbolTable.referenceClass(context.builtIns.array)

    val primitiveArrays = PrimitiveType.values()
        .associate { context.symbolTable.referenceClass(context.builtIns.getPrimitiveArrayClassDescriptor(it)) to it }

    val jsArray by lazy { getInternalFunction("arrayWithFun") }
    val jsFillArray by lazy { getInternalFunction("fillArrayFun") }


    val jsArrayIteratorFunction by lazy { getInternalFunction("arrayIterator") }

    val jsPrimitiveArrayIteratorFunctions by lazy {
        PrimitiveType.values().associate { it to getInternalFunction("${it.typeName.asString().toLowerCase()}ArrayIterator") }
    }


    val primitiveToSizeConstructor by lazy {
        PrimitiveType.values().associate { type ->
            type to (context.intrinsics.primitiveToSizeConstructor[type] ?: getInternalFunction("${type.typeName.asString().toLowerCase()}Array"))
        }
    }

    val primitiveToLiteralConstructor by lazy {
        PrimitiveType.values().associate { type ->
            type to (context.intrinsics.primitiveToLiteralConstructor[type] ?: getInternalFunction("${type.typeName.asString().toLowerCase()}ArrayOf"))
        }
    }

    val arrayConcat by lazy { getInternalWithoutPackage("arrayConcat") }

    val primitiveArrayConcat by lazy { getInternalWithoutPackage("primitiveArrayConcat") }

    // TODO move to IntrinsifyCallsLowering
    val doNotIntrinsifyAnnotationSymbol by lazy { context.symbolTable.referenceClass(context.getJsInternalClass("DoNotIntrinsify")) }

    // TODO move CharSequence-related stiff to IntrinsifyCallsLowering
    val charSequenceClassSymbol by lazy { context.symbolTable.referenceClass(context.getClass(FqName("kotlin.CharSequence"))) }

    val charSequenceLengthPropertyGetterSymbol =
        charSequenceClassSymbol.owner.declarations.filterIsInstance<IrProperty>().first { it.name.asString() == "length" }.getter!!.symbol


    val charSequenceGetFunctionSymbol by lazy {
        charSequenceClassSymbol.owner.declarations.filterIsInstance<IrFunction>().single { it.name.asString() == "get" }.symbol
    }

    val charSequenceSubSequenceFunctionSymbol by lazy {
        charSequenceClassSymbol.owner.declarations.filterIsInstance<IrFunction>().single { it.name.asString() == "subSequence" }.symbol
    }


    val jsCharSequenceGet by lazy { getInternalFunction("charSequenceGet") }
    val jsCharSequenceLength by lazy { getInternalFunction("charSequenceLength") }
    val jsCharSequenceSubSequence by lazy { getInternalFunction("charSequenceSubSequence") }

    val jsBoxIntrinsic by lazy { getInternalFunction("boxIntrinsic") }
    val jsUnboxIntrinsic by lazy { getInternalFunction("unboxIntrinsic") }

    // Helpers:

    private fun getInternalFunction(name: String) =
        context.symbolTable.referenceSimpleFunction(context.getJsInternalFunction(name))

    private fun getInternalWithoutPackage(name: String) =
        context.symbolTable.referenceSimpleFunction(context.getFunctions(FqName(name)).single())

    private fun getInternalClassWithoutPackage(fqName: String) =
        context.symbolTable.referenceClass(context.getClass(FqName(fqName)))


}