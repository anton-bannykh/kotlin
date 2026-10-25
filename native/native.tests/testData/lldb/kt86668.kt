// KIND: STANDALONE_LLDB
// WITH_PLATFORM_LIBS
// DISABLE_NATIVE: isAppleTarget=false
// INPUT_DATA_FILE: kt86668.in
// OUTPUT_DATA_FILE: kt86668.out

// MODULE: cinterop
// FILE: kt86668.def
language = Objective-C
headers = kt86668.h

// FILE: kt86668.h
#import <Foundation/Foundation.h>

@interface Greeter : NSObject
- (instancetype)initWithName:(NSString *)name;
@end

// FILE: kt86668.m
#import "kt86668.h"

@implementation Greeter {
    NSString *_name;
}
- (instancetype)initWithName:(NSString *)name {
    if (self = [super init]) _name = name;
    return self;
}
- (NSString *)description {
    return [NSString stringWithFormat:@"Greeter(%@)", _name];
}
@end

// MODULE: main(cinterop)
// FILE: main.kt
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

import kt86668.*

fun main() {
    val greeter = Greeter("Name")
    println(greeter)
}
