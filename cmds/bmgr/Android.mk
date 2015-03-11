# Copyright 2007 The Android Open Source Project
#
LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES := $(call all-subdir-java-files)
LOCAL_MODULE := bmgr
include $(BUILD_JAVA_LIBRARY)

bmgr_installed_module := $(LOCAL_INSTALLED_MODULE)

include $(CLEAR_VARS)
LOCAL_MODULE := bmgr_cmd
LOCAL_MODULE_STEM := bmgr
LOCAL_SRC_FILES := bmgr
LOCAL_MODULE_CLASS := EXECUTABLES
include $(BUILD_PREBUILT)

$(bmgr_installed_module): | $(LOCAL_MODULE)
