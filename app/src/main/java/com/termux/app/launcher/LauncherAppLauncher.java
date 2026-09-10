package com.termux.app.launcher;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.os.UserHandle;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.app.launcher.model.LauncherAppEntry;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class LauncherAppLauncher {

    private LauncherAppLauncher() {
    }

    public static boolean launchEntry(@NonNull Context context, @NonNull LauncherAppEntry entry) {
        if (entry.appRef.packageName.startsWith("injected.test")) {
            return false;
        }

        Integer displayId = resolveCurrentDisplayId(context);
        if (displayId == null) {
            return false;
        }
        Bundle launchOptions = createLaunchOptions(displayId);

        if (entry.appRef.clonedProfile && tryStartProfileMainActivity(context, entry, launchOptions)) {
            return true;
        }

        PackageManager packageManager = context.getPackageManager();
        String activityName = entry.appRef.activityName;
        if (!TextUtils.isEmpty(activityName) && activityName.startsWith(".")) {
            activityName = entry.appRef.packageName + activityName;
        }

        Intent explicit = null;
        Intent explicitNoCategory = null;
        if (!TextUtils.isEmpty(activityName)) {
            explicit = new Intent(Intent.ACTION_MAIN);
            explicit.addCategory(Intent.CATEGORY_LAUNCHER);
            explicit.setComponent(new ComponentName(entry.appRef.packageName, activityName));

            explicitNoCategory = new Intent(Intent.ACTION_MAIN);
            explicitNoCategory.setComponent(new ComponentName(entry.appRef.packageName, activityName));
        }

        Intent packageDefault = packageManager.getLaunchIntentForPackage(entry.appRef.packageName);
        ComponentName packageDefaultComponent = packageDefault != null ? packageDefault.getComponent() : null;
        ComponentName explicitComponent = explicit != null ? explicit.getComponent() : null;
        boolean explicitIsPackageDefault = sameComponent(explicitComponent, packageDefaultComponent);

        if (explicitIsPackageDefault && tryStartActivity(context, packageDefault, launchOptions)) {
            return true;
        }
        if (tryStartActivity(context, explicit, launchOptions)) {
            return true;
        }
        if (!explicitIsPackageDefault && tryStartActivity(context, packageDefault, launchOptions)) {
            return true;
        }

        Intent resolveFallback = new Intent(Intent.ACTION_MAIN);
        resolveFallback.addCategory(Intent.CATEGORY_LAUNCHER);
        resolveFallback.setPackage(entry.appRef.packageName);
        ComponentName resolved = resolveFallback.resolveActivity(packageManager);
        if (resolved != null) {
            resolveFallback.setComponent(resolved);
        }

        if (tryStartActivity(context, explicitNoCategory, launchOptions)) {
            return true;
        }
        if (resolved != null && tryStartActivity(context, resolveFallback, launchOptions)) {
            return true;
        }
        if (tryStartMainActivity(context, explicit != null ? explicit.getComponent() : null, launchOptions)) {
            return true;
        }
        if (tryStartMainActivity(context, packageDefault != null ? packageDefault.getComponent() : null, launchOptions)) {
            return true;
        }
        if (tryStartMainActivity(context, resolved, launchOptions)) {
            return true;
        }

        Intent packageMain = new Intent(Intent.ACTION_MAIN);
        packageMain.addCategory(Intent.CATEGORY_LAUNCHER);
        packageMain.setPackage(entry.appRef.packageName);
        List<ResolveInfo> matches = packageManager.queryIntentActivities(packageMain, 0);
        for (ResolveInfo match : matches) {
            if (match == null || match.activityInfo == null) continue;
            String pkg = match.activityInfo.packageName;
            String cls = match.activityInfo.name;
            if (TextUtils.isEmpty(pkg) || TextUtils.isEmpty(cls)) continue;
            Intent fallbackExplicit = new Intent(Intent.ACTION_MAIN);
            fallbackExplicit.addCategory(Intent.CATEGORY_LAUNCHER);
            fallbackExplicit.setComponent(new ComponentName(pkg, cls));
            if (tryStartActivity(context, fallbackExplicit, launchOptions)
                || tryStartMainActivity(context, fallbackExplicit.getComponent(), launchOptions)) {
                return true;
            }
        }

        return false;
    }

    public static boolean tryStartProfileMainActivity(@NonNull Context context,
                                                      @NonNull LauncherAppEntry entry,
                                                      @Nullable Bundle options) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP || entry.appRef.userId < 0) {
            return false;
        }
        Integer displayId = resolveCurrentDisplayId(context);
        if (displayId == null) {
            return false;
        }
        Bundle launchOptions = options != null ? options : createLaunchOptions(displayId);

        String activityName = entry.appRef.activityName;
        if (!TextUtils.isEmpty(activityName) && activityName.startsWith(".")) {
            activityName = entry.appRef.packageName + activityName;
        }
        if (TextUtils.isEmpty(activityName)) {
            return false;
        }
        try {
            LauncherApps launcherApps = (LauncherApps) context.getSystemService(Context.LAUNCHER_APPS_SERVICE);
            if (launcherApps == null) {
                return false;
            }
            launcherApps.startMainActivity(
                new ComponentName(entry.appRef.packageName, activityName),
                userHandleFor(entry.appRef.userId),
                null,
                launchOptions
            );
            return true;
        } catch (Throwable ignored) {
            return tryStartProfileWithAm(entry.appRef.userId, entry.appRef.packageName, activityName, displayId);
        }
    }

    @NonNull
    private static UserHandle userHandleFor(int userId) throws Exception {
        Method method = UserHandle.class.getDeclaredMethod("of", int.class);
        method.setAccessible(true);
        Object value = method.invoke(null, userId);
        if (value instanceof UserHandle) {
            return (UserHandle) value;
        }
        throw new IllegalStateException("UserHandle.of did not return a handle");
    }

    private static boolean tryStartProfileWithAm(int userId,
                                                 @NonNull String packageName,
                                                 @NonNull String activityName,
                                                 int displayId) {
        if (userId < 0) {
            return false;
        }
        try {
            String component = packageName + "/" + activityName;
            java.lang.Process process = new ProcessBuilder("am", "start", "--user",
                String.valueOf(userId), "--display", String.valueOf(displayId), "-n", component)
                .redirectErrorStream(true)
                .start();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            return finished && process.exitValue() == 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean sameComponent(@Nullable ComponentName first, @Nullable ComponentName second) {
        return first != null && second != null && first.equals(second);
    }

    private static boolean tryStartMainActivity(@NonNull Context context,
                                                @Nullable ComponentName componentName,
                                                @NonNull Bundle options) {
        if (componentName == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return false;
        }
        try {
            LauncherApps launcherApps = (LauncherApps) context.getSystemService(Context.LAUNCHER_APPS_SERVICE);
            if (launcherApps == null) {
                return false;
            }
            launcherApps.startMainActivity(componentName, Process.myUserHandle(), null, options);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean tryStartActivity(@NonNull Context context,
                                            @Nullable Intent intent,
                                            @NonNull Bundle options) {
        if (intent == null) return false;
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            if (context instanceof Activity) {
                ((Activity) context).startActivity(intent, options);
            } else {
                context.startActivity(intent, options);
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    @NonNull
    private static Bundle createLaunchOptions(int displayId) {
        ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(displayId);
        return options.toBundle();
    }

    @Nullable
    private static Integer resolveCurrentDisplayId(@NonNull Context context) {
        Activity activity = findActivity(context);
        if (activity == null || activity.getWindowManager().getDefaultDisplay() == null) {
            return null;
        }
        return activity.getWindowManager().getDefaultDisplay().getDisplayId();
    }

    @Nullable
    private static Activity findActivity(@NonNull Context context) {
        Context current = context;
        while (current instanceof ContextWrapper) {
            if (current instanceof Activity) {
                return (Activity) current;
            }
            Context base = ((ContextWrapper) current).getBaseContext();
            if (base == current) {
                break;
            }
            current = base;
        }
        return current instanceof Activity ? (Activity) current : null;
    }
}
