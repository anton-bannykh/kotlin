/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package kotlin.js

external fun <T> Array(size: Int): Array<T>

internal fun <T> newArray(size: Int, initValue: T) = fillArrayVal(Array<T>(size), initValue)

internal fun <T> fillArrayVal(array: Array<T>, initValue: T): Array<T> {
    for (i in 0..array.size - 1) {
        array[i] = initValue
    }
    return array
}

internal inline fun <T> arrayWithFun(size: Int, init: (Int) -> T) = fillArrayFun(Array<T>(size), init)

internal inline fun <T> fillArrayFun(array: dynamic, init: (Int) -> T): Array<T> {
    val result = array.unsafeCast<Array<T>>()
    var i = 0
    while (i != result.size) {
        result[i] = init(i)
        ++i
    }
    return result
}

internal fun booleanArray(size: Int): BooleanArray {
    val result: dynamic = Array<Boolean>(size)
    result.`$type$` = "BooleanArray"
    return result.unsafeCast<BooleanArray>()
}

internal fun charArray(size: Int): CharArray {
    val result: dynamic = Array<Char>(size)
    result.`$type$` = "CharArray"
    return result.unsafeCast<CharArray>()
}

internal fun longArray(size: Int): LongArray {
    val result: dynamic = Array<Long>(size)
    result.`$type$` = "LongArray"
    return result.unsafeCast<LongArray>()
}

internal fun <T> arrayIterator(array: Array<T>) = object : Iterator<T> {
    var index = 0
    override fun hasNext() = index != array.size
    override fun next() = if (index != array.size) array[index++] else throw NoSuchElementException("$index")
}

internal fun booleanArrayIterator(array: BooleanArray) = object : BooleanIterator() {
    var index = 0
    override fun hasNext() = index != array.size
    override fun nextBoolean() = if (index != array.size) array[index++] else throw NoSuchElementException("$index")
}

internal fun byteArrayIterator(array: ByteArray) = object : ByteIterator() {
    var index = 0
    override fun hasNext() = index != array.size
    override fun nextByte() = if (index != array.size) array[index++] else throw NoSuchElementException("$index")
}

internal fun shortArrayIterator(array: ShortArray) = object : ShortIterator() {
    var index = 0
    override fun hasNext() = index != array.size
    override fun nextShort() = if (index != array.size) array[index++] else throw NoSuchElementException("$index")
}

internal fun charArrayIterator(array: CharArray) = object : CharIterator() {
    var index = 0
    override fun hasNext() = index != array.size
    override fun nextChar() = if (index != array.size) array[index++] else throw NoSuchElementException("$index")
}

internal fun intArrayIterator(array: IntArray) = object : IntIterator() {
    var index = 0
    override fun hasNext() = index != array.size
    override fun nextInt() = if (index != array.size) array[index++] else throw NoSuchElementException("$index")
}

internal fun floatArrayIterator(array: FloatArray) = object : FloatIterator() {
    var index = 0
    override fun hasNext() = index != array.size
    override fun nextFloat() = if (index != array.size) array[index++] else throw NoSuchElementException("$index")
}

internal fun doubleArrayIterator(array: DoubleArray) = object : DoubleIterator() {
    var index = 0
    override fun hasNext() = index != array.size
    override fun nextDouble() = if (index != array.size) array[index++] else throw NoSuchElementException("$index")
}

internal fun longArrayIterator(array: LongArray) = object : LongIterator() {
    var index = 0
    override fun hasNext() = index != array.size
    override fun nextLong() = if (index != array.size) array[index++] else throw NoSuchElementException("$index")
}


/** Concat regular Array's and TypedArray's into an Array.
 */
internal fun <T> arrayConcat(args: Array<T>): T {
    val len = args.size
    val typed = js("Array(len)").unsafeCast<Array<T>>()
    for (i in 0..args.size - 1) {
        val arr = args[i]
        if (arr !is Array<*>) {
            typed[i] = js("[]").slice.call(arr)
        } else {
            typed[i] = arr
        }
    }
    return js("[]").concat.apply(js("[]"), typed);
}

/** Concat primitive arrays. Main use: prepare vararg arguments.
 */
@Suppress("UNUSED_PARAMETER")
internal fun <T> primitiveArrayConcat(args: Array<T>): T {
    var size = 0
    for (i in 0..args.size - 1) {
        size += args[i].asDynamic().length as Int
    }
    val a = args[0]
    val result = js("new a.constructor(size)")
//    kotlin.copyArrayType(a, result)
    size = 0
    for (i in 0..args.size - 1) {
        val arr = args[i].asDynamic()
        for (j in 0 until arr.length) {
            result[size++] = arr[j]
        }
    }
    return result
}

// Intrinsic. Do we need a declaration?
internal external fun <T> arrayLiteral(vararg a: T): Array<T>
