package com.inkprint.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

final class AnalysisHistoryStore {
    static final int MAX_ENTRIES = 5;
    private static final int THUMBNAIL_MAX_SIZE = 160;

    private final SharedPreferences preferences;
    private final File directory;

    AnalysisHistoryStore(Context context, String userId) {
        Context appContext = context.getApplicationContext();
        String userKey = sha256(userId);
        preferences = appContext.getSharedPreferences(
                "analysis_history_" + userKey, Context.MODE_PRIVATE);
        directory = new File(appContext.getFilesDir(), "analysis-history/" + userKey);
    }

    List<Entry> getEntries() throws IOException {
        String json = preferences.getString("entries", "[]");
        try {
            JSONArray array = new JSONArray(json);
            List<Entry> entries = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                entries.add(new Entry(
                        item.getString("id"),
                        item.getLong("createdAt"),
                        (float) item.getDouble("score"),
                        (float) item.getDouble("shape"),
                        (float) item.getDouble("weight"),
                        (float) item.getDouble("density"),
                        (float) item.getDouble("proportions"),
                        item.getString("firstName"),
                        item.getString("secondName"),
                        item.getString("firstThumbnail"),
                        item.getString("secondThumbnail")));
            }
            return entries;
        } catch (JSONException exception) {
            throw new IOException("Saved comparison history could not be read.", exception);
        }
    }

    Entry add(
            HandwritingAnalyzer.Comparison comparison,
            String firstName,
            String secondName,
            Bitmap firstImage,
            Bitmap secondImage) throws IOException {
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Local comparison history could not be created.");
        }

        String id = UUID.randomUUID().toString();
        String firstFile = id + "-first.jpg";
        String secondFile = id + "-second.jpg";
        writeThumbnail(firstImage, new File(directory, firstFile));
        try {
            writeThumbnail(secondImage, new File(directory, secondFile));
        } catch (IOException exception) {
            new File(directory, firstFile).delete();
            throw exception;
        }

        Entry entry = new Entry(
                id,
                System.currentTimeMillis(),
                comparison.score,
                comparison.shape,
                comparison.weight,
                comparison.density,
                comparison.proportions,
                firstName,
                secondName,
                firstFile,
                secondFile);
        List<Entry> entries;
        try {
            entries = getEntries();
        } catch (IOException exception) {
            new File(directory, firstFile).delete();
            new File(directory, secondFile).delete();
            throw exception;
        }
        entries.add(0, entry);
        List<Entry> retained = new ArrayList<>(
                entries.subList(0, Math.min(entries.size(), MAX_ENTRIES)));
        if (!saveEntries(retained)) {
            new File(directory, firstFile).delete();
            new File(directory, secondFile).delete();
            throw new IOException("Comparison history could not be saved.");
        }
        for (int i = MAX_ENTRIES; i < entries.size(); i++) {
            deleteThumbnails(entries.get(i));
        }
        return entry;
    }

    Bitmap loadThumbnail(String fileName) {
        return BitmapFactory.decodeFile(new File(directory, fileName).getAbsolutePath());
    }

    void delete(String id) throws IOException {
        List<Entry> entries = getEntries();
        Entry target = null;
        List<Entry> retained = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.id.equals(id)) target = entry;
            else retained.add(entry);
        }
        if (target == null) return;
        if (!saveEntries(retained)) {
            throw new IOException("Comparison history could not be updated.");
        }
        deleteThumbnails(target);
    }

    private boolean saveEntries(List<Entry> entries) {
        JSONArray array = new JSONArray();
        for (Entry entry : entries) {
            JSONObject item = new JSONObject();
            try {
                item.put("id", entry.id);
                item.put("createdAt", entry.createdAt);
                item.put("score", entry.score);
                item.put("shape", entry.shape);
                item.put("weight", entry.weight);
                item.put("density", entry.density);
                item.put("proportions", entry.proportions);
                item.put("firstName", entry.firstName);
                item.put("secondName", entry.secondName);
                item.put("firstThumbnail", entry.firstThumbnail);
                item.put("secondThumbnail", entry.secondThumbnail);
            } catch (JSONException exception) {
                throw new IllegalStateException("A comparison could not be encoded.", exception);
            }
            array.put(item);
        }
        return preferences.edit().putString("entries", array.toString()).commit();
    }

    private void writeThumbnail(Bitmap source, File destination) throws IOException {
        float scale = Math.min(
                1f, (float) THUMBNAIL_MAX_SIZE / Math.max(source.getWidth(), source.getHeight()));
        int width = Math.max(1, Math.round(source.getWidth() * scale));
        int height = Math.max(1, Math.round(source.getHeight() * scale));
        Bitmap thumbnail = Bitmap.createScaledBitmap(source, width, height, true);
        try (FileOutputStream output = new FileOutputStream(destination)) {
            if (!thumbnail.compress(Bitmap.CompressFormat.JPEG, 55, output)) {
                throw new IOException("A comparison thumbnail could not be compressed.");
            }
        } catch (IOException exception) {
            destination.delete();
            throw exception;
        } finally {
            if (thumbnail != source) thumbnail.recycle();
        }
    }

    private void deleteThumbnails(Entry entry) {
        new File(directory, entry.firstThumbnail).delete();
        new File(directory, entry.secondThumbnail).delete();
    }

    static String formatDate(long timestamp) {
        return new SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault())
                .format(new Date(timestamp));
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) result.append(String.format(Locale.ROOT, "%02x", item & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available on this Android device.", exception);
        }
    }

    static final class Entry {
        final String id;
        final long createdAt;
        final float score;
        final float shape;
        final float weight;
        final float density;
        final float proportions;
        final String firstName;
        final String secondName;
        final String firstThumbnail;
        final String secondThumbnail;

        Entry(
                String id,
                long createdAt,
                float score,
                float shape,
                float weight,
                float density,
                float proportions,
                String firstName,
                String secondName,
                String firstThumbnail,
                String secondThumbnail) {
            this.id = id;
            this.createdAt = createdAt;
            this.score = score;
            this.shape = shape;
            this.weight = weight;
            this.density = density;
            this.proportions = proportions;
            this.firstName = firstName;
            this.secondName = secondName;
            this.firstThumbnail = firstThumbnail;
            this.secondThumbnail = secondThumbnail;
        }
    }
}
