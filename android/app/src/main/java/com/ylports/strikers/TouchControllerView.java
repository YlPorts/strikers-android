package com.ylports.strikers;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.SparseIntArray;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * Compact, configurable touch controller for Strikers.
 *
 * The default layout deliberately keeps only the controls needed during normal play.
 * D-pad and C-stick are available as "advanced" controls, but stay hidden by default.
 * The movement stick is a floating football-game style thumb dot with an invisible
 * travel radius instead of a large permanent GameCube ring.
 */
final class TouchControllerView extends View {
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
    private static final int ROLE_COUNT = 12;

    private static final String PREF_TOUCH_SCALE = "touch_scale";
    private static final String PREF_TOUCH_OPACITY = "touch_opacity";
    private static final String PREF_TOUCH_ADVANCED = "touch_advanced";
    private static final String PREF_TOUCH_FLOATING_STICK = "touch_floating_stick";
    private static final String PREF_TOUCH_CUSTOM_POSITIONS = "touch_custom_positions";

    private static final float DEFAULT_SCALE = 0.90f;
    private static final float DEFAULT_OPACITY = 0.42f;

    private static final String[] ROLE_KEYS = {
            "none", "stick", "cstick", "dpad", "a", "b", "x", "y", "l", "r", "z", "start"
    };

    private final StrikersActivity activity;
    private final SharedPreferences prefs;
    private final float density;
    private final SparseIntArray pointerRoles = new SparseIntArray();
    private final float[] posX = new float[ROLE_COUNT];
    private final float[] posY = new float[ROLE_COUNT];

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint banner = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float controlScale = DEFAULT_SCALE;
    private float opacity = DEFAULT_OPACITY;
    private boolean advancedControls;
    private boolean floatingStick = true;
    private boolean editMode;
    private int editPointerId = -1;
    private int editRole = ROLE_NONE;

    private float faceRadius;
    private float smallRadius;
    private float shoulderRadius;
    private float startRadius;
    private float stickKnobRadius;
    private float stickTravelRadius;
    private float cStickRadius;
    private float dpadStep;
    private float settingsCx;
    private float settingsCy;
    private float settingsRadius;

    private boolean mainStickActive;
    private int mainStickPointerId = -1;
    private float mainStickOriginX;
    private float mainStickOriginY;

    private int buttons;
    private int stickX;
    private int stickY;
    private int substickX;
    private int substickY;
    private int triggerLeft;
    private int triggerRight;
    private boolean stateSent;

    TouchControllerView(StrikersActivity context) {
        super(context);
        activity = context;
        prefs = context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        density = getResources().getDisplayMetrics().density;

        setFocusable(false);
        setClickable(true);
        setWillNotDraw(false);

        fill.setStyle(Paint.Style.FILL);
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeWidth(dp(1.35f));
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);
        banner.setStyle(Paint.Style.FILL);

        setDefaultPositions();
        reloadPreferences();
    }

    void reloadPreferences() {
        controlScale = clamp(prefs.getFloat(PREF_TOUCH_SCALE, DEFAULT_SCALE), 0.65f, 1.40f);
        opacity = clamp(prefs.getFloat(PREF_TOUCH_OPACITY, DEFAULT_OPACITY), 0.15f, 0.85f);
        advancedControls = prefs.getBoolean(PREF_TOUCH_ADVANCED, false);
        floatingStick = prefs.getBoolean(PREF_TOUCH_FLOATING_STICK, true);

        setDefaultPositions();
        if (prefs.getBoolean(PREF_TOUCH_CUSTOM_POSITIONS, false)) {
            for (int role = 1; role < ROLE_COUNT; role++) {
                posX[role] = clamp(prefs.getFloat(positionKey(role, "x"), posX[role]), 0.035f, 0.965f);
                posY[role] = clamp(prefs.getFloat(positionKey(role, "y"), posY[role]), 0.045f, 0.955f);
            }
        }
        recalculateSizes();
        postInvalidateOnAnimation();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        recalculateSizes();
    }

    private void recalculateSizes() {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        float shortSide = Math.min(w, h);
        faceRadius = clamp(shortSide * 0.052f * controlScale, dp(28), dp(48));
        smallRadius = faceRadius * 0.74f;
        shoulderRadius = faceRadius * 0.67f;
        startRadius = faceRadius * 0.55f;
        stickKnobRadius = clamp(shortSide * 0.037f * controlScale, dp(22), dp(38));
        stickTravelRadius = clamp(shortSide * 0.132f * controlScale, dp(64), dp(110));
        cStickRadius = faceRadius * 0.66f;
        dpadStep = faceRadius * 0.92f;
        settingsRadius = clamp(shortSide * 0.031f, dp(19), dp(27));
        settingsCx = settingsRadius + dp(10);
        settingsCy = settingsRadius + dp(9);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getWidth() <= 0 || getHeight() <= 0) {
            return;
        }

        if (advancedControls) {
            drawDpad(canvas);
            drawCStick(canvas);
        }

        drawMainStick(canvas);
        drawFaceButton(canvas, ROLE_A, "A", faceRadius, 0xFF52B978,
                (buttons & PAD_BUTTON_A) != 0);
        drawFaceButton(canvas, ROLE_B, "B", smallRadius, 0xFFD96868,
                (buttons & PAD_BUTTON_B) != 0);
        drawFaceButton(canvas, ROLE_X, "X", smallRadius, 0xFF679BE2,
                (buttons & PAD_BUTTON_X) != 0);
        drawFaceButton(canvas, ROLE_Y, "Y", smallRadius, 0xFFD5B74F,
                (buttons & PAD_BUTTON_Y) != 0);

        drawMinimalButton(canvas, ROLE_L, "L", shoulderRadius,
                (buttons & PAD_TRIGGER_L) != 0);
        drawMinimalButton(canvas, ROLE_R, "R", shoulderRadius,
                (buttons & PAD_TRIGGER_R) != 0);
        drawMinimalButton(canvas, ROLE_Z, "Z", shoulderRadius,
                (buttons & PAD_TRIGGER_Z) != 0);
        drawMinimalButton(canvas, ROLE_START, "II", startRadius,
                (buttons & PAD_BUTTON_START) != 0);

        drawSettingsButton(canvas);
        if (editMode) {
            drawEditorBanner(canvas);
        }
    }

    private void drawMainStick(Canvas canvas) {
        float cx = xForRole(ROLE_MAIN_STICK);
        float cy = yForRole(ROLE_MAIN_STICK);
        float knobX = cx;
        float knobY = cy;

        if (mainStickActive) {
            float originX = floatingStick ? mainStickOriginX : cx;
            float originY = floatingStick ? mainStickOriginY : cy;
            float visualTravel = stickTravelRadius * 0.78f;
            float dx = stickX / 127f * visualTravel;
            float dy = -stickY / 127f * visualTravel;
            knobX = originX + dx;
            knobY = originY + dy;
        }

        fill.setColor(colorWithOpacity(0xFFEDF2F7,
                opacity * (mainStickActive ? 0.82f : 0.52f)));
        canvas.drawCircle(knobX, knobY, stickKnobRadius, fill);
        outline.setColor(colorWithOpacity(0xFFFFFFFF, opacity * 0.72f));
        outline.setStrokeWidth(dp(1.25f));
        canvas.drawCircle(knobX, knobY, stickKnobRadius, outline);

        if (editMode) {
            drawEditHalo(canvas, cx, cy, stickKnobRadius * 1.45f);
        }
    }

    private void drawCStick(Canvas canvas) {
        float cx = xForRole(ROLE_C_STICK);
        float cy = yForRole(ROLE_C_STICK);
        float dx = substickX / 127f * cStickRadius * 0.78f;
        float dy = -substickY / 127f * cStickRadius * 0.78f;
        fill.setColor(colorWithOpacity(0xFFE8D36B, opacity * 0.45f));
        canvas.drawCircle(cx + dx, cy + dy, cStickRadius, fill);
        outline.setColor(colorWithOpacity(0xFFFFFFFF, opacity * 0.55f));
        canvas.drawCircle(cx + dx, cy + dy, cStickRadius, outline);
        drawText(canvas, "C", cx + dx, cy + dy, cStickRadius * 0.72f, opacity * 0.82f);
        if (editMode) {
            drawEditHalo(canvas, cx, cy, cStickRadius * 1.35f);
        }
    }

    private void drawDpad(Canvas canvas) {
        float cx = xForRole(ROLE_DPAD);
        float cy = yForRole(ROLE_DPAD);
        float r = dpadStep * 0.42f;
        drawDpadDirection(canvas, cx - dpadStep, cy, r, "<", PAD_BUTTON_LEFT);
        drawDpadDirection(canvas, cx + dpadStep, cy, r, ">", PAD_BUTTON_RIGHT);
        drawDpadDirection(canvas, cx, cy - dpadStep, r, "^", PAD_BUTTON_UP);
        drawDpadDirection(canvas, cx, cy + dpadStep, r, "v", PAD_BUTTON_DOWN);
        if (editMode) {
            drawEditHalo(canvas, cx, cy, dpadStep * 1.55f);
        }
    }

    private void drawDpadDirection(Canvas canvas, float cx, float cy, float radius,
                                   String label, int mask) {
        boolean pressed = (buttons & mask) != 0;
        fill.setColor(colorWithOpacity(0xFF69727E, opacity * (pressed ? 0.70f : 0.25f)));
        canvas.drawCircle(cx, cy, radius, fill);
        outline.setColor(colorWithOpacity(0xFFFFFFFF, opacity * 0.42f));
        canvas.drawCircle(cx, cy, radius, outline);
        drawText(canvas, label, cx, cy, radius * 0.90f, opacity * 0.75f);
    }

    private void drawFaceButton(Canvas canvas, int role, String label, float radius,
                                int color, boolean pressed) {
        float cx = xForRole(role);
        float cy = yForRole(role);
        fill.setColor(colorWithOpacity(color, opacity * (pressed ? 0.90f : 0.44f)));
        canvas.drawCircle(cx, cy, radius, fill);
        outline.setColor(colorWithOpacity(0xFFFFFFFF, opacity * (pressed ? 0.88f : 0.58f)));
        canvas.drawCircle(cx, cy, radius, outline);
        drawText(canvas, label, cx, cy, radius * 0.72f, opacity * 0.94f);
        if (editMode) {
            drawEditHalo(canvas, cx, cy, radius * 1.24f);
        }
    }

    private void drawMinimalButton(Canvas canvas, int role, String label, float radius,
                                   boolean pressed) {
        float cx = xForRole(role);
        float cy = yForRole(role);
        fill.setColor(colorWithOpacity(0xFF3F4855, opacity * (pressed ? 0.80f : 0.20f)));
        canvas.drawCircle(cx, cy, radius, fill);
        outline.setColor(colorWithOpacity(0xFFFFFFFF, opacity * (pressed ? 0.88f : 0.48f)));
        canvas.drawCircle(cx, cy, radius, outline);
        drawText(canvas, label, cx, cy, radius * 0.72f, opacity * 0.88f);
        if (editMode) {
            drawEditHalo(canvas, cx, cy, radius * 1.35f);
        }
    }

    private void drawSettingsButton(Canvas canvas) {
        fill.setColor(colorWithOpacity(0xFF1D2630, editMode ? 0.75f : 0.22f));
        canvas.drawCircle(settingsCx, settingsCy, settingsRadius, fill);
        outline.setColor(colorWithOpacity(0xFFFFFFFF, editMode ? 0.80f : 0.32f));
        canvas.drawCircle(settingsCx, settingsCy, settingsRadius, outline);
        drawText(canvas, editMode ? "OK" : "SET", settingsCx, settingsCy,
                settingsRadius * 0.55f, editMode ? 0.95f : 0.54f);
    }

    private void drawEditorBanner(Canvas canvas) {
        String message = "ARRASTRA LOS CONTROLES · OK PARA GUARDAR";
        float textSize = Math.max(dp(11), Math.min(getWidth(), getHeight()) * 0.024f);
        text.setTextSize(textSize);
        float width = text.measureText(message) + dp(28);
        float height = textSize * 2.05f;
        float left = getWidth() * 0.5f - width * 0.5f;
        float top = dp(8);
        banner.setColor(0xB5202730);
        canvas.drawRoundRect(left, top, left + width, top + height,
                height * 0.45f, height * 0.45f, banner);
        drawText(canvas, message, getWidth() * 0.5f, top + height * 0.5f,
                textSize, 0.95f);
    }

    private void drawEditHalo(Canvas canvas, float cx, float cy, float radius) {
        outline.setColor(0xBFFFFFFF);
        outline.setStrokeWidth(dp(1.15f));
        canvas.drawCircle(cx, cy, radius, outline);
    }

    private void drawText(Canvas canvas, String label, float x, float y, float size, float alpha) {
        text.setTextSize(Math.max(dp(10), size));
        text.setColor(colorWithOpacity(Color.WHITE, alpha));
        Paint.FontMetrics fm = text.getFontMetrics();
        float baseline = y - (fm.ascent + fm.descent) * 0.5f;
        canvas.drawText(label, x, baseline, text);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_OUTSIDE) {
            if (editMode) {
                editPointerId = -1;
                editRole = ROLE_NONE;
            } else {
                releaseAll();
            }
            return true;
        }

        if (editMode) {
            return handleEditorTouch(event);
        }

        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            int index = event.getActionIndex();
            float px = event.getX(index);
            float py = event.getY(index);
            if (insideCircle(px, py, settingsCx, settingsCy, settingsRadius * 1.45f)) {
                releaseAll();
                showTouchSettings();
                return true;
            }

            int pointerId = event.getPointerId(index);
            int role = roleForPoint(px, py, pointerId);
            if (role != ROLE_NONE) {
                pointerRoles.put(pointerId, role);
                if (role == ROLE_MAIN_STICK) {
                    mainStickActive = true;
                    mainStickPointerId = pointerId;
                    mainStickOriginX = floatingStick ? px : xForRole(ROLE_MAIN_STICK);
                    mainStickOriginY = floatingStick ? py : yForRole(ROLE_MAIN_STICK);
                }
            }
        }

        int liftedPointerId = -1;
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
            int index = event.getActionIndex();
            liftedPointerId = event.getPointerId(index);
            int role = pointerRoles.get(liftedPointerId, ROLE_NONE);
            pointerRoles.delete(liftedPointerId);
            if (role == ROLE_MAIN_STICK) {
                mainStickActive = false;
                mainStickPointerId = -1;
            }
            if (action == MotionEvent.ACTION_UP) {
                performClick();
            }
        }

        rebuildState(event, liftedPointerId);
        return true;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private boolean handleEditorTouch(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            float px = event.getX(0);
            float py = event.getY(0);
            if (insideCircle(px, py, settingsCx, settingsCy, settingsRadius * 1.55f)) {
                finishEditing();
                return true;
            }
            editPointerId = event.getPointerId(0);
            editRole = editableRoleForPoint(px, py);
            if (editRole != ROLE_NONE) {
                moveRoleTo(editRole, px, py);
            }
        } else if (action == MotionEvent.ACTION_MOVE && editRole != ROLE_NONE) {
            int index = event.findPointerIndex(editPointerId);
            if (index >= 0) {
                moveRoleTo(editRole, event.getX(index), event.getY(index));
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
            int index = event.getActionIndex();
            if (event.getPointerId(index) == editPointerId) {
                if (editRole != ROLE_NONE) {
                    moveRoleTo(editRole, event.getX(index), event.getY(index));
                    savePositions();
                }
                editPointerId = -1;
                editRole = ROLE_NONE;
            }
        }
        return true;
    }

    private int roleForPoint(float px, float py, int pointerId) {
        if (hitRole(px, py, ROLE_A)) return ROLE_A;
        if (hitRole(px, py, ROLE_B)) return ROLE_B;
        if (hitRole(px, py, ROLE_X)) return ROLE_X;
        if (hitRole(px, py, ROLE_Y)) return ROLE_Y;
        if (hitRole(px, py, ROLE_L)) return ROLE_L;
        if (hitRole(px, py, ROLE_R)) return ROLE_R;
        if (hitRole(px, py, ROLE_Z)) return ROLE_Z;
        if (hitRole(px, py, ROLE_START)) return ROLE_START;

        if (advancedControls) {
            if (!roleInUse(ROLE_C_STICK, pointerId)
                    && insideCircle(px, py, xForRole(ROLE_C_STICK), yForRole(ROLE_C_STICK),
                    cStickRadius * 1.65f)) {
                return ROLE_C_STICK;
            }
            float dcx = xForRole(ROLE_DPAD);
            float dcy = yForRole(ROLE_DPAD);
            if (insideCircle(px, py, dcx, dcy, dpadStep * 1.75f)) {
                return ROLE_DPAD;
            }
        }

        if (!roleInUse(ROLE_MAIN_STICK, pointerId)) {
            if (floatingStick) {
                if (px < getWidth() * 0.47f && py > getHeight() * 0.28f) {
                    return ROLE_MAIN_STICK;
                }
            } else if (insideCircle(px, py, xForRole(ROLE_MAIN_STICK),
                    yForRole(ROLE_MAIN_STICK), stickTravelRadius * 1.20f)) {
                return ROLE_MAIN_STICK;
            }
        }
        return ROLE_NONE;
    }

    private int editableRoleForPoint(float px, float py) {
        int bestRole = ROLE_NONE;
        float bestDistance2 = Float.MAX_VALUE;
        for (int role = 1; role < ROLE_COUNT; role++) {
            if (!isRoleVisible(role)) {
                continue;
            }
            float dx = px - xForRole(role);
            float dy = py - yForRole(role);
            float distance2 = dx * dx + dy * dy;
            float radius = editHitRadius(role);
            if (distance2 <= radius * radius && distance2 < bestDistance2) {
                bestDistance2 = distance2;
                bestRole = role;
            }
        }
        return bestRole;
    }

    private boolean hitRole(float px, float py, int role) {
        return insideCircle(px, py, xForRole(role), yForRole(role), gameplayHitRadius(role));
    }

    private float gameplayHitRadius(int role) {
        switch (role) {
            case ROLE_A:
                return faceRadius * 1.18f;
            case ROLE_B:
            case ROLE_X:
            case ROLE_Y:
                return smallRadius * 1.28f;
            case ROLE_L:
            case ROLE_R:
            case ROLE_Z:
                return shoulderRadius * 1.45f;
            case ROLE_START:
                return startRadius * 1.65f;
            default:
                return faceRadius;
        }
    }

    private float editHitRadius(int role) {
        switch (role) {
            case ROLE_MAIN_STICK:
                return stickKnobRadius * 1.65f;
            case ROLE_C_STICK:
                return cStickRadius * 1.55f;
            case ROLE_DPAD:
                return dpadStep * 1.75f;
            default:
                return gameplayHitRadius(role) * 1.15f;
        }
    }

    private boolean roleInUse(int role, int exceptPointerId) {
        for (int i = 0; i < pointerRoles.size(); i++) {
            if (pointerRoles.keyAt(i) != exceptPointerId && pointerRoles.valueAt(i) == role) {
                return true;
            }
        }
        return false;
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
                    float cx = floatingStick ? mainStickOriginX : xForRole(ROLE_MAIN_STICK);
                    float cy = floatingStick ? mainStickOriginY : yForRole(ROLE_MAIN_STICK);
                    float dx = px - cx;
                    float dy = py - cy;
                    float distance = (float) Math.hypot(dx, dy);
                    float travel = stickTravelRadius * 0.78f;
                    if (distance > travel && distance > 0f) {
                        float factor = travel / distance;
                        dx *= factor;
                        dy *= factor;
                    }
                    newStickX = Math.round(clamp(dx / travel, -1f, 1f) * 127f);
                    newStickY = Math.round(clamp(-dy / travel, -1f, 1f) * 127f);
                    break;
                }
                case ROLE_C_STICK: {
                    float cx = xForRole(ROLE_C_STICK);
                    float cy = yForRole(ROLE_C_STICK);
                    float dx = px - cx;
                    float dy = py - cy;
                    float travel = cStickRadius * 1.25f;
                    float distance = (float) Math.hypot(dx, dy);
                    if (distance > travel && distance > 0f) {
                        float factor = travel / distance;
                        dx *= factor;
                        dy *= factor;
                    }
                    newSubstickX = Math.round(clamp(dx / travel, -1f, 1f) * 127f);
                    newSubstickY = Math.round(clamp(-dy / travel, -1f, 1f) * 127f);
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
                newTriggerLeft, newTriggerRight, false);
    }

    private int dpadForPoint(float px, float py) {
        float dx = px - xForRole(ROLE_DPAD);
        float dy = py - yForRole(ROLE_DPAD);
        float dead = dpadStep * 0.28f;
        if (Math.abs(dx) < dead && Math.abs(dy) < dead) {
            return 0;
        }
        if (Math.abs(dx) > Math.abs(dy)) {
            return dx < 0f ? PAD_BUTTON_LEFT : PAD_BUTTON_RIGHT;
        }
        return dy < 0f ? PAD_BUTTON_UP : PAD_BUTTON_DOWN;
    }

    void releaseAll() {
        pointerRoles.clear();
        mainStickActive = false;
        mainStickPointerId = -1;
        setState(0, 0, 0, 0, 0, 0, 0, true);
    }

    private void setState(int newButtons, int newStickX, int newStickY,
                          int newSubstickX, int newSubstickY,
                          int newTriggerLeft, int newTriggerRight,
                          boolean forceVisualRefresh) {
        newStickX = clampInt(newStickX, -127, 127);
        newStickY = clampInt(newStickY, -127, 127);
        newSubstickX = clampInt(newSubstickX, -127, 127);
        newSubstickY = clampInt(newSubstickY, -127, 127);
        newTriggerLeft = clampInt(newTriggerLeft, 0, 255);
        newTriggerRight = clampInt(newTriggerRight, 0, 255);

        boolean changed = newButtons != buttons
                || newStickX != stickX || newStickY != stickY
                || newSubstickX != substickX || newSubstickY != substickY
                || newTriggerLeft != triggerLeft || newTriggerRight != triggerRight;

        if (!changed && stateSent && !forceVisualRefresh) {
            return;
        }

        buttons = newButtons;
        stickX = newStickX;
        stickY = newStickY;
        substickX = newSubstickX;
        substickY = newSubstickY;
        triggerLeft = newTriggerLeft;
        triggerRight = newTriggerRight;

        if (changed || !stateSent) {
            StrikersActivity.nativeSetTouchState(buttons, stickX, stickY,
                    substickX, substickY, triggerLeft, triggerRight);
            stateSent = true;
        }
        postInvalidateOnAnimation();
    }

    private void showTouchSettings() {
        final LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(dp(20));
        root.setPadding(pad, Math.round(dp(8)), pad, 0);

        final TextView sizeLabel = settingLabel("Tamaño · " + Math.round(controlScale * 100f) + "%");
        root.addView(sizeLabel);
        SeekBar size = new SeekBar(activity);
        size.setMin(65);
        size.setMax(140);
        size.setProgress(Math.round(controlScale * 100f));
        root.addView(size);

        final TextView opacityLabel = settingLabel("Opacidad · " + Math.round(opacity * 100f) + "%");
        root.addView(opacityLabel);
        SeekBar alpha = new SeekBar(activity);
        alpha.setMin(15);
        alpha.setMax(85);
        alpha.setProgress(Math.round(opacity * 100f));
        root.addView(alpha);

        CheckBox floating = new CheckBox(activity);
        floating.setText("Joystick flotante (estilo juego de fútbol)");
        floating.setChecked(floatingStick);
        root.addView(floating);

        CheckBox advanced = new CheckBox(activity);
        advanced.setText("Controles avanzados (cruceta + C-stick)");
        advanced.setChecked(advancedControls);
        root.addView(advanced);

        Button move = new Button(activity);
        move.setText("Mover botones en pantalla");
        move.setAllCaps(false);
        root.addView(move);

        Button reset = new Button(activity);
        reset.setText("Restablecer controles");
        reset.setAllCaps(false);
        root.addView(reset);

        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Controles táctiles")
                .setView(root)
                .setPositiveButton("Listo", null)
                .create();

        size.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                controlScale = progress / 100f;
                sizeLabel.setText("Tamaño · " + progress + "%");
                recalculateSizes();
                postInvalidateOnAnimation();
            }
        });
        alpha.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                opacity = progress / 100f;
                opacityLabel.setText("Opacidad · " + progress + "%");
                postInvalidateOnAnimation();
            }
        });
        floating.setOnCheckedChangeListener((buttonView, isChecked) -> {
            floatingStick = isChecked;
            releaseAll();
        });
        advanced.setOnCheckedChangeListener((buttonView, isChecked) -> {
            advancedControls = isChecked;
            releaseAll();
        });
        move.setOnClickListener(v -> {
            savePreferences();
            releaseAll();
            editMode = true;
            editPointerId = -1;
            editRole = ROLE_NONE;
            dialog.dismiss();
            postInvalidateOnAnimation();
        });
        reset.setOnClickListener(v -> {
            resetTouchSettings();
            size.setProgress(Math.round(controlScale * 100f));
            alpha.setProgress(Math.round(opacity * 100f));
            sizeLabel.setText("Tamaño · " + Math.round(controlScale * 100f) + "%");
            opacityLabel.setText("Opacidad · " + Math.round(opacity * 100f) + "%");
            floating.setChecked(floatingStick);
            advanced.setChecked(advancedControls);
            postInvalidateOnAnimation();
        });
        dialog.setOnDismissListener(v -> savePreferences());
        dialog.show();
    }

    private TextView settingLabel(String value) {
        TextView label = new TextView(activity);
        label.setText(value);
        label.setTextSize(14f);
        label.setTextColor(Color.WHITE);
        label.setPadding(0, Math.round(dp(10)), 0, 0);
        return label;
    }

    private void finishEditing() {
        savePositions();
        editMode = false;
        editPointerId = -1;
        editRole = ROLE_NONE;
        postInvalidateOnAnimation();
    }

    private void moveRoleTo(int role, float px, float py) {
        if (getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        float edgeX = Math.max(dp(16), editHitRadius(role) * 0.66f);
        float edgeY = Math.max(dp(16), editHitRadius(role) * 0.66f);
        px = clamp(px, edgeX, getWidth() - edgeX);
        py = clamp(py, edgeY, getHeight() - edgeY);
        posX[role] = px / getWidth();
        posY[role] = py / getHeight();
        postInvalidateOnAnimation();
    }

    private void savePositions() {
        SharedPreferences.Editor editor = prefs.edit().putBoolean(PREF_TOUCH_CUSTOM_POSITIONS, true);
        for (int role = 1; role < ROLE_COUNT; role++) {
            editor.putFloat(positionKey(role, "x"), posX[role]);
            editor.putFloat(positionKey(role, "y"), posY[role]);
        }
        editor.apply();
    }

    private void savePreferences() {
        prefs.edit()
                .putFloat(PREF_TOUCH_SCALE, controlScale)
                .putFloat(PREF_TOUCH_OPACITY, opacity)
                .putBoolean(PREF_TOUCH_ADVANCED, advancedControls)
                .putBoolean(PREF_TOUCH_FLOATING_STICK, floatingStick)
                .apply();
    }

    private void resetTouchSettings() {
        controlScale = DEFAULT_SCALE;
        opacity = DEFAULT_OPACITY;
        advancedControls = false;
        floatingStick = true;
        editMode = false;
        setDefaultPositions();
        prefs.edit()
                .remove(PREF_TOUCH_SCALE)
                .remove(PREF_TOUCH_OPACITY)
                .remove(PREF_TOUCH_ADVANCED)
                .remove(PREF_TOUCH_FLOATING_STICK)
                .remove(PREF_TOUCH_CUSTOM_POSITIONS)
                .apply();
        SharedPreferences.Editor editor = prefs.edit();
        for (int role = 1; role < ROLE_COUNT; role++) {
            editor.remove(positionKey(role, "x"));
            editor.remove(positionKey(role, "y"));
        }
        editor.apply();
        recalculateSizes();
        releaseAll();
    }

    private void setDefaultPositions() {
        posX[ROLE_MAIN_STICK] = 0.13f;
        posY[ROLE_MAIN_STICK] = 0.78f;
        posX[ROLE_C_STICK] = 0.65f;
        posY[ROLE_C_STICK] = 0.80f;
        posX[ROLE_DPAD] = 0.28f;
        posY[ROLE_DPAD] = 0.78f;

        posX[ROLE_A] = 0.875f;
        posY[ROLE_A] = 0.74f;
        posX[ROLE_B] = 0.785f;
        posY[ROLE_B] = 0.83f;
        posX[ROLE_X] = 0.952f;
        posY[ROLE_X] = 0.82f;
        posX[ROLE_Y] = 0.865f;
        posY[ROLE_Y] = 0.61f;

        posX[ROLE_L] = 0.68f;
        posY[ROLE_L] = 0.105f;
        posX[ROLE_Z] = 0.805f;
        posY[ROLE_Z] = 0.105f;
        posX[ROLE_R] = 0.93f;
        posY[ROLE_R] = 0.105f;
        posX[ROLE_START] = 0.515f;
        posY[ROLE_START] = 0.105f;
    }

    private boolean isRoleVisible(int role) {
        if ((role == ROLE_C_STICK || role == ROLE_DPAD) && !advancedControls) {
            return false;
        }
        return role > ROLE_NONE && role < ROLE_COUNT;
    }

    private float xForRole(int role) {
        return posX[role] * getWidth();
    }

    private float yForRole(int role) {
        return posY[role] * getHeight();
    }

    private static String positionKey(int role, String axis) {
        return "touch_pos_" + ROLE_KEYS[role] + "_" + axis;
    }

    private int colorWithOpacity(int color, float amount) {
        int alpha = clampInt(Math.round(clamp(amount, 0f, 1f) * 255f), 0, 255);
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private static boolean insideCircle(float px, float py, float cx, float cy, float radius) {
        float dx = px - cx;
        float dy = py - cy;
        return dx * dx + dy * dy <= radius * radius;
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

    private abstract static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {}

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {}
    }
}
