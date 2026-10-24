/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

#include "FinalizerProcessor.hpp"

#include "Common.h"

using namespace kotlin;

namespace {

THREAD_LOCAL_VARIABLE bool currentThreadIsFinalizerThread = false;

} // namespace

void alloc::internal::markCurrentThreadAsFinalizerThread() noexcept {
    currentThreadIsFinalizerThread = true;
}

bool alloc::isFinalizerThread() noexcept {
    return currentThreadIsFinalizerThread;
}
