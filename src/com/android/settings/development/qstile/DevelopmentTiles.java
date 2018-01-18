/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.android.settings.development.qstile;

import android.content.Context;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.support.annotation.VisibleForTesting;
import android.util.Log;
import android.view.IWindowManager;
import android.view.ThreadedRenderer;
import android.view.View;
import android.view.WindowManagerGlobal;
import android.widget.Toast;

import com.android.internal.app.LocalePicker;
import com.android.settings.wrapper.IWindowManagerWrapper;
import com.android.settingslib.development.SystemPropPoker;

public abstract class DevelopmentTiles extends TileService {
    private static final String TAG = "DevelopmentTiles";

    protected abstract boolean isEnabled();

    protected abstract void setIsEnabled(boolean isEnabled);

    @Override
    public void onStartListening() {
        super.onStartListening();
        refresh();
    }

    public void refresh() {
        getQsTile().setState(isEnabled() ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        getQsTile().updateTile();
    }

    @Override
    public void onClick() {
        setIsEnabled(getQsTile().getState() == Tile.STATE_INACTIVE);
        SystemPropPoker.getInstance().poke(); // Settings app magic
        refresh();
    }

    /**
     * Tile to control the "Show layout bounds" developer setting
     */
    public static class ShowLayout extends DevelopmentTiles {

        @Override
        protected boolean isEnabled() {
            return SystemProperties.getBoolean(View.DEBUG_LAYOUT_PROPERTY, false);
        }

        @Override
        protected void setIsEnabled(boolean isEnabled) {
            SystemProperties.set(View.DEBUG_LAYOUT_PROPERTY, isEnabled ? "true" : "false");
        }
    }

    /**
     * Tile to control the "GPU profiling" developer setting
     */
    public static class GPUProfiling extends DevelopmentTiles {

        @Override
        protected boolean isEnabled() {
            final String value = SystemProperties.get(ThreadedRenderer.PROFILE_PROPERTY);
            return value.equals("visual_bars");
        }

        @Override
        protected void setIsEnabled(boolean isEnabled) {
            SystemProperties.set(ThreadedRenderer.PROFILE_PROPERTY, isEnabled ? "visual_bars" : "");
        }
    }

    /**
     * Tile to control the "Force RTL" developer setting
     */
    public static class ForceRTL extends DevelopmentTiles {

        @Override
        protected boolean isEnabled() {
            return Settings.Global.getInt(
                    getContentResolver(), Settings.Global.DEVELOPMENT_FORCE_RTL, 0) != 0;
        }

        @Override
        protected void setIsEnabled(boolean isEnabled) {
            Settings.Global.putInt(
                    getContentResolver(), Settings.Global.DEVELOPMENT_FORCE_RTL, isEnabled ? 1 : 0);
            SystemProperties.set(Settings.Global.DEVELOPMENT_FORCE_RTL, isEnabled ? "1" : "0");
            LocalePicker.updateLocales(getResources().getConfiguration().getLocales());
        }
    }

    /**
     * Tile to control the "Animation speed" developer setting
     */
    public static class AnimationSpeed extends DevelopmentTiles {

        @Override
        protected boolean isEnabled() {
            IWindowManager wm = WindowManagerGlobal.getWindowManagerService();
            try {
                return wm.getAnimationScale(0) != 1;
            } catch (RemoteException e) { }
            return false;
        }

        @Override
        protected void setIsEnabled(boolean isEnabled) {
            IWindowManager wm = WindowManagerGlobal.getWindowManagerService();
            float scale = isEnabled ? 10 : 1;
            try {
                wm.setAnimationScale(0, scale);
                wm.setAnimationScale(1, scale);
                wm.setAnimationScale(2, scale);
            } catch (RemoteException e) { }
        }
    }

    /**
     * Tile to toggle Window Trace.
     */
    public static class WindowTrace extends DevelopmentTiles {
        @VisibleForTesting
        IWindowManagerWrapper mWindowManager;
        @VisibleForTesting
        Toast mToast;

        @Override
        public void onCreate() {
            super.onCreate();
            mWindowManager = new IWindowManagerWrapper(WindowManagerGlobal
                    .getWindowManagerService());
            Context context = getApplicationContext();
            CharSequence text = "Trace written to /data/misc/wmtrace/wm_trace.pb";
            mToast = Toast.makeText(context, text, Toast.LENGTH_LONG);
        }

        @Override
        protected boolean isEnabled() {
            try {
                return mWindowManager.isWindowTraceEnabled();
            } catch (RemoteException e) {
                Log.e(TAG,
                        "Could not get window trace status, defaulting to false." + e.toString());
            }
            return false;
        }

        @Override
        protected void setIsEnabled(boolean isEnabled) {
            try {
                if (isEnabled) {
                    mWindowManager.startWindowTrace();
                } else {
                    mWindowManager.stopWindowTrace();
                    mToast.show();
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Could not set window trace status." + e.toString());
            }
        }
    }

    /**
     * Tile to toggle Layer Trace.
     */
    public static class LayerTrace extends DevelopmentTiles {
        @VisibleForTesting
        static final int SURFACE_FLINGER_LAYER_TRACE_CONTROL_CODE = 1025;
        @VisibleForTesting
        static final int SURFACE_FLINGER_LAYER_TRACE_STATUS_CODE = 1026;
        @VisibleForTesting
        IBinder mSurfaceFlinger;
        @VisibleForTesting
        Toast mToast;

        @Override
        public void onCreate() {
            super.onCreate();
            mSurfaceFlinger = ServiceManager.getService("SurfaceFlinger");
            Context context = getApplicationContext();
            CharSequence text = "Trace written to /data/misc/wmtrace/layers_trace.pb";
            mToast = Toast.makeText(context, text, Toast.LENGTH_LONG);
        }

        @Override
        protected boolean isEnabled() {
            boolean surfaceTraceEnabled = false;
            Parcel reply = null;
            Parcel data = null;
            try {
                if (mSurfaceFlinger != null) {
                    reply = Parcel.obtain();
                    data = Parcel.obtain();
                    data.writeInterfaceToken("android.ui.ISurfaceComposer");
                    mSurfaceFlinger.transact(SURFACE_FLINGER_LAYER_TRACE_STATUS_CODE,
                            data, reply, 0 /* flags */ );
                    surfaceTraceEnabled = reply.readBoolean();
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Could not get layer trace status, defaulting to false." + e.toString());
            } finally {
                if (data != null) {
                    data.recycle();
                    reply.recycle();
                }
            }
            return surfaceTraceEnabled;
        }

        @Override
        protected void setIsEnabled(boolean isEnabled) {
            Parcel data = null;
            try {
                if (mSurfaceFlinger != null) {
                    data = Parcel.obtain();
                    data.writeInterfaceToken("android.ui.ISurfaceComposer");
                    data.writeInt(isEnabled ? 1 : 0);
                    mSurfaceFlinger.transact(SURFACE_FLINGER_LAYER_TRACE_CONTROL_CODE,
                            data, null, 0 /* flags */);
                    if (!isEnabled){
                        mToast.show();
                    }
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Could not set layer tracing." + e.toString());
            } finally {
                if (data != null) {
                    data.recycle();
                }
            }
        }
    }
}