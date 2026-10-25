// TARGET_BACKEND: NATIVE
// FILECHECK_STAGE: LinkBitcodeDependencies

// CHECK-DAG: {{define|declare}}{{.*}} void @EnterFrame({{[^\)]*}}) #[[ENTER_FRAME_ATTRS:[0-9]+]]

// CHECK-DAG: define ptr @"kfun:#box(){}kotlin.String"(ptr %0) #[[BOX_ATTRS:[0-9]+]]
fun box(): String = "OK"

// CHECK-DAG: attributes #[[ENTER_FRAME_ATTRS]] = {{{.*}}"target-cpu"="[[RT_CPU:[^"]+]]" "target-features"="[[RT_FEATURES:[^"]+]]"

// CHECK-DAG: attributes #[[BOX_ATTRS]] = {{{.*}}"target-cpu"="[[RT_CPU]]" "target-features"="{{[^"]*}}[[RT_FEATURES]]{{[^"]*}}"
