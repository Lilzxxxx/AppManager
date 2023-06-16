// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.compat;

import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
import static android.content.pm.PackageManager.DONT_KILL_APP;
import static android.content.pm.PackageManager.SYNCHRONOUS;

import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.UserIdInt;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageInstaller;
import android.content.pm.IPackageManager;
import android.content.pm.IPackageManagerN;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.content.pm.PermissionInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.SuspendDialogInfo;
import android.os.Build;
import android.os.DeadObjectException;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandleHidden;
import android.util.AndroidException;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.annotation.RequiresApi;
import androidx.annotation.RequiresPermission;
import androidx.annotation.WorkerThread;
import androidx.core.os.BuildCompat;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import dev.rikka.tools.refine.Refine;
import io.github.muntashirakon.AppManager.ipc.ProxyBinder;
import io.github.muntashirakon.AppManager.self.SelfPermissions;
import io.github.muntashirakon.AppManager.settings.Ops;
import io.github.muntashirakon.AppManager.types.UserPackagePair;
import io.github.muntashirakon.AppManager.users.Users;
import io.github.muntashirakon.AppManager.utils.BroadcastUtils;
import io.github.muntashirakon.AppManager.utils.ContextUtils;
import io.github.muntashirakon.AppManager.utils.PermissionUtils;
import io.github.muntashirakon.AppManager.utils.ThreadUtils;

public final class PackageManagerCompat {
    public static final int MATCH_STATIC_SHARED_AND_SDK_LIBRARIES = 0x04000000;
    public static final int GET_SIGNING_CERTIFICATES;
    public static final int GET_SIGNING_CERTIFICATES_APK;
    public static final int MATCH_DISABLED_COMPONENTS;
    public static final int MATCH_UNINSTALLED_PACKAGES;

    static {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            GET_SIGNING_CERTIFICATES = PackageManager.GET_SIGNING_CERTIFICATES;
        } else {
            //noinspection deprecation
            GET_SIGNING_CERTIFICATES = PackageManager.GET_SIGNATURES;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            GET_SIGNING_CERTIFICATES_APK = PackageManager.GET_SIGNING_CERTIFICATES;
        } else {
            //noinspection deprecation
            GET_SIGNING_CERTIFICATES_APK = PackageManager.GET_SIGNATURES;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            MATCH_DISABLED_COMPONENTS = PackageManager.MATCH_DISABLED_COMPONENTS;
            MATCH_UNINSTALLED_PACKAGES = PackageManager.MATCH_UNINSTALLED_PACKAGES;
        } else {
            //noinspection deprecation
            MATCH_DISABLED_COMPONENTS = PackageManager.GET_DISABLED_COMPONENTS;
            //noinspection deprecation
            MATCH_UNINSTALLED_PACKAGES = PackageManager.GET_UNINSTALLED_PACKAGES;
        }
    }

    @IntDef({
            COMPONENT_ENABLED_STATE_DEFAULT,
            COMPONENT_ENABLED_STATE_ENABLED,
            COMPONENT_ENABLED_STATE_DISABLED,
            COMPONENT_ENABLED_STATE_DISABLED_USER,
            COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface EnabledState {
    }

    @IntDef(flag = true, value = {
            DONT_KILL_APP,
            SYNCHRONOUS
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface EnabledFlags {
    }

    private static final int WORKING_FLAGS = PackageManager.GET_META_DATA | MATCH_UNINSTALLED_PACKAGES
            | MATCH_STATIC_SHARED_AND_SDK_LIBRARIES;

    @SuppressLint("NewApi")
    @SuppressWarnings("deprecation")
    @WorkerThread
    public static List<PackageInfo> getInstalledPackages(int flags, @UserIdInt int userHandle)
            throws RemoteException {
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.M && (flags & ~WORKING_FLAGS) != 0) {
            // Need workaround
            List<ApplicationInfo> applicationInfoList = getInstalledApplications(flags & WORKING_FLAGS, userHandle);
            List<PackageInfo> packageInfoList = new ArrayList<>(applicationInfoList.size());
            for (int i = 0; i < applicationInfoList.size(); ++i) {
                try {
                    packageInfoList.add(getPackageInfo(applicationInfoList.get(i).packageName, flags, userHandle));
                    if (i % 100 == 0) {
                        // Prevent DeadObjectException
                        SystemClock.sleep(300);
                    }
                    if (ThreadUtils.isInterrupted()) {
                        break;
                    }
                } catch (Exception e) {
                    throw new RemoteException(e.getMessage());
                }
            }
            return packageInfoList;
        }
        IPackageManager pm = getPackageManager();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return pm.getInstalledPackages((long) flags, userHandle).getList();
        }
        return pm.getInstalledPackages(flags, userHandle).getList();
    }

    @SuppressLint("NewApi")
    @SuppressWarnings("deprecation")
    @WorkerThread
    public static List<ApplicationInfo> getInstalledApplications(int flags, @UserIdInt int userHandle)
            throws RemoteException {
        IPackageManager pm = getPackageManager();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return pm.getInstalledApplications((long) flags, userHandle).getList();
        }
        return pm.getInstalledApplications(flags, userHandle).getList();
    }

    @NonNull
    public static PackageInfo getPackageInfo(String packageName, int flags, @UserIdInt int userHandle)
            throws RemoteException, PackageManager.NameNotFoundException {
        IPackageManager pm = getPackageManager();
        PackageInfo info = null;
        try {
            info = getPackageInfoInternal(pm, packageName, flags, userHandle);
        } catch (DeadObjectException ignore) {
        }
        if (info == null) {
            // The app might not be loaded properly due parcel size limit, try to load components separately.
            // first check the existence of the package
            int strippedFlags = flags & ~(PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES
                    | PackageManager.GET_PROVIDERS | PackageManager.GET_RECEIVERS | PackageManager.GET_PERMISSIONS);
            info = getPackageInfoInternal(pm, packageName, strippedFlags, userHandle);
            if (info == null) {
                // At this point, it should return package info.
                // Returning null denotes that it failed again even after the major flags have been stripped.
                throw new PackageManager.NameNotFoundException(String.format("Could not retrieve info for package %s with flags 0x%X for user %d",
                        packageName, strippedFlags, userHandle));
            }
            // Load info for major flags
            ActivityInfo[] activities = null;
            if ((flags & PackageManager.GET_ACTIVITIES) != 0) {
                int newFlags = flags & ~(PackageManager.GET_SERVICES | PackageManager.GET_PROVIDERS
                        | PackageManager.GET_RECEIVERS | PackageManager.GET_PERMISSIONS);
                PackageInfo info1 = getPackageInfoInternal(pm, packageName, newFlags, userHandle);
                if (info1 != null) activities = info1.activities;
            }
            ServiceInfo[] services = null;
            if ((flags & PackageManager.GET_SERVICES) != 0) {
                int newFlags = flags & ~(PackageManager.GET_ACTIVITIES | PackageManager.GET_PROVIDERS
                        | PackageManager.GET_RECEIVERS | PackageManager.GET_PERMISSIONS);
                PackageInfo info1 = getPackageInfoInternal(pm, packageName, newFlags, userHandle);
                if (info1 != null) services = info1.services;
            }
            ProviderInfo[] providers = null;
            if ((flags & PackageManager.GET_PROVIDERS) != 0) {
                int newFlags = flags & ~(PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES
                        | PackageManager.GET_RECEIVERS | PackageManager.GET_PERMISSIONS);
                PackageInfo info1 = getPackageInfoInternal(pm, packageName, newFlags, userHandle);
                if (info1 != null) providers = info1.providers;
            }
            ActivityInfo[] receivers = null;
            if ((flags & PackageManager.GET_RECEIVERS) != 0) {
                int newFlags = flags & ~(PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES
                        | PackageManager.GET_PROVIDERS | PackageManager.GET_PERMISSIONS);
                PackageInfo info1 = getPackageInfoInternal(pm, packageName, newFlags, userHandle);
                if (info1 != null) receivers = info1.receivers;
            }
            PermissionInfo[] permissions = null;
            if ((flags & PackageManager.GET_PERMISSIONS) != 0) {
                int newFlags = flags & ~(PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES
                        | PackageManager.GET_PROVIDERS | PackageManager.GET_RECEIVERS);
                PackageInfo info1 = getPackageInfoInternal(pm, packageName, newFlags, userHandle);
                if (info1 != null) permissions = info1.permissions;
            }
            info.activities = activities;
            info.services = services;
            info.providers = providers;
            info.receivers = receivers;
            info.permissions = permissions;
        }
        // Info should never be null here but it's checked anyway.
        return Objects.requireNonNull(info);
    }

    @SuppressLint("NewApi")
    @SuppressWarnings("deprecation")
    @NonNull
    public static ApplicationInfo getApplicationInfo(String packageName, int flags, @UserIdInt int userHandle)
            throws RemoteException, PackageManager.NameNotFoundException {
        IPackageManager pm = getPackageManager();
        ApplicationInfo applicationInfo;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            applicationInfo = pm.getApplicationInfo(packageName, (long) flags, userHandle);
        } else applicationInfo = pm.getApplicationInfo(packageName, flags, userHandle);
        if (applicationInfo == null) {
            throw new PackageManager.NameNotFoundException("Package " + packageName + " not found.");
        }
        return applicationInfo;
    }

    @Nullable
    public static String getInstallerPackageName(@NonNull String packageName, @UserIdInt int userId)
            throws RemoteException {
        return getInstallSourceInfo(packageName, userId).getInstallingPackageName();
    }

    @OptIn(markerClass = BuildCompat.PrereleaseSdkCheck.class)
    @SuppressWarnings("deprecation")
    @NonNull
    public static InstallSourceInfoCompat getInstallSourceInfo(@NonNull String packageName, @UserIdInt int userId)
            throws RemoteException {
        IPackageManager pm = getPackageManager();
        if (BuildCompat.isAtLeastU()) {
            return new InstallSourceInfoCompat(pm.getInstallSourceInfo(packageName, userId));
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return new InstallSourceInfoCompat(pm.getInstallSourceInfo(packageName));
        }
        String installerPackageName = null;
        try {
            installerPackageName = getPackageManager().getInstallerPackageName(packageName);
        } catch (IllegalArgumentException e) {
            String message = e.getMessage();
            if (message != null && message.startsWith("Unknown package:")) {
                throw new RemoteException(message);
            }
        }
        return new InstallSourceInfoCompat(installerPackageName);
    }

    @SuppressLint("NewApi")
    @SuppressWarnings("deprecation")
    @NonNull
    public static List<ResolveInfo> queryIntentActivities(@NonNull Context context, @NonNull Intent intent, int flags,
                                                          @UserIdInt int userId)
            throws RemoteException {
        IPackageManager pm = getPackageManager();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            IPackageManagerN pmN = Refine.unsafeCast(pm);
            ParceledListSlice<ResolveInfo> resolveInfoList;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                resolveInfoList = pmN.queryIntentActivities(intent,
                        intent.resolveTypeIfNeeded(context.getContentResolver()), (long) flags, userId);
            } else {
                resolveInfoList = pmN.queryIntentActivities(intent,
                        intent.resolveTypeIfNeeded(context.getContentResolver()), flags, userId);
            }
            return resolveInfoList.getList();
        } else {
            return pm.queryIntentActivities(intent, intent.resolveTypeIfNeeded(context.getContentResolver()), flags,
                    userId);
        }
    }

    @EnabledState
    public static int getComponentEnabledSetting(ComponentName componentName,
                                                 @UserIdInt int userId)
            throws RemoteException {
        return getPackageManager().getComponentEnabledSetting(componentName, userId);
    }

    @RequiresPermission(value = Manifest.permission.CHANGE_COMPONENT_ENABLED_STATE)
    public static void setComponentEnabledSetting(ComponentName componentName,
                                                  @EnabledState int newState,
                                                  @EnabledFlags int flags,
                                                  @UserIdInt int userId)
            throws RemoteException {
        getPackageManager().setComponentEnabledSetting(componentName, newState, flags, userId);
        if (userId != UserHandleHidden.myUserId()) {
            BroadcastUtils.sendPackageAltered(ContextUtils.getContext(), new String[]{componentName.getPackageName()});
        }
    }

    @RequiresPermission(value = Manifest.permission.CHANGE_COMPONENT_ENABLED_STATE)
    public static void setApplicationEnabledSetting(String packageName, @EnabledState int newState,
                                                    @EnabledFlags int flags, @UserIdInt int userId)
            throws RemoteException {
        getPackageManager().setApplicationEnabledSetting(packageName, newState, flags, userId, null);
        if (userId != UserHandleHidden.myUserId()) {
            BroadcastUtils.sendPackageAltered(ContextUtils.getContext(), new String[]{packageName});
        }
    }

    public static int getApplicationEnabledSetting(String packageName, @UserIdInt int userId) throws RemoteException {
        return getPackageManager().getApplicationEnabledSetting(packageName, userId);
    }

    @SuppressWarnings("deprecation")
    @RequiresApi(Build.VERSION_CODES.N)
    @RequiresPermission(allOf = {"android.permission.SUSPEND_APPS", ManifestCompat.permission.MANAGE_USERS})
    public static void suspendPackages(String[] packageNames, @UserIdInt int userId, boolean suspend) throws RemoteException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getPackageManager().setPackagesSuspendedAsUser(packageNames, suspend, null, null, (SuspendDialogInfo) null, ActivityManagerCompat.SHELL_PACKAGE_NAME, userId);
        } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.P) {
            getPackageManager().setPackagesSuspendedAsUser(packageNames, suspend, null, null, (String) null, ActivityManagerCompat.SHELL_PACKAGE_NAME, userId);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            getPackageManager().setPackagesSuspendedAsUser(packageNames, suspend, userId);
        }
        if (userId != UserHandleHidden.myUserId()) {
            BroadcastUtils.sendPackageAltered(ContextUtils.getContext(), packageNames);
        }
    }

    public static boolean isPackageSuspended(String packageName, @UserIdInt int userId) throws RemoteException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return getPackageManager().isPackageSuspendedForUser(packageName, userId);
        }
        return false;
    }

    @RequiresPermission(ManifestCompat.permission.MANAGE_USERS)
    public static void hidePackage(String packageName, @UserIdInt int userId, boolean hide) throws RemoteException {
        if (SelfPermissions.checkSelfOrRemotePermission(ManifestCompat.permission.MANAGE_USERS)) {
            boolean hidden = getPackageManager().setApplicationHiddenSettingAsUser(packageName, hide, userId);
            if (userId != UserHandleHidden.myUserId()) {
                if (hidden) {
                    if (hide) {
                        BroadcastUtils.sendPackageRemoved(ContextUtils.getContext(), new String[]{packageName});
                    } else BroadcastUtils.sendPackageAdded(ContextUtils.getContext(), new String[]{packageName});
                }
            }
        } else throw new RemoteException("Missing required permission: android.permission.MANAGE_USERS.");
    }

    public static boolean isPackageHidden(String packageName, @UserIdInt int userId) throws RemoteException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                // Find using private flags
                ApplicationInfo info = getApplicationInfo(packageName,
                        PackageManagerCompat.MATCH_STATIC_SHARED_AND_SDK_LIBRARIES, userId);
                return (ApplicationInfoCompat.getPrivateFlags(info) & ApplicationInfoCompat.PRIVATE_FLAG_HIDDEN) != 0;
            } catch (PackageManager.NameNotFoundException ignore) {
            }
        }
        if (SelfPermissions.checkSelfOrRemotePermission(ManifestCompat.permission.MANAGE_USERS)) {
            return getPackageManager().getApplicationHiddenSettingAsUser(packageName, userId);
        }
        // Otherwise, there is no way to detect if the package is hidden
        return false;
    }

    @SuppressWarnings("deprecation")
    public static int installExistingPackageAsUser(@NonNull String packageName, @UserIdInt int userId, int installFlags,
                                                   int installReason, @Nullable List<String> whiteListedPermissions)
            throws RemoteException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return getPackageManager().installExistingPackageAsUser(packageName, userId, installFlags, installReason, whiteListedPermissions);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return getPackageManager().installExistingPackageAsUser(packageName, userId, installFlags, installReason);
        }
        return getPackageManager().installExistingPackageAsUser(packageName, userId);
    }

    public static void clearApplicationUserData(@NonNull UserPackagePair pair) throws AndroidException {
        IPackageManager pm = getPackageManager();
        ClearDataObserver obs = new ClearDataObserver();
        pm.clearApplicationUserData(pair.getPackageName(), obs, pair.getUserHandle());
        //noinspection SynchronizationOnLocalVariableOrMethodParameter
        synchronized (obs) {
            while (!obs.isCompleted()) {
                try {
                    obs.wait(60_000);
                } catch (InterruptedException ignore) {
                }
            }
        }
        if (!obs.isSuccessful()) {
            throw new AndroidException("Could not clear data of package " + pair);
        }
        if (pair.getUserHandle() != UserHandleHidden.myUserId()) {
            BroadcastUtils.sendPackageAltered(ContextUtils.getContext(), new String[]{pair.getPackageName()});
        }
    }

    public static boolean clearApplicationUserData(@NonNull String packageName, @UserIdInt int userId) {
        try {
            clearApplicationUserData(new UserPackagePair(packageName, userId));
            return true;
        } catch (AndroidException | SecurityException e) {
            e.printStackTrace();
            return false;
        }
    }

    public static void deleteApplicationCacheFilesAsUser(UserPackagePair pair) throws AndroidException {
        IPackageManager pm = getPackageManager();
        ClearDataObserver obs = new ClearDataObserver();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            pm.deleteApplicationCacheFilesAsUser(pair.getPackageName(), pair.getUserHandle(), obs);
        } else pm.deleteApplicationCacheFiles(pair.getPackageName(), obs);
        //noinspection SynchronizationOnLocalVariableOrMethodParameter
        synchronized (obs) {
            while (!obs.isCompleted()) {
                try {
                    obs.wait(60_000);
                } catch (InterruptedException ignore) {
                }
            }
        }
        if (!obs.isSuccessful()) {
            throw new AndroidException("Could not clear cache of package " + pair);
        }
        if (pair.getUserHandle() != UserHandleHidden.myUserId()) {
            BroadcastUtils.sendPackageAltered(ContextUtils.getContext(), new String[]{pair.getPackageName()});
        }
    }

    public static boolean deleteApplicationCacheFilesAsUser(String packageName, int userId) {
        try {
            deleteApplicationCacheFilesAsUser(new UserPackagePair(packageName, userId));
            return true;
        } catch (AndroidException | SecurityException e) {
            e.printStackTrace();
            return false;
        }
    }

    @RequiresPermission(ManifestCompat.permission.FORCE_STOP_PACKAGES)
    public static void forceStopPackage(String packageName, int userId) throws RemoteException {
        ActivityManagerCompat.getActivityManager().forceStopPackage(packageName, userId);
        if (userId != UserHandleHidden.myUserId()) {
            BroadcastUtils.sendPackageAltered(ContextUtils.getContext(), new String[]{packageName});
        }
    }

    @NonNull
    public static IPackageInstaller getPackageInstaller() throws RemoteException {
        return IPackageInstaller.Stub.asInterface(new ProxyBinder(getPackageManager().getPackageInstaller().asBinder()));
    }

    @SuppressWarnings("deprecation")
    public static void freeStorageAndNotify(@Nullable String volumeUuid,
                                            long freeStorageSize,
                                            @StorageManagerCompat.AllocateFlags int storageFlags)
            throws RemoteException {
        IPackageManager pm;
        ClearDataObserver obs = new ClearDataObserver();
        if (PermissionUtils.hasSelfPermission(Manifest.permission.CLEAR_APP_CACHE)) {
            // Clear cache using unprivileged method: Mostly applicable for Android Lollipop
            pm = getUnprivilegedPackageManager();
        } else { // Use privileged mode
            if (Ops.isAdb() && Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                // IPackageManager#freeStorageAndNotify cannot be used before Android Oreo because Shell does not have
                // the permission android.permission.CLEAR_APP_CACHE
                for (int userId : Users.getUsersIds()) {
                    for (ApplicationInfo info : getInstalledApplications(MATCH_STATIC_SHARED_AND_SDK_LIBRARIES, userId)) {
                        deleteApplicationCacheFilesAsUser(info.packageName, userId);
                    }
                }
                return;
            }
            pm = getPackageManager();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            pm.freeStorageAndNotify(volumeUuid, freeStorageSize, storageFlags, obs);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pm.freeStorageAndNotify(volumeUuid, freeStorageSize, obs);
        } else {
            pm.freeStorageAndNotify(freeStorageSize, obs);
        }
        //noinspection SynchronizationOnLocalVariableOrMethodParameter
        synchronized (obs) {
            while (!obs.isCompleted()) {
                try {
                    obs.wait(60_000);
                } catch (InterruptedException ignore) {
                }
            }
        }
    }

    @SuppressLint("NewApi")
    @SuppressWarnings("deprecation")
    private static PackageInfo getPackageInfoInternal(IPackageManager pm, String packageName, int flags, @UserIdInt int userId)
            throws RemoteException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return pm.getPackageInfo(packageName, (long) flags, userId);
        }
        return pm.getPackageInfo(packageName, flags, userId);
    }

    public static IPackageManager getPackageManager() {
        return IPackageManager.Stub.asInterface(ProxyBinder.getService("package"));
    }

    public static IPackageManager getUnprivilegedPackageManager() {
        return IPackageManager.Stub.asInterface(ProxyBinder.getUnprivilegedService("package"));
    }
}
