package ai.sealgate.stdiod.mcp

import android.os.Build

/** Production [DeviceInfoSource] backed by [Build]. */
object AndroidDeviceInfo : DeviceInfoSource {
    override val manufacturer: String get() = Build.MANUFACTURER ?: "unknown"
    override val model: String get() = Build.MODEL ?: "unknown"
    override val osVersion: String get() = Build.VERSION.RELEASE ?: "unknown"
    override val sdkInt: Int get() = Build.VERSION.SDK_INT
}
