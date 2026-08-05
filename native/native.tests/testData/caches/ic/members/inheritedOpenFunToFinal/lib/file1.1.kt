package test

open class Parent {
    fun foo(): String = "parent-final"
}

open class Child : Parent()
