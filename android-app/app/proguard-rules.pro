# DXO One Multi-Camera App ProGuard Rules

# Keep USB-related classes
-keep class com.dxoone.multicam.usb.** { *; }

# Keep JSON-RPC message classes for Gson serialization
-keep class com.dxoone.multicam.usb.JsonRpcRequest { *; }
-keep class com.dxoone.multicam.usb.JsonRpcResponse { *; }
-keep class com.dxoone.multicam.usb.JsonRpcError { *; }

# Gson rules
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# Compose
-dontwarn androidx.compose.**
