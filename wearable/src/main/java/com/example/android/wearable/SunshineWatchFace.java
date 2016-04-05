/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.example.android.wearable;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFace extends CanvasWatchFaceService {
    private static final String LOG_TAG = SunshineWatchFace.class.getSimpleName();
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);


    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineWatchFace.Engine> mWeakReference;

        public EngineHandler(SunshineWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mTimePaint;
        Paint mAmPmPaint;
        Paint mMinTempPaint;;
        Paint mMaxTempPaint;
        Paint mDatePaint;
        boolean mAmbient;
        Calendar mCalendar;
        Date mDate;
        SimpleDateFormat mDateFormat;
        SimpleDateFormat mTimeFormat;
        SimpleDateFormat m24HrTimeFormat;
        String mAmString;
        String mPmString;

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };
        float mYOffset;
        float mLineHeight;
        float mSpaceWidth;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        private static final String KEY_UUID = "uuid";
        private static final String KEY_PATH = "/weather";
        private static final String KEY_WEATHER_ID = "weather_id";
        private static final String KEY_MIN_TEMP = "min_temp";
        private static final String KEY_MAX_TEMP = "max_temp";

        private Bitmap mWeatherArt;
        private String mMinTemp;
        private String mMaxTemp;

        GoogleApiClient mGoogleApiClient = new GoogleApiClient.Builder(SunshineWatchFace.this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Wearable.API)
                .build();

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());
            Resources resources = SunshineWatchFace.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);
            mLineHeight = resources.getDimension(R.dimen.digital_line_height);
            mSpaceWidth = resources.getDimension(R.dimen.digital_space_width);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mTimePaint = createTextPaint(resources.getColor(R.color.digital_text));
            mDatePaint = createTextPaint(resources.getColor(R.color.digital_text));
            mAmPmPaint = createTextPaint(resources.getColor(R.color.digital_text));;
            mMinTempPaint = createTextPaint(resources.getColor(R.color.digital_text));;
            mMaxTempPaint = createTextPaint(resources.getColor(R.color.digital_text));;

            mCalendar = Calendar.getInstance();
            mDate = new Date();

            mAmString = resources.getString(R.string.digital_am);
            mPmString = resources.getString(R.string.digital_pm);

            initFormats();

        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                if (mGoogleApiClient != null) {
                    mGoogleApiClient.connect();
                }
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                mCalendar.setTime(new Date());
                initFormats();
            } else {
                unregisterReceiver();
                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void initFormats() {
            mTimeFormat = new SimpleDateFormat("hh:mm", Locale.getDefault());
            mTimeFormat.setCalendar(mCalendar);

            m24HrTimeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
            m24HrTimeFormat.setCalendar(mCalendar);

            mDateFormat = new SimpleDateFormat("EEE, MMM dd yyyy", Locale.getDefault());
            mDateFormat.setCalendar(mCalendar);
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SunshineWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatchFace.this.getResources();
            float textSize = resources.getDimension(R.dimen.digital_text_size);
            float smallTextSize = resources.getDimension(R.dimen.digital_small_text_size);
            float mediumTextSize = resources.getDimension(R.dimen.digital_medium_text_size);

            mTimePaint.setTextSize(textSize);
            mDatePaint.setTextSize(smallTextSize);
            mAmPmPaint.setTextSize(textSize);
            mMinTempPaint.setTextSize(mediumTextSize);
            mMaxTempPaint.setTextSize(mediumTextSize);

            mMinTempPaint.setARGB(240,240,240,240);
            mDatePaint.setARGB(240,240,240,240);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTimePaint.setAntiAlias(!inAmbientMode);
                    mDatePaint.setAntiAlias(!inAmbientMode);
                    mAmPmPaint.setAntiAlias(!inAmbientMode);
                    mMinTempPaint.setAntiAlias(!inAmbientMode);
                    mMaxTempPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private String getAmPmString(int amPm) {
            return amPm == Calendar.AM ? mAmString : mPmString;
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {

            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);
            mDate.setTime(now);
            boolean is24Hour = DateFormat.is24HourFormat(SunshineWatchFace.this);
            float y = mYOffset;
            float x;
            String timeText, ampmText;

            if(is24Hour) {
                timeText = m24HrTimeFormat.format(mDate);
                x = mTimePaint.measureText(timeText) / 2;
                canvas.drawText(timeText, x, y, mTimePaint);
            }
            else {
                timeText = mTimeFormat.format(mDate);
                ampmText = getAmPmString(mCalendar.get(Calendar.AM_PM));
                x = (mTimePaint.measureText(timeText) + mSpaceWidth + mAmPmPaint.measureText(ampmText)) / 2;
                canvas.drawText(timeText, bounds.centerX() - x, mYOffset, mTimePaint);
                canvas.drawText(ampmText, bounds.centerX() - x + mSpaceWidth + mTimePaint.measureText(timeText), y, mAmPmPaint);
            }

            String datetext =  mDateFormat.format(mDate).toUpperCase();

            x = mDatePaint.measureText(datetext) / 2;
            y = y + mLineHeight;
            canvas.drawText(datetext, bounds.centerX() -x, y, mDatePaint);

            y = y + mLineHeight;
            canvas.drawLine(bounds.centerX() - 20, y, bounds.centerX() + 20, y , mDatePaint);
            y = y + (2*mLineHeight);


            if(mMaxTemp != null && mMinTemp != null) {
                x = mMaxTempPaint.measureText(mMaxTemp) + (2* mSpaceWidth) + mMinTempPaint.measureText(mMinTemp);
                if(!isInAmbientMode() && mWeatherArt != null) {
                    x = (x + mWeatherArt.getWidth())/2;
                    canvas.drawBitmap(mWeatherArt, bounds.centerX() - x, y - mWeatherArt.getHeight(), null);
                    x = x - (2* mSpaceWidth) - mWeatherArt.getWidth();
                }
                else{
                    x = x/2;
                }
                canvas.drawText(mMaxTemp, bounds.centerX() -x , y, mMaxTempPaint);
                canvas.drawText(mMinTemp, bounds.centerX() -x + mSpaceWidth + mMaxTempPaint.measureText(mMaxTemp)  , y, mMinTempPaint);
            }


        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        @Override
        public void onConnected(Bundle bundle) {
            Log.v(LOG_TAG,"onConnected");
            Wearable.DataApi.addListener(mGoogleApiClient, Engine.this);
            getWeatherInfo();
        }

        private void getWeatherInfo() {
            PutDataMapRequest putDataMapRequest = PutDataMapRequest.create(KEY_PATH);
            String id = UUID.randomUUID().toString();
            putDataMapRequest.getDataMap().putString(KEY_UUID, id);

            Log.d(LOG_TAG,"getWeatherInfo id=" +id);
            PutDataRequest request = putDataMapRequest.asPutDataRequest();

            Wearable.DataApi.putDataItem(mGoogleApiClient, request)
                    .setResultCallback(new ResultCallback<DataApi.DataItemResult>() {
                        @Override
                        public void onResult(DataApi.DataItemResult dataItemResult) {
                            if (!dataItemResult.getStatus().isSuccess()) {
                                Log.d("WatchFaceService", "Data call failed");
                            }
                        }
                    });
        }

        @Override
        public void onConnectionSuspended(int i) {

        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
           for (DataEvent dataEvent : dataEventBuffer) {
                if (dataEvent.getType() == DataEvent.TYPE_CHANGED){
                    DataItem dataItem = dataEvent.getDataItem();
                    if (dataItem.getUri().getPath().equals(KEY_PATH)) {
                        DataMap dataMap = DataMapItem.fromDataItem(dataItem).getDataMap();

                        if (dataMap.containsKey(KEY_WEATHER_ID)) {

                            int weatherId = dataMap.getInt(KEY_WEATHER_ID);
                            Drawable b = getResources().getDrawable(getArtResourceForWeatherCondition(weatherId));
                            Bitmap icon = ((BitmapDrawable) b).getBitmap();
                            float scaledWidth = (mMaxTempPaint.getTextSize() / icon.getHeight()) * icon.getWidth();
                            mWeatherArt = Bitmap.createScaledBitmap(icon, (int) scaledWidth, (int) mMaxTempPaint.getTextSize(), true);
                        }

                        if (dataMap.containsKey(KEY_MAX_TEMP)) {
                            mMaxTemp = dataMap.getString(KEY_MAX_TEMP);
                        }
                        if (dataMap.containsKey(KEY_MIN_TEMP)) {
                            mMinTemp = dataMap.getString(KEY_MIN_TEMP);
                        }

                        Log.d(LOG_TAG, "weatherId" +dataMap.getInt(KEY_WEATHER_ID));
                        Log.d(LOG_TAG, "mMaxTemp" +dataMap.getString(KEY_MAX_TEMP));
                        Log.d(LOG_TAG, "mMinTemp" +dataMap.getString(KEY_MIN_TEMP));
                        invalidate();
                    }
                }
            }
        }

        @Override
        public void onConnectionFailed(ConnectionResult connectionResult) {

        }

        private int getArtResourceForWeatherCondition(int weatherId) {
            // Based on weather code data found at:
            // http://bugs.openweathermap.org/projects/api/wiki/Weather_Condition_Codes
            if (weatherId >= 200 && weatherId <= 232) {
                return R.drawable.art_storm;
            } else if (weatherId >= 300 && weatherId <= 321) {
                return R.drawable.art_light_rain;
            } else if (weatherId >= 500 && weatherId <= 504) {
                return R.drawable.art_rain;
            } else if (weatherId == 511) {
                return R.drawable.art_snow;
            } else if (weatherId >= 520 && weatherId <= 531) {
                return R.drawable.art_rain;
            } else if (weatherId >= 600 && weatherId <= 622) {
                return R.drawable.art_snow;
            } else if (weatherId >= 701 && weatherId <= 761) {
                return R.drawable.art_fog;
            } else if (weatherId == 761 || weatherId == 781) {
                return R.drawable.art_storm;
            } else if (weatherId == 800) {
                return R.drawable.art_clear;
            } else if (weatherId == 801) {
                return R.drawable.art_light_clouds;
            } else if (weatherId >= 802 && weatherId <= 804) {
                return R.drawable.art_clouds;
            }
            return -1;
        }

    }
}
