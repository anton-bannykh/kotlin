// RUN_PIPELINE_TILL: CODEGEN
// ISSUE: KT-86193
// Packages `cnames` and `objcnames` are reserved for forward declarations of C and Objective-C types.

// FILE: cnames.kt
package cnames

class A

// FILE: cnamesStructs.kt
package cnames.structs

class B

// FILE: objcnames.kt
package objcnames.classes.nested

class C

// FILE: allowed.kt
package cnamesAndObjcnames.cnames.objcnames

class D
