# Copyright 2008 The Android Open Source Project
#
LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES := $(call all-subdir-java-files)
LOCAL_MODULE := input
include $(BUILD_JAVA_LIBRARY)

input_installed_module := $(LOCAL_INSTALLED_MODULE)

include $(CLEAR_VARS)
LOCAL_MODULE := input_cmd
LOCAL_MODULE_STEM := input
LOCAL_SRC_FILES := input
LOCAL_MODULE_CLASS := EXECUTABLES
include $(BUILD_PREBUILT)

$(input_installed_module): | $(LOCAL_MODULE)
