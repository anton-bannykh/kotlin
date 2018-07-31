// LANGUAGE_VERSION: 1.0
//fun myNotEquals(a: Double?,
// b: Double) = a != b

inline fun foo(init: (Int) -> Unit) {
    var i = 0
    while (i < 10) {
        init(i)
        ++i
    }
}



fun box(): String {
    foo { it * it }

    Array<Int>(10) { it }

//    if (myNotEquals(null, null)) return "fail 1"
//    if (!myNotEquals(null, 0.0)) return "fail 2"
//    if (!myNotEquals(0.0, null)) return "fail 3"
//    if (myNotEquals(0.0, 0.0)) return "fail 4"
//
//    if (!myNotEquals1(null, 0.0)) return "fail 5"
//    if (myNotEquals1(0.0, 0.0)) return "fail 6"
//
//    if (!myNotEquals2(0.0, null)) return "fail 7"
//    if (myNotEquals2(0.0, 0.0)) return "fail 8"
//
//    if (myNotEquals0(0.0, 0.0)) return "fail 9"

    return "OK"
}