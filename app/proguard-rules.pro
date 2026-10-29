# Keep native methods
-keepclassmembers class * {
    native <methods>;
}

# keyboard-cipher: the Rust side writes the fields of IncomingResult through
# JNI, with GetFieldID by name. R8 sees no Kotlin reader of those fields and
# would remove them (-dontobfuscate keeps names, not unused members). The
# failure would be at runtime, inside a crypto operation, not at build time.
-keep class helium314.keyboard.cipher.CipherCore { *; }
-keep class helium314.keyboard.cipher.CipherCore$IncomingResult { *; }

# Keep classes that are used as a parameter type of methods that are also marked as keep
# to preserve changing those methods' signature.
-keep class helium314.keyboard.latin.dictionary.Dictionary
-keep class helium314.keyboard.latin.NgramContext
-keep class helium314.keyboard.latin.makedict.ProbabilityInfo

# after upgrading to gradle 8, stack traces contain "unknown source"
-keepattributes SourceFile,LineNumberTable
-dontobfuscate
