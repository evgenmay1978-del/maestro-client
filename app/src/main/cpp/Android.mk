# JNI launcher for the Xray core (see xray_spawn.c): a native fork/exec is the
# only way to hand the VpnService tun fd to a spawned binary — Android's
# ProcessBuilder closes every fd >= 3 across exec.
LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := xrayspawn
LOCAL_SRC_FILES := xray_spawn.c
LOCAL_CFLAGS := -O2 -Wall -Werror
include $(BUILD_SHARED_LIBRARY)
