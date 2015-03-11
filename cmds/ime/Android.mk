# Copyright 2007 The Android Open Source Project
#
LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES := $(call all-subdir-java-files)
LOCAL_MODULE := ime
include $(BUILD_JAVA_LIBRARY)

ime_installed_module := $(LOCAL_INSTALLED_MODULE)

include $(CLEAR_VARS)
LOCAL_MODULE := ime_cmd
LOCAL_MODULE_STEM := ime
LOCAL_SRC_FILES := ime
LOCAL_MODULE_CLASS := EXECUTABLES
include $(BUILD_PREBUILT)

$(ime_installed_module): | $(LOCAL_MODULE)
