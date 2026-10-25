package b

import a.foo

fun test(): Int = try { foo() } catch (e: Throwable) { -1 }
