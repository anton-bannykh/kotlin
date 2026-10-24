/*
 * Copyright 2010-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

#pragma once

#include <cstdint>

namespace kotlin {
namespace internal {

inline constexpr uintptr_t FILE_NOT_INITIALIZED = 0;
inline constexpr uintptr_t FILE_BEING_INITIALIZED = 1;
inline constexpr uintptr_t FILE_INITIALIZED = 2;
inline constexpr uintptr_t FILE_FAILED_TO_INITIALIZE = 3;

// Identifies the current thread in the state of a file being initialized by it: `FILE_BEING_INITIALIZED | marker`.
// It is the address of a thread-local variable, so it is unique among the live threads, and it is aligned so that
// the two lowest bits are free for the state. Unlike a shifted thread id, it doesn't lose any bits (KT-72426).
uintptr_t currentThreadInitializationMarker() noexcept;

} // namespace internal
} // namespace kotlin
