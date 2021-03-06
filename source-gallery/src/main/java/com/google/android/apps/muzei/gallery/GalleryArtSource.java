/*
 * Copyright 2014 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.apps.muzei.gallery;

import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.location.Address;
import android.location.Geocoder;
import android.media.ExifInterface;
import android.net.Uri;
import android.provider.MediaStore;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Log;

import com.google.android.apps.muzei.api.Artwork;
import com.google.android.apps.muzei.api.MuzeiArtSource;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

public class GalleryArtSource extends MuzeiArtSource {
    private static final String TAG = "GalleryArtSource";
    private static final String SOURCE_NAME = "GalleryArtSource";

    static final String ACTION_GALLERY_CHOSEN_URIS_CHANGED
            = "com.google.android.apps.muzei.gallery.gallery_chosen_uris_changed";

    public static final String PREF_ROTATE_INTERVAL_MIN = "rotate_interval_min";

    public static final int DEFAULT_ROTATE_INTERVAL_MIN = 60 * 6;

    public static final String ACTION_PUBLISH_NEXT_GALLERY_ITEM
            = "com.google.android.apps.muzei.gallery.action.PUBLISH_NEXT_GALLERY_ITEM";
    public static final String ACTION_ADD_CHOSEN_URIS
            = "com.google.android.apps.muzei.gallery.action.ADD_CHOSEN_URIS";
    public static final String ACTION_REMOVE_CHOSEN_URIS
            = "com.google.android.apps.muzei.gallery.action.REMOVE_CHOSEN_URIS";
    public static final String EXTRA_URIS
            = "com.google.android.apps.muzei.gallery.extra.URIS";
    public static final String EXTRA_ALLOW_PUBLISH
            = "com.google.android.apps.muzei.gallery.extra.ALLOW_PUBLISH";
    public static final String ACTION_SCHEDULE_NEXT
            = "com.google.android.apps.muzei.gallery.action.SCHEDULE_NEXT";
    public static final String EXTRA_FORCE_URI
            = "com.google.android.apps.muzei.gallery.extra.FORCE_URI";

    public static final int CURRENT_METADATA_CACHE_VERSION = 1;
    private static SimpleDateFormat sExifDateFormat = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss");

    private static File sImageStorageRoot;

    private static final Set<String> sOmitCountryCodes = new HashSet<>();
    static {
        sOmitCountryCodes.add("US");
    }

    private Geocoder mGeocoder;
    private GalleryStore mStore;

    public GalleryArtSource() {
        super(SOURCE_NAME);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mStore = GalleryStore.getInstance(this);
        mGeocoder = new Geocoder(this);
        ensureStorageRoot(this);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent == null) {
            super.onHandleIntent(intent);
            return;
        }

        String action = intent.getAction();
        if (ACTION_PUBLISH_NEXT_GALLERY_ITEM.equals(action)) {
            Uri forceUri = null;
            if (intent.hasExtra(EXTRA_FORCE_URI)) {
                forceUri = intent.getParcelableExtra(EXTRA_FORCE_URI);
            }

            publishNextArtwork(forceUri);
            return;

        } else if (ACTION_ADD_CHOSEN_URIS.equals(action)) {
            handleAddChosenUris(intent.<Uri>getParcelableArrayListExtra(EXTRA_URIS),
                    intent.getBooleanExtra(EXTRA_ALLOW_PUBLISH, true));
            return;

        } else if (ACTION_REMOVE_CHOSEN_URIS.equals(action)) {
            handleRemoveChosenUris(intent.<Uri>getParcelableArrayListExtra(EXTRA_URIS));
            return;

        } else if (ACTION_SCHEDULE_NEXT.equals(action)) {
            scheduleNext();
            return;
        }

        super.onHandleIntent(intent);
    }

    private void writeUriToFile(Uri uri, File destFile) throws IOException {
        InputStream in = null;
        OutputStream out = null;
        try {
            in = getContentResolver().openInputStream(uri);
            if (in == null) {
                return;
            }
            out = new FileOutputStream(destFile);
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) > 0) {
                out.write(buffer, 0, bytesRead);
            }
            out.flush();
        } catch (SecurityException e) {
            throw new IOException("Unable to read Uri: " + uri, e);
        } finally {
            if (in != null) {
                in.close();
            }
            if (out != null) {
                out.close();
            }
        }
    }

    private void handleAddChosenUris(ArrayList<Uri> addUris, boolean allowPublishNewArtwork) {
        // Filter out duplicates
        Set<Uri> current = new HashSet<>(mStore.getChosenUris());
        addUris.removeAll(current);

        for (Uri uri : addUris) {
            // Download each file
            try {
                writeUriToFile(uri, getStoredFileForUri(this, uri));
            } catch (IOException e) {
                Log.e(TAG, "Error downloading gallery image.", e);
                return;
            }
        }

        List<Uri> chosenUris = mStore.getChosenUris();
        chosenUris.addAll(addUris);
        mStore.setChosenUris(chosenUris);

        LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(ACTION_GALLERY_CHOSEN_URIS_CHANGED));

        if (current.size() == 0 && allowPublishNewArtwork) {
            publishNextArtwork(null);
        }

        updateMeta();
    }

    private void handleRemoveChosenUris(List<Uri> removeUris) {
        if (removeUris == null) {
            File[] files = sImageStorageRoot.listFiles();
            if (files != null) {
                for (File file : files) {
                    file.delete();
                }
            }
            mStore.setChosenUris(new ArrayList<Uri>());
            publishNextArtwork(null);

        } else {
            Artwork currentArtwork = getCurrentArtwork();
            boolean currentlyShowingRemovedArtwork = false;
            List<Uri> chosenUris = mStore.getChosenUris();
            chosenUris.removeAll(removeUris);
            for (Uri uri : removeUris) {
                if (!currentlyShowingRemovedArtwork && currentArtwork != null
                        && TextUtils.equals(currentArtwork.getToken(), uri.toString())) {
                    currentlyShowingRemovedArtwork = true;
                }

                File f = getStoredFileForUri(this, uri);
                if (f != null) {
                    f.delete();
                }
            }
            mStore.setChosenUris(chosenUris);

            if (currentlyShowingRemovedArtwork) {
                publishNextArtwork(null);
            }
        }

        LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(ACTION_GALLERY_CHOSEN_URIS_CHANGED));
        updateMeta();
    }

    static void ensureStorageRoot(Context context) {
        if (sImageStorageRoot == null) {
            sImageStorageRoot = new File(context.getExternalFilesDir(null),
                    "gallery_images");
            sImageStorageRoot.mkdirs();
        }
    }

    public static File getStoredFileForUri(Context context, Uri uri) {
        ensureStorageRoot(context);

        if (uri == null) {
            Log.w(TAG, "Empty uri.");
            return null;
        }

        StringBuilder filename = new StringBuilder();
        filename.append(uri.getScheme()).append("_")
                .append(uri.getHost()).append("_");
        String encodedPath = uri.getEncodedPath();
        if (!TextUtils.isEmpty(encodedPath)) {
            int length = encodedPath.length();
            if (length > 60) {
                encodedPath = encodedPath.substring(length - 60);
            }
            encodedPath = encodedPath.replace('/', '_');
            filename.append(encodedPath).append("_");
        }
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(uri.toString().getBytes("UTF-8"));
            byte[] digest = md.digest();
            for (byte b : digest) {
                if ((0xff & b) < 0x10) {
                    filename.append("0").append(Integer.toHexString((0xFF & b)));
                } else {
                    filename.append(Integer.toHexString(0xFF & b));
                }
            }
        } catch (NoSuchAlgorithmException | UnsupportedEncodingException e) {
            filename.append(uri.toString().hashCode());
        }

        return new File(sImageStorageRoot, filename.toString());
    }

    @Override
    protected void onUpdate(int reason) {
        if (reason == UPDATE_REASON_INITIAL) {
            updateMeta();
        }
        publishNextArtwork(null);
    }

    static SharedPreferences getSharedPreferences(Context context) {
        return MuzeiArtSource.getSharedPreferences(context, SOURCE_NAME);
    }

    private void publishNextArtwork(Uri forceUri) {
        // schedule next
        scheduleNext();

        List<Uri> chosenUris = mStore.getChosenUris();
        int numChosenUris = (chosenUris != null) ? chosenUris.size() : 0;

        Artwork currentArtwork = getCurrentArtwork();
        String lastToken = (currentArtwork != null) ? currentArtwork.getToken() : null;

        boolean useStoredFile = true;
        Uri imageUri;
        Random random = new Random();
        if (forceUri != null) {
            imageUri = forceUri;

        } else if (numChosenUris > 0) {
            while (true) {
                imageUri = chosenUris.get(random.nextInt(chosenUris.size()));
                if (numChosenUris <= 1 || !imageUri.toString().equals(lastToken)) {
                    break;
                }
            }

        } else {
            useStoredFile = false;
            if (ContextCompat.checkSelfPermission(this,
                    android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Missing read external storage permission.");
                return;
            }
            Cursor cursor = getContentResolver().query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    ImagesQuery.PROJECTION,
                    MediaStore.Images.Media.BUCKET_DISPLAY_NAME + " NOT LIKE '%Screenshots%'",
                    null, null);
            if (cursor == null) {
                Log.w(TAG, "Empty cursor.");
                return;
            }

            int count = cursor.getCount();
            if (count == 0) {
                Log.e(TAG, "No photos in the gallery.");
                return;
            }

            while (true) {
                cursor.moveToPosition(random.nextInt(count));
                imageUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        cursor.getLong(ImagesQuery._ID));
                if (!imageUri.toString().equals(lastToken)) {
                    break;
                }
            }

            cursor.close();
        }

        String token = imageUri.toString();

        // Retrieve metadata for item
        GalleryStore.Metadata metadata = getOrCreateMetadata(imageUri);

        // Publish the actual artwork
        String title;
        if (metadata.datetime > 0) {
            title = DateUtils.formatDateTime(this, metadata.datetime,
                    DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR
                            | DateUtils.FORMAT_SHOW_WEEKDAY);
        } else {
            title = getString(R.string.gallery_from_gallery);
        }

        String byline;
        if (!TextUtils.isEmpty(metadata.location)) {
            byline = metadata.location;
        } else {
            byline = getString(R.string.gallery_touch_to_view);
        }

        Uri finalImageUri = imageUri;
        if (useStoredFile) {
            // Previously stored in handleAddChosenUris
            finalImageUri = Uri.fromFile(getStoredFileForUri(this, imageUri));
        }

        publishArtwork(new Artwork.Builder()
                .imageUri(finalImageUri)
                .title(title)
                .byline(byline)
                .token(token)
                .viewIntent(new Intent(Intent.ACTION_VIEW)
                        .setDataAndType(finalImageUri, "image/jpeg"))
                .build());
    }

    private void updateMeta() {
        int numChosenUris = mStore.getChosenUris().size();
        setDescription(numChosenUris > 0
                ? getResources().getQuantityString(
                R.plurals.gallery_description_choice_template,
                numChosenUris, numChosenUris)
                : getString(R.string.gallery_description));
        if (numChosenUris != 1) {
            setUserCommands(BUILTIN_COMMAND_ID_NEXT_ARTWORK);
        } else {
            removeAllUserCommands();
        }
    }

    private void scheduleNext() {
        int rotateIntervalMinutes = getSharedPreferences().getInt(PREF_ROTATE_INTERVAL_MIN,
                DEFAULT_ROTATE_INTERVAL_MIN);
        if (rotateIntervalMinutes > 0) {
            scheduleUpdate(System.currentTimeMillis() + rotateIntervalMinutes * 60 * 1000);
        }
    }

    private GalleryStore.Metadata getOrCreateMetadata(Uri imageUri) {
        GalleryStore store = GalleryStore.getInstance(this);
        GalleryStore.Metadata metadata = store.getCachedMetadata(imageUri);
        if (metadata == null || metadata.version < CURRENT_METADATA_CACHE_VERSION) {
            // No cached metadata or it's stale, need to pull it separately using Exif
            metadata = new GalleryStore.Metadata();
            metadata.version = CURRENT_METADATA_CACHE_VERSION;

            File tempImageFile = new File(getCacheDir(), "tempimage");
            try {
                writeUriToFile(imageUri, tempImageFile);

                ExifInterface exifInterface = new ExifInterface(tempImageFile.getPath());
                String dateString = exifInterface.getAttribute(ExifInterface.TAG_DATETIME);
                if (!TextUtils.isEmpty(dateString)) {
                    Date date = sExifDateFormat.parse(dateString);
                    metadata.datetime = date.getTime();
                }

                float[] latlong = new float[2];
                if (exifInterface.getLatLong(latlong)) {
                    // Reverse geocode
                    List<Address> addresses = mGeocoder.getFromLocation(latlong[0], latlong[1], 1);
                    if (addresses != null && addresses.size() > 0) {
                        Address addr = addresses.get(0);
                        String locality = addr.getLocality();
                        String adminArea = addr.getAdminArea();
                        String countryCode = addr.getCountryCode();
                        StringBuilder sb = new StringBuilder();
                        if (!TextUtils.isEmpty(locality)) {
                            sb.append(locality);
                        }
                        if (!TextUtils.isEmpty(adminArea)) {
                            if (sb.length() > 0) {
                                sb.append(", ");
                            }
                            sb.append(adminArea);
                        }
                        if (!TextUtils.isEmpty(countryCode)
                                && !sOmitCountryCodes.contains(countryCode)) {
                            if (sb.length() > 0) {
                                sb.append(", ");
                            }
                            sb.append(countryCode);
                        }
                        metadata.location = sb.toString();
                    }
                }

                tempImageFile.delete();
                store.putCachedMetadata(imageUri, metadata);
            } catch (ParseException e) {
                Log.w(TAG, "Couldn't read image metadata.", e);
            } catch (IOException e) {
                Log.w(TAG, "Couldn't write temporary image file.", e);
            }
        }

        return metadata;
    }

    private interface ImagesQuery {
        String[] PROJECTION = {
                MediaStore.MediaColumns._ID,
                MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME,
        };

        int _ID = 0;
        int BUCKET_DISPLAY_NAME = 1;
    }
}
