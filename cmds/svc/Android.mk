# Copyright 2007 The Android Open Source Project
#
LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES := $(call all-subdir-java-files)
LOCAL_MODULE := svc
include $(BUILD_JAVA_LIBRARY)

svc_installed_module := $(LOCAL_INSTALLED_MODULE)

include $(CLEAR_VARS)
LOCAL_MODULE := svc_cmd
LOCAL_MODULE_STEM := svc
LOCAL_SRC_FILES := svc
LOCAL_MODULE_CLASS := EXECUTABLES
include $(BUILD_PREBUILT)

$(svc_installed_module): | $(LOCAL_MODULE)
