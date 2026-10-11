# PdfBox-Android (ticket digital en PDF): carga fuentes y filtros por reflexion y trae
# referencias opcionales a JPEG2000 que no incluimos.
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**
