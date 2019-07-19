/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js

import org.jetbrains.kotlin.builtins.PrimitiveType
import org.jetbrains.kotlin.descriptors.impl.EmptyPackageFragmentDescriptor
import org.jetbrains.kotlin.ir.backend.js.ir.JsIrBuilder
import org.jetbrains.kotlin.ir.backend.js.utils.Namer
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.impl.IrExternalPackageFragmentImpl
import org.jetbrains.kotlin.ir.declarations.mapIndexedTo
import org.jetbrains.kotlin.ir.descriptors.IrBuiltIns
import org.jetbrains.kotlin.ir.symbols.impl.IrExternalPackageFragmentSymbolImpl
import org.jetbrains.kotlin.ir.types.isLong
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.findDeclaration
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi2ir.findSingleFunction
import org.jetbrains.kotlin.types.KotlinType
import java.util.*

class JsIntrinsics(private val irBuiltIns: IrBuiltIns) {

    private val internalPackageFragmentDescriptor = EmptyPackageFragmentDescriptor(irBuiltIns.builtIns.builtInsModule, FqName("kotlin.js.internal"))
    private val externalPackageFragmentSymbol = IrExternalPackageFragmentSymbolImpl(internalPackageFragmentDescriptor)
    val externalPackageFragment = IrExternalPackageFragmentImpl(externalPackageFragmentSymbol)


    // TODO: Should we drop operator intrinsics in favor of IrDynamicOperatorExpression?

    // Equality operations:

    val jsEqeq = binOpBool("jsEqeq")
    val jsNotEq = binOpBool("jsNotEq")
    val jsEqeqeq = binOpBool("jsEqeqeq")
    val jsNotEqeq = binOpBool("jsNotEqeq")

    val jsGt = binOpBool("jsGt")
    val jsGtEq = binOpBool("jsGtEq")
    val jsLt = binOpBool("jsLt")
    val jsLtEq = binOpBool("jsLtEq")


    // Unary operations:

    val jsNot = unOpBool("jsNot")

    val jsUnaryPlus = unOp("jsUnaryPlus")
    val jsUnaryMinus = unOp("jsUnaryMinus")

    val jsPrefixInc = unOp("jsPrefixInc")
    val jsPostfixInc = unOp("jsPostfixInc")
    val jsPrefixDec = unOp("jsPrefixDec")
    val jsPostfixDec = unOp("jsPostfixDec")

    // Binary operations:

    val jsPlus = binOp("jsPlus")
    val jsMinus = binOp("jsMinus")
    val jsMult = binOp("jsMult")
    val jsDiv = binOp("jsDiv")
    val jsMod = binOp("jsMod")

    val jsPlusAssign = binOp("jsPlusAssign")
    val jsMinusAssign = binOp("jsMinusAssign")
    val jsMultAssign = binOp("jsMultAssign")
    val jsDivAssign = binOp("jsDivAssign")
    val jsModAssign = binOp("jsModAssign")

    val jsAnd = binOp("jsAnd")
    val jsOr = binOp("jsOr")


    // Bit operations:

    val jsBitAnd = binOpInt("jsBitAnd")
    val jsBitOr = binOpInt("jsBitOr")
    val jsBitXor = binOpInt("jsBitXor")
    val jsBitNot = unOpInt("jsBitNot")

    val jsBitShiftR = binOpInt("jsBitShiftR")
    val jsBitShiftRU = binOpInt("jsBitShiftRU")
    val jsBitShiftL = binOpInt("jsBitShiftL")


    // KFunction operations:

    val jsName = unOp("kCallableName", irBuiltIns.string)
    val jsPropertyGet = binOp("kPropertyGet")
    val jsPropertySet = tripleOp("kPropertySet", irBuiltIns.unit)


    // Type checks:

    val jsInstanceOf = binOpBool("jsInstanceOf")
    val jsTypeOf = unOp("jsTypeOf", irBuiltIns.string)

    // Other:
    val jsObjectCreate = defineObjectCreateIntrinsic() // Object.create
    val jsGetJSField = defineGetJSPropertyIntrinsic() // till we don't have dynamic type we use intrinsic which sets a field with any name
    val jsSetJSField = defineSetJSPropertyIntrinsic() // till we don't have dynamic type we use intrinsic which sets a field with any name
    val unreachable = defineUnreachableIntrinsic()

    val jsArraySlice = unOp("slice")
    val jsBind = defineJsBindIntrinsic()

    val jsArrayLength = unOp("jsArrayLength")
    val jsArrayGet = binOp("jsArrayGet")
    val jsArraySet = tripleOp("jsArraySet")

    val arrayLiteral = unOp("arrayLiteral")

    val primitiveToTypedArrayMap = EnumMap(
        mapOf(
            PrimitiveType.BYTE to "Int8",
            PrimitiveType.SHORT to "Int16",
            PrimitiveType.INT to "Int32",
            PrimitiveType.FLOAT to "Float32",
            PrimitiveType.DOUBLE to "Float64"
        )
    )

    val primitiveToSizeConstructor by lazy {
        primitiveToTypedArrayMap.entries.associate { (type, name) ->
            type to unOp("${name.toLowerCase()}Array")
        }
    }

    val primitiveToLiteralConstructor by lazy {
        primitiveToTypedArrayMap.entries.associate { (type, name) ->
            type to unOp("${name.toLowerCase()}ArrayOf")
        }
    }


    // TODO: unify how we create intrinsic symbols
    private fun defineObjectCreateIntrinsic(): IrSimpleFunction {
        return JsIrBuilder.buildFunction(
            "Object\$create",
            returnType = irBuiltIns.anyType,
            parent = externalPackageFragment,
            isInline = true,
            origin = JsLoweredDeclarationOrigin.JS_INTRINSICS_STUB
        ).also {
            val typeParameter = JsIrBuilder.buildTypeParameter(Name.identifier("T"), 0, true)
            typeParameter.parent = it
            typeParameter.superTypes += irBuiltIns.anyType
            it.typeParameters += typeParameter
            externalPackageFragment.declarations += it
        }
    }

    private fun defineGetJSPropertyIntrinsic() =
        JsIrBuilder.buildFunction(
            "\$getJSProperty\$",
            returnType = irBuiltIns.anyNType,
            parent = externalPackageFragment,
            origin = JsLoweredDeclarationOrigin.JS_INTRINSICS_STUB
        ).also {
            listOf("receiver", "fieldName").mapIndexedTo(it.valueParameters) { i, p ->
                JsIrBuilder.buildValueParameter(p, i, irBuiltIns.anyType).also { v -> v.parent = it }
            }
            externalPackageFragment.declarations += it
        }

    private fun defineJsBindIntrinsic() =
        JsIrBuilder.buildFunction(
            "\$jsBind\$",
            returnType = irBuiltIns.anyNType,
            parent = externalPackageFragment,
            origin = JsLoweredDeclarationOrigin.JS_INTRINSICS_STUB
        ).also {
            listOf("receiver", "target").mapIndexedTo(it.valueParameters) { i, p ->
                JsIrBuilder.buildValueParameter(p, i, irBuiltIns.anyType).also { v -> v.parent = it }
            }
            externalPackageFragment.declarations += it
        }

    private fun defineSetJSPropertyIntrinsic() =
        JsIrBuilder.buildFunction(
            "\$setJSProperty\$",
            returnType = irBuiltIns.unitType,
            parent = externalPackageFragment,
            origin = JsLoweredDeclarationOrigin.JS_INTRINSICS_STUB
        ).also {
            listOf("receiver", "fieldName", "fieldValue").mapIndexedTo(it.valueParameters) { i, p ->
                JsIrBuilder.buildValueParameter(p, i, irBuiltIns.anyType).also { v -> v.parent = it }
            }
            externalPackageFragment.declarations += it
        }

    private fun defineUnreachableIntrinsic() =
        JsIrBuilder.buildFunction(
            Namer.UNREACHABLE_NAME,
            returnType = irBuiltIns.nothingType,
            parent = externalPackageFragment,
            origin = JsLoweredDeclarationOrigin.JS_INTRINSICS_STUB
        ).also {
            externalPackageFragment.declarations += it
        }

    private fun unOp(name: String, returnType: KotlinType = irBuiltIns.anyN) =
        irBuiltIns.run { defineOperator(name, returnType, listOf(anyN)) }

    private fun unOpBool(name: String) = unOp(name, irBuiltIns.bool)
    private fun unOpInt(name: String) = unOp(name, irBuiltIns.int)

    private fun binOp(name: String, returnType: KotlinType = irBuiltIns.anyN) =
        irBuiltIns.run { defineOperator(name, returnType, listOf(anyN, anyN)) }

    private fun tripleOp(name: String, returnType: KotlinType = irBuiltIns.anyN) =
        irBuiltIns.run { defineOperator(name, returnType, listOf(anyN, anyN, anyN)) }

    private fun binOpBool(name: String) = binOp(name, irBuiltIns.bool)
    private fun binOpInt(name: String) = binOp(name, irBuiltIns.int)
}
