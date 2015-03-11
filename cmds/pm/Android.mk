# Copyright 2007 The Android Open Source Project
#
LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES := $(call all-subdir-java-files)
LOCAL_MODULE := pm
include $(BUILD_JAVA_LIBRARY)

pm_installed_module := $(LOCAL_INSTALLED_MODULE)

include $(CLEAR_VARS)
LOCAL_MODULE := pm_cmd
LOCAL_MODULE_STEM := pm
LOCAL_SRC_FILES := pm
LOCAL_MODULE_CLASS := EXECUTABLES
include $(BUILD_PREBUILT)

$(pm_installed_module): | $(LOCAL_MODULE)
