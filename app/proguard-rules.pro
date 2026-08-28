# Add project specific ProGuard rules here.
# By default the flags in this file are appended to flags specified
# in $ANDROID_HOME/tools/proguard/proguard-android-optimize.txt

# Keep the foreground service entry point.
-keep class ai.sealgate.stdiod.TunnelService { *; }
