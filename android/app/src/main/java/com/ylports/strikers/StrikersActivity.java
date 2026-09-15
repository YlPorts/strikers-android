package com.ylports.strikers;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.hardware.input.InputManager;
import android.os.Bundle;
import android.util.SparseIntArray;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import dev.encounter.aurora.AuroraSurface;
import org.libsdl.app.SDLActivity;
import org.libsdl.app.SDLSurface;

/** Runs the native Strikers port through SDL3 and Aurora. */
public final class StrikersActivity extends SDLActivity
        implements InputManager.InputDeviceListener {
    private TouchControllerView touchController;
    private InputManager inputManager;
    private boolean physicalGamepadConnected;

    private static native void nativeSetTouchState(
            int buttons,
            int stickX,
            int stickY,
            int substickX,
            int substickY,
            int triggerLeft,
            int triggerRight);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        RunLog.installJavaCrashHandler(this);
        RunLog.append(this, "SDL activity: onCreate BEFORE SDLActivity.onCreate");
        super.onCreate(savedInstanceState);
        RunLog.append(this, "SDL activity: onCreate AFTER SDLActivity.onCreate");

        touchController = new TouchControllerView(this);
        addContentView(touchController, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        touchController.releaseAll();

        inputManager = (InputManager) getSystemService(Context.INPUT_SERVICE);
        if (inputManager != null) {
            inputManager.registerInputDeviceListener(this, null);
        }
        updateTouchOverlayVisibility();
        RunLog.append(this, "SDL activity: Android touch GameCube overlay installed");
    }

    @Override
    protected void onStart() {
        RunLog.append(this, "SDL activity: onStart BEFORE super");
        super.onStart();
        RunLog.append(this, "SDL activity: onStart AFTER super");
    }

    @Override
    protected void onResume() {
        RunLog.append(this, "SDL activity: onResume BEFORE super");
        super.onResume();
        updateTouchOverlayVisibility();
        RunLog.append(this, "SDL activity: onResume AFTER super");
    }

    @Override
    protected void onPause() {
        RunLog.append(this, "SDL activity: onPause BEFORE super");
        if (touchController != null) {
            touchController.releaseAll();
        }
        super.onPause();
        RunLog.append(this, "SDL activity: onPause AFTER super");
    }

    @Override
    protected void onStop() {
        RunLog.append(this, "SDL activity: onStop BEFORE super");
        if (touchController != null) {
            touchController.releaseAll();
        }
        super.onStop();
        RunLog.append(this, "SDL activity: onStop AFTER super");
    }

    @Override
    protected void onDestroy() {
        RunLog.append(this, "SDL activity: onDestroy BEFORE super");
        if (inputManager != null) {
            inputManager.unregisterInputDeviceListener(this);
            inputManager = null;
        }
        if (touchController != null) {
            touchController.releaseAll();
        }
        super.onDestroy();
        RunLog.append(this, "SDL activity: onDestroy AFTER super");
    }

    @Override
    public void onInputDeviceAdded(int deviceId) {
        runOnUiThread(this::updateTouchOverlayVisibility);
    }

    @Override
    public void onInputDeviceRemoved(int deviceId) {
        runOnUiThread(this::updateTouchOverlayVisibility);
    }

    @Override
    public void onInputDeviceChanged(int deviceId) {
        runOnUiThread(this::updateTouchOverlayVisibility);
    }

    private void updateTouchOverlayVisibility() {
        if (touchController == null) {
            return;
        }

        boolean connected = hasPhysicalGamepad();
        if (connected != physicalGamepadConnected) {
            physicalGamepadConnected = connected;
            RunLog.append(this, connected
                    ? "SDL activity: physical gamepad connected; touch overlay hidden"
                    : "SDL activity: physical gamepad disconnected; touch overlay restored");
        }

        if (connected) {
            touchController.releaseAll();
            touchController.setVisibility(View.GONE);
        } else {
            touchController.setVisibility(View.VISIBLE);
            touchController.invalidate();
        }
    }

    private boolean hasPhysicalGamepad() {
        int[] ids = InputDevice.getDeviceIds();
        for (int id : ids) {
            InputDevice device = InputDevice.getDevice(id);
            if (device == null || device.isVirtual()) {
                continue;
            }
            int sources = device.getSources();
            boolean gamepad = (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD;
            boolean joystick = (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
            if (gamepad || joystick) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected String[] getLibraries() {
        // Match SDL3's Android project layout: SDL's JNI runtime is loaded first,
        // then SDLActivity runs SDL_main from the Strikers library.
        RunLog.append(this, "SDL activity: getLibraries -> SDL3, strikers");
        return new String[] { "SDL3", "strikers" };
    }

    @Override
    protected SDLSurface createSDLSurface(Context context) {
        RunLog.append(context, "SDL activity: createSDLSurface -> AuroraSurface");
        return new AuroraSurface(context);
    }

    /**
     * Transparent multitouch overlay. It feeds Aurora's virtual GameCube PAD instead
     * of sending keyboard events, so menus and gameplay see a normal controller.
     *
     * Each finger keeps a role by Android pointer ID. Analog roles are exclusive and
     * sticky until that finger is lifted, so pointer-index renumbering cannot kill the
     * movement stick while another button is being pressed.
     */
    private static final class TouchControllerView extends View {
        private static final int PAD_BUTTON_LEFT = 0x0001;
        private static final int PAD_BUTTON_RIGHT = 0x0002;
        private static final int PAD_BUTTON_DOWN = 0x0004;
        private static final int PAD_BUTTON_UP = 0x0008;
        private static final int PAD_TRIGGER_Z = 0x0010;
        private static final int PAD_TRIGGER_R = 0x0020;
        private static final int PAD_TRIGGER_L = 0x0040;
        private static final int PAD_BUTTON_A = 0x0100;
        private static final int PAD_BUTTON_B = 0x0200;
        private static final int PAD_BUTTON_X = 0x0400;
        private static final int PAD_BUTTON_Y = 0x0800;
        private static final int PAD_BUTTON_START = 0x1000;

        private static final int ROLE_NONE = 0;
        private static final int ROLE_MAIN_STICK = 1;
        private static final int ROLE_C_STICK = 2;
        private static final int ROLE_DPAD = 3;
        private static final int ROLE_A = 4;
        private static final int ROLE_B = 5;
        private static final int ROLE_X = 6;
        private static final int ROLE_Y = 7;
        private static final int ROLE_L = 8;
        private static final int ROLE_R = 9;
        private static final int ROLE_Z = 10;
        private static final int ROLE_START = 11;

        private final float density;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF lRect = new RectF();
        private final RectF rRect = new RectF();
        private final RectF zRect = new RectF();
        private final RectF startRect = new RectF();
        private final SparseIntArray pointerRoles = new SparseIntArray();

        private float stickCx;
        private float stickCy;
        private float stickRadius;
        private float cStickCx;
        private float cStickCy;
        private float cStickRadius;
        private float dpadCx;
        private float dpadCy;
        private float dpadStep;
        private float faceRadius;
        private float aX;
        private float aY;
        private float bX;
        private float bY;
        private float xX;
        private float xY;
        private float yX;
        private float yY;

        private int buttons;
        private int stickX;
        private int stickY;
        private int substickX;
        private int substickY;
        private int triggerLeft;
        private int triggerRight;

        TouchControllerView(Context context) {
            super(context);
            density = getResources().getDisplayMetrics().density;
            setFocusable(false);
            setClickable(true);
            setWillNotDraw(false);

            outline.setStyle(Paint.Style.STROKE);
            outline.setStrokeWidth(dp(2));
            outline.setColor(0xAAFFFFFF);
            fill.setStyle(Paint.Style.FILL);
            text.setColor(Color.WHITE);
            text.setTextAlign(Paint.Align.CENTER);
            text.setFakeBoldText(true);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            float shortSide = Math.min(w, h);
            float margin = Math.max(dp(18), shortSide * 0.025f);

            stickRadius = clamp(shortSide * 0.135f, dp(66), dp(112));
            stickCx = margin + stickRadius;
            stickCy = h - margin - stickRadius;

            cStickRadius = clamp(shortSide * 0.065f, dp(38), dp(58));
            cStickCx = w * 0.67f;
            cStickCy = h - margin - cStickRadius;

            faceRadius = clamp(shortSide * 0.060f, dp(36), dp(55));
            aX = w - margin - faceRadius * 1.35f;
            aY = h - margin - faceRadius * 1.65f;
            bX = aX - faceRadius * 1.75f;
            bY = aY + faceRadius * 0.55f;
            xX = aX + faceRadius * 1.55f;
            xY = aY - faceRadius * 0.25f;
            yX = aX - faceRadius * 0.20f;
            yY = aY - faceRadius * 1.65f;

            dpadStep = clamp(shortSide * 0.052f, dp(30), dp(45));
            dpadCx = stickCx + stickRadius * 1.75f;
            dpadCy = stickCy - stickRadius * 0.05f;

            float shoulderH = clamp(shortSide * 0.075f, dp(38), dp(58));
            float shoulderW = clamp(w * 0.105f, dp(95), dp(150));
            lRect.set(margin, margin, margin + shoulderW, margin + shoulderH);
            rRect.set(w - margin - shoulderW, margin, w - margin, margin + shoulderH);
            zRect.set(rRect.left - margin * 0.55f - shoulderW * 0.64f,
                    margin,
                    rRect.left - margin * 0.55f,
                    margin + shoulderH);

            float startW = clamp(shortSide * 0.15f, dp(86), dp(132));
            float startH = clamp(shortSide * 0.055f, dp(34), dp(48));
            startRect.set(w * 0.5f - startW * 0.5f,
                    h - margin - startH,
                    w * 0.5f + startW * 0.5f,
                    h - margin);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            drawStick(canvas, stickCx, stickCy, stickRadius, stickX, stickY, "L");
            drawStick(canvas, cStickCx, cStickCy, cStickRadius, substickX, substickY, "C");
            drawDpad(canvas);

            drawFace(canvas, aX, aY, faceRadius * 1.08f, "A", 0x6654C878,
                    (buttons & PAD_BUTTON_A) != 0);
            drawFace(canvas, bX, bY, faceRadius * 0.86f, "B", 0x66E75B5B,
                    (buttons & PAD_BUTTON_B) != 0);
            drawFace(canvas, xX, xY, faceRadius * 0.84f, "X", 0x6670A9FF,
                    (buttons & PAD_BUTTON_X) != 0);
            drawFace(canvas, yX, yY, faceRadius * 0.84f, "Y", 0x66E7C95B,
                    (buttons & PAD_BUTTON_Y) != 0);

            drawRectButton(canvas, lRect, "L", (buttons & PAD_TRIGGER_L) != 0);
            drawRectButton(canvas, rRect, "R", (buttons & PAD_TRIGGER_R) != 0);
            drawRectButton(canvas, zRect, "Z", (buttons & PAD_TRIGGER_Z) != 0);
            drawRectButton(canvas, startRect, "START", (buttons & PAD_BUTTON_START) != 0);
        }

        private void drawStick(Canvas canvas, float cx, float cy, float radius,
                               int axisX, int axisY, String label) {
            fill.setColor(0x443C424A);
            canvas.drawCircle(cx, cy, radius, fill);
            canvas.drawCircle(cx, cy, radius, outline);

            float knobR = radius * 0.44f;
            float knobX = cx + axisX / 127f * (radius - knobR);
            float knobY = cy - axisY / 127f * (radius - knobR);
            fill.setColor((axisX != 0 || axisY != 0) ? 0x997F8C9D : 0x667F8C9D);
            canvas.drawCircle(knobX, knobY, knobR, fill);
            canvas.drawCircle(knobX, knobY, knobR, outline);
            drawCenteredText(canvas, label, knobX, knobY, Math.max(dp(13), knobR * 0.62f));
        }

        private void drawDpad(Canvas canvas) {
            float r = dpadStep * 0.48f;
            drawFace(canvas, dpadCx - dpadStep, dpadCy, r, "◀", 0x55464B53,
                    (buttons & PAD_BUTTON_LEFT) != 0);
            drawFace(canvas, dpadCx + dpadStep, dpadCy, r, "▶", 0x55464B53,
                    (buttons & PAD_BUTTON_RIGHT) != 0);
            drawFace(canvas, dpadCx, dpadCy - dpadStep, r, "▲", 0x55464B53,
                    (buttons & PAD_BUTTON_UP) != 0);
            drawFace(canvas, dpadCx, dpadCy + dpadStep, r, "▼", 0x55464B53,
                    (buttons & PAD_BUTTON_DOWN) != 0);
        }

        private void drawFace(Canvas canvas, float cx, float cy, float radius,
                              String label, int baseColor, boolean pressed) {
            int alpha = pressed ? 0xCC : ((baseColor >>> 24) & 0xFF);
            fill.setColor((baseColor & 0x00FFFFFF) | (alpha << 24));
            canvas.drawCircle(cx, cy, radius, fill);
            canvas.drawCircle(cx, cy, radius, outline);
            drawCenteredText(canvas, label, cx, cy, Math.max(dp(13), radius * 0.58f));
        }

        private void drawRectButton(Canvas canvas, RectF rect, String label, boolean pressed) {
            fill.setColor(pressed ? 0xBB7F8C9D : 0x55464B53);
            float corner = Math.min(rect.width(), rect.height()) * 0.32f;
            canvas.drawRoundRect(rect, corner, corner, fill);
            canvas.drawRoundRect(rect, corner, corner, outline);
            drawCenteredText(canvas, label, rect.centerX(), rect.centerY(),
                    Math.max(dp(12), rect.height() * 0.42f));
        }

        private void drawCenteredText(Canvas canvas, String label, float x, float y, float size) {
            text.setTextSize(size);
            Paint.FontMetrics fm = text.getFontMetrics();
            float baseline = y - (fm.ascent + fm.descent) * 0.5f;
            canvas.drawText(label, x, baseline, text);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_OUTSIDE) {
                releaseAll();
                return true;
            }

            int liftedPointerId = -1;
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
                int index = event.getActionIndex();
                int pointerId = event.getPointerId(index);
                int role = roleForPoint(event.getX(index), event.getY(index), pointerId);
                if (role != ROLE_NONE) {
                    pointerRoles.put(pointerId, role);
                }
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
                int index = event.getActionIndex();
                liftedPointerId = event.getPointerId(index);
                pointerRoles.delete(liftedPointerId);
                if (action == MotionEvent.ACTION_UP) {
                    performClick();
                }
            }

            // A finger that landed slightly outside the painted circle can still slide
            // into an analog stick and claim it. This is especially important on small
            // phone screens where the old exact-down requirement felt like a dead stick.
            claimUnassignedAnalogPointers(event, liftedPointerId);
            rebuildState(event, liftedPointerId);
            return true;
        }

        @Override
        public boolean performClick() {
            super.performClick();
            return true;
        }

        void releaseAll() {
            pointerRoles.clear();
            setState(0, 0, 0, 0, 0, 0, 0);
        }

        private int roleForPoint(float px, float py, int pointerId) {
            if (lRect.contains(px, py)) return ROLE_L;
            if (rRect.contains(px, py)) return ROLE_R;
            if (zRect.contains(px, py)) return ROLE_Z;
            if (startRect.contains(px, py)) return ROLE_START;

            if (insideCircle(px, py, aX, aY, faceRadius * 1.20f)) return ROLE_A;
            if (insideCircle(px, py, bX, bY, faceRadius * 1.08f)) return ROLE_B;
            if (insideCircle(px, py, xX, xY, faceRadius * 1.06f)) return ROLE_X;
            if (insideCircle(px, py, yX, yY, faceRadius * 1.06f)) return ROLE_Y;

            // Analog sticks get a larger invisible capture circle than the painted
            // circle, and only one pointer may own each stick at a time.
            if (!roleInUse(ROLE_MAIN_STICK, pointerId)
                    && insideCircle(px, py, stickCx, stickCy, stickRadius * 1.58f)) {
                return ROLE_MAIN_STICK;
            }
            if (!roleInUse(ROLE_C_STICK, pointerId)
                    && insideCircle(px, py, cStickCx, cStickCy, cStickRadius * 1.55f)) {
                return ROLE_C_STICK;
            }

            float dpadHit = dpadStep * 0.74f;
            if (insideCircle(px, py, dpadCx - dpadStep, dpadCy, dpadHit)
                    || insideCircle(px, py, dpadCx + dpadStep, dpadCy, dpadHit)
                    || insideCircle(px, py, dpadCx, dpadCy - dpadStep, dpadHit)
                    || insideCircle(px, py, dpadCx, dpadCy + dpadStep, dpadHit)) {
                return ROLE_DPAD;
            }
            return ROLE_NONE;
        }

        private boolean roleInUse(int role, int exceptPointerId) {
            for (int i = 0; i < pointerRoles.size(); i++) {
                if (pointerRoles.keyAt(i) != exceptPointerId
                        && pointerRoles.valueAt(i) == role) {
                    return true;
                }
            }
            return false;
        }

        private void claimUnassignedAnalogPointers(MotionEvent event, int liftedPointerId) {
            for (int i = 0; i < event.getPointerCount(); i++) {
                int pointerId = event.getPointerId(i);
                if (pointerId == liftedPointerId
                        || pointerRoles.get(pointerId, ROLE_NONE) != ROLE_NONE) {
                    continue;
                }
                float px = event.getX(i);
                float py = event.getY(i);
                if (!roleInUse(ROLE_MAIN_STICK, pointerId)
                        && insideCircle(px, py, stickCx, stickCy, stickRadius * 1.72f)) {
                    pointerRoles.put(pointerId, ROLE_MAIN_STICK);
                } else if (!roleInUse(ROLE_C_STICK, pointerId)
                        && insideCircle(px, py, cStickCx, cStickCy, cStickRadius * 1.68f)) {
                    pointerRoles.put(pointerId, ROLE_C_STICK);
                }
            }
        }

        private void rebuildState(MotionEvent event, int liftedPointerId) {
            int newButtons = 0;
            int newStickX = 0;
            int newStickY = 0;
            int newSubstickX = 0;
            int newSubstickY = 0;
            int newTriggerLeft = 0;
            int newTriggerRight = 0;

            for (int i = 0; i < event.getPointerCount(); i++) {
                int pointerId = event.getPointerId(i);
                if (pointerId == liftedPointerId) {
                    continue;
                }
                int role = pointerRoles.get(pointerId, ROLE_NONE);
                if (role == ROLE_NONE) {
                    continue;
                }

                float px = event.getX(i);
                float py = event.getY(i);
                switch (role) {
                    case ROLE_MAIN_STICK: {
                        // Reach the full GameCube range before the finger reaches the
                        // edge of the painted ring. PADClamp will apply the console's
                        // own deadzone afterwards.
                        float[] axis = axisForAssignedPoint(px, py, stickCx, stickCy,
                                stickRadius * 0.78f);
                        newStickX = Math.round(axis[0] * 127f);
                        newStickY = Math.round(-axis[1] * 127f);
                        break;
                    }
                    case ROLE_C_STICK: {
                        float[] axis = axisForAssignedPoint(px, py, cStickCx, cStickCy,
                                cStickRadius * 0.82f);
                        newSubstickX = Math.round(axis[0] * 127f);
                        newSubstickY = Math.round(-axis[1] * 127f);
                        break;
                    }
                    case ROLE_DPAD:
                        newButtons |= dpadForPoint(px, py);
                        break;
                    case ROLE_A:
                        newButtons |= PAD_BUTTON_A;
                        break;
                    case ROLE_B:
                        newButtons |= PAD_BUTTON_B;
                        break;
                    case ROLE_X:
                        newButtons |= PAD_BUTTON_X;
                        break;
                    case ROLE_Y:
                        newButtons |= PAD_BUTTON_Y;
                        break;
                    case ROLE_L:
                        newButtons |= PAD_TRIGGER_L;
                        newTriggerLeft = 180;
                        break;
                    case ROLE_R:
                        newButtons |= PAD_TRIGGER_R;
                        newTriggerRight = 180;
                        break;
                    case ROLE_Z:
                        newButtons |= PAD_TRIGGER_Z;
                        break;
                    case ROLE_START:
                        newButtons |= PAD_BUTTON_START;
                        break;
                    default:
                        break;
                }
            }

            setState(newButtons, newStickX, newStickY, newSubstickX, newSubstickY,
                    newTriggerLeft, newTriggerRight);
        }

        private int dpadForPoint(float px, float py) {
            float dx = px - dpadCx;
            float dy = py - dpadCy;
            float dead = dpadStep * 0.28f;
            if (Math.abs(dx) < dead && Math.abs(dy) < dead) {
                return 0;
            }
            if (Math.abs(dx) > Math.abs(dy)) {
                return dx < 0f ? PAD_BUTTON_LEFT : PAD_BUTTON_RIGHT;
            }
            return dy < 0f ? PAD_BUTTON_UP : PAD_BUTTON_DOWN;
        }

        private void setState(int newButtons, int newStickX, int newStickY,
                              int newSubstickX, int newSubstickY,
                              int newTriggerLeft, int newTriggerRight) {
            buttons = newButtons;
            stickX = clampAxis(newStickX);
            stickY = clampAxis(newStickY);
            substickX = clampAxis(newSubstickX);
            substickY = clampAxis(newSubstickY);
            triggerLeft = clampInt(newTriggerLeft, 0, 255);
            triggerRight = clampInt(newTriggerRight, 0, 255);

            nativeSetTouchState(buttons, stickX, stickY, substickX, substickY,
                    triggerLeft, triggerRight);
            invalidate();
        }

        private static float[] axisForAssignedPoint(float px, float py, float cx, float cy,
                                                     float travelRadius) {
            float dx = px - cx;
            float dy = py - cy;
            float distance = (float) Math.hypot(dx, dy);
            if (distance > travelRadius && distance > 0f) {
                float scale = travelRadius / distance;
                dx *= scale;
                dy *= scale;
            }
            return new float[] {
                    clamp(dx / travelRadius, -1f, 1f),
                    clamp(dy / travelRadius, -1f, 1f)
            };
        }

        private static boolean insideCircle(float px, float py, float cx, float cy, float radius) {
            float dx = px - cx;
            float dy = py - cy;
            return dx * dx + dy * dy <= radius * radius;
        }

        private int clampAxis(int value) {
            return clampInt(value, -127, 127);
        }

        private static int clampInt(int value, int min, int max) {
            return Math.max(min, Math.min(max, value));
        }

        private static float clamp(float value, float min, float max) {
            return Math.max(min, Math.min(max, value));
        }

        private float dp(float value) {
            return value * density;
        }
    }
}
