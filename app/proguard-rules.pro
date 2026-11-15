# Room entities and DAOs are kept by Room's generated code.
# Glance action callbacks are instantiated reflectively by class name.
-keep class * implements androidx.glance.appwidget.action.ActionCallback { <init>(); }
# Workers are instantiated reflectively by WorkManager.
-keep class * extends androidx.work.ListenableWorker { <init>(android.content.Context, androidx.work.WorkerParameters); }
