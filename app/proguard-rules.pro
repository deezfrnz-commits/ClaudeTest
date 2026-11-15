# Methods called from the document WebView.
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
-dontwarn org.json.**
