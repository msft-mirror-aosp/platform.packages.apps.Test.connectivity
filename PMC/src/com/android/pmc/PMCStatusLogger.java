/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.pmc;

import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;

/**
 * Logging class to log status so PMC can communicate the status back to client
 */
public class PMCStatusLogger {
    private File mFile;
    public static String TAG;
    public static String LOG_DIR = "/mnt/sdcard/Download";

    /**
     * Construtor - check if the file exist. If it is delete and create a new.
     *
     * @param message - message to be logged
     */
    public PMCStatusLogger(String fileName, String tag) {
        TAG = tag;

        try {
            mFile = new File(LOG_DIR + fileName);
            if (mFile.exists()) mFile.delete();
            mFile.createNewFile();
        } catch (IOException e) {
            Log.e(TAG, "Exception creating log file: " + fileName + " " + e);
        }
    }

    /**
     * Function to log status message into log file
     *
     * @param message - message to be logged
     */
    public void logStatus(String message) {
        try {
            FileOutputStream fos = new FileOutputStream(mFile, true);
            BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(fos));
            bw.write(message);
            bw.newLine();
            bw.close();
        } catch (IOException e) {
            Log.e(TAG, "Exception writing log: " + message + " " + e);
        }
    }
}

