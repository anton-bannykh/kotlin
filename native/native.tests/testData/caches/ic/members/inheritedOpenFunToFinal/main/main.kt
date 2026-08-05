import kotlin.test.*
import test.*

@Test
fun runTest() {
    assertEquals("parent-open", viaChild(Child()))
}
