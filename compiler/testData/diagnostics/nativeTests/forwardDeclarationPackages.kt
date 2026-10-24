// RUN_PIPELINE_TILL: CODEGEN
// IGNORE_FIR_DIAGNOSTICS
// ISSUE: KT-86193
// Packages `cnames` and `objcnames` are reserved for forward declarations of C and Objective-C types.

// FILE: cnames.kt
<!FORWARD_DECLARATIONS_PACKAGE!>package cnames<!>

class A

// FILE: cnamesStructs.kt
<!FORWARD_DECLARATIONS_PACKAGE!>package cnames.structs<!>

class B

// FILE: objcnames.kt
<!FORWARD_DECLARATIONS_PACKAGE!>package objcnames.classes.nested<!>

class C

// FILE: allowed.kt
package cnamesAndObjcnames.cnames.objcnames

class D
