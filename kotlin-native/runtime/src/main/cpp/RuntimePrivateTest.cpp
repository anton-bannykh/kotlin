/*
 * Copyright 2010-2026 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

#include "RuntimePrivate.hpp"

#include <atomic>
#include <set>
#include <vector>

#include "gmock/gmock.h"
#include "gtest/gtest.h"

#include "concurrent/ScopedThread.hpp"

using namespace kotlin;

namespace {

void checkMarker(uintptr_t marker) {
    // The two lowest bits hold the state of the file being initialized.
    EXPECT_THAT(marker & 3, uintptr_t{0});
    EXPECT_THAT(marker, testing::Ne(uintptr_t{0}));
    auto state = internal::FILE_BEING_INITIALIZED | marker;
    EXPECT_THAT(state, testing::Ne(internal::FILE_NOT_INITIALIZED));
    EXPECT_THAT(state, testing::Ne(internal::FILE_INITIALIZED));
    EXPECT_THAT(state, testing::Ne(internal::FILE_FAILED_TO_INITIALIZE));
}

} // namespace

// KT-72426
TEST(RuntimePrivateTest, ThreadInitializationMarker) {
    auto marker = internal::currentThreadInitializationMarker();
    checkMarker(marker);
    EXPECT_THAT(internal::currentThreadInitializationMarker(), marker);
}

TEST(RuntimePrivateTest, ThreadInitializationMarkersOfLiveThreadsAreDistinct) {
    constexpr int kThreadCount = 8;
    std::vector<uintptr_t> markers(kThreadCount);
    std::atomic<int> started = 0;
    std::atomic<bool> canFinish = false;
    std::vector<ScopedThread> threads;
    for (int i = 0; i < kThreadCount; ++i) {
        threads.emplace_back([&, i] {
            markers[i] = internal::currentThreadInitializationMarker();
            ++started;
            // Keep all the threads alive until every marker is collected.
            while (!canFinish.load()) {
                std::this_thread::yield();
            }
        });
    }
    while (started.load() < kThreadCount) {
        std::this_thread::yield();
    }
    canFinish = true;
    for (auto& thread : threads) {
        thread.join();
    }

    std::set<uintptr_t> distinct(markers.begin(), markers.end());
    distinct.insert(internal::currentThreadInitializationMarker());
    EXPECT_THAT(distinct.size(), static_cast<size_t>(kThreadCount + 1));
    for (auto marker : markers) {
        checkMarker(marker);
    }
}
