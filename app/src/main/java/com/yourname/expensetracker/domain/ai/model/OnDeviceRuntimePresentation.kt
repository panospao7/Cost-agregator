package com.yourname.expensetracker.domain.ai.model

fun OnDeviceModelStatus.toRuntimeStatusMessage(capabilityLabel: String): String? {
    return when (this) {
        OnDeviceModelStatus.AVAILABLE -> null
        OnDeviceModelStatus.NOT_INSTALLED -> "On-device $capabilityLabel is available but the model is not installed yet."
        OnDeviceModelStatus.DOWNLOADING -> "On-device $capabilityLabel model download is in progress."
        OnDeviceModelStatus.UNAVAILABLE -> "On-device $capabilityLabel is unavailable on this phone right now. This usually means Android AICore / Gemini Nano is missing, not provisioned yet, or unsupported by the device vendor."
        OnDeviceModelStatus.UNSUPPORTED_DEVICE -> "This device does not support on-device $capabilityLabel."
        OnDeviceModelStatus.UNSUPPORTED_ANDROID_VERSION -> "On-device $capabilityLabel requires Android 14 or newer."
        OnDeviceModelStatus.DISABLED_BY_POLICY -> "On-device $capabilityLabel is disabled by policy."
        OnDeviceModelStatus.UNKNOWN -> "On-device $capabilityLabel availability is unknown right now."
    }
}
