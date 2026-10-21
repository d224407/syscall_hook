# Keep line numbers so release stack traces stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# JNI entry point (legacy-mode supercall)
-keep class app.sclviewer.Supercall { native <methods>; }
