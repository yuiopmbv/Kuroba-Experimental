/*
 * KurobaEx - *chan browser https://github.com/K1rakishou/Kuroba-Experimental/
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.github.adamantcheese.chan.core.manager;

import android.Manifest;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.StrictMode;
import android.text.Html;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.widget.Button;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;

import com.android.volley.RequestQueue;
import com.android.volley.toolbox.JsonObjectRequest;
import com.github.adamantcheese.chan.BuildConfig;
import com.github.adamantcheese.chan.R;
import com.github.adamantcheese.chan.StartActivity;
import com.github.adamantcheese.chan.core.cache.FileCacheListener;
import com.github.adamantcheese.chan.core.cache.FileCacheV2;
import com.github.adamantcheese.chan.core.cache.downloader.CancelableDownload;
import com.github.adamantcheese.chan.core.net.UpdateApiRequest;
import com.github.adamantcheese.chan.core.net.UpdateApiRequest.UpdateApiResponse;
import com.github.adamantcheese.chan.core.settings.ChanSettings;
import com.github.adamantcheese.chan.core.settings.state.PersistableChanState;
import com.github.adamantcheese.chan.ui.helper.RuntimePermissionsHelper;
import com.github.adamantcheese.chan.ui.settings.SettingNotificationType;
import com.github.adamantcheese.chan.utils.BackgroundUtils;
import com.github.adamantcheese.chan.utils.Logger;
import com.github.k1rakishou.fsaf.FileChooser;
import com.github.k1rakishou.fsaf.FileManager;
import com.github.k1rakishou.fsaf.callback.FileCreateCallback;
import com.github.k1rakishou.fsaf.file.ExternalFile;
import com.github.k1rakishou.fsaf.file.RawFile;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.inject.Inject;

import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import okhttp3.HttpUrl;

import static com.github.adamantcheese.chan.Chan.inject;
import static com.github.adamantcheese.chan.utils.AndroidUtils.getAppFileProvider;
import static com.github.adamantcheese.chan.utils.AndroidUtils.getApplicationLabel;
import static com.github.adamantcheese.chan.utils.AndroidUtils.getString;
import static com.github.adamantcheese.chan.utils.AndroidUtils.openIntent;
import static com.github.adamantcheese.chan.utils.AndroidUtils.showToast;
import static com.github.adamantcheese.chan.utils.BackgroundUtils.runOnMainThread;
import static java.util.concurrent.TimeUnit.DAYS;

/**
 * Calls the update API and downloads and requests installs of APK files.
 * <p>The APK files are downloaded to the public Download directory, and the default APK install
 * screen is launched after downloading.
 */
public class UpdateManager {
    private static final String TAG = "UpdateManager";

    @Inject
    RequestQueue volleyRequestQueue;
    @Inject
    FileCacheV2 fileCacheV2;
    @Inject
    FileManager fileManager;
    @Inject
    SettingsNotificationManager settingsNotificationManager;
    @Inject
    FileChooser fileChooser;

    private ProgressDialog updateDownloadDialog;
    private Context context;

    @Nullable
    private CancelableDownload cancelableDownload;

    public UpdateManager(Context context) {
        inject(this);
        this.context = context;
    }

    /**
     * Runs every time onCreate is called on the StartActivity.
     */
    public void autoUpdateCheck() {
        if (PersistableChanState.previousVersion.get() < BuildConfig.VERSION_CODE
                && PersistableChanState.previousVersion.get() != 0) {
            // Show dialog because release updates are infrequent so it's fine
            Spanned text = Html.fromHtml(
                    "<h3>" + getApplicationLabel() + " was updated to " + BuildConfig.VERSION_NAME + "</h3>");
            final AlertDialog dialog =
                    new AlertDialog.Builder(context).setMessage(text).setPositiveButton(R.string.ok, null).create();
            dialog.setCanceledOnTouchOutside(false);
            dialog.show();

            final Button button = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
            button.setEnabled(false);
            runOnMainThread(() -> {
                dialog.setCanceledOnTouchOutside(true);
                button.setEnabled(true);
            }, 1500);

            // Also set the new app version to not show this message again
            PersistableChanState.previousVersion.set(BuildConfig.VERSION_CODE);
            cancelApkUpdateNotification();

            // Don't process the updater because a dialog is now already showing.
            return;
        }

        if (BuildConfig.DEV_BUILD && !PersistableChanState.previousDevHash.get().equals(BuildConfig.COMMIT_HASH)) {
            // Show toast because dev updates may happen every day (to avoid alert dialog spam)
            showToast(context, getApplicationLabel() + " was updated to the latest commit.");

            PersistableChanState.previousDevHash.set(BuildConfig.COMMIT_HASH);
            cancelApkUpdateNotification();

            return;
        }

        runUpdateApi(false);
    }

    public void manualUpdateCheck() {
        runUpdateApi(true);
    }

    private void runUpdateApi(final boolean manual) {
        if (PersistableChanState.hasNewApkUpdate.get()) {
            // If we noticed that there was an apk update on the previous check - show the
            // notification
            notifyNewApkUpdate();
        }

        if (!manual) {
            long lastUpdateTime = PersistableChanState.updateCheckTime.get();
            long interval = DAYS.toMillis(BuildConfig.UPDATE_DELAY);
            long now = System.currentTimeMillis();
            long delta = (lastUpdateTime + interval) - now;
            if (delta > 0) {
                return;
            } else {
                PersistableChanState.updateCheckTime.set(now);
            }
        }

        Logger.d(TAG, "Calling update API");
        if (!BuildConfig.DEV_BUILD) {
            //region Release build
            volleyRequestQueue.add(new UpdateApiRequest(response -> {
                if (!processUpdateApiResponse(response, manual) && manual && BackgroundUtils.isInForeground()) {
                    new AlertDialog.Builder(context).setTitle(getString(R.string.update_none, getApplicationLabel()))
                            .setPositiveButton(R.string.ok, null)
                            .show();
                }
            }, error -> failedUpdate(manual)));
            //endregion
        } else {
            //region Dev build
            //@formatter:off
            JsonObjectRequest request = new JsonObjectRequest(
                    BuildConfig.DEV_API_ENDPOINT + "/latest_apk_uuid",
                    null,
                    response -> {
                        try {
                            int versionCode = response.getInt("apk_version");
                            String commitHash = response.getString("commit_hash");
                            if (commitHash.equals(BuildConfig.COMMIT_HASH)) {
                                //same version and commit, no update needed
                                if (manual && BackgroundUtils.isInForeground()) {
                                    new AlertDialog.Builder(context)
                                            .setTitle(getString(R.string.update_none,
                                                    getApplicationLabel()
                                            ))
                                            .setPositiveButton(R.string.ok, null)
                                            .show();
                                }

                                cancelApkUpdateNotification();
                            } else {
                                //new version or commit, update
                                Matcher versionCodeStringMatcher = Pattern.compile("(\\d+)(\\d{2})(\\d{2})")
                                        .matcher(String.valueOf(versionCode));
                                if (versionCodeStringMatcher.matches()) {
                                    UpdateApiResponse fauxResponse = new UpdateApiResponse();
                                    fauxResponse.versionCode = versionCode;
                                    fauxResponse.versionCodeString =
                                            "v" + Integer.valueOf(versionCodeStringMatcher.group(1))
                                                    + "." + Integer.valueOf(versionCodeStringMatcher.group(2))
                                                    + "." + Integer.valueOf(versionCodeStringMatcher.group(3))
                                                    + "-" + commitHash.substring(0, 7);
                                    fauxResponse.apkURL = HttpUrl.parse(BuildConfig.DEV_API_ENDPOINT
                                            + "/apk/" + versionCode + "_" + commitHash + ".apk");
                                    fauxResponse.body = SpannableStringBuilder.valueOf("New dev build; see commits!");
                                    processUpdateApiResponse(fauxResponse, manual);
                                } else {
                                    throw new Exception(); // to reuse the failed code below
                                }
                            }
                        } catch (Exception e) {
                            // any exceptions just fail out

                            Logger.e(TAG, "Failed to update", e);
                            failedUpdate(manual);
                        }
                    },
                    response -> failedUpdate(manual)
            );
            volleyRequestQueue.add(request);
            //@formatter:on
            //endregion
        }
    }

    private boolean processUpdateApiResponse(UpdateApiResponse response, boolean manual) {
        if ((response.versionCode > BuildConfig.VERSION_CODE || BuildConfig.DEV_BUILD)
                && BackgroundUtils.isInForeground()) {

            // Do not spam dialogs if this is not the manual update check, use the notifications
            // instead
            if (manual) {
                boolean concat = !response.updateTitle.isEmpty();
                CharSequence updateMessage =
                        concat ? TextUtils.concat(response.updateTitle, "; ", response.body) : response.body;
                AlertDialog dialog = new AlertDialog.Builder(context).setTitle(
                        getApplicationLabel() + " " + response.versionCodeString + " available")
                        .setMessage(updateMessage)
                        .setNegativeButton(R.string.update_later, null)
                        .setPositiveButton(R.string.update_install,
                                (dialog1, which) -> updateInstallRequested(response)
                        )
                        .create();
                dialog.setCanceledOnTouchOutside(false);
                dialog.show();
            }

            // There is an update, show the notification.
            //
            // (In case of the dev build we check whether the apk hashes differ or not beforehand,
            // so if they are the same this method won't even get called. In case of the release
            // build this method will be called in both cases so we do the check in this method)
            notifyNewApkUpdate();
            return true;
        }

        cancelApkUpdateNotification();
        return false;
    }

    private void notifyNewApkUpdate() {
        PersistableChanState.hasNewApkUpdate.set(true);
        settingsNotificationManager.notify(SettingNotificationType.ApkUpdate);
    }

    private void cancelApkUpdateNotification() {
        PersistableChanState.hasNewApkUpdate.set(false);
        settingsNotificationManager.cancel(SettingNotificationType.ApkUpdate);
    }

    private void failedUpdate(boolean manual) {
        Logger.e(TAG, "Failed to process " + (BuildConfig.DEV_BUILD ? "dev" : "stable") + " API call for updating");
        if (manual && BackgroundUtils.isInForeground()) {
            new AlertDialog.Builder(context).setTitle(R.string.update_check_failed)
                    .setPositiveButton(R.string.ok, null)
                    .show();
        }
    }

    /**
     * Install the APK file specified in {@code update}. This methods needs the storage permission.
     *
     * @param response that contains the APK file URL
     */
    public void doUpdate(UpdateApiResponse response) {
        BackgroundUtils.ensureMainThread();

        if (cancelableDownload != null) {
            cancelableDownload.cancel();
            cancelableDownload = null;
        }

        cancelableDownload =
                fileCacheV2.enqueueNormalDownloadFileRequest(response.apkURL.toString(), new FileCacheListener() {
                    @Override
                    public void onProgress(int chunkIndex, long downloaded, long total) {
                        BackgroundUtils.ensureMainThread();

                        if (updateDownloadDialog != null) {
                            updateDownloadDialog.setProgress((int) (updateDownloadDialog.getMax() * (downloaded
                                    / (double) total)));
                        }
                    }

                    @Override
                    public void onSuccess(RawFile file) {
                        BackgroundUtils.ensureMainThread();

                        if (updateDownloadDialog != null) {
                            updateDownloadDialog.setOnDismissListener(null);
                            updateDownloadDialog.dismiss();
                            updateDownloadDialog = null;
                        }

                        String fileName = getApplicationLabel() + "_" + response.versionCodeString + ".apk";
                        suggestCopyingApkToAnotherDirectory(file, fileName, () -> {
                            BackgroundUtils.runOnMainThread(() -> {
                                //install from the filecache rather than downloads, as the
                                // Environment.DIRECTORY_DOWNLOADS may not be "Download"
                                installApk(file);

                                // Run the installApk a little bit later so the activity has time
                                // to switch to the foreground state after we exit the SAF file
                                // chooser
                            }, TimeUnit.SECONDS.toMillis(1));

                            return Unit.INSTANCE;
                        });
                    }

                    @Override
                    public void onNotFound() {
                        onFail(new IOException("Not found"));
                    }

                    @Override
                    public void onFail(Exception exception) {
                        Logger.e(TAG, "Failed to download APK update", exception);

                        if (!BackgroundUtils.isInForeground()) return;
                        BackgroundUtils.ensureMainThread();

                        String description =
                                getString(R.string.update_install_download_failed_description, exception.getMessage());

                        if (updateDownloadDialog != null) {
                            updateDownloadDialog.setOnDismissListener(null);
                            updateDownloadDialog.dismiss();
                            updateDownloadDialog = null;
                        }
                        new AlertDialog.Builder(context).setTitle(R.string.update_install_download_failed)
                                .setMessage(description)
                                .setPositiveButton(R.string.ok, null)
                                .show();
                    }

                    @Override
                    public void onCancel() {
                        if (!BackgroundUtils.isInForeground()) return;
                        BackgroundUtils.ensureMainThread();

                        if (updateDownloadDialog != null) {
                            updateDownloadDialog.setOnDismissListener(null);
                            updateDownloadDialog.dismiss();
                            updateDownloadDialog = null;
                        }
                        new AlertDialog.Builder(context).setTitle(R.string.update_install_download_failed)
                                .setPositiveButton(R.string.ok, null)
                                .show();
                    }
                });
    }

    private void suggestCopyingApkToAnotherDirectory(RawFile file, String fileName, Function0<Unit> onDone) {
        if (!BackgroundUtils.isInForeground() || !ChanSettings.showCopyApkUpdateDialog.get()) {
            onDone.invoke();
            return;
        }

        AlertDialog alertDialog = new AlertDialog.Builder(context).setTitle(R.string.update_manager_copy_apk_title)
                .setMessage(R.string.update_manager_copy_apk_message)
                .setNegativeButton(R.string.no, (dialog, which) -> onDone.invoke())
                .setPositiveButton(R.string.yes, (dialog, which) -> {
                    fileChooser.openCreateFileDialog(fileName, new FileCreateCallback() {
                        @Override
                        public void onResult(@NotNull Uri uri) {
                            onApkFilePathSelected(file, uri);
                            onDone.invoke();
                        }

                        @Override
                        public void onCancel(@NotNull String reason) {
                            showToast(context, reason);
                            onDone.invoke();
                        }
                    });
                })
                .create();

        alertDialog.show();
    }

    private void onApkFilePathSelected(RawFile downloadedFile, Uri uri) {
        ExternalFile newApkFile = fileManager.fromUri(uri);
        if (newApkFile == null) {
            String message = getString(R.string.update_manager_could_not_convert_uri, uri.toString());

            showToast(context, message);
            return;
        }

        if (!fileManager.exists(downloadedFile)) {
            String message = getString(R.string.update_manager_input_file_does_not_exist, downloadedFile.getFullPath());

            showToast(context, message);
            return;
        }

        if (!fileManager.exists(newApkFile)) {
            String message = getString(R.string.update_manager_output_file_does_not_exist, newApkFile.toString());

            showToast(context, message);
            return;
        }

        if (!fileManager.copyFileContents(downloadedFile, newApkFile)) {
            String message = getString(R.string.update_manager_could_not_copy_apk,
                    downloadedFile.getFullPath(),
                    newApkFile.getFullPath()
            );

            showToast(context, message);
            return;
        }

        showToast(context, R.string.update_manager_apk_copied);
    }

    private void installApk(RawFile apk) {
        if (!BackgroundUtils.isInForeground()) return;
        // First open the dialog that asks to retry and calls this method again.
        new AlertDialog.Builder(context).setTitle(R.string.update_retry_title)
                .setMessage(getString(R.string.update_retry, getApplicationLabel()))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.update_retry_button, (dialog, which) -> installApk(apk))
                .show();

        // Then launch the APK install intent.
        Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_GRANT_READ_URI_PERMISSION);

        File apkFile = new File(apk.getFullPath());
        Uri apkURI = FileProvider.getUriForFile(context, getAppFileProvider(), apkFile);

        intent.setDataAndType(apkURI, "application/vnd.android.package-archive");

        // The installer wants a content scheme from android N and up,
        // but I don't feel like implementing a content provider just for this feature.
        // Temporary change the strictmode policy while starting the intent.
        StrictMode.VmPolicy vmPolicy = StrictMode.getVmPolicy();
        StrictMode.setVmPolicy(StrictMode.VmPolicy.LAX);

        openIntent(intent);

        StrictMode.setVmPolicy(vmPolicy);
    }

    private void updateInstallRequested(final UpdateApiResponse response) {
        RuntimePermissionsHelper runtimePermissionsHelper = ((StartActivity) context).getRuntimePermissionsHelper();
        runtimePermissionsHelper.requestPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE, granted -> {
            if (granted) {
                updateDownloadDialog = new ProgressDialog(context);
                updateDownloadDialog.setCanceledOnTouchOutside(true);
                updateDownloadDialog.setOnDismissListener((dialog) -> {
                    showToast(context, "Download will continue in background.");
                    updateDownloadDialog = null;
                });
                updateDownloadDialog.setTitle(R.string.update_install_downloading);
                updateDownloadDialog.setMax(10000);
                updateDownloadDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
                updateDownloadDialog.setProgressNumberFormat("");
                updateDownloadDialog.show();
                doUpdate(response);
            } else {
                runtimePermissionsHelper.showPermissionRequiredDialog(context,
                        getString(R.string.update_storage_permission_required_title),
                        getString(R.string.update_storage_permission_required),
                        () -> updateInstallRequested(response)
                );
            }
        });
    }

    public void onDestroy() {
        if (cancelableDownload != null) {
            cancelableDownload.cancel();
            cancelableDownload = null;
        }
    }
}
