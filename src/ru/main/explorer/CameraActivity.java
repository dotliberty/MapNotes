package ru.main.explorer;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.location.*;
import android.media.*;
import android.os.*;
import android.util.Size;
import android.view.*;
import android.widget.*;
import java.nio.ByteBuffer;
import java.util.*;

/** Camera2 preview and full JPEG capture remain inside MapNotes. */
public class CameraActivity extends Activity implements LocationListener {
    private final CameraControls controls = new CameraControls(this);
    private int deviceTransitions;
    private boolean restartPending, modeUnavailable;

    boolean cameraControlsEditable() {
        return active && (ready || modeUnavailable) && !capturing && !switching;
    }

    private boolean switching;
    private FrameLayout cameraRoot;
    private Button backControl;
    float controlsAngle;
    int controlsDegrees = -1;
    android.animation.ValueAnimator orientationMotion;

    void orientControls(int degrees) {
        deviceDegrees = degrees;
        if (controlsDegrees == degrees) return;
        controlsDegrees = degrees;
        if (orientationMotion != null) orientationMotion.cancel();
        float from = controlsAngle, to = AtlasMotion.nearestAngle(from, -degrees);
        if (!AtlasMotion.enabled()) {
            controlsAngle = to;
            paintOrientation();
            return;
        }
        orientationMotion = android.animation.ValueAnimator.ofFloat(from, to);
        orientationMotion.setDuration(300);
        orientationMotion.setInterpolator(AtlasMotion.EASE);
        orientationMotion.addUpdateListener(
                a -> {
                    controlsAngle = (float) a.getAnimatedValue();
                    paintOrientation();
                });
        orientationMotion.start();
    }

    void paintOrientation() {
        Button[] buttons = {backControl, light, sizeButton, controls.facing, controls.lens};
        for (Button button : buttons)
            if (button != null) {
                android.graphics.drawable.Drawable foreground = button.getForeground();
                if (foreground instanceof AtlasIcon) {
                    ((AtlasIcon) foreground).angle = controlsAngle;
                    foreground.invalidateSelf();
                }
                for (android.graphics.drawable.Drawable icon : button.getCompoundDrawables())
                    if (icon instanceof AtlasIcon) {
                        ((AtlasIcon) icon).angle = controlsAngle;
                        icon.invalidateSelf();
                    }
            }
        if (controls.zoom != null) controls.zoom.setRotation(controlsAngle);
    }

    private ImageView switchImage;
    private Bitmap switchBitmap;

    void clearSwitchImage() {
        if (switchImage != null) {
            AtlasMotion.cancel(switchImage);
            cameraRoot.removeView(switchImage);
            switchImage.setImageDrawable(null);
            switchImage = null;
        }
        // A queued hardware display list can still reference the removed ImageView's bitmap.
        // Drop our reference; let the renderer/GC release storage after the last draw.
        switchBitmap = null;
    }

    void snapshotSwitch() {
        clearSwitchImage();
        if (!preview.isAvailable() || preview.getWidth() == 0 || preview.getHeight() == 0) return;
        try {
            int width = Math.min(720, preview.getWidth()),
                    height =
                            Math.max(
                                    1,
                                    Math.round(
                                            width
                                                    * preview.getHeight()
                                                    / (float) preview.getWidth()));
            switchBitmap = preview.getBitmap(width, height);
            if (switchBitmap == null) return;
            switchImage = new ImageView(this);
            switchImage.setImageBitmap(switchBitmap);
            switchImage.setScaleType(ImageView.ScaleType.FIT_XY);
            FrameLayout.LayoutParams p =
                    new FrameLayout.LayoutParams(preview.getWidth(), preview.getHeight());
            p.leftMargin = preview.getLeft();
            p.topMargin = preview.getTop();
            cameraRoot.addView(switchImage, 1, p);
            switchImage.setCameraDistance(8000 * getResources().getDisplayMetrics().density);

        } catch (RuntimeException failure) {
            clearSwitchImage();
        }
    }

    void switchCamera() {
        if (!active || capturing || switching) return;
        switching = true;
        ready = false;
        refreshControls();
        // Never apply a 3D transform to the live TextureView/Camera2 output surface.
        AtlasMotion.cancel(preview);
        if (!AtlasMotion.enabled()) {
            restartCamera();
            return;
        }
        snapshotSwitch();
        if (switchImage == null) {
            restartCamera();
            return;
        }
        final int token = generation;
        AtlasMotion.turn(
                switchImage,
                88,
                220,
                () -> {
                    if (active && switching && token == generation) {
                        restartCamera();
                    }
                });
    }

    void finishSwitch() {
        if (!switching || !ready) return;
        switching = false;
        AtlasMotion.cancel(preview);
        ImageView overlay = switchImage;
        if (overlay != null && AtlasMotion.enabled()) {
            float alpha = overlay.getAlpha();
            AtlasMotion.frames(
                    overlay,
                    180,
                    t -> overlay.setAlpha(alpha * (1 - t)),
                    () -> {
                        if (switchImage == overlay) clearSwitchImage();
                    });
        } else {
            clearSwitchImage();
        }
        refreshControls();
    }

    int cameraGeneration() {
        return generation;
    }

    void attachPreviewZoom() {
        controls.attachPreview(preview);
    }

    String sizeKey(String name) {
        return name + "." + controls.selected.key();
    }

    void restartCamera() {
        if (!active || capturing) return;
        modeUnavailable = false;
        restartPending = true;
        closeCamera();
        refreshControls();
        openCamera();
    }

    protected void attachBaseContext(Context base) {
        super.attachBaseContext(Language.wrap(base));
    }

    private Button light, sizeButton;
    private TextView geo;
    private Size[] jpegSizes;
    private Size selectedSize;
    private boolean torch, hasFlash, front;
    private volatile boolean ready;
    private volatile int generation;
    private OrientationEventListener orientation;
    private volatile int deviceDegrees;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable gpsTick =
            new Runnable() {
                public void run() {
                    if (!active) return;
                    refreshControls();
                    ui.postDelayed(this, 1000);
                }
            };

    void refreshControls() {
        controls.update(active && ready, capturing);
        shutter.setEnabled(active && ready && !capturing);
        shutter.setText(capturing ? "…" : "●");
        shutter.setAlpha(active && ready && !capturing ? 1f : .45f);
        light.setVisibility(hasFlash ? View.VISIBLE : View.GONE);
        light.setEnabled(ready && !capturing);
        light.setText(
                torch
                        ? Language.text(CameraActivity.this, R.string.light_on)
                        : Language.text(CameraActivity.this, R.string.light_off));
        if (sizeButton != null) {
            sizeButton.setEnabled(cameraControlsEditable());
            sizeButton.setText(
                    selectedSize == null
                            ? Language.text(CameraActivity.this, R.string.photo_size)
                            : String.format(
                                    Language.locale(CameraActivity.this),
                                    Language.text(CameraActivity.this, R.string.mp),
                                    selectedSize.getWidth()
                                            * selectedSize.getHeight()
                                            / 1000000.0));
            sizeButton.setContentDescription(
                    Language.text(this, R.string.photo_dimensions) + ": " + sizeButton.getText());
        }
        geo.setText(
                freshFix(fix, SystemClock.elapsedRealtimeNanos())
                        ? Language.text(CameraActivity.this, R.string.map_location_gps)
                                + Math.round(fix.getAccuracy())
                                + Language.text(CameraActivity.this, R.string.m)
                        : Language.text(
                                CameraActivity.this,
                                R.string.waiting_for_gps_a_fresh_location_is_needed_for_a_photo));
        paintControls();
    }

    void paintControls() {
        controls.iconify();
        light.setSelected(torch);
        AtlasIcon.bind(
                light,
                AtlasIcon.LIGHT,
                Language.text(this, torch ? R.string.light_on : R.string.light_off)
                        + " · "
                        + Language.text(this, R.string.camera_light_hint),
                false);
        AtlasIcon.bind(
                sizeButton,
                AtlasIcon.SIZE,
                sizeButton.getContentDescription(),
                selectedSize != null);
        shutter.setTextColor(capturing ? AtlasUi.INK : Color.TRANSPARENT);
        shutter.setTooltipText(shutter.getContentDescription());
    }

    static boolean freshFix(Location l, long now) {
        return l != null
                && now - l.getElapsedRealtimeNanos() >= -1000000000L
                && now - l.getElapsedRealtimeNanos() <= 30000000000L;
    }

    static int jpegRotation(int sensor, int device, boolean front) {
        return (sensor + (front ? -device : device) + 360) % 360;
    }

    void applyLight(CaptureRequest.Builder request) {
        if (controls.keys.contains(CaptureRequest.CONTROL_AE_MODE))
            request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
        if (hasFlash && controls.keys.contains(CaptureRequest.FLASH_MODE))
            request.set(
                    CaptureRequest.FLASH_MODE,
                    torch ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
        controls.applyZoom(request);
    }

    void repeatPreview() {
        try {
            if (camera == null || session == null) return;
            CaptureRequest.Builder request =
                    controls.request(camera, CameraDevice.TEMPLATE_PREVIEW);
            request.addTarget(previewSurface);
            autofocus(request);
            applyLight(request);
            session.setRepeatingRequest(request.build(), null, ui);
        } catch (Exception e) {
            torch = false;
            message(
                    Language.text(CameraActivity.this, R.string.could_not_turn_on_light)
                            + e.getMessage());
        }
    }

    void closeCamera() {
        generation++;
        opening = false;
        ready = false;
        torch = false;
        if (session != null) {
            session.close();
            session = null;
        }
        if (camera != null) {
            camera.close();
            camera = null;
        }
        if (reader != null) {
            reader.close();
            reader = null;
        }
        if (previewSurface != null) {
            previewSurface.release();
            previewSurface = null;
        }
    }

    private AspectTexture preview;
    private TextView status;
    private Button shutter;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private Surface previewSurface;
    private HandlerThread cameraThread;
    private Handler worker;
    private LocationManager locations;
    private volatile Location fix;
    private volatile boolean capturing;
    private volatile boolean active;
    private boolean opening;
    private int sensorOrientation = 90;
    private int[] focusModes;
    private String cameraId;
    private Location shotFix;
    private long shotTime;

    public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setFlags(1024, 1024);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);
        UiInsets.apply(root, 0, 0, 0, 0);
        preview = new AspectTexture(this);
        root.addView(preview, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(1);
        header.setBackgroundColor(0xb3142227);
        root.addView(header, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        LinearLayout actions = new LinearLayout(this);
        header.addView(actions);
        Button back = new Button(this);
        back.setText(Language.text(CameraActivity.this, R.string.map));
        actions.addView(back, new LinearLayout.LayoutParams(0, -2, 1));
        back.setOnClickListener(v -> onBackPressed());
        sizeButton = new Button(this);
        sizeButton.setText(Language.text(CameraActivity.this, R.string.photo_size));
        actions.addView(sizeButton, new LinearLayout.LayoutParams(0, -2, 1));
        sizeButton.setOnClickListener(v -> selectSize());
        light = new Button(this);
        actions.addView(light, new LinearLayout.LayoutParams(0, -2, 1));
        light.setOnClickListener(
                v -> {
                    torch = !torch;
                    repeatPreview();
                    refreshControls();
                });
        controls.attach(header);
        geo = new TextView(this);
        geo.setTextSize(12);
        geo.setGravity(Gravity.CENTER);
        header.addView(geo);
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(1);
        bottom.setGravity(Gravity.CENTER);
        bottom.setBackgroundColor(0xb3142227);
        root.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        status = new TextView(this);
        status.setText(Language.text(CameraActivity.this, R.string.preparing_camera_and_location));
        status.setTextSize(12);
        status.setGravity(Gravity.CENTER);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        bottom.addView(status);
        shutter = new Button(this);
        shutter.setText("●");
        shutter.setContentDescription(
                Language.text(CameraActivity.this, R.string.take_a_photo_with_a_map_location));
        int diameter = Math.round(72 * getResources().getDisplayMetrics().density);
        bottom.addView(shutter, new LinearLayout.LayoutParams(diameter, diameter));
        shutter.setOnClickListener(v -> capture());
        AtlasUi.apply(root);
        shutter.setTextSize(30);
        shutter.setPadding(0, 0, 0, 0);
        shutter.setBackground(AtlasUi.panel(0xfff3e8ce, 0xffbda374, diameter));
        shutter.setTextColor(0xff244541);
        fix = getIntent().getParcelableExtra("location");
        locations = (LocationManager) getSystemService(LOCATION_SERVICE);
        refreshControls();

        AtlasIcon.bind(back, AtlasIcon.BACK, Language.text(this, R.string.return_to_map), false);
        // Apply after shared styling: auto-size must not shrink the shutter to body text.
        shutter.setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE);
        shutter.setTextSize(40);
        TextView captureLabel = new TextView(this);
        captureLabel.setText(Language.text(this, R.string.camera_capture));
        captureLabel.setTextColor(AtlasUi.CREAM);
        captureLabel.setTextSize(16);
        captureLabel.setGravity(Gravity.CENTER);
        captureLabel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        bottom.addView(captureLabel);
        preview.setContentDescription(Language.text(this, R.string.camera_pinch_hint));
        preview.setTooltipText(Language.text(this, R.string.camera_pinch_hint));
        // Keep the changing on/off text as the spoken label; tooltip explains torch semantics.
        light.setTooltipText(Language.text(this, R.string.camera_light_hint));
        backControl = back;
        cameraRoot = root;
        AtlasCameraLayout.apply(
                this,
                root,
                header,
                bottom,
                captureLabel,
                controls,
                sizeButton,
                shutter,
                geo,
                status);
        paintControls();
        int[] origin = getIntent().getIntArrayExtra("cameraOrigin");
        if (state == null && origin != null && origin.length == 4 && origin[2] > 0 && origin[3] > 0)
            AtlasMotion.launch(root, origin);
        orientation =
                new OrientationEventListener(this) {
                    public void onOrientationChanged(int angle) {
                        if (angle != ORIENTATION_UNKNOWN)
                            orientControls(((angle + 45) / 90 * 90) % 360);
                    }
                };
        preview.setSurfaceTextureListener(
                new TextureView.SurfaceTextureListener() {
                    public void onSurfaceTextureAvailable(SurfaceTexture t, int w, int h) {
                        openCamera();
                    }

                    public void onSurfaceTextureSizeChanged(SurfaceTexture t, int w, int h) {
                        transformPreview();
                    }

                    public boolean onSurfaceTextureDestroyed(SurfaceTexture t) {
                        switching = false;
                        clearSwitchImage();
                        AtlasMotion.cancel(preview);
                        closeCamera();
                        return true;
                    }

                    public void onSurfaceTextureUpdated(SurfaceTexture t) {
                        if (ready) finishSwitch();
                    }
                });
    }

    void selectSize() {
        if (!cameraControlsEditable() || jpegSizes == null) return;
        final int token = generation;
        ArrayList<Size> options = new ArrayList<>(Arrays.asList(jpegSizes));
        Collections.sort(
                options,
                (a, b) ->
                        Long.compare(
                                (long) b.getWidth() * b.getHeight(),
                                (long) a.getWidth() * a.getHeight()));
        String[] labels = new String[options.size()];
        int current = 0;
        for (int i = 0; i < labels.length; i++) {
            Size s = options.get(i);
            labels[i] =
                    String.format(
                            Language.locale(CameraActivity.this),
                            Language.text(CameraActivity.this, R.string.mp_2),
                            s.getWidth(),
                            s.getHeight(),
                            s.getWidth() * s.getHeight() / 1000000.0);
            if (s.equals(selectedSize)) current = i;
        }
        new AtlasUi.DialogBuilder(this, sizeButton)
                .setTitle(Language.text(CameraActivity.this, R.string.photo_dimensions))
                .setSingleChoiceItems(
                        labels,
                        current,
                        (dialog, index) -> {
                            if (cameraControlsEditable() && token == generation) {
                                selectedSize = options.get(index);
                                getPreferences(0)
                                        .edit()
                                        .putInt(sizeKey("jpegWidth"), selectedSize.getWidth())
                                        .putInt(sizeKey("jpegHeight"), selectedSize.getHeight())
                                        .apply();
                                restartCamera();
                            }
                            dialog.dismiss();
                        })
                .setNegativeButton(Language.text(CameraActivity.this, R.string.cancel), null)
                .show();
    }

    public void onConfigurationChanged(android.content.res.Configuration configuration) {
        super.onConfigurationChanged(configuration);
        preview.requestLayout();
        preview.post(this::transformPreview);
    }

    protected void onResume() {
        super.onResume();
        active = true;
        capturing = false;
        modeUnavailable = false;
        restartPending = true;
        cameraThread = new HandlerThread("mapnotes-camera");
        cameraThread.start();
        worker = new Handler(cameraThread.getLooper());
        orientation.enable();
        ui.post(gpsTick);
        try {
            locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this);
            if (locations.getAllProviders().contains(LocationManager.NETWORK_PROVIDER))
                locations.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000, 0, this);
        } catch (SecurityException e) {
            message(
                    Language.text(
                            CameraActivity.this,
                            R.string.allow_location_access_to_place_photos_on_the_map));
        }
        if (preview.isAvailable()) openCamera();
    }

    void message(String text) {
        runOnUiThread(
                () -> {
                    if (!isDestroyed()) {
                        if (switching && !opening && !ready) {
                            switching = false;
                            clearSwitchImage();
                            AtlasMotion.cancel(preview);
                        }
                        status.setText(text);
                        refreshControls();
                    }
                });
    }

    void cameraUnavailable(String text) {
        restartPending = false;
        modeUnavailable = true;
        capturing = false;
        closeCamera();
        message(text);
    }

    void openCamera() {
        if (!active
                || opening
                || deviceTransitions > 0
                || camera != null
                || worker == null
                || !preview.isAvailable()) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.CAMERA}, 1);
            return;
        }
        try {
            opening = true;
            restartPending = false;
            CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
            CameraCharacteristics info = controls.prepare(manager);
            cameraId = controls.selected.device;
            Integer sensor = info.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorOrientation = sensor == null ? 90 : sensor;
            focusModes = info.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            StreamConfigurationMap config =
                    info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (config == null)
                throw new Exception(
                        Language.text(
                                CameraActivity.this,
                                R.string.camera_did_not_report_supported_formats));
            Size[] previews = config.getOutputSizes(SurfaceTexture.class);
            jpegSizes = config.getOutputSizes(ImageFormat.JPEG);
            selectedSize =
                    preferredSize(
                            jpegSizes,
                            getPreferences(0).getInt(sizeKey("jpegWidth"), 0),
                            getPreferences(0).getInt(sizeKey("jpegHeight"), 0));
            Size jpeg = selectedSize;
            Size chosen = choosePreview(previews, jpeg);
            final int token = ++generation;
            hasFlash = Boolean.TRUE.equals(info.get(CameraCharacteristics.FLASH_INFO_AVAILABLE));
            front =
                    Integer.valueOf(CameraCharacteristics.LENS_FACING_FRONT)
                            .equals(info.get(CameraCharacteristics.LENS_FACING));
            preview.bufferWidth = chosen.getWidth();
            preview.bufferHeight = chosen.getHeight();
            preview.requestLayout();
            preview.post(() -> transformPreview());
            preview.getSurfaceTexture().setDefaultBufferSize(chosen.getWidth(), chosen.getHeight());
            reader =
                    ImageReader.newInstance(jpeg.getWidth(), jpeg.getHeight(), ImageFormat.JPEG, 2);
            reader.setOnImageAvailableListener(
                    source -> {
                        if (token != generation) return;
                        try (Image image = source.acquireNextImage()) {
                            if (image == null || token != generation || !capturing) return;
                            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                            byte[] bytes = new byte[buffer.remaining()];
                            buffer.get(bytes);
                            final Location savedFix = new Location(shotFix);
                            final long savedTime = shotTime;
                            new Thread(
                                            () -> {
                                                try {
                                                    long photoId =
                                                            PhotoFiles.save(
                                                                    this, bytes, savedFix,
                                                                    savedTime);
                                                    ui.post(
                                                            () -> {
                                                                if (!isDestroyed()
                                                                        && active
                                                                        && token == generation) {
                                                                    startActivity(
                                                                            new Intent(
                                                                                            this,
                                                                                            PhotoActivity
                                                                                                    .class)
                                                                                    .putExtra(
                                                                                            "id",
                                                                                            photoId));
                                                                    finish();
                                                                }
                                                            });
                                                } catch (Exception e) {
                                                    ui.post(
                                                            () -> {
                                                                if (token == generation) {
                                                                    capturing = false;
                                                                    message(
                                                                            Language.text(
                                                                                            CameraActivity
                                                                                                    .this,
                                                                                            R.string
                                                                                                    .could_not_save_photo)
                                                                                    + e
                                                                                            .getMessage());
                                                                }
                                                            });
                                                }
                                            },
                                            "mapnotes-photo-save")
                                    .start();
                        } catch (Exception e) {
                            if (token == generation) {
                                capturing = false;
                                message(
                                        Language.text(
                                                        CameraActivity.this,
                                                        R.string.could_not_receive_photo)
                                                + e.getMessage());
                            }
                        }
                    },
                    ui);
            deviceTransitions++;
            try {
                manager.openCamera(
                        cameraId,
                        new CameraDevice.StateCallback() {
                            public void onOpened(CameraDevice device) {
                                if (!active || token != generation) {
                                    device.close();
                                    return;
                                }
                                opening = false;
                                camera = device;
                                startPreview();
                            }

                            public void onClosed(CameraDevice device) {
                                deviceTransitions = Math.max(0, deviceTransitions - 1);
                                if (active && restartPending) openCamera();
                            }

                            public void onDisconnected(CameraDevice device) {
                                device.close();
                                if (token != generation) return;
                                cameraUnavailable(
                                        Language.text(
                                                CameraActivity.this,
                                                R.string
                                                        .camera_disconnected_return_to_the_map_and_open_it_again));
                            }

                            public void onError(CameraDevice device, int error) {
                                device.close();
                                if (token != generation) return;
                                cameraUnavailable(
                                        Language.text(CameraActivity.this, R.string.camera_error)
                                                + error
                                                + Language.text(
                                                        CameraActivity.this,
                                                        R.string
                                                                .return_to_the_map_and_open_the_camera_again));
                            }
                        },
                        ui);
            } catch (Exception e) {
                deviceTransitions--;
                throw e;
            }
        } catch (Exception e) {
            cameraUnavailable(
                    Language.text(CameraActivity.this, R.string.could_not_open_camera)
                            + e.getMessage());
        }
    }

    static Size preferredSize(Size[] sizes, int width, int height) {
        if (sizes != null)
            for (Size size : sizes)
                if (size.getWidth() == width && size.getHeight() == height) return size;
        return chooseJpeg(sizes);
    }

    static Size choosePreview(Size[] sizes, Size jpeg) {
        if (sizes == null || sizes.length == 0)
            throw new IllegalArgumentException(Messages.text("no_preview_available"));
        Size chosen = sizes[0];
        double best = Double.MAX_VALUE;
        for (Size size : sizes) {
            if (size.getWidth() > 1920 || size.getHeight() > 1080) continue;
            double score =
                    Math.abs(
                                            (double) size.getWidth() / size.getHeight()
                                                    - (double) jpeg.getWidth() / jpeg.getHeight())
                                    * 10000000
                            + Math.abs((long) size.getWidth() * size.getHeight() - 1280L * 720);
            if (score < best) {
                best = score;
                chosen = size;
            }
        }
        return chosen;
    }

    static Size chooseJpeg(Size[] sizes) {
        if (sizes == null || sizes.length == 0)
            throw new IllegalArgumentException(Messages.text("jpeg_format_unavailable"));
        Size best = sizes[0];
        long area = 0;
        for (Size s : sizes) {
            long a = (long) s.getWidth() * s.getHeight();
            if (a <= 12000000 && a > area) {
                best = s;
                area = a;
            }
        }
        if (area == 0)
            for (Size s : sizes)
                if ((long) s.getWidth() * s.getHeight() < (long) best.getWidth() * best.getHeight())
                    best = s;
        return best;
    }

    void autofocus(CaptureRequest.Builder request) {
        if (!controls.keys.contains(CaptureRequest.CONTROL_AF_MODE)) return;
        for (int mode : focusModes == null ? new int[0] : focusModes)
            if (mode == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) {
                request.set(CaptureRequest.CONTROL_AF_MODE, mode);
                return;
            }
    }

    void startPreview() {
        final int token = generation;
        try {
            previewSurface = new Surface(preview.getSurfaceTexture());
            controls.session(
                    camera,
                    Arrays.asList(previewSurface, reader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        public void onConfigured(CameraCaptureSession s) {
                            if (!active || camera == null || token != generation) {
                                s.close();
                                return;
                            }
                            session = s;
                            try {
                                CaptureRequest.Builder request =
                                        controls.request(camera, CameraDevice.TEMPLATE_PREVIEW);
                                request.addTarget(previewSurface);
                                autofocus(request);
                                applyLight(request);
                                s.setRepeatingRequest(request.build(), null, ui);
                                ready = true;
                                message(
                                        Language.text(
                                                CameraActivity.this,
                                                R.string
                                                        .camera_ready_the_original_will_be_saved_in_mapnotes));
                            } catch (Exception e) {
                                modeFailed(e.getMessage());
                            }
                        }

                        public void onConfigureFailed(CameraCaptureSession s) {
                            s.close();
                            if (token == generation) modeFailed("");
                        }
                    },
                    ui);
        } catch (Exception e) {
            if (token == generation) modeFailed(e.getMessage());
        }
    }

    void modeFailed(String detail) {
        modeUnavailable = true;
        ready = false;
        opening = false;
        message(Language.text(this, R.string.camera_mode_unavailable) + " " + detail);
    }

    void transformPreview() {
        int display = getWindowManager().getDefaultDisplay().getRotation() * 90;
        int rotation = relativeRotation(sensorOrientation, display, front);
        if (preview.rotation != rotation) {
            preview.rotation = rotation;
            preview.requestLayout();
        }
        float w = preview.getWidth(), h = preview.getHeight();
        if (w <= 0 || h <= 0) return;
        preview.setTransform(
                previewMatrix(
                        w,
                        h,
                        preview.bufferWidth,
                        preview.bufferHeight,
                        sensorOrientation,
                        display,
                        front));
    }

    // TextureView already applies the Camera2 producer's sensor rotation AND front mirror.
    // Transform that oriented image, not the raw sensor buffer (double rotation flips it).
    static int relativeRotation(int sensor, int display, boolean front) {
        return (sensor - display * (front ? 1 : -1) + 360) % 360;
    }

    static Matrix previewMatrix(
            float w, float h, int bw, int bh, int sensor, int display, boolean front) {
        Matrix m = new Matrix();
        if (w <= 0 || h <= 0 || bw <= 0 || bh <= 0) return m;
        float ow = sensor % 180 == 0 ? bw : bh, oh = sensor % 180 == 0 ? bh : bw;
        int relative = relativeRotation(sensor, display, front);
        float rw = relative % 180 == 0 ? bw : bh, rh = relative % 180 == 0 ? bh : bw;
        // FIT_CENTER: keep the entire JPEG-aspect preview, with no extra crop or stretch.
        float scale = Math.min(w / rw, h / rh);
        m.setScale(ow / w, oh / h);
        m.postTranslate(-ow / 2, -oh / 2);
        m.postRotate(-display);
        m.postScale(scale, scale);
        m.postTranslate(w / 2, h / 2);
        return m;
    }

    void capture() {
        if (capturing) return;
        if (!active || !ready || camera == null || session == null) {
            message(Language.text(CameraActivity.this, R.string.camera_is_not_ready_yet));
            return;
        }
        Location location = fix;
        if (!freshFix(location, SystemClock.elapsedRealtimeNanos())) {
            message(
                    Language.text(
                            CameraActivity.this,
                            R.string.waiting_for_a_fresh_location_to_place_the_photo_on_the_map));
            return;
        }
        shotFix = new Location(location);
        shotTime = System.currentTimeMillis();
        capturing = true;
        message(Language.text(CameraActivity.this, R.string.saving_photo));
        final int token = generation;
        try {
            CaptureRequest.Builder request =
                    controls.request(camera, CameraDevice.TEMPLATE_STILL_CAPTURE);
            request.addTarget(reader.getSurface());
            autofocus(request);
            applyLight(request);
            request.set(
                    CaptureRequest.JPEG_ORIENTATION,
                    jpegRotation(sensorOrientation, deviceDegrees, front));
            if (controls.keys.contains(CaptureRequest.JPEG_QUALITY))
                request.set(CaptureRequest.JPEG_QUALITY, (byte) 95);
            session.capture(
                    request.build(),
                    new CameraCaptureSession.CaptureCallback() {
                        public void onCaptureFailed(
                                CameraCaptureSession s, CaptureRequest r, CaptureFailure f) {
                            if (token != generation) return;
                            capturing = false;
                            message(
                                    Language.text(
                                            CameraActivity.this,
                                            R.string.photo_capture_failed_try_again));
                        }
                    },
                    ui);
        } catch (Exception e) {
            capturing = false;
            message(
                    Language.text(CameraActivity.this, R.string.could_not_take_photo)
                            + e.getMessage());
        }
    }

    public void onLocationChanged(Location l) {
        if (!l.hasAccuracy()
                || !Float.isFinite(l.getAccuracy())
                || l.getAccuracy() <= 0
                || l.getAccuracy() > 1000
                || l.isFromMockProvider()
                || !Double.isFinite(l.getLatitude())
                || !Double.isFinite(l.getLongitude())
                || Math.abs(l.getLatitude()) > 85
                || Math.abs(l.getLongitude()) > 180
                || (l.hasSpeed() && l.getSpeed() > 4.5)) return;
        long age = SystemClock.elapsedRealtimeNanos() - l.getElapsedRealtimeNanos();
        if (age < -1000000000L || age > 15000000000L) return;
        Location old = fix;
        if (old != null) {
            double dt = (l.getElapsedRealtimeNanos() - old.getElapsedRealtimeNanos()) / 1e9;
            if (dt <= 0 || (dt < 10 && old.getAccuracy() < l.getAccuracy())) return;
            if (dt < 120 && l.distanceTo(old) > old.getAccuracy() + l.getAccuracy() + 4.5 * dt)
                return;
        }
        fix = new Location(l);
        refreshControls();
    }

    public void onProviderEnabled(String p) {}

    public void onProviderDisabled(String p) {}

    public void onStatusChanged(String p, int status, Bundle b) {}

    public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request == 1 && grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED)
            openCamera();
        else
            message(
                    Language.text(
                            CameraActivity.this,
                            R.string.camera_permission_is_required_to_take_photos));
    }

    public void onBackPressed() {
        if (capturing) {
            message(
                    Language.text(
                            CameraActivity.this, R.string.wait_for_the_photo_to_finish_saving));
            return;
        }
        super.onBackPressed();
    }

    protected void onPause() {
        active = false;
        switching = false;
        clearSwitchImage();
        if (orientationMotion != null) orientationMotion.cancel();
        AtlasMotion.cancel(preview);
        if (cameraRoot != null) AtlasMotion.cancel(cameraRoot);
        ui.removeCallbacks(gpsTick);
        orientation.disable();
        locations.removeUpdates(this);
        closeCamera();
        capturing = false;
        refreshControls();
        if (cameraThread != null) {
            cameraThread.quitSafely();
            cameraThread = null;
        }
        worker = null;
        super.onPause();
    }

    static class AspectTexture extends TextureView {
        int bufferWidth = 1280, bufferHeight = 720, rotation = 90;

        AspectTexture(Context c) {
            super(c);
        }

        protected void onMeasure(int w, int h) {
            int width = MeasureSpec.getSize(w), height = MeasureSpec.getSize(h);
            double ratio =
                    rotation % 180 == 0
                            ? (double) bufferWidth / bufferHeight
                            : (double) bufferHeight / bufferWidth;
            if (width < height * ratio) setMeasuredDimension(width, (int) (width / ratio));
            else setMeasuredDimension((int) (height * ratio), height);
        }
    }
}
