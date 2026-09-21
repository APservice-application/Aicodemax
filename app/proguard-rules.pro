# v0: minification is off; keep serialization models just in case.
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class kotlinx.serialization.** { *; }
