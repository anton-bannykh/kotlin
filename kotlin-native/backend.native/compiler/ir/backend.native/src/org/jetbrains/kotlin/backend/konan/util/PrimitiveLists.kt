/*
 * Copyright 2010-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

package org.jetbrains.kotlin.backend.konan.util

class IntArrayList : Iterable<Int> {
    private var array = IntArray(3)
    private var length = 0

    val size get() = length

    fun isEmpty() = size == 0

    fun add(x: Int) {
        length++
        ensureCapacity(length)
        set(length - 1, x)
    }

    fun reserve(minSize: Int) {
        if (length >= minSize) return
        length = minSize
        ensureCapacity(minSize)
    }

    operator fun get(index: Int): Int = when {
        index < 0 || index >= length -> throw IndexOutOfBoundsException("Index out of range: $index")

        else -> array[index]
    }

    operator fun set(index: Int, x: Int) = when {
        index < 0 || index >= length -> throw IndexOutOfBoundsException("Index out of range: $index")

        else -> array[index] = x
    }

    override operator fun iterator(): Iterator<Int> = Itr()

    fun toIntArray() = array.copyOf(length)

    private fun ensureCapacity(minCapacity: Int) {
        val oldArray = array
        if (minCapacity > oldArray.size) {
            // Compute in Long: `oldArray.size * 3 / 2` overflows Int for sizes above 715_827_882.
            var newSize = minOf(oldArray.size.toLong() * 3 / 2, MAX_ARRAY_SIZE.toLong()).toInt()
            if (minCapacity > newSize)
                newSize = minCapacity
            array = oldArray.copyOf(newSize)
        }
    }

    private inner class Itr : Iterator<Int> {
        private var index = 0

        override fun hasNext() = index < size

        override fun next() = get(index++)
    }
}

// Some VMs reserve header words in an array, so the largest allocatable array is slightly smaller than Int.MAX_VALUE.
private const val MAX_ARRAY_SIZE = Int.MAX_VALUE - 8

@PublishedApi
internal const val BIG_ARRAY_CHUNK_BITS = 16

@PublishedApi
internal const val BIG_ARRAY_CHUNK_SIZE = 1 shl BIG_ARRAY_CHUNK_BITS

@PublishedApi
internal const val BIG_ARRAY_CHUNK_MASK = BIG_ARRAY_CHUNK_SIZE - 1

/**
 * A growable list of [Long]s which can hold more than [Int.MAX_VALUE] elements.
 *
 * The elements are stored in fixed-size chunks, so growing never copies the existing elements.
 */
class BigLongArrayList {
    @PublishedApi
    internal val chunks = ArrayList<LongArray>()

    var size = 0L
        private set

    fun isEmpty() = size == 0L

    fun add(x: Long) {
        val indexInChunk = (size and BIG_ARRAY_CHUNK_MASK.toLong()).toInt()
        if (indexInChunk == 0)
            chunks.add(LongArray(BIG_ARRAY_CHUNK_SIZE))
        chunks[chunks.size - 1][indexInChunk] = x
        size++
    }

    operator fun get(index: Long): Long {
        if (index < 0 || index >= size) throw IndexOutOfBoundsException("Index out of range: $index")
        return chunks[(index ushr BIG_ARRAY_CHUNK_BITS).toInt()][(index and BIG_ARRAY_CHUNK_MASK.toLong()).toInt()]
    }

    inline fun forEach(block: (Long) -> Unit) {
        var remaining = size
        for (chunk in chunks) {
            val count = minOf(remaining, BIG_ARRAY_CHUNK_SIZE.toLong()).toInt()
            for (i in 0 until count)
                block(chunk[i])
            remaining -= count
        }
    }
}

/**
 * A fixed-size array of [Int]s which can hold more than [Int.MAX_VALUE] elements.
 */
class BigIntArray(val size: Long) {
    init {
        require(size >= 0) { "Negative size: $size" }
    }

    @PublishedApi
    internal val chunks = Array(((size + BIG_ARRAY_CHUNK_MASK) ushr BIG_ARRAY_CHUNK_BITS).toInt()) { chunkIndex ->
        IntArray(minOf(size - (chunkIndex.toLong() shl BIG_ARRAY_CHUNK_BITS), BIG_ARRAY_CHUNK_SIZE.toLong()).toInt())
    }

    operator fun get(index: Long): Int =
            chunks[(index ushr BIG_ARRAY_CHUNK_BITS).toInt()][(index and BIG_ARRAY_CHUNK_MASK.toLong()).toInt()]

    operator fun set(index: Long, value: Int) {
        chunks[(index ushr BIG_ARRAY_CHUNK_BITS).toInt()][(index and BIG_ARRAY_CHUNK_MASK.toLong()).toInt()] = value
    }

    /**
     * Iterates over the elements with indices in `[from, until)`.
     */
    inline fun forEach(from: Long, until: Long, block: (Int) -> Unit) {
        var index = from
        while (index < until) {
            val chunk = chunks[(index ushr BIG_ARRAY_CHUNK_BITS).toInt()]
            var indexInChunk = (index and BIG_ARRAY_CHUNK_MASK.toLong()).toInt()
            val endInChunk = minOf(BIG_ARRAY_CHUNK_SIZE.toLong(), until - index + indexInChunk).toInt()
            index += endInChunk - indexInChunk
            while (indexInChunk < endInChunk)
                block(chunk[indexInChunk++])
        }
    }
}
