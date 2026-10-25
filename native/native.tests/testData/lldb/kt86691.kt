// KIND: STANDALONE_LLDB
// FREE_COMPILER_ARGS: -opt-in=kotlinx.cinterop.ExperimentalForeignApi
// INPUT_DATA_FILE: kt86691.in
// OUTPUT_DATA_FILE: kt86691.out

import kotlinx.cinterop.*

fun main() {
    val pointer = nativeHeap.alloc<IntVar>().ptr
    val point = Point(1, 2)
    nativeHeap.free(pointer)
    println(point)
}

data class Point(val x: Int, val y: Int)
