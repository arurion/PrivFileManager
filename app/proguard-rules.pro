# Shizuku / JGit はリフレクションを利用するため難読化対象から除外
-keep class rikka.shizuku.** { *; }
-keep class org.eclipse.jgit.** { *; }
-dontwarn org.eclipse.jgit.**
