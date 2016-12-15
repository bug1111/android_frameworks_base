/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.server.pm;

import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.IPinItemRequest;
import android.content.pm.LauncherApps;
import android.content.pm.LauncherApps.PinItemRequest;
import android.content.pm.ShortcutInfo;
import android.os.Bundle;
import android.os.UserHandle;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;

/**
 * Handles {@link android.content.pm.ShortcutManager#requestPinShortcut} related tasks.
 */
class ShortcutRequestPinProcessor {
    private static final String TAG = ShortcutService.TAG;
    private static final boolean DEBUG = ShortcutService.DEBUG;

    private final ShortcutService mService;
    private final Object mLock;

    /**
     * Internal for {@link android.content.pm.LauncherApps.PinItemRequest} which receives callbacks.
     */
    private static class PinShortcutRequestInner extends IPinItemRequest.Stub {
        private final ShortcutRequestPinProcessor mProcessor;
        public final ShortcutInfo shortcut;
        private final IntentSender mResultIntent;

        public final String launcherPackage;
        public final int launcherUserId;
        public final boolean preExisting;

        @GuardedBy("this")
        private boolean mAccepted;

        private PinShortcutRequestInner(ShortcutRequestPinProcessor processor,
                ShortcutInfo shortcut, IntentSender resultIntent,
                String launcherPackage, int launcherUserId, boolean preExisting) {
            mProcessor = processor;
            this.shortcut = shortcut;
            mResultIntent = resultIntent;
            this.launcherPackage = launcherPackage;
            this.launcherUserId = launcherUserId;
            this.preExisting = preExisting;
        }

        @Override
        public boolean isValid() {
            // TODO When an app calls requestPinShortcut(), all pending requests should be
            // invalidated.
            synchronized (this) {
                return !mAccepted;
            }
        }

        /**
         * Called when the launcher calls {@link PinItemRequest#accept}.
         */
        @Override
        public boolean accept(Bundle options) {
            // Make sure the options are unparcellable by the FW. (e.g. not containing unknown
            // classes.)
            if (options != null) {
                try {
                    options.size();
                } catch (RuntimeException e) {
                    throw new IllegalArgumentException("options cannot be unparceled", e);
                }
            }
            synchronized (this) {
                if (mAccepted) {
                    throw new IllegalStateException("accept() called already");
                }
                mAccepted = true;
            }
            if (DEBUG) {
                Slog.d(TAG, "Launcher accepted shortcut. ID=" + shortcut.getId()
                        + " package=" + shortcut.getPackage()
                        + " options=" + options);
            }

            // Pin it and send the result intent.
            if (mProcessor.directPinShortcut(this)) {
                mProcessor.sendResultIntent(mResultIntent);
                return true;
            } else {
                return false;
            }
        }
    }

    public ShortcutRequestPinProcessor(ShortcutService service, Object lock) {
        mService = service;
        mLock = lock;
    }

    public boolean isRequestPinnedShortcutSupported(int callingUserId) {
        return getRequestPinShortcutConfirmationActivity(callingUserId) != null;
    }

    /**
     * Handle {@link android.content.pm.ShortcutManager#requestPinShortcut)}.
     */
    public boolean requestPinShortcutLocked(ShortcutInfo inShortcut, IntentSender resultIntent) {

        // First, make sure the launcher supports it.

        // Find the confirmation activity in the default launcher.
        final Pair<ComponentName, Integer> confirmActivity =
                getRequestPinShortcutConfirmationActivity(inShortcut.getUserId());

        // If the launcher doesn't support it, just return a rejected result and finish.
        if (confirmActivity == null) {
            Log.w(TAG, "Launcher doesn't support requestPinnedShortcut(). Shortcut not created.");
            return false;
        }

        final ComponentName launcherComponent = confirmActivity.first;
        final String launcherPackage = confirmActivity.first.getPackageName();
        final int launcherUserId = confirmActivity.second;

        // Make sure the launcher user is unlocked. (it's always the parent profile, so should
        // really be unlocked here though.)
        mService.throwIfUserLockedL(launcherUserId);

        // Next, validate the incoming shortcut, etc.

        final ShortcutPackage ps = mService.getPackageShortcutsForPublisherLocked(
                inShortcut.getPackage(), inShortcut.getUserId());

        final ShortcutInfo existing = ps.findShortcutById(inShortcut.getId());
        final boolean existsAlready = existing != null;

        if (DEBUG) {
            Slog.d(TAG, "requestPinnedShortcut package=" + inShortcut.getPackage()
                    + " existsAlready=" + existsAlready
                    + " shortcut=" + inShortcut.toInsecureString());
        }

        // This is the shortcut that'll be sent to the launcher.
        final ShortcutInfo shortcutToSend;

        if (existsAlready) {
            validateExistingShortcut(existing);

            // See if it's already pinned.
            if (mService.getLauncherShortcutsLocked(
                    launcherPackage, existing.getUserId(), launcherUserId).hasPinned(existing)) {
                Log.i(TAG, "Launcher's already pinning shortcut " + existing.getId()
                        + " for package " + existing.getPackage());
                sendResultIntent(resultIntent);
                return true;
            }

            // Pass a clone, not the original.
            // Note this will remove the intent and icons.
            shortcutToSend = existing.clone(ShortcutInfo.CLONE_REMOVE_FOR_LAUNCHER);
            shortcutToSend.clearFlags(ShortcutInfo.FLAG_PINNED);
        } else {
            // It doesn't exist, so it must have all mandatory fields.
            mService.validateShortcutForPinRequest(inShortcut);

            // Initialize the ShortcutInfo for pending approval.
            inShortcut.resolveResourceStrings(mService.injectGetResourcesForApplicationAsUser(
                    inShortcut.getPackage(), inShortcut.getUserId()));
            if (DEBUG) {
                Slog.d(TAG, "resolved shortcut=" + inShortcut.toInsecureString());
            }
            // TODO Remove the intent here -- don't pass shortcut intents to the launcher.
            shortcutToSend = inShortcut;
        }

        // Create a request object.
        final PinShortcutRequestInner inner =
                new PinShortcutRequestInner(this, shortcutToSend, resultIntent,
                        launcherPackage, launcherUserId, existsAlready);

        final PinItemRequest outer = new PinItemRequest(PinItemRequest.REQUEST_TYPE_SHORTCUT,
                shortcutToSend, inner);

        return startRequestConfirmActivity(launcherComponent, launcherUserId, outer);
    }

    private void validateExistingShortcut(ShortcutInfo shortcutInfo) {
        // Make sure it's enabled.
        // (Because we can't always force enable it automatically as it may be a stale
        // manifest shortcut.)
        Preconditions.checkState(shortcutInfo.isEnabled(),
                "Shortcut ID=" + shortcutInfo + " already exists but disabled.");

    }

    private boolean startRequestConfirmActivity(ComponentName activity, int launcherUserId,
            PinItemRequest request) {
        // Start the activity.
        final Intent confirmIntent = new Intent(LauncherApps.ACTION_CONFIRM_PIN_ITEM);
        confirmIntent.setComponent(activity);
        confirmIntent.putExtra(LauncherApps.EXTRA_PIN_ITEM_REQUEST, request);
        confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);

        final long token = mService.injectClearCallingIdentity();
        try {
            mService.mContext.startActivityAsUser(
                    confirmIntent, UserHandle.of(launcherUserId));
        } catch (RuntimeException e) { // ActivityNotFoundException, etc.
            Log.e(TAG, "Unable to start activity " + activity, e);
            return false;
        } finally {
            mService.injectRestoreCallingIdentity(token);
        }
        return true;
    }

    /**
     * Find the activity that handles {@link LauncherApps#ACTION_CONFIRM_PIN_ITEM} in the
     * default launcher.
     */
    @Nullable
    @VisibleForTesting
    Pair<ComponentName, Integer> getRequestPinShortcutConfirmationActivity(
            int callingUserId) {
        // Find the default launcher.
        final int launcherUserId = mService.getParentOrSelfUserId(callingUserId);
        final ComponentName defaultLauncher = mService.getDefaultLauncher(launcherUserId);

        if (defaultLauncher == null) {
            Log.e(TAG, "Default launcher not found.");
            return null;
        }
        final ComponentName activity = mService.injectGetPinConfirmationActivity(
                defaultLauncher.getPackageName(), launcherUserId);
        return (activity == null) ? null : Pair.create(activity, launcherUserId);
    }

    public void sendResultIntent(@Nullable IntentSender intent) {
        if (DEBUG) {
            Slog.d(TAG, "Sending result intent.");
        }
        mService.injectSendIntentSender(intent);
    }

    /**
     * The last step of the "request pin shortcut" flow.  Called when the launcher accepted a
     * request.
     */
    public boolean directPinShortcut(PinShortcutRequestInner request) {

        final ShortcutInfo original = request.shortcut;
        final int appUserId = original.getUserId();
        final String appPackageName = original.getPackage();
        final int launcherUserId = request.launcherUserId;
        final String launcherPackage = request.launcherPackage;
        final String shortcutId = original.getId();

        synchronized (mLock) {
            if (!(mService.isUserUnlockedL(appUserId)
                    && mService.isUserUnlockedL(request.launcherUserId))) {
                Log.w(TAG, "User is locked now.");
                return false;
            }

            final ShortcutPackage ps = mService.getPackageShortcutsForPublisherLocked(
                    appPackageName, appUserId);
            final ShortcutInfo current = ps.findShortcutById(shortcutId);

            // The shortcut might have been changed, so we need to do the same validation again.
            try {
                if (current == null) {
                    // It doesn't exist, so it must have all necessary fields.
                    mService.validateShortcutForPinRequest(request.shortcut);
                } else {
                    validateExistingShortcut(current);
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "Unable to pin shortcut: " + e.getMessage());
                return false;
            }

            // If the shortcut doesn't exist, need to create it.
            // First, create it as a dynamic shortcut.
            if (current == null) {
                if (DEBUG) {
                    Slog.d(TAG, "Temporarily adding " + shortcutId + " as dynamic");
                }
                // Add as a dynamic shortcut.
                if (original.getActivity() == null) {
                    original.setActivity(mService.getDummyMainActivity(appPackageName));
                }
                ps.addOrUpdateDynamicShortcut(original);
            }

            // Pin the shortcut.
            if (DEBUG) {
                Slog.d(TAG, "Pinning " + shortcutId);
            }

            final ShortcutLauncher launcher = mService.getLauncherShortcutsLocked(
                    launcherPackage, appUserId, launcherUserId);
            launcher.attemptToRestoreIfNeededAndSave();
            launcher.addPinnedShortcut(appPackageName, appUserId, shortcutId);

            if (current == null) {
                if (DEBUG) {
                    Slog.d(TAG, "Removing " + shortcutId + " as dynamic");
                }
                ps.deleteDynamicWithId(shortcutId);
            }

            ps.adjustRanks(); // Shouldn't be needed, but just in case.
        }

        mService.verifyStates();
        mService.packageShortcutsChanged(appPackageName, appUserId);

        return true;
    }
}