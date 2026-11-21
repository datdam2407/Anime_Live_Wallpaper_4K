# Add project specific ProGuard rules here.
# Keep source file and line numbers for readable release crash stack traces.
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# Moshi & Retrofit remote DTOs (Wallhaven, Reddit, LiveCatalogApi)
-keep class com.example.data.remote.** { *; }

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

# Keep Live Wallpaper Services under R8
-keep class com.example.service.AnimeLiveWallpaperService { *; }
-keep class com.example.service.AnimeLiveWallpaperAlternateService { *; }
