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

    val jsNumberToByte by lazyVal { getInternalFunction("numberToByte") }
    val jsNumberToDouble by lazyVal { getInternalFunction("numberToDouble") }
    val jsNumberToInt by lazyVal { getInternalFunction("numberToInt") }
    val jsNumberToShort by lazyVal { getInternalFunction("numberToShort") }
    val jsNumberToLong by lazyVal { getInternalFunction("numberToLong") }
    val jsNumberToChar by lazyVal { getInternalFunction("numberToChar") }
    val jsToByte by lazyVal { getInternalFunction("toByte") }
    val jsToShort by lazyVal { getInternalFunction("toShort") }
    val jsToLong by lazyVal { getInternalFunction("toLong") }


    // RTTI:

    val isInterfaceSymbol by lazyVal { getInternalFunction("isInterface") }
    val isArraySymbol by lazyVal { getInternalFunction("isArray") }
    //    val isCharSymbol = getInternalFunction("isChar")
    val isObjectSymbol by lazyVal { getInternalFunction("isObject") }

    val isNumberSymbol by lazyVal { getInternalFunction("isNumber") }
    val isComparableSymbol by lazyVal { getInternalFunction("isComparable") }
    val isCharSequenceSymbol by lazyVal { getInternalFunction("isCharSequence") }

    val isPrimitiveArray by lazyVal {
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

    val enumValueOfIntrinsic by lazyVal { getInternalFunction("enumValueOfIntrinsic") }
    val enumValuesIntrinsic by lazyVal { getInternalFunction("enumValuesIntrinsic") }


    // Other:
    val jsCode by lazyVal { getInternalFunction("js") } // js("<code>")
    val jsHashCode by lazyVal { getInternalFunction("hashCode") }
    val jsGetObjectHashCode by lazyVal { getInternalFunction("getObjectHashCode") }
    val jsToString by lazyVal { getInternalFunction("toString") }
    val jsAnyToString by lazyVal { getInternalFunction("anyToString") }
    val jsCompareTo by lazyVal { getInternalFunction("compareTo") }
    val jsEquals by lazyVal { getInternalFunction("equals") }

    val jsImul by lazyVal { getInternalFunction("imul") }

    // Coroutines

    val jsCoroutineContext by lazyVal { context.symbolTable.referenceSimpleFunction(context.coroutineContextProperty.getter!!) }

    val jsGetContinuation by lazyVal { getInternalFunction("getContinuation") }
    val jsGetKClass by lazyVal { getInternalWithoutPackage("getKClass") }
    val jsGetKClassFromExpression by lazyVal { getInternalWithoutPackage("getKClassFromExpression") }
    val jsClass by lazyVal { getInternalFunction("jsClass") }

    val jsNumberRangeToNumber by lazyVal { getInternalFunction("numberRangeToNumber") }
    val jsNumberRangeToLong by lazyVal { getInternalFunction("numberRangeToLong") }

    val longClassSymbol by lazyVal { getInternalClassWithoutPackage("kotlin.Long") }

    val longToDouble by lazyVal {
        context.symbolTable.referenceSimpleFunction(
            context.getClass(FqName("kotlin.Long")).unsubstitutedMemberScope.findSingleFunction(
                Name.identifier("toDouble")
            )
        )
    }

    val longToFloat by lazyVal {
        context.symbolTable.referenceSimpleFunction(
            context.getClass(FqName("kotlin.Long")).unsubstitutedMemberScope.findSingleFunction(
                Name.identifier("toFloat")
            )
        )
    }

    val longCompareToLong: IrSimpleFunction by lazyVal {
        longClassSymbol.owner.findDeclaration<IrSimpleFunction> {
            it.name == Name.identifier("compareTo") && it.valueParameters[0].type.isLong()
        }!!
    }

    val charClassSymbol by lazyVal { getInternalClassWithoutPackage("kotlin.Char") }
    val charConstructor by lazyVal { charClassSymbol.constructors.single().owner }

    val uByteClassSymbol by lazyVal { getInternalClassWithoutPackage("kotlin.UByte") }
    val uShortClassSymbol by lazyVal { getInternalClassWithoutPackage("kotlin.UShort") }
    val uIntClassSymbol by lazyVal { getInternalClassWithoutPackage("kotlin.UInt") }
    val uLongClassSymbol by lazyVal { getInternalClassWithoutPackage("kotlin.ULong") }


    val returnIfSuspended by lazyVal { getInternalFunction("returnIfSuspended") }
    val getContinuation by lazyVal { getInternalFunction("getContinuation") }

    // Arrays:
    val array = context.symbolTable.referenceClass(context.builtIns.array)

    val primitiveArrays = PrimitiveType.values()
        .associate { context.symbolTable.referenceClass(context.builtIns.getPrimitiveArrayClassDescriptor(it)) to it }

    val jsArray by lazyVal { getInternalFunction("arrayWithFun") }
    val jsFillArray by lazyVal { getInternalFunction("fillArrayFun") }


    val jsArrayIteratorFunction by lazyVal { getInternalFunction("arrayIterator") }

    val jsPrimitiveArrayIteratorFunctions by lazyVal {
        PrimitiveType.values().associate { it to getInternalFunction("${it.typeName.asString().toLowerCase()}ArrayIterator") }
    }


    val primitiveToSizeConstructor by lazyVal {
        PrimitiveType.values().associate { type ->
            type to (context.intrinsics.primitiveToSizeConstructor[type] ?: getInternalFunction("${type.typeName.asString().toLowerCase()}Array"))
        }
    }

    val primitiveToLiteralConstructor by lazyVal {
        PrimitiveType.values().associate { type ->
            type to (context.intrinsics.primitiveToLiteralConstructor[type] ?: getInternalFunction("${type.typeName.asString().toLowerCase()}ArrayOf"))
        }
    }

    val arrayConcat by lazyVal { getInternalWithoutPackage("arrayConcat") }

    val primitiveArrayConcat by lazyVal { getInternalWithoutPackage("primitiveArrayConcat") }

    // TODO move to IntrinsifyCallsLowering
    val doNotIntrinsifyAnnotationSymbol by lazyVal { context.symbolTable.referenceClass(context.getJsInternalClass("DoNotIntrinsify")) }

    // TODO move CharSequence-related stiff to IntrinsifyCallsLowering
    val charSequenceClassSymbol by lazyVal { context.symbolTable.referenceClass(context.getClass(FqName("kotlin.CharSequence"))) }

    val charSequenceLengthPropertyGetterSymbol =
        charSequenceClassSymbol.owner.declarations.filterIsInstance<IrProperty>().first { it.name.asString() == "length" }.getter!!.symbol


    val charSequenceGetFunctionSymbol by lazyVal {
        charSequenceClassSymbol.owner.declarations.filterIsInstance<IrFunction>().single { it.name.asString() == "get" }.symbol
    }

    val charSequenceSubSequenceFunctionSymbol by lazyVal {
        charSequenceClassSymbol.owner.declarations.filterIsInstance<IrFunction>().single { it.name.asString() == "subSequence" }.symbol
    }


    val jsCharSequenceGet by lazyVal { getInternalFunction("charSequenceGet") }
    val jsCharSequenceLength by lazyVal { getInternalFunction("charSequenceLength") }
    val jsCharSequenceSubSequence by lazyVal { getInternalFunction("charSequenceSubSequence") }

    val jsBoxIntrinsic by lazyVal { getInternalFunction("boxIntrinsic") }
    val jsUnboxIntrinsic by lazyVal { getInternalFunction("unboxIntrinsic") }

    // Helpers:

    private fun getInternalFunction(name: String) =
        context.symbolTable.referenceSimpleFunction(context.getJsInternalFunction(name))

    private fun getInternalWithoutPackage(name: String) =
        context.symbolTable.referenceSimpleFunction(context.getFunctions(FqName(name)).single())

    private fun getInternalClassWithoutPackage(fqName: String) =
        context.symbolTable.referenceClass(context.getClass(FqName(fqName)))


}