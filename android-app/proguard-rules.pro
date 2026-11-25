# Foundation's exported JNI symbols include the owning Java class and method names.
-keepclasseswithmembernames,includedescriptorclasses class com.mocharealm.foundation.audio.** {
    native <methods>;
}

# Native code constructs these values by class name and exact constructor signature.
-keep,allowoptimization class com.mocharealm.foundation.audio.TrackMetadata {
    public <init>(java.lang.String, java.lang.String, java.lang.String, java.lang.String, java.lang.String, int, int, int, long);
}
-keep,allowoptimization class com.mocharealm.foundation.audio.PlaybackStatus {
    public <init>(long, long, boolean, float, long);
}

# Preserve useful retrace locations; library consumer rules handle serialization and Ktor.
-keepattributes SourceFile,LineNumberTable
