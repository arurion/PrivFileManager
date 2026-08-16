# Shizukuはリフレクションを利用するため難読化対象から除外
-keep class rikka.shizuku.** { *; }

# commons-compress / junrar はJava標準にない古いAPI(java.awt等)への
# 任意依存を含む場合があるため、存在しないクラスの警告は無視する
-dontwarn org.apache.commons.compress.**
-dontwarn com.github.junrar.**
-dontwarn org.tukaani.xz.**
