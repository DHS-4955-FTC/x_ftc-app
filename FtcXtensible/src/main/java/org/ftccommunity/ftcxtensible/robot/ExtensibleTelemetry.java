package org.ftccommunity.ftcxtensible.robot;

import android.util.Log;

import com.google.common.base.Strings;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.ForwardingCache;
import com.google.common.cache.ForwardingLoadingCache;
import com.google.common.collect.EvictingQueue;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multiset;
import com.qualcomm.robotcore.robocol.Telemetry;

import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.TimeUnit;

import static com.google.common.base.Preconditions.checkArgument;

public class ExtensibleTelemetry {
    private static final String TAG = "XTENSILBLE_TELEMETRY::";
    private final Telemetry parent;

    public static final int DEFAULT_DATA_MAX = 192;
    public static final int MAX_DATA_MAX = 255;
    private final int dataPointsToSend;

    private final EvictingQueue<String> dataCache;
    private final LinkedHashMultimap<String, String> data;

    private final Cache<String, String> cache;
    
    private Process logcat;
    private BufferedReader reader;
    private final Queue<String> log;

    private long lastModificationTime;
    private long cacheBuildTime;

    public ExtensibleTelemetry(@NotNull Telemetry telemetry) {
        this(DEFAULT_DATA_MAX, telemetry);
    }

    public ExtensibleTelemetry(int dataPointsToSend, @NotNull Telemetry telemetry) {
        checkArgument(dataPointsToSend < MAX_DATA_MAX);

        this.parent = telemetry;

        this.dataPointsToSend = dataPointsToSend;
        cache = CacheBuilder.newBuilder().
                concurrencyLevel(4).
                expireAfterAccess(250, TimeUnit.MILLISECONDS).
                maximumSize(dataPointsToSend).build();
        
        dataCache = EvictingQueue.create((int)(dataPointsToSend * .75));
        data = LinkedHashMultimap.create();
        log = new LinkedList<>();

        try {
            logcat = Runtime.getRuntime().exec("logcat -v time *:W");
            reader = new BufferedReader(new InputStreamReader(logcat.getInputStream()));
        } catch (IOException e) {
            Log.e(TAG, "Cannot start logcat monitor", e);
        }
    }

    public void data(String tag, String message) {
        checkArgument(Strings.isNullOrEmpty(message), "Your message shouldn't be empty.");
        tag = Strings.nullToEmpty(tag);
        
        synchronized (dataCache) {
            lastModificationTime = System.nanoTime();
            dataCache.add((!tag.equals("") ? tag.toUpperCase(Locale.getDefault()) + " " : "") + message);
        }
    }

    public void addPersistentData(String tag, String mess) {
        synchronized (data) {
            lastModificationTime = System.nanoTime();
            data.put(tag, mess);
        }
    }

    public void data(String tag, double message) {
        data(tag, Double.toString(message));
    }

    public void updateLog() {
        while (!Thread.currentThread().isInterrupted()) {
            String temp = null;
            try {
                temp = reader.readLine();
            } catch (IOException e) {
                Log.e(TAG, "An error occurred while reading the log.", e);
            }
            if (temp != null) {
                log.add(temp);
            } else {
                break;
            }
        }

    }

    public void close() throws IOException {
        reader.close();
        logcat.destroy();

        parent.clearData();

        log.clear();
        dataCache.clear();
        data.clear();
        cache.invalidateAll();
    }

    public void updateCache() {
        int cacheSize = (int) cache.size();
        if (lastModificationTime > cacheBuildTime) {
            forceUpdateCache();
        } else {
            cache.cleanUp();
            if (cacheSize > cache.size()) {
                forceUpdateCache();
            }
        }
    }
    
    public void forceUpdateCache() {
        int numberOfElements;
        synchronized (cache) {
            cache.invalidateAll();

            synchronized (dataCache) {
                int numberOfElementsAdded = 0;
                for (; numberOfElementsAdded <= Math.min(dataCache.size(),
                        (int) (dataPointsToSend * .75)); numberOfElementsAdded++) {
                    cache.put("0" + Integer.toString(numberOfElementsAdded), dataCache.poll());
                }
                numberOfElements = numberOfElementsAdded;
            }

            synchronized (data) {
                int numberOfElementsAdded = 0;
                HashMap<String, String> entries = new HashMap<>();

                LinkedList<Multiset.Entry<String>> keys = new LinkedList<>(data.keys().entrySet());
                for (Multiset.Entry<String> key : keys) {
                    LinkedList<String> dataElements = new LinkedList<>(data.get(key.getElement()));
                    for (int index = 0;
                         numberOfElementsAdded < dataElements.size(); numberOfElementsAdded++) {
                        entries.put(key.getElement() + Integer.toString(index), dataElements.get(index));
                    }
                }

                LinkedList<Map.Entry<String, String>> entriesToSend = new LinkedList<>(entries.entrySet());
                for (; numberOfElementsAdded <=
                        Math.min(data.size(), dataPointsToSend - numberOfElements);
                     numberOfElementsAdded++) {
                    Map.Entry<String, String> entry = entriesToSend.get(numberOfElementsAdded);
                    cache.put(entry.getKey(), entry.getValue());
                }
            }


        }
    }

    public void sendData() {
        updateCache();

        LinkedList<Map.Entry<String, String>> data = new LinkedList<>(cache.asMap().entrySet());
        for (Map.Entry<String, String> entry : data) {
            parent.addData(entry.getKey(), entry.getValue());
        }

        synchronized (log) {
            if (data.size() < dataPointsToSend) {
                int numberOfElementsAdded = 0;
                for (;
                     numberOfElementsAdded <= Math.min(dataPointsToSend - data.size(), log.size());
                     numberOfElementsAdded++) {
                    parent.addData("x_log" + numberOfElementsAdded, log.poll());
                }
            }
        }
    }
}