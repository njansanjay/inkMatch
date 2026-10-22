package com.inkprint.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@RunWith(AndroidJUnit4.class)
public class NativeAppTest {

    @Test
    public void launchesNativeSignInScreen() {
        ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class);
        try {
            scenario.onActivity(activity -> {
                ViewGroup root = activity.findViewById(android.R.id.content);
                assertTrue(containsText(root, "Welcome back"));
                assertEquals(2, countInputs(root));
                assertTrue(containsText(root, "Continue with Google"));
            });
        } finally {
            scenario.close();
        }
    }

    @Test
    public void signInCanOpenAccountCreationAndPasswordRecovery() {
        ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class);
        try {
            scenario.onActivity(activity -> {
                ViewGroup root = activity.findViewById(android.R.id.content);
                findText(root, "New to InkMatch?  Create an account").performClick();
                ViewGroup signup = activity.findViewById(android.R.id.content);
                assertTrue(containsText(signup, "Create an Account"));
                assertEquals(4, countInputs(signup));
                activity.getOnBackPressedDispatcher().onBackPressed();
                ViewGroup returnedSignIn = activity.findViewById(android.R.id.content);
                assertTrue(containsText(returnedSignIn, "Welcome back"));
                findText(returnedSignIn, "New to InkMatch?  Create an account").performClick();
                signup = activity.findViewById(android.R.id.content);
                findText(signup, "Already have an account?  Sign in").performClick();
                ViewGroup signIn = activity.findViewById(android.R.id.content);
                findText(signIn, "Forgot password?").performClick();
                ViewGroup reset = activity.findViewById(android.R.id.content);
                assertTrue(containsText(reset, "Forgot Password"));
                assertTrue(containsText(reset, "Send Reset Link"));
            });
        } finally {
            scenario.close();
        }
    }

    @Test
    public void localHistoryRetainsFiveEntriesAndDeletesSelectedEntry() throws IOException {
        Context context = ApplicationProvider.getApplicationContext();
        AnalysisHistoryStore store = new AnalysisHistoryStore(context, UUID.randomUUID().toString());
        Bitmap first = Bitmap.createBitmap(80, 50, Bitmap.Config.ARGB_8888);
        Bitmap second = Bitmap.createBitmap(70, 60, Bitmap.Config.ARGB_8888);
        try {
            for (int i = 0; i < 6; i++) {
                store.add(
                        new HandwritingAnalyzer.Comparison(i * 10f, 0.5f, 0.6f, 0.7f, 0.8f),
                        "first-" + i,
                        "second-" + i,
                        first,
                        second);
            }
            List<AnalysisHistoryStore.Entry> entries = store.getEntries();
            assertEquals(AnalysisHistoryStore.MAX_ENTRIES, entries.size());
            assertEquals("first-5", entries.get(0).firstName);
            Bitmap thumbnail = store.loadThumbnail(entries.get(0).firstThumbnail);
            assertNotNull(thumbnail);
            if (thumbnail != null) thumbnail.recycle();
            store.delete(entries.get(0).id);
            List<AnalysisHistoryStore.Entry> remaining = store.getEntries();
            assertEquals(AnalysisHistoryStore.MAX_ENTRIES - 1, remaining.size());
            for (AnalysisHistoryStore.Entry entry : remaining) store.delete(entry.id);
        } finally {
            first.recycle();
            second.recycle();
        }
    }

    @Test
    public void matchingFeaturesProduceFullVisualSimilarity() {
        float[] grid = new float[160];
        grid[42] = 1f;
        HandwritingAnalyzer.Features features =
                new HandwritingAnalyzer.Features(grid, 0.4f, 1.8f, 0.6f);

        HandwritingAnalyzer.Comparison result =
                HandwritingAnalyzer.compare(features, features);

        assertEquals(100f, result.score, 0.001f);
        assertEquals(1f, result.shape, 0.001f);
        assertEquals(1f, result.weight, 0.001f);
        assertEquals(1f, result.density, 0.001f);
        assertEquals(1f, result.proportions, 0.001f);
    }

    @Test
    public void smallGridTranslationDoesNotLowerSimilarityForMatchingWriting() {
        float[] originalGrid = new float[160];
        originalGrid[3 * 16 + 4] = 0.8f;
        originalGrid[3 * 16 + 5] = 0.4f;
        originalGrid[4 * 16 + 5] = 1f;
        originalGrid[5 * 16 + 6] = 0.6f;
        float[] translatedGrid = new float[160];
        for (int y = 0; y < 9; y++) {
            for (int x = 0; x < 15; x++) {
                translatedGrid[(y + 1) * 16 + x + 1] = originalGrid[y * 16 + x];
            }
        }
        HandwritingAnalyzer.Features original =
                new HandwritingAnalyzer.Features(originalGrid, 0.4f, 1.8f, 0.6f);
        HandwritingAnalyzer.Features translated =
                new HandwritingAnalyzer.Features(translatedGrid, 0.4f, 1.8f, 0.6f);

        HandwritingAnalyzer.Comparison result = HandwritingAnalyzer.compare(original, translated);

        assertEquals(100f, result.score, 0.001f);
        assertEquals(1f, result.shape, 0.001f);
    }

    @Test
    public void analyzesPhotoAndComparesMatchingHandwriting() {
        Bitmap firstBitmap = handwritingSample();
        Bitmap secondBitmap = handwritingSample();
        try {
            HandwritingAnalyzer.Features first = HandwritingAnalyzer.analyze(firstBitmap);
            HandwritingAnalyzer.Features second = HandwritingAnalyzer.analyze(secondBitmap);

            assertEquals(100f, HandwritingAnalyzer.compare(first, second).score, 0.001f);
        } finally {
            firstBitmap.recycle();
            secondBitmap.recycle();
        }
    }

    private Bitmap handwritingSample() {
        Bitmap bitmap = Bitmap.createBitmap(320, 160, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.BLACK);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(6f);
        canvas.drawText("Inkprint", 24f, 94f, paint);
        canvas.drawLine(25f, 112f, 180f, 112f, paint);
        return bitmap;
    }

    private boolean containsText(View view, String expected) {
        if (view instanceof TextView && expected.contentEquals(((TextView) view).getText())) return true;
        if (!(view instanceof ViewGroup)) return false;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            if (containsText(group.getChildAt(i), expected)) return true;
        }
        return false;
    }

    private View findText(View view, String expected) {
        if (view instanceof TextView && expected.contentEquals(((TextView) view).getText())) return view;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View match = findText(group.getChildAt(i), expected);
            if (match != null) return match;
        }
        return null;
    }

    private int countInputs(View view) {
        int count = view instanceof EditText ? 1 : 0;
        if (!(view instanceof ViewGroup)) return count;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) count += countInputs(group.getChildAt(i));
        return count;
    }
}
