# Правила R8 для release-сборки.

# Обратные вызовы из нативного кода ищут классы, поля и методы по имени.
-keep class org.videolan.libvlc.** { *; }
-keep class androidx.media3.decoder.ffmpeg.** { *; }

# ass-media (libass) читает поля MatroskaExtractor рефлексией (extractorOutput, subtitleSample),
# и у неё самой JNI.
-keep class io.github.peerless2012.ass.** { *; }
-keep class androidx.media3.extractor.mkv.MatroskaExtractor { *; }

# Стек-трейсы в логах остаются читаемыми.
-keepattributes SourceFile,LineNumberTable

# Open-source app: obfuscation gives nothing, and readable class names keep log tags
# (Timber uses the class name) and stack traces usable. R8 still shrinks and optimises.
-dontobfuscate
