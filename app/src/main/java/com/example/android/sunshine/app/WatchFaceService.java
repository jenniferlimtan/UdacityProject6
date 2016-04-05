package com.example.android.sunshine.app;

import android.util.Log;

import com.example.android.sunshine.app.sync.SunshineSyncAdapter;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.WearableListenerService;

/**
 * Created by JenniferAntonette on 29/3/2016.
 */
public class WatchFaceService extends WearableListenerService {

    private static final String LOG_TAG = WatchFaceService.class.getSimpleName();
    private static final String WEATHER_PATH = "/weather";
    private static final String KEY_UUID = "uuid";

    @Override
    public void onDataChanged(DataEventBuffer dataEvents) {
        Log.d(LOG_TAG, "onDataChanged" );
        for (DataEvent dataEvent : dataEvents) {
            if (DataEvent.TYPE_CHANGED == dataEvent.getType()) {
                DataItem dataItem = dataEvent.getDataItem();
                DataMap dataMap = DataMapItem.fromDataItem(dataItem).getDataMap();

                String path = dataEvent.getDataItem().getUri().getPath();
                if (path.equals(WEATHER_PATH)) {
                    Log.d(LOG_TAG, "/weather id=" + dataMap.getString(KEY_UUID));
                    SunshineSyncAdapter.syncImmediately(this);
                }
            }
        }
    }
}
