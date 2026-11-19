# Add project specific ProGuard rules here.
# Keep source file and line numbers for readable release crash stack traces.
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# Moshi & Retrofit remote DTOs (Wallhaven, Reddit)
-keep class com.example.data.remote.** { *; }

# Local JSON DTOs (LiveWallpaperDto.kt -> LiveWallpaperItem & companion serializer)
-keep class com.example.data.local.LiveWallpaperDto { *; }
-keep class com.example.data.local.LiveWallpaperItem { *; }
-keep class com.example.data.local.LiveWallpaperItem$** { *; }

# Type-safe Navigation Compose @Serializable routes
-keep class com.example.ui.navigation.** { *; }

# Kotlinx Serialization runtime serializers & @SerialName fields
-keep,includedescriptorclasses class com.example.**$$serializer { *; }
-keepclassmembers class com.example.** {
    *** Companion;
}
-keepclasseswithmembers class com.example.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}

# Moshi reflection / generated adapters safety
-keepclasseswithmembers class * {
    @com.squareup.moshi.Json <fields>;
}
-keep class com.squareup.moshi.** { *; }



