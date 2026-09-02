# The Machine — R8 rules.
#
# The app has no reflection-heavy surface today; these rules exist for the pieces
# that are inherently reflective or crossed by JNI.

# --- Native bridges: JNI looks methods up by name, R8 cannot see the call. ---
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# --- kotlinx.serialization: generated serializers are found reflectively. ---
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- Keep line numbers so stack traces from sideloaded builds stay readable. ---
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
