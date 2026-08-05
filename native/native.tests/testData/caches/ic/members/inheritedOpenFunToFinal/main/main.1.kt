import kotlin.test.*
import test.*

@Test
fun runTest() {
    assertEquals("parent-final", viaChild(Child()))
}
