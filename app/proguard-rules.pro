# R8 rules for release (isMinifyEnabled + isShrinkResources are on).
# The AGP defaults (proguard-android-optimize.txt) plus the AndroidX/Compose
# consumer rules cover the framework; the app itself uses no reflection on its
# own classes (org.json + DataStore are accessed directly, not reflectively).

# usb-serial-for-android resolves concrete driver classes through its prober
# at runtime; without this R8 can strip the driver for an attached dongle.
-keep class com.hoho.android.usbserial.** { *; }

# usb-serial pulls in optional classes it references but does not require.
-dontwarn com.hoho.android.usbserial.**
