/*
 * Copyright 2019 The Android Open Source Project
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

package androidx.room;

import android.content.Context;
import android.database.sqlite.SQLiteException;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.room.util.CopyLock;
import androidx.room.util.DBUtil;
import androidx.room.util.FileUtil;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.sqlite.db.SupportSQLiteOpenHelper;
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.util.concurrent.Callable;

/**
 * An open helper that will copy & open a pre-populated database if it doesn't exists in internal
 * storage.
 */
class SQLiteCopyOpenHelper implements SupportSQLiteOpenHelper {

    @NonNull
    private final Context mContext;
    @Nullable
    private final String mCopyFromAssetPath;
    @Nullable
    private final File mCopyFromFile;
    @Nullable
    private final Callable<InputStream> mCopyFromInputStream;
    private final int mDatabaseVersion;
    @NonNull
    private final SupportSQLiteOpenHelper mDelegate;
    @Nullable
    private DatabaseConfiguration mDatabaseConfiguration;

    private boolean mVerified;

    SQLiteCopyOpenHelper(
            @NonNull Context context,
            @Nullable String copyFromAssetPath,
            @Nullable File copyFromFile,
            @Nullable Callable<InputStream> copyFromInputStream,
            int databaseVersion,
            @NonNull SupportSQLiteOpenHelper supportSQLiteOpenHelper) {
        mContext = context;
        mCopyFromAssetPath = copyFromAssetPath;
        mCopyFromFile = copyFromFile;
        mCopyFromInputStream = copyFromInputStream;
        mDatabaseVersion = databaseVersion;
        mDelegate = supportSQLiteOpenHelper;
    }

    @Override
    public String getDatabaseName() {
        return mDelegate.getDatabaseName();
    }

    @Override
    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN)
    public void setWriteAheadLoggingEnabled(boolean enabled) {
        mDelegate.setWriteAheadLoggingEnabled(enabled);
    }

    @Override
    public synchronized SupportSQLiteDatabase getWritableDatabase() {
        if (!mVerified) {
            verifyDatabaseFile();
            mVerified = true;
            callOnOpenPrepackagedDatabaseCallbacks(true);
        }
        return mDelegate.getWritableDatabase();
    }

    @Override
    public synchronized SupportSQLiteDatabase getReadableDatabase() {
        if (!mVerified) {
            verifyDatabaseFile();
            mVerified = true;
            callOnOpenPrepackagedDatabaseCallbacks(false);
        }
        return mDelegate.getReadableDatabase();
    }

    @Override
    public synchronized void close() {
        mDelegate.close();
        mVerified = false;
    }

    // Can't be constructor param because the factory is needed by the database builder which in
    // turn is the one that actually builds the configuration.
    void setDatabaseConfiguration(@Nullable DatabaseConfiguration databaseConfiguration) {
        mDatabaseConfiguration = databaseConfiguration;
    }

    private void verifyDatabaseFile() {
        String databaseName = getDatabaseName();
        File databaseFile = mContext.getDatabasePath(databaseName);
        boolean processLevelLock = mDatabaseConfiguration == null
                || mDatabaseConfiguration.multiInstanceInvalidation;
        CopyLock copyLock = new CopyLock(databaseName, mContext.getFilesDir(), processLevelLock);
        try {
            // Acquire a copy lock, this lock works across threads and processes, preventing
            // concurrent copy attempts from occurring.
            copyLock.lock();

            if (!databaseFile.exists()) {
                try {
                    // No database file found, copy and be done.
                    copyDatabaseFile(databaseFile);
                    return;
                } catch (IOException e) {
                    throw new RuntimeException("Unable to copy database file.", e);
                }
            }

            if (mDatabaseConfiguration == null) {
                return;
            }

            // A database file is present, check if we need to re-copy it.
            Integer currentVersion = readDatabaseVersion(databaseFile);

            if (currentVersion == null || currentVersion == mDatabaseVersion) {
                return;
            }

            if (mDatabaseConfiguration.isMigrationRequired(currentVersion, mDatabaseVersion)) {
                // From the current version to the desired version a migration is required, i.e.
                // we won't be performing a copy destructive migration.
                return;
            }

            if (mContext.deleteDatabase(databaseName)) {
                try {
                    copyDatabaseFile(databaseFile);
                } catch (IOException e) {
                    // We are more forgiving copying a database on a destructive migration since
                    // there is already a database file that can be opened.
                    Log.w(Room.LOG_TAG, "Unable to copy database file.", e);
                }
            } else {
                Log.w(Room.LOG_TAG, "Failed to delete database file ("
                        + databaseName + ") for a copy destructive migration.");
            }
        } finally {
            copyLock.unlock();
        }
    }

    private void copyDatabaseFile(File destinationFile) throws IOException {
        ReadableByteChannel input;
        if (mCopyFromAssetPath != null) {
            input = Channels.newChannel(mContext.getAssets().open(mCopyFromAssetPath));
        } else if (mCopyFromFile != null) {
            input = new FileInputStream(mCopyFromFile).getChannel();
        } else if (mCopyFromInputStream != null) {
            final InputStream inputStream;
            try {
                inputStream = mCopyFromInputStream.call();
            } catch (Exception e) {
                throw new IOException("inputStreamCallable exception on call", e);
            }
            input = Channels.newChannel(inputStream);
        } else {
            throw new IllegalStateException("copyFromAssetPath, copyFromFile and "
                    + "copyFromInputStream are all null!");
        }

        // An intermediate file is used so that we never end up with a half-copied database file
        // in the internal directory.
        File intermediateFile = File.createTempFile(
                "room-copy-helper", ".tmp", mContext.getCacheDir());
        intermediateFile.deleteOnExit();
        FileChannel output = new FileOutputStream(intermediateFile).getChannel();
        FileUtil.copy(input, output);

        File parent = destinationFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Failed to create directories for "
                    + destinationFile.getAbsolutePath());
        }

        if (!intermediateFile.renameTo(destinationFile)) {
            throw new IOException("Failed to move intermediate file ("
                    + intermediateFile.getAbsolutePath() + ") to destination ("
                    + destinationFile.getAbsolutePath() + ").");
        }
    }

    private Integer readDatabaseVersion(File databaseFile) {
        try {
            return DBUtil.readVersion(databaseFile);
        } catch (IOException e) {
            Log.w(Room.LOG_TAG, "Unable to read database version.", e);
            return null;
        }
    }

    private void callOnOpenPrepackagedDatabaseCallbacks(boolean writable) {
        if(mDatabaseConfiguration.prepackagedCallback != null) {
            // Temporarily open database using FrameworkSQLiteOpenHelper
            SupportSQLiteOpenHelper helper = createFrameworkOpenHelper();
            SupportSQLiteDatabase db = null;
            try {
                db = writable ? helper.getWritableDatabase() : helper.getReadableDatabase();
                // Invoke callbacks passing db as a param
                mDatabaseConfiguration.prepackagedCallback.onOpenPrepackagedDatabase(db);
            } catch (SQLiteException e) {
                throw new RuntimeException("Unable to invoke pre-packaged callback.", e);
            } finally {
                // Close the db and let Room re-open it through a normal path
                if(db != null) {
                    try {
                        db.close();
                    } catch (IOException e) {
                        Log.w(Room.LOG_TAG, "Unable to close the database after invoking "
                                + "pre-packaged callback", e);
                    }
                }
            }
        }
    }

    private SupportSQLiteOpenHelper createFrameworkOpenHelper() {
        String databaseName = getDatabaseName();
        File databaseFile = mContext.getDatabasePath(databaseName);
        Integer version = readDatabaseVersion(databaseFile);
        if(version == null) {
            throw new RuntimeException("Unable to invoke pre-packaged callback.");
        }

        FrameworkSQLiteOpenHelperFactory factory = new FrameworkSQLiteOpenHelperFactory();
        Configuration configuration = Configuration.builder(mContext)
                .name(databaseName)
                .callback(new Callback(version){
                    @Override
                    public void onCreate(@NonNull SupportSQLiteDatabase db) {
                    }

                    @Override
                    public void onUpgrade(@NonNull SupportSQLiteDatabase db, int oldVersion,
                            int newVersion) {
                    }
                })
                .build();
        return factory.create(configuration);
    }
}
