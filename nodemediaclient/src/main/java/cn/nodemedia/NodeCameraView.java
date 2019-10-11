package cn.nodemedia;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.Camera.CameraInfo;
import android.hardware.Camera.Parameters;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.support.annotation.AttrRes;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.StyleRes;
import android.util.AttributeSet;
import android.util.Log;
import android.view.SurfaceHolder;
import android.widget.FrameLayout;

import java.io.IOException;
import java.util.List;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import static cn.nodemedia.NodePublisher.CAMERA_BACK;
import static cn.nodemedia.NodePublisher.CAMERA_FRONT;


/**
 * Created by Mingliang Chen on 17/3/6.
 */

public class NodeCameraView extends FrameLayout implements GLSurfaceView.Renderer, SurfaceHolder.Callback, SurfaceTexture.OnFrameAvailableListener {
    private static final String TAG = "NodeMedia.CameraView";
    public static final int NO_TEXTURE = -1;

    private GLSurfaceView mGLSurfaceView;
    private SurfaceTexture mSurfaceTexture;
    private Context mContext;
    private Camera mCamera;
    private int mTextureId = -1;

    private boolean isStarting;
    private boolean isAutoFocus = true;
    private int mCameraId = 0;
    private int mCameraNum = 0;
    private Camera.CameraInfo mCameraInfo;
    private int mCameraWidth;
    private int mCameraHeight;
    private int mSurfaceWidth;
    private int mSurfaceHeight;
    private NodeCameraViewCallback mNodeCameraViewCallback;
    private boolean isMediaOverlay = false;

    public NodeCameraView(@NonNull Context context) {
        super(context);
        initView(context);
    }

    public NodeCameraView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        initView(context);
    }

    public NodeCameraView(@NonNull Context context, @Nullable AttributeSet attrs, @AttrRes int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initView(context);
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public NodeCameraView(@NonNull Context context, @Nullable AttributeSet attrs, @AttrRes int defStyleAttr, @StyleRes int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        initView(context);
    }


    private void initView(Context context) {
        mContext = context;
        mCameraNum = Camera.getNumberOfCameras();
    }

    // Texture Helper

    public int getExternalOESTextureID() {
        int[] texture = new int[1];

        GLES20.glGenTextures(1, texture, 0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture[0]);
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL10.GL_TEXTURE_MIN_FILTER, GL10.GL_LINEAR);
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL10.GL_TEXTURE_MAG_FILTER, GL10.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL10.GL_TEXTURE_WRAP_S, GL10.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL10.GL_TEXTURE_WRAP_T, GL10.GL_CLAMP_TO_EDGE);

        return texture[0];
    }

    private void createTexture() {
        if (mTextureId == NO_TEXTURE) {
            Log.d(TAG, "GL createTexture");

            // SurfaceTexture
            mTextureId = getExternalOESTextureID();
            mSurfaceTexture = new SurfaceTexture(mTextureId);
            mSurfaceTexture.setOnFrameAvailableListener(this);
        }
    }

    private void destroyTexture() {
        if (mTextureId > NO_TEXTURE) {
            Log.d(TAG, "GL destroyTexture");
            mTextureId = NO_TEXTURE;
            mSurfaceTexture.setOnFrameAvailableListener(null);
            mSurfaceTexture.release();
            mSurfaceTexture = null;
        }
    }

    public GLSurfaceView getGLSurfaceView() {
        return mGLSurfaceView;
    }

    public synchronized int startPreview(int cameraId) {
        if (isStarting) return -1;
        Log.d(TAG, "startPreview");
        try {
            mCameraId = cameraId > mCameraNum - 1 ? 0 : cameraId;
            mCamera = Camera.open(mCameraId);
        } catch (Exception e) {
            Log.d(TAG, e.getMessage());
            return -2;
        }
        try {
            Camera.Parameters para = mCamera.getParameters();
            choosePreviewSize(para, 1280, 720);
            mCamera.setParameters(para);
            setAutoFocus(this.isAutoFocus);
        } catch (Exception e) {
            Log.d(TAG, "startPreview setParameters:" + e.getMessage());
        }

        mGLSurfaceView = new GLSurfaceView(mContext);
        mGLSurfaceView.setEGLContextClientVersion(2);
        mGLSurfaceView.setRenderer(this);
        mGLSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        mGLSurfaceView.getHolder().addCallback(this);
        mGLSurfaceView.getHolder().setKeepScreenOn(true);
        mGLSurfaceView.setZOrderMediaOverlay(isMediaOverlay);
        addView(mGLSurfaceView);
        isStarting = true;
        return 0;
    }

    public synchronized int stopPreview() {
        if (!isStarting) return -1;
        isStarting = false;
        Log.d(TAG, "stopPreview");
        mGLSurfaceView.queueEvent(new Runnable() {
            @Override
            public void run() {
                if (mNodeCameraViewCallback != null) {
                    mNodeCameraViewCallback.OnDestroy();
                }
            }
        });
        mGLSurfaceView.onPause();
        removeView(mGLSurfaceView);
        mGLSurfaceView = null;
        try {
            if (mCamera != null) {
                mCamera.stopPreview();
                mCamera.release();
                mCamera = null;
            }
        } catch (Exception e) {
            Log.d(TAG, e.getMessage());
            e.printStackTrace();
        }
        return 0;
    }


    private CameraInfo getCameraInfo() {
        CameraInfo cameraInfo = new CameraInfo();
        Camera.getCameraInfo(mCameraId, cameraInfo);
        return cameraInfo;
    }

    public Camera.Size getPreviewSize() {
        Camera.Size size = null;

        try {
            // Prevent bad camera state
            size = mCamera.getParameters().getPreviewSize();
        } catch (Exception e) {
            Log.d(TAG, e.getMessage());
        }

        return size;
    }

    public boolean isFrontCamera() {
        CameraInfo info = getCameraInfo();
        return info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT;
    }


    public int getCameraOrientation() {
        return getCameraInfo().orientation;
    }

    private void choosePreviewSize(Camera.Parameters parms, int width, int height) {
        Camera.Size ppsfv = parms.getPreferredPreviewSizeForVideo();
        if (ppsfv != null) {
            Log.d(TAG, "Camera preferred preview size for video is " + ppsfv.width + "x" + ppsfv.height);
        }

        for (Camera.Size size : parms.getSupportedPreviewSizes()) {
            if (size.width == width && size.height == height) {
                parms.setPreviewSize(width, height);
                return;
            }
        }
    }

    public int setAutoFocus(boolean isAutoFocus) {
        this.isAutoFocus = isAutoFocus;

        if (mCamera == null) {
            return -1;
        }

        Parameters parameters = mCamera.getParameters();

        if (isAutoFocus) {
            List<String> focusModes = parameters.getSupportedFocusModes();

            if (focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            } else if (focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            }
        } else {
            List<String> fms = parameters.getSupportedFocusModes();
            if (fms.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
            }

            mCamera.autoFocus(null);
        }

        mCamera.setParameters(parameters);
        return 0;
    }

    public int setFlashEnable(boolean on) {
        if (mCamera == null) {
            return -1;
        }
        Parameters parameters = mCamera.getParameters();
        List<String> flashModes = parameters.getSupportedFlashModes();
        if (flashModes == null) {
            return -1;
        }
        if (flashModes.contains(Parameters.FLASH_MODE_TORCH) && flashModes.contains(Parameters.FLASH_MODE_OFF)) {
            int ret = 1;
            if (on) {
                parameters.setFlashMode(Parameters.FLASH_MODE_TORCH);
            } else {
                parameters.setFlashMode(Parameters.FLASH_MODE_OFF);
                ret = 0;
            }
            mCamera.setParameters(parameters);
            return ret;
        } else {
            return -1;
        }
    }

    public void switchCamera() {
        if (mCameraNum <= 1) {
            return;
        }

        if (mCamera != null) {
            mCamera.stopPreview();
            mCamera.release();
            mCamera = null;
        }

        mCameraId = mCameraId == CAMERA_BACK ? CAMERA_FRONT : CAMERA_BACK;

        try {
            mCamera = Camera.open(mCameraId);
        } catch (RuntimeException e) {
            Log.d(TAG, e.getMessage());
            return;
        }

        try {
            Camera.Parameters para = mCamera.getParameters();
            choosePreviewSize(para, 1280, 720);
            mCamera.setParameters(para);
        } catch (Exception e) {
            Log.d(TAG, "switchCamera setParameters:" + e.getMessage());
        }
        setAutoFocus(this.isAutoFocus);
        try {
            mCamera.setPreviewTexture(mSurfaceTexture);
            mCamera.startPreview();
            mCameraWidth = getPreviewSize().width;
            mCameraHeight = getPreviewSize().height;
            mGLSurfaceView.queueEvent(new Runnable() {
                @Override
                public void run() {
                if (mNodeCameraViewCallback != null) {
                        mNodeCameraViewCallback.OnChange(mCameraWidth, mCameraHeight, mSurfaceWidth, mSurfaceHeight);
                    }
                }
            });
            return;
        } catch (Exception e) {
            Log.d(TAG, e.getMessage());
            return;
        }
    }

    //GLSurface callback

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        Log.d(TAG, "GL onSurfaceCreated");
        createTexture();
        if (mNodeCameraViewCallback != null) {
            mNodeCameraViewCallback.OnCreate();
        }
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        Log.d(TAG, "GL onSurfaceChanged");

        try {
            if (mCamera == null) {
                mCamera = Camera.open(mCameraId);
                Camera.Parameters para = mCamera.getParameters();
                choosePreviewSize(para, 1280, 720);
                mCamera.setParameters(para);
                setAutoFocus(this.isAutoFocus);
            }

            mCamera.setPreviewTexture(mSurfaceTexture);
            mCamera.startPreview();
        } catch (Exception e) {
            Log.d(TAG, e.getMessage());
        }

        Camera.Size size = getPreviewSize();

        if (size != null) {
            mCameraWidth = getPreviewSize().width;
            mCameraHeight = getPreviewSize().height;
        }

        mSurfaceWidth = width;

        mSurfaceHeight = height;

        if (mNodeCameraViewCallback != null) {
            mNodeCameraViewCallback.OnChange(mCameraWidth, mCameraHeight, mSurfaceWidth, mSurfaceHeight);
        }
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        synchronized (this) {
            mSurfaceTexture.updateTexImage();
            if (mNodeCameraViewCallback != null) {
                mNodeCameraViewCallback.OnDraw(mTextureId);
            }
        }
    }

    interface NodeCameraViewCallback {

        void OnCreate();

        void OnChange(int cameraWidth, int cameraHeight, int surfaceWidth, int surfaceHeight);

        void OnDraw(int textureId);

        void OnDestroy();
    }

    public void setNodeCameraViewCallback(NodeCameraViewCallback callback) {
        mNodeCameraViewCallback = callback;
    }

    //Surface callback
    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Log.d(TAG, "SV surfaceCreated");
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.d(TAG, "SV surfaceChanged");
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.d(TAG, "SV surfaceDestroyed");
        if (mCamera != null) {
            mCamera.stopPreview();
        }
        if (!isStarting) {
            if (mNodeCameraViewCallback != null) {
                mNodeCameraViewCallback.OnDestroy();
            }
            if (mCamera != null) {
                mCamera.release();
                mCamera = null;
            }
        }
    }

    @Override
    public synchronized void onFrameAvailable(SurfaceTexture surfaceTexture) {
        if (mGLSurfaceView != null) {
            mGLSurfaceView.requestRender();
        }
    }
}
