/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js

import org.jetbrains.kotlin.builtins.PrimitiveType
import org.jetbrains.kotlin.ir.backend.js.utils.Namer
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addTypeParameter
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.declarations.IrFactory
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.impl.IrExternalPackageFragmentImpl
import org.jetbrains.kotlin.ir.descriptors.IrBuiltIns
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.types.impl.IrDynamicTypeImpl
import org.jetbrains.kotlin.ir.types.impl.IrSimpleTypeBuilder
import org.jetbrains.kotlin.ir.types.impl.buildSimpleType
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.types.Variance
import org.jetbrains.kotlin.util.capitalizeDecapitalize.toLowerCaseAsciiOnly
import java.util.*

class JsIntrinsics(private val irBuiltIns: IrBuiltIns, private val irFactory: IrFactory) {

    val dynamicType: IrDynamicType = IrDynamicTypeImpl(null, emptyList(), Variance.INVARIANT)

    val externalPackageFragment = IrExternalPackageFragmentImpl.createEmptyExternalPackageFragment(
        irBuiltIns.builtIns.builtInsModule, FqName("kotlin.js.internal")
    )

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

    // Type checks:

    val jsInstanceOf = binOpBool("jsInstanceOf")

    // Other:

    val jsObjectCreate = defineObjectCreateIntrinsic() // Object.create
    val jsNewTarget = unOp("jsNewTarget")
    val jsEmptyObject = unOp("emptyObject")
    val jsOpenInitializerBox = binOp("openInitializerBox")
    val es6DefaultType = defineEs6DefaultTypeIntrinsic().symbol

    val unreachable = defineUnreachableIntrinsic()


    // Arrays

    val jsArrayLength = unOp("jsArrayLength")
    val jsArrayGet = binOp("jsArrayGet")
    val jsArraySet = tripleOp("jsArraySet")

    val arrayLiteral = unOp("arrayLiteral")

    val jsArraySlice = defineJsSliceIntrinsic().symbol

    val primitiveToTypedArrayMap = EnumMap(
        mapOf(
            PrimitiveType.BYTE to "Int8",
            PrimitiveType.SHORT to "Int16",
            PrimitiveType.INT to "Int32",
            PrimitiveType.FLOAT to "Float32",
            PrimitiveType.DOUBLE to "Float64"
        )
    )

    val typedArraySizeConstructors = primitiveToTypedArrayMap.entries.associate { (type, name) -> type to unOp("${name.toLowerCaseAsciiOnly()}Array") }

    val typedArrayLiteralConstructors = primitiveToTypedArrayMap.entries.associate { (type, name) -> type to unOp("${name.toLowerCaseAsciiOnly()}ArrayOf") }

    val jsBind = defineJsBindIntrinsic()

    val createSharedBox = defineCreateSharedBox()
    val readSharedBox = defineReadSharedBox()
    val writeSharedBox = defineWriteSharedBox()

    val jsUndefined = defineJsUndefinedIntrinsic()

    // Helpers:

    // TODO: unify how we create intrinsic symbols
    private fun defineObjectCreateIntrinsic(): IrSimpleFunction {
        return irFactory.addFunction(externalPackageFragment) {
            name = Name.identifier("Object\$create")
            origin = JsLoweredDeclarationOrigin.JS_INTRINSICS_STUB
        }.apply {
            returnType = addTypeParameter("T", irBuiltIns.anyType).defaultType
        }
    }

    private fun defineCreateSharedBox(): IrSimpleFunction {
        return irFactory.addFunction(externalPackageFragment) {
            name = Name.identifier("\$sharedBox\$create")
            origin = JsLoweredDeclarationOrigin.JS_INTRINSICS_STUB
            returnType = dynamicType
        }.apply {
            val tp = addTypeParameter("T", irBuiltIns.anyNType)
            addValueParameter {
                index = 0
                name = Name.identifier("v")
                type = IrSimpleTypeBuilder().run {
                    classifier = tp.symbol
                    hasQuestionMark = true
                    buildSimpleType()
                }
            }
        }
    }

    private fun defineReadSharedBox(): IrSimpleFunction {
        return irFactory.addFunction(externalPackageFragment) {
            name = Name.identifier("\$sharedBox\$read")
            origin = JsLoweredDeclarationOrigin.JS_INTRINSICS_STUB
        }.apply {
            val tp = addTypeParameter("T", irBuiltIns.anyNType)
            returnType = IrSimpleTypeBuilder().run {
                classifier = tp.symbol
                hasQuestionMark = true
                buildSimpleType()
            }
            addValueParameter {
                index = 0
                name = Name.identifier("box")
                type = dynamicType
            }
        }
    }

    private fun defineWriteSharedBox(): IrSimpleFunction {
        return irFactory.addFunction(externalPackageFragment) {
            name = Name.identifier("\$sharedBox\$write")
            origin = JsLoweredDeclarationOrigin.JS_INTRINSICS_STUB
            returnType = irBuiltIns.unitType
        }.apply {
            val tp = addTypeParameter("T", irBuiltIns.anyNType)
            addValueParameter {
                index = 0
                name = Name.identifier("box")
                type = dynamicType
            }
            addValueParameter {
                index = 1
                name = Name.identifier("nv")
                type = IrSimpleTypeBuilder().run {
                    classifier = tp.symbol
                    hasQuestionMark = true
                    buildSimpleType()
                }
            }
        }
    }

    private fun defineJsUndefinedIntrinsic(): IrSimpleFunction {
        return irFactory.addFunction(externalPackageFragment) {
            name = Name.identifier("\$undefined")
        }.apply {
            returnType = irBuiltIns.nothingNType
        }
    }

    private fun defineEs6DefaultTypeIntrinsic(): IrSimpleFunction {
        return irFactory.addFunction(externalPackageFragment) {
            name = Name.identifier("DefaultType")
        }.apply {
            returnType = addTypeParameter("T", irBuiltIns.anyType).defaultType
        }
    }

    private fun defineJsBindIntrinsic(): IrSimpleFunction {
        return irFactory.addFunction(externalPackageFragment) {
            name = Name.identifier("\$jsBind\$")
            origin = JsLoweredDeclarationOrigin.JS_INTRINSICS_STUB
            returnType = irBuiltIns.anyNType
        }.apply {
            listOf("receiver", "target").forEach { addValueParameter(it, irBuiltIns.anyType) }
        }
    }

    private fun defineJsSliceIntrinsic(): IrSimpleFunction {
        return irFactory.addFunction(externalPackageFragment) {
            name = Name.identifier("slice")
            origin = JsLoweredDeclarationOrigin.JS_INTRINSICS_STUB
        }.apply {
            val aType = addTypeParameter("A", irBuiltIns.anyType).defaultType
            returnType = aType
            addValueParameter("a", aType)
        }
    }

    private fun defineUnreachableIntrinsic(): IrSimpleFunction {
        return irFactory.addFunction(externalPackageFragment) {
            name = Name.identifier(Namer.UNREACHABLE_NAME)
            origin = JsLoweredDeclarationOrigin.JS_INTRINSICS_STUB
            returnType = irBuiltIns.nothingType
        }
    }

    private fun unOp(name: String, returnType: IrType = irBuiltIns.anyNType) =
        irBuiltIns.run { defineOperator(name, returnType, listOf(anyNType)) }

    private fun unOpBool(name: String) = unOp(name, irBuiltIns.booleanType)
    private fun unOpInt(name: String) = unOp(name, irBuiltIns.intType)

    private fun binOp(name: String, returnType: IrType = irBuiltIns.anyNType) =
        irBuiltIns.run { defineOperator(name, returnType, listOf(anyNType, anyNType)) }

    private fun tripleOp(name: String, returnType: IrType = irBuiltIns.anyNType) =
        irBuiltIns.run { defineOperator(name, returnType, listOf(anyNType, anyNType, anyNType)) }

    private fun binOpBool(name: String) = binOp(name, irBuiltIns.booleanType)
    private fun binOpInt(name: String) = binOp(name, irBuiltIns.intType)
}
