/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.util

import kotlin.test.*

class PrimitiveListsTest {
    // Sizes around the chunk boundaries.
    private val sizes = listOf(0, 1, 2, BIG_ARRAY_CHUNK_SIZE - 1, BIG_ARRAY_CHUNK_SIZE, BIG_ARRAY_CHUNK_SIZE + 1,
                               3 * BIG_ARRAY_CHUNK_SIZE - 1, 3 * BIG_ARRAY_CHUNK_SIZE, 3 * BIG_ARRAY_CHUNK_SIZE + 1)

    private fun value(index: Long) = index * 0x9E3779B97F4A7C15uL.toLong()

    @Test
    fun intArrayListGrows() {
        val list = IntArrayList()
        assertTrue(list.isEmpty())
        for (i in 0 until 100_000)
            list.add(i * 31)
        assertEquals(100_000, list.size)
        for (i in 0 until 100_000)
            assertEquals(i * 31, list[i])
        assertContentEquals(IntArray(100_000) { it * 31 }, list.toIntArray())
    }

    @Test
    fun bigLongArrayListAddAndGet() {
        for (size in sizes) {
            val list = BigLongArrayList()
            assertTrue(list.isEmpty())
            for (i in 0L until size)
                list.add(value(i))
            assertEquals(size.toLong(), list.size, "size=$size")
            assertEquals(size == 0, list.isEmpty(), "size=$size")
            for (i in 0L until size)
                assertEquals(value(i), list[i], "size=$size, index=$i")
            assertFailsWith<IndexOutOfBoundsException> { list[-1L] }
            assertFailsWith<IndexOutOfBoundsException> { list[size.toLong()] }
        }
    }

    @Test
    fun bigLongArrayListForEach() {
        for (size in sizes) {
            val list = BigLongArrayList()
            for (i in 0L until size)
                list.add(value(i))
            var index = 0L
            list.forEach {
                assertEquals(value(index), it, "size=$size, index=$index")
                index++
            }
            assertEquals(size.toLong(), index, "size=$size")
        }
    }

    @Test
    fun bigIntArrayGetAndSet() {
        for (size in sizes) {
            val array = BigIntArray(size.toLong())
            assertEquals(size.toLong(), array.size)
            for (i in 0L until size)
                assertEquals(0, array[i], "size=$size, index=$i")
            for (i in 0L until size)
                array[i] = value(i).toInt()
            for (i in 0L until size)
                assertEquals(value(i).toInt(), array[i], "size=$size, index=$i")
        }
        assertFailsWith<IllegalArgumentException> { BigIntArray(-1) }
    }

    @Test
    fun bigIntArrayForEachRange() {
        val size = 3L * BIG_ARRAY_CHUNK_SIZE + 17
        val array = BigIntArray(size)
        for (i in 0L until size)
            array[i] = value(i).toInt()
        val bounds = listOf(0L, 1L, BIG_ARRAY_CHUNK_SIZE - 1L, BIG_ARRAY_CHUNK_SIZE.toLong(), BIG_ARRAY_CHUNK_SIZE + 1L,
                            2L * BIG_ARRAY_CHUNK_SIZE, 3L * BIG_ARRAY_CHUNK_SIZE, size - 1, size)
        for (from in bounds) {
            for (until in bounds) {
                var index = from
                array.forEach(from, until) {
                    assertEquals(value(index).toInt(), it, "from=$from, until=$until, index=$index")
                    index++
                }
                assertEquals(maxOf(from, until), index, "from=$from, until=$until")
            }
        }
    }
}
