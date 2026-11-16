# Language features end to end

*Skip this page: it is only the index of the feature pages.*

Most pages of this guide describe one part of Kotlin/Native. These pages go the other way: each takes one language feature
and follows it through every part that takes care of it, from the lowerings through code generation to the runtime.

| Page | The feature | Parts involved |
|---|---|---|
| [initialization.md](initialization.md) | Top-level properties, objects, companions and enum entries: when they are initialized | lowerings, a check emitted by code generation, a slow path in the runtime |
| [coroutines.md](coroutines.md) | `suspend` functions | several lowerings that build a state machine, a jump emitted by code generation, the stdlib |
| [exceptions.md](exceptions.md) | `throw`, `try`, `catch`, `finally` | a lowering for `finally`, landing pads from code generation, the runtime and the system unwinder |

Features that are handled by lowerings alone (enums, lambdas, default arguments, inner classes, ...) are described on the
lowering pages: see the [index of lowerings](../compiler/lowerings/README.md).
