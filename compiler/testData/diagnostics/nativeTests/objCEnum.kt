// RUN_PIPELINE_TILL: CODEGEN
// IGNORE_FIR_DIAGNOSTICS
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
    <!INAPPLICABLE_OBJC_ENUM_ENTRY_NAME!>@ObjCEnum.EntryName("first")<!> FIRST,
    SECOND,
}

<!INAPPLICABLE_OBJC_ENUM!>@ObjCEnum<!>
class NotAnEnum {
    <!INAPPLICABLE_OBJC_ENUM_ENTRY_NAME!>@ObjCEnum.EntryName("property")<!> val property: Int = 0
}

<!INAPPLICABLE_OBJC_ENUM!>@ObjCEnum<!>
interface NotAnEnumInterface

<!INAPPLICABLE_OBJC_ENUM!>@ObjCEnum<!>
object NotAnEnumObject

<!INAPPLICABLE_OBJC_ENUM!>@ObjCEnum<!>
annotation class NotAnEnumAnnotation

<!INAPPLICABLE_OBJC_ENUM_ENTRY_NAME!>@ObjCEnum.EntryName("topLevelProperty")<!>
val topLevelProperty: Int = 0

@ObjCEnum
enum class EnumWithMembers {
    ENTRY;

    <!INAPPLICABLE_OBJC_ENUM_ENTRY_NAME!>@ObjCEnum.EntryName("member")<!> val member: Int = 0
}
