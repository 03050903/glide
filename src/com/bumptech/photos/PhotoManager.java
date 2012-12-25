/*
 * Copyright (c) 2012. Bump Technologies Inc. All Rights Reserved.
 */

package com.bumptech.photos;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.SystemClock;
import com.bumptech.photos.cache.LruPhotoCache;
import com.bumptech.photos.cache.PhotoDiskCache;
import com.bumptech.photos.resize.PhotoStreamResizer;

import java.io.File;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;

/**
 * Created by IntelliJ IDEA.
 * User: sam
 * Date: 2/9/12
 * Time: 5:02 PM
 * To change this template use File | Settings | File Templates.
 */
public class PhotoManager {
    private PhotoDiskCache diskCache;
    private LruPhotoCache memoryCache;
    private PhotoStreamResizer resizer;
    private Handler backgroundHandler;
    private Map<Bitmap, Integer> bitmapReferenceCounter = new HashMap<Bitmap, Integer>();
    private Map<String, Queue<Bitmap>> recycledBitmapsForSize = new HashMap<String, Queue<Bitmap>>();

    public PhotoManager(int maxMemCacheSize, long maxDiskCacheSize, File diskCacheDir, Handler mainHandler, Handler backgroundHandler) {
        this.backgroundHandler = backgroundHandler;
        this.memoryCache = new LruPhotoCache(maxMemCacheSize);
        memoryCache.setPhotoRemovedListener(new LruPhotoCache.PhotoRemovedListener() {
            @Override
            public void onPhotoRemoved(String key, Bitmap bitmap) {
                Log.d("RECYCLE: onPhotoRemoved key=" + key + " bitmap=" + bitmap);
                releaseBitmap(bitmap);
            }
        });
        this.diskCache = new PhotoDiskCache(diskCacheDir, maxDiskCacheSize, mainHandler, backgroundHandler);
        this.resizer = new PhotoStreamResizer(mainHandler);
    }

    /**
     * Loads the image for the given id
     * @param path - the path id to the image
     * @param cb - the callback called when the load completes
     * @return A token tracking this request
     */
    public Object getImage(final String path, final LoadedCallback cb){
        final Object token = cb;
        final String key = getKey(path);
        if (!returnFromCache(key, cb)) {
            final Runnable task = resizer.loadAsIs(path, getResizeCb(key, token, cb, false, false));
            postJob(task, token);
        }
        return token;
    }

    /**
     * Loads the image for the given id to nearly the given width and height maintaining the original proportions
     * @param path - the id of the image
     * @param width - the desired width in pixels
     * @param height - the desired height of the slice
     * @param cb - the callback called when the task finishes
     * @return A token tracking this request
     */
    public Object getImage(final String path, final int width, final int height, final LoadedCallback cb){
        final Object token = cb;
        final String key = getKey(path, width, height);
        if (!returnFromCache(key, cb)) {
            final Bitmap recycled = getRecycledBitmap(width, height);
            Runnable checkDiskCache = diskCache.get(key, new DiskCacheCallback(key, token, recycled, cb) {
                @Override
                public Runnable resizeIfNotFound(PhotoStreamResizer.ResizeCallback resizeCallback) {
                    return resizer.loadApproximate(path, width, height, resizeCallback);
                }
            });
            postJob(checkDiskCache, token);
        }
        return token;
    }

    private Bitmap getRecycledBitmap(int width, int height) {
        Bitmap result = null;
        if (width != 0 && height != 0) {
            Queue<Bitmap> available = recycledBitmapsForSize.get(getSizeKey(width, height));
            if (available != null && available.size() > 0) {
                result = available.remove();
            }
        }
        return result;
    }

    /**
     * Loads the image for the given id, resizes it to be exactly width pixels wide keeping proportions,
     * and then returns a section from the center of image exactly height pixels tall
     * @param path - the id of the image
     * @param width - the desired width in pixels
     * @param height - the desired height of the slice
     * @param cb - the callback called when the task finishes
     * @return A token tracking this request
     */
    public Object centerSlice(final String path, final int width, final int height, final LoadedCallback cb){
        final Object token = cb;
        final String key = getKey(path, width, height);
        if (!returnFromCache(key, cb)) {
            final Bitmap recycled = getRecycledBitmap(width, height);
            Runnable checkDiskCache = diskCache.get(key, new DiskCacheCallback(key, token, recycled, cb) {
                @Override
                public Runnable resizeIfNotFound(PhotoStreamResizer.ResizeCallback resizeCallback) {
                    return resizer.resizeCenterCrop(path, width, height, resizeCallback);
                }
            });
            postJob(checkDiskCache, token);
        }
        return token;
    }

    /**
     * Loads the image for the given id and resizes it, maintaining the original proportions, so that the image fills
     * an area of width*height.
     * @param path - the id of the image
     * @param width - the width of the space
     * @param height - the height of the space
     * @param cb - the callback called when the task finishes
     * @return A token tracking this request
     */
    public Object fitCenter(final String path, final int width, final int height, final LoadedCallback cb){
        final Object token = cb;
        final String key = getKey(path, width, height);
        if (!returnFromCache(key, cb)) {
            final Bitmap recycled = getRecycledBitmap(width, height);
            Runnable checkDiskCache = diskCache.get(key, new DiskCacheCallback(key, token, recycled, cb) {
                @Override
                public Runnable resizeIfNotFound(PhotoStreamResizer.ResizeCallback resizeCallback) {
                    return resizer.fitInSpace(path, width, height, resizeCallback);
                }
            });
            postJob(checkDiskCache, token);
        }
        return token;
    }

    private boolean returnFromCache(String key, LoadedCallback cb) {
        boolean found = false;
        Bitmap inCache = memoryCache.get(key);
        if (inCache != null) {
            found = true;
            cb.loadCompleted(inCache);
        }
        return found;
    }

    private abstract class DiskCacheCallback implements PhotoDiskCache.GetCallback {
        private String key;
        private Object token;
        private Bitmap recycled;
        private LoadedCallback cb;

        public DiskCacheCallback(String key, Object token, Bitmap recycled, LoadedCallback cb) {
            this.key = key;
            this.token = token;
            this.recycled = recycled;
            this.cb = cb;
        }

        @Override
        public void onGet(InputStream is) {
            final Runnable task;
            final boolean inDiskCache = is != null;
            final PhotoStreamResizer.ResizeCallback resizeCb = getResizeCb(key, token, cb, inDiskCache, true);
            if (inDiskCache) {
                task = resizer.loadAsIs(is, recycled, resizeCb);
            } else {
                task = resizeIfNotFound(resizeCb);
            }
            postJob(task, token);
        }

        public abstract Runnable resizeIfNotFound(PhotoStreamResizer.ResizeCallback cb);
    }

    private PhotoStreamResizer.ResizeCallback getResizeCb(final String key, final Object token, final LoadedCallback cb, final boolean inDiskCache, final boolean useDiskCache ) {
        return new PhotoStreamResizer.ResizeCallback() {
            @Override
            public void onResizeComplete(Bitmap resized) {
                memoryCache.put(key, resized);
                acquireBitmap(resized);
                if (!inDiskCache && useDiskCache) {
                    Runnable putToDiskCache = diskCache.put(key, resized);
                    postJob(putToDiskCache, token);
                }
                cb.loadCompleted(resized);
            }

            @Override
            public void onResizeFailed(Exception e) {
                cb.onLoadFailed(e);
            }
        };
    }

    private void postJob(Runnable job, Object token) {
        backgroundHandler.postAtTime(job, token, SystemClock.uptimeMillis());
    }

    public void cancelTask(Object token){
        backgroundHandler.removeCallbacksAndMessages(token);
    }

    public void acquireBitmap(Bitmap b) {
        if (!b.isMutable()) return;

        Integer currentCount = bitmapReferenceCounter.get(b);
        if (currentCount == null) {
            currentCount = 0;
        }
        bitmapReferenceCounter.put(b, currentCount+1);
    }

    public void releaseBitmap(Bitmap b) {
        if (!b.isMutable()) return;

        Integer currentCount = bitmapReferenceCounter.get(b);
        currentCount--;
        if (currentCount == 0) {
            bitmapReferenceCounter.remove(b);
            final String sizeKey = getSizeKey(b.getWidth(), b.getHeight());
            Queue<Bitmap> available = recycledBitmapsForSize.get(sizeKey);
            if (available == null) {
                available = new LinkedList<Bitmap>();
                recycledBitmapsForSize.put(sizeKey, available);
            }
            available.add(b);
        } else {
            bitmapReferenceCounter.put(b, currentCount);
        }
    }

    private static String getSizeKey(int width, int height) {
        return "_" + width + "_" + height;
    }

    private static String getKey(String path){
        return getKey(path, 0, 0);
    }

    private static String getKey(String path, int width, int height){
        return sha1Hash(path) + getSizeKey(width, height);
    }

    private static String sha1Hash(String toHash) {
        String hash = null;
        try {
            byte[] bytes = toHash.getBytes();
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(bytes, 0, bytes.length);
            hash = new BigInteger(1, digest.digest()).toString(16);
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        }
        return hash;
    }

}