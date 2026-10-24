// RUN_PIPELINE_TILL: CODEGEN
// ISSUE: KT-85011
// OPT_IN: kotlin.experimental.ExperimentalObjCEnum

import kotlin.native.ObjCEnum

@ObjCEnum
enum class GoodEnum {
    @ObjCEnum.EntryName("first") FIRST,
    @ObjCEnum.EntryName(name = "second", swiftName = "secondSwift") SECOND,
    THIRD,
}

@ObjCEnum("NamedEnum", swiftName = "NamedEnumSwift")
enum class GoodNamedEnum {
    @ObjCEnum.EntryName("first") FIRST,
}

enum class EnumWithoutObjCEnum {
    @ObjCEnum.EntryName("first") FIRST,
    SECOND,
}

@ObjCEnum
class NotAnEnum {
    @ObjCEnum.EntryName("property") val property: Int = 0
}

@ObjCEnum
interface NotAnEnumInterface

@ObjCEnum
object NotAnEnumObject

@ObjCEnum
annotation class NotAnEnumAnnotation

@ObjCEnum.EntryName("topLevelProperty")
val topLevelProperty: Int = 0

@ObjCEnum
enum class EnumWithMembers {
    ENTRY;

    @ObjCEnum.EntryName("member") val member: Int = 0
}
