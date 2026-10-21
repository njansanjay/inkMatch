package com.inkprint.app;

import android.graphics.Bitmap;

final class HandwritingAnalyzer {
    private static final int MAX_SIZE = 1100;
    private static final int GRID_WIDTH = 16;
    private static final int GRID_HEIGHT = 10;

    private HandwritingAnalyzer() {}

    static Features analyze(Bitmap source) {
        float scale = Math.min(1f, (float) MAX_SIZE / Math.max(source.getWidth(), source.getHeight()));
        int width = Math.max(1, Math.round(source.getWidth() * scale));
        int height = Math.max(1, Math.round(source.getHeight() * scale));
        Bitmap bitmap = source;
        if (width != source.getWidth() || height != source.getHeight()) {
            bitmap = Bitmap.createScaledBitmap(source, width, height, true);
        }

        int pixelCount = width * height;
        int[] pixels = new int[pixelCount];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        if (bitmap != source) bitmap.recycle();

        byte[] gray = new byte[pixelCount];
        int[] histogram = new int[256];
        for (int i = 0; i < pixelCount; i++) {
            int color = pixels[i];
            int alpha = color >>> 24;
            int red = composite((color >>> 16) & 0xff, alpha);
            int green = composite((color >>> 8) & 0xff, alpha);
            int blue = composite(color & 0xff, alpha);
            int value = Math.round(0.299f * red + 0.587f * green + 0.114f * blue);
            gray[i] = (byte) value;
            histogram[value]++;
        }

        int background = percentile(histogram, pixelCount, 0.9f);
        int threshold = Math.min(220, background - 32);
        int minX = width;
        int minY = height;
        int maxX = -1;
        int maxY = -1;
        int inkCount = 0;
        int neighborCount = 0;
        byte[] ink = new byte[pixelCount];

        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                int index = row + x;
                if ((gray[index] & 0xff) >= threshold) continue;
                ink[index] = 1;
                inkCount++;
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
                if (x > 0 && ink[index - 1] != 0) neighborCount++;
            }
        }

        if (inkCount < 80 || maxX < 0) {
            throw new IllegalArgumentException(
                    "We couldn't find enough handwriting. Try a clearer, closer photo with darker writing.");
        }

        int boxWidth = maxX - minX + 1;
        int boxHeight = maxY - minY + 1;
        float[] grid = new float[GRID_WIDTH * GRID_HEIGHT];
        for (int cellY = 0; cellY < GRID_HEIGHT; cellY++) {
            int startY = cellY * MAX_SIZE / GRID_HEIGHT;
            int endY = (cellY + 1) * MAX_SIZE / GRID_HEIGHT;
            for (int cellX = 0; cellX < GRID_WIDTH; cellX++) {
                int startX = cellX * MAX_SIZE / GRID_WIDTH;
                int endX = (cellX + 1) * MAX_SIZE / GRID_WIDTH;
                int count = 0;
                for (int y = startY; y < endY; y++) {
                    int sourceY = minY + Math.min(boxHeight - 1, y * boxHeight / MAX_SIZE);
                    for (int x = startX; x < endX; x++) {
                        int sourceX = minX + Math.min(boxWidth - 1, x * boxWidth / MAX_SIZE);
                        count += ink[sourceY * width + sourceX];
                    }
                }
                grid[cellY * GRID_WIDTH + cellX] =
                        (float) count / ((endX - startX) * (endY - startY));
            }
        }

        return new Features(
                grid,
                (float) inkCount / (boxWidth * boxHeight),
                (float) boxWidth / boxHeight,
                (float) neighborCount / inkCount);
    }

    static Comparison compare(Features first, Features second) {
        float shape = vectorSimilarity(first.grid, second.grid);
        float weight = scalarSimilarity(first.strokeWeight, second.strokeWeight);
        float density = scalarSimilarity(first.density, second.density);
        float proportions = scalarSimilarity(first.aspect, second.aspect);
        float score = 100f * (shape * 0.55f + weight * 0.18f + density * 0.14f + proportions * 0.13f);
        return new Comparison(score, shape, weight, density, proportions);
    }

    private static int composite(int channel, int alpha) {
        return (channel * alpha + 255 * (255 - alpha)) / 255;
    }

    private static int percentile(int[] histogram, int total, float fraction) {
        int target = (int) (total * fraction);
        int count = 0;
        for (int value = 0; value < histogram.length; value++) {
            count += histogram[value];
            if (count >= target) return value;
        }
        return 255;
    }

    private static float vectorSimilarity(float[] first, float[] second) {
        float dot = 0f;
        float normFirst = 0f;
        float normSecond = 0f;
        for (int i = 0; i < first.length; i++) {
            dot += first[i] * second[i];
            normFirst += first[i] * first[i];
            normSecond += second[i] * second[i];
        }
        if (normFirst == 0f || normSecond == 0f) return 0f;
        return clamp(dot / (float) Math.sqrt(normFirst * normSecond));
    }

    private static float scalarSimilarity(float first, float second) {
        float larger = Math.max(first, second);
        return larger == 0f ? 1f : clamp(1f - Math.abs(first - second) / larger);
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    static final class Features {
        final float[] grid;
        final float density;
        final float aspect;
        final float strokeWeight;

        Features(float[] grid, float density, float aspect, float strokeWeight) {
            this.grid = grid;
            this.density = density;
            this.aspect = aspect;
            this.strokeWeight = strokeWeight;
        }
    }

    static final class Comparison {
        final float score;
        final float shape;
        final float weight;
        final float density;
        final float proportions;

        Comparison(float score, float shape, float weight, float density, float proportions) {
            this.score = score;
            this.shape = shape;
            this.weight = weight;
            this.density = density;
            this.proportions = proportions;
        }
    }
}
