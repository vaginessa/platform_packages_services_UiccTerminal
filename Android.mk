LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES := $(call all-java-files-under, src)

LOCAL_AIDL_INCLUDES := packages/apps/SmartCardService/openmobileapi/src/org/simalliance/openmobileapi/service

LOCAL_PACKAGE_NAME := UiccTerminal
LOCAL_CERTIFICATE := platform

LOCAL_JAVA_LIBRARIES := framework org.simalliance.openmobileapi

LOCAL_PROGUARD_ENABLED := disabled
LOCAL_DEX_PREOPT := false

include $(BUILD_PACKAGE)
