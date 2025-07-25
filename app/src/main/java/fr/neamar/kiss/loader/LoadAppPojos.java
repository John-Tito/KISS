package fr.neamar.kiss.loader;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.UserManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.TagsHandler;
import fr.neamar.kiss.db.AppRecord;
import fr.neamar.kiss.db.DBHelper;
import fr.neamar.kiss.pojo.AppPojo;
import fr.neamar.kiss.utils.PackageManagerUtils;
import fr.neamar.kiss.utils.UserHandle;
import name.pilgr.pipinyin.PiPinyin;

public class LoadAppPojos extends LoadPojos<AppPojo> {

    private static final String TAG = LoadAppPojos.class.getSimpleName();
    private final TagsHandler tagsHandler;
    private final PiPinyin piPinyin;

    public LoadAppPojos(Context context) {
        super(context, "app://");
        piPinyin = new PiPinyin(context);

        tagsHandler = KissApplication.getApplication(context).getDataHandler().getTagsHandler();
    }


    public static boolean containsHanScript(String s) {
        for (int i = 0; i < s.length(); ) {
            int codepoint = s.codePointAt(i);
            i += Character.charCount(codepoint);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                if (Character.UnicodeScript.of(codepoint) == Character.UnicodeScript.HAN) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    protected List<AppPojo> doInBackground(Void... params) {
        long start = System.currentTimeMillis();

        List<AppPojo> apps = new ArrayList<>();

        Context ctx = context.get();
        if (ctx == null) {
            return apps;
        }

        Set<String> excludedAppList = KissApplication.getApplication(ctx).getDataHandler().getExcluded();
        Set<String> excludedFromHistoryAppList = KissApplication.getApplication(ctx).getDataHandler().getExcludedFromHistory();
        Set<String> excludedShortcutsAppList = KissApplication.getApplication(ctx).getDataHandler().getExcludedShortcutApps();

        if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            UserManager manager = (UserManager) ctx.getSystemService(Context.USER_SERVICE);
            LauncherApps launcherApps = (LauncherApps) ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE);

            // Handle multi-profile support introduced in Android 5 (#542)
            for (android.os.UserHandle profile : manager.getUserProfiles()) {
                UserHandle user = new UserHandle(manager.getSerialNumberForUser(profile), profile);
                for (LauncherActivityInfo activityInfo : launcherApps.getActivityList(null, profile)) {
                    if (isCancelled()) {
                        break;
                    }
                    ApplicationInfo appInfo = activityInfo.getApplicationInfo();
                    boolean disabled = PackageManagerUtils.isAppSuspended(appInfo) || isQuietModeEnabled(manager, profile);
                    final AppPojo app = createPojo(user, appInfo.packageName, activityInfo.getName(), activityInfo.getLabel(), disabled, excludedAppList, excludedFromHistoryAppList, excludedShortcutsAppList);

                    // 添加中文转拼音标签功能
                    String tags = tagsHandler.getTags(app.id);
                    if(containsHanScript(app.getName().toString())){
                        tags = tags.concat(" "+piPinyin.toPinyin(app.getName().toString(),"").toLowerCase());
                    }
                    app.setTags(tags);

                    apps.add(app);
                }
            }
        } else {
            PackageManager manager = ctx.getPackageManager();

            Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
            mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);

            for (ResolveInfo info : manager.queryIntentActivities(mainIntent, 0)) {
                if (isCancelled()) {
                    break;
                }
                ApplicationInfo appInfo = info.activityInfo.applicationInfo;
                boolean disabled = PackageManagerUtils.isAppSuspended(appInfo);
                final AppPojo app = createPojo(new UserHandle(), appInfo.packageName, info.activityInfo.name, info.loadLabel(manager), disabled, excludedAppList, excludedFromHistoryAppList, excludedShortcutsAppList);

                // 添加中文转拼音标签功能（兼容旧版本）
                String tags = tagsHandler.getTags(app.id);
                if(containsHanScript(app.getName().toString())){
                    tags = tags.concat(" "+piPinyin.toPinyin(app.getName().toString(),"").toLowerCase());
                }
                app.setTags(tags);

                apps.add(app);
            }
        }

        Map<String, AppRecord> customApps = DBHelper.getCustomAppData(ctx);
        for (AppPojo app : apps) {
            AppRecord customApp = customApps.get(app.getComponentName());
            if (customApp == null)
                continue;
            if (customApp.hasCustomName())
                app.setName(customApp.name);
            if (customApp.hasCustomIcon())
                app.setCustomIconId(customApp.dbId);
        }

        long end = System.currentTimeMillis();
        Log.i(TAG, (end - start) + " milliseconds to list apps");

        return apps;
    }

    private boolean isQuietModeEnabled(UserManager manager, android.os.UserHandle profile) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return manager.isQuietModeEnabled(profile);
        }
        return false;
    }

    private AppPojo createPojo(UserHandle userHandle, String packageName, String activityName, CharSequence label, boolean disabled, Set<String> excludedAppList, Set<String> excludedFromHistoryAppList, Set<String> excludedShortcutsAppList) {
        String id = userHandle.addUserSuffixToString(pojoScheme + packageName + "/" + activityName, '/');

        boolean isExcluded = excludedAppList.contains(AppPojo.getComponentName(packageName, activityName, userHandle));
        boolean isExcludedFromHistory = excludedFromHistoryAppList.contains(id);
        boolean isExcludedShortcuts = excludedShortcutsAppList.contains(packageName);

        AppPojo app = new AppPojo(id, packageName, activityName, userHandle, isExcluded, isExcludedFromHistory, isExcludedShortcuts, disabled);

        app.setName(label.toString());

        // 注意：这里移除了 tagsHandler.getTags(app.id) 的调用
        // 因为我们现在在 doInBackground 中手动设置标签
        return app;
    }
}