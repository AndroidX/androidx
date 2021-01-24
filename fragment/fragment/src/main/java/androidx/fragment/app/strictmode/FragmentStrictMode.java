/*
 * Copyright 2021 The Android Open Source Project
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

package androidx.fragment.app.strictmode;

import android.annotation.SuppressLint;
import android.os.Process;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
import androidx.annotation.VisibleForTesting;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * FragmentStrictMode is a tool which detects things you might be doing by accident and brings
 * them to your attention so you can fix them. Basically, it's a version of
 * {@link android.os.StrictMode} specifically for fragment-related issues.
 *
 * <p>You can decide what should happen when a violation is detected. For example, using {@link
 * Policy.Builder#penaltyLog} you can watch the output of <code>adb logcat</code> while you
 * use your application to see the violations as they happen.
 */
@SuppressLint("SyntheticAccessor")
@RestrictTo(RestrictTo.Scope.LIBRARY) // TODO: Make API public as soon as we have a few checks
public final class FragmentStrictMode {
    private static final String TAG = "FragmentStrictMode";
    private static Policy defaultPolicy = Policy.LAX;

    private enum Flag {
        PENALTY_LOG,
        PENALTY_DEATH
    }

    private FragmentStrictMode() {}

    /**
     * When #{@link Policy.Builder#penaltyListener} is enabled, the listener is called when a
     * violation occurs.
     */
    public interface OnViolationListener {

        /** Called on a VM policy violation. */
        void onViolation(@NonNull Violation violation);
    }

    /**
     * {@link FragmentStrictMode} policy applied to a certain thread.
     *
     * <p>This policy can either be enabled globally using {@link #setDefaultPolicy} or for a
     * specific {@link FragmentManager} using {@link FragmentManager#setStrictModePolicy(Policy)}.
     * The current policy can be retrieved using {@link #getDefaultPolicy} and
     * {@link FragmentManager#getStrictModePolicy} respectively.
     *
     * <p>Note that multiple penalties may be provided and they're run in order from least to most
     * severe (logging before process death, for example). There's currently no mechanism to choose
     * different penalties for different detected actions.
     */
    public static final class Policy {
        private final Set<Flag> flags;
        private final OnViolationListener listener;

        /** The default, lax policy which doesn't catch anything. */
        public static final Policy LAX = new Policy(new HashSet<Flag>(), null);

        private Policy(@NonNull Set<Flag> flags, @Nullable OnViolationListener listener) {
            this.flags = Collections.unmodifiableSet(flags);
            this.listener = listener;
        }

        /**
         * Creates {@link Policy} instances. Methods whose names start with {@code detect} specify
         * what problems we should look for. Methods whose names start with {@code penalty} specify
         * what we should do when we detect a problem.
         *
         * <p>You can call as many {@code detect} and {@code penalty} methods as you like. Currently
         * order is insignificant: all penalties apply to all detected problems.
         */
        public static final class Builder {
            private final Set<Flag> flags;
            private OnViolationListener listener;

            /** Create a Builder that detects nothing and has no violations. */
            public Builder() {
                flags = new HashSet<>();
            }

            /** Log detected violations to the system log. */
            @NonNull
            public Builder penaltyLog() {
                flags.add(Flag.PENALTY_LOG);
                return this;
            }

            /**
             * Crash the whole process on violation. This penalty runs at the end of all enabled
             * penalties so you'll still get to see logging or other violations before the process
             * dies.
             */
            @NonNull
            public Builder penaltyDeath() {
                flags.add(Flag.PENALTY_DEATH);
                return this;
            }

            /** Call #{@link OnViolationListener#onViolation} for every violation. */
            @NonNull
            public Builder penaltyListener(@NonNull OnViolationListener listener) {
                this.listener = listener;
                return this;
            }

            /**
             * Construct the Policy instance.
             *
             * <p>Note: if no penalties are enabled before calling <code>build</code>, {@link
             * #penaltyLog} is implicitly set.
             */
            @NonNull
            public Policy build() {
                if (listener == null && !flags.contains(Flag.PENALTY_DEATH)) {
                    penaltyLog();
                }
                return new Policy(flags, listener);
            }
        }
    }

    /** Returns the current default policy. */
     @NonNull
    public static Policy getDefaultPolicy() {
        return defaultPolicy;
    }

    /**
     * Sets the policy for what actions should be detected, as well as the penalty if such actions
     * occur.
     *
     * @param policy the policy to put into place
     */
    public static void setDefaultPolicy(@NonNull Policy policy) {
        defaultPolicy = policy;
    }

    private static Policy getNearestPolicy(@Nullable Fragment fragment) {
        while (fragment != null) {
            if (fragment.isAdded()) {
                FragmentManager fragmentManager = fragment.getParentFragmentManager();
                if (fragmentManager.getStrictModePolicy() != null) {
                    return fragmentManager.getStrictModePolicy();
                }
            }
            fragment = fragment.getParentFragment();
        }
        return defaultPolicy;
    }

    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    static void onPolicyViolation(@NonNull Fragment fragment, @NonNull Violation violation) {
        onPolicyViolation(getNearestPolicy(fragment), violation);
    }

    private static void onPolicyViolation(@NonNull Policy policy, @NonNull Violation violation) {
        if (policy.flags.contains(Flag.PENALTY_LOG)) {
            Log.d(TAG, "FragmentStrictMode policy violation: ", violation);
        }

        if (policy.listener != null) {
            policy.listener.onViolation(violation);
        }

        if (policy.flags.contains(Flag.PENALTY_DEATH)) {
            Log.e(TAG, "FragmentStrictMode policy violation with PENALTY_DEATH - shutting down.");
            Process.killProcess(Process.myPid());
            System.exit(10);
        }
    }
}
