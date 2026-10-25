package lib1

import lib2.bar

fun callBar(): Int = try { bar() } catch (e: Throwable) { -1 }
