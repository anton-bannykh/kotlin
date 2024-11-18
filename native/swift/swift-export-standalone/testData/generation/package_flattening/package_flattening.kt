// KIND: STANDALONE
// MODULE: main
// SWIFT_EXPORT_CONFIG: packageRoot=org.kotlin.foo
// FILE: baz.kt
package org.kotlin.baz

typealias Integer = Int
// FILE: foo.bar.kt
package org.kotlin.foo.bar

typealias Integer = Int
// FILE: foo.kt
package org.kotlin.foo

typealias Typealias = Int

class Clazz

val Int.x: String
    get() = "test"

fun String.y(): Int = 5

fun function(arg: Int) = arg

var variable: Int = 0

val constant: Int = 0
