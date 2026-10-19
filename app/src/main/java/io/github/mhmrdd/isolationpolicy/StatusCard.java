package io.github.mhmrdd.isolationpolicy;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Modern glass status indicator.
 *
 * Replaces the old monospace text dump with:
 *  - header + health pill (Checking / Healthy / Module off / Issue detected)
 *  - pulsing status dots per row
 *  - shimmer line while the isolated service is binding
 *  - cross-fading values, animated detail box, count-up numbers
 *  - tap the error box to retry, long-press the card to copy the plain text
 */
public final class StatusCard extends LinearLayout {

    public enum Service { BINDING, BOUND, BLOCKED, FAILED }

    private static final int TEXT_PRIMARY   = 0xFFF5F7FA;
    private static final int TEXT_SECONDARY = 0xFFB8C0CC;
    private static final int TEXT_DIM       = 0xFF7F8896;
    private static final int ACCENT = 0xFF5AC8FA;
    private static final int OK     = 0xFF3DDC97;
    private static final int WARN   = 0xFFFFB547;
    private static final int BAD    = 0xFFFF6B6B;

    private static final long PERIOD_FAST = 900L;
    private static final long PERIOD_SLOW = 2400L;

    private final float density;
    private final PulseDot moduleDot;
    private final PulseDot serviceDot;
    private final PulseDot pillDot;
    private final TextView moduleValue;
    private final TextView serviceValue;
    private final TextView pillText;
    private final TextView detailText;
    private final SweepLine sweep;
    private final Counter eligible;
    private final Counter selected;
    private final GradientDrawable pillBg = new GradientDrawable();
    private final GradientDrawable detailBg = new GradientDrawable();

    private Runnable retry;
    private boolean entered;
    private int pillShown = ACCENT;
    private int pillTarget = ACCENT;
    private ValueAnimator pillAnim;
    private ValueAnimator detailAnim;
    private String detailKey;

    private boolean lastActive;
    private Service lastService = Service.BINDING;
    private String lastDetail = "";
    private int lastEligible;
    private int lastSelected;

    public StatusCard(Context ctx) {
        super(ctx);
        density = ctx.getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);
        setPadding(dp(18), dp(16), dp(18), dp(16));

        // Glass background with a soft diagonal sheen
        final float radius = dp(22);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0x3CFFFFFF, 0x14FFFFFF});
        bg.setCornerRadius(radius);
        bg.setStroke(dp(1), 0x40FFFFFF);
        setBackground(bg);
        setClipToOutline(true);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });

        // ── Header: title + health pill ───────────────────────────────
        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = text(10.5f, TEXT_SECONDARY, 600, 0.16f);
        title.setText("SYSTEM STATUS");
        header.addView(title, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout pill = new LinearLayout(ctx);
        pill.setOrientation(HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(8), dp(4), dp(12), dp(4));
        pillBg.setCornerRadius(dp(20));
        pill.setBackground(pillBg);
        applyPillTint(ACCENT);

        pillDot = new PulseDot(ctx, dp(3));
        pill.addView(pillDot, new LinearLayout.LayoutParams(dp(14), dp(14)));
        pillText = text(11.5f, ACCENT, 600, 0.02f);
        LinearLayout.LayoutParams ptp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        ptp.leftMargin = dp(4);
        pill.addView(pillText, ptp);
        header.addView(pill, new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        addView(header, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        // ── Shimmer divider ───────────────────────────────────────────
        sweep = new SweepLine(ctx);
        LinearLayout.LayoutParams swp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(2));
        swp.topMargin = dp(14);
        swp.bottomMargin = dp(4);
        addView(sweep, swp);

        // ── Module row ────────────────────────────────────────────────
        moduleDot = new PulseDot(ctx, dp(4));
        moduleValue = text(14f, TEXT_PRIMARY, 600, 0f);
        addView(row("Module", moduleDot, moduleValue),
                new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        // ── Isolated service row + detail box ─────────────────────────
        serviceDot = new PulseDot(ctx, dp(4));
        serviceValue = text(14f, TEXT_PRIMARY, 600, 0f);
        LinearLayout serviceBlock = new LinearLayout(ctx);
        serviceBlock.setOrientation(VERTICAL);
        serviceBlock.addView(row("Isolated service", serviceDot, serviceValue),
                new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        detailText = text(12.5f, TEXT_SECONDARY, 400, 0f);
        detailText.setPadding(dp(12), dp(10), dp(12), dp(10));
        detailBg.setCornerRadius(dp(12));
        detailText.setBackground(new RippleDrawable(ColorStateList.valueOf(0x30FFFFFF), detailBg, null));
        detailText.setOnClickListener(v -> {
            if (lastService == Service.FAILED && retry != null) retry.run();
        });
        detailText.setClickable(false);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        dlp.leftMargin = dp(26);
        dlp.topMargin = dp(2);
        dlp.bottomMargin = dp(4);
        serviceBlock.addView(detailText, dlp);
        addView(serviceBlock, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        // ── Stat tiles ────────────────────────────────────────────────
        LinearLayout stats = new LinearLayout(ctx);
        stats.setOrientation(HORIZONTAL);
        TextView eligibleNum = bigNumber();
        TextView selectedNum = bigNumber();
        eligible = new Counter(eligibleNum);
        selected = new Counter(selectedNum);
        LinearLayout.LayoutParams t1 = new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        t1.rightMargin = dp(5);
        LinearLayout.LayoutParams t2 = new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        t2.leftMargin = dp(5);
        stats.addView(tile("ELIGIBLE APPS", eligibleNum), t1);
        stats.addView(tile("SELECTED", selectedNum), t2);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(8);
        addView(stats, sp);

        // Long-press copies the plain-text report (replaces the old selectable text)
        setOnLongClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("IsolPolicy status", toPlainText()));
                Toast.makeText(getContext(), "Status copied", Toast.LENGTH_SHORT).show();
            }
            return true;
        });

        // Prepare staggered entrance
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            c.setAlpha(0f);
            c.setTranslationY(dp(10));
        }
    }

    /* ── Public API ───────────────────────────────────────────────────── */

    public void setOnRetryListener(Runnable r) {
        this.retry = r;
    }

    public void update(boolean moduleActive, Service service, String detail,
                       int eligibleApps, int selectedApps) {
        final String d = detail == null ? "" : detail;
        lastActive = moduleActive;
        lastService = service;
        lastDetail = d;
        lastEligible = eligibleApps;
        lastSelected = selectedApps;

        int modColor = moduleActive ? OK : WARN;
        moduleDot.setState(modColor, moduleActive, PERIOD_SLOW);
        setValue(moduleValue, moduleActive ? "Active" : "Disabled", modColor);

        // BLOCKED = bind accepted but no connection: the hook is denying the
        // app zygote fork for this package, which is the expected result here.
        // BOUND while the module is active means the hook did NOT take effect.
        int svcColor;
        String svcLabel;
        switch (service) {
            case BINDING: svcColor = ACCENT; svcLabel = "Binding\u2026"; break;
            case BLOCKED: svcColor = OK;     svcLabel = "Blocked (OK)"; break;
            case BOUND:
                svcColor = moduleActive ? WARN : OK;
                svcLabel = moduleActive ? "Not blocked" : "Bound";
                break;
            default:      svcColor = BAD;    svcLabel = "Error"; break;
        }
        serviceDot.setState(svcColor, service != Service.FAILED,
                service == Service.BINDING ? PERIOD_FAST : PERIOD_SLOW);
        setValue(serviceValue, svcLabel, svcColor);
        sweep.setActive(service == Service.BINDING);

        setDetail(service, d);
        applyHealth(moduleActive, service);

        eligible.set(eligibleApps);
        selected.set(selectedApps);
        selected.tv.setTextColor(selectedApps > 0 ? ACCENT : TEXT_PRIMARY);
    }

    /** Plain-text version, same layout as the old indicator (handy for bug reports). */
    public String toPlainText() {
        StringBuilder sb = new StringBuilder();
        sb.append("module           : ").append(lastActive ? "active" : "disabled").append('\n');
        switch (lastService) {
            case BINDING:
                sb.append("isolated service : binding ...\n");
                break;
            case BOUND:
                sb.append(lastActive ? "isolated service : bound (NOT blocked)\n"
                                     : "isolated service : bound\n");
                if (!lastDetail.isEmpty()) sb.append(lastDetail).append('\n');
                break;
            case BLOCKED:
                sb.append("isolated service : blocked by policy (expected)\n");
                break;
            default:
                sb.append("isolated service : not bound\n");
                if (!lastDetail.isEmpty()) sb.append("reason : ").append(lastDetail).append('\n');
                break;
        }
        sb.append("eligible: ").append(lastEligible).append("    selected: ").append(lastSelected);
        return sb.toString();
    }

    /* ── Lifecycle ────────────────────────────────────────────────────── */

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (entered) return;
        entered = true;
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).animate()
                    .alpha(1f).translationY(0f)
                    .setStartDelay(60L * i)
                    .setDuration(380L)
                    .setInterpolator(new DecelerateInterpolator(1.6f))
                    .start();
        }
    }

    /* ── Internals ────────────────────────────────────────────────────── */

    private int dp(float v) {
        return (int) (v * density + 0.5f);
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private TextView text(float sp, int color, int weight, float letterSpacing) {
        TextView t = new TextView(getContext());
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(Typeface.DEFAULT, weight, false));
        t.setLetterSpacing(letterSpacing);
        t.setIncludeFontPadding(false);
        return t;
    }

    private TextView bigNumber() {
        TextView t = text(28f, TEXT_PRIMARY, 300, -0.01f);
        t.setFontFeatureSettings("tnum");
        return t;
    }

    private LinearLayout row(String label, PulseDot dot, TextView value) {
        LinearLayout r = new LinearLayout(getContext());
        r.setOrientation(HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(8), 0, dp(8));
        LinearLayout.LayoutParams dp1 = new LinearLayout.LayoutParams(dp(16), dp(16));
        dp1.rightMargin = dp(10);
        r.addView(dot, dp1);
        TextView l = text(13.5f, TEXT_SECONDARY, 400, 0f);
        l.setText(label);
        r.addView(l, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        r.addView(value, new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        return r;
    }

    private LinearLayout tile(String caption, TextView number) {
        LinearLayout t = new LinearLayout(getContext());
        t.setOrientation(VERTICAL);
        t.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(16));
        g.setColor(0x14FFFFFF);
        g.setStroke(dp(1), 0x1FFFFFFF);
        t.setBackground(g);
        t.addView(number, new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        TextView cap = text(9.5f, TEXT_DIM, 600, 0.14f);
        cap.setText(caption);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        cp.topMargin = dp(4);
        t.addView(cap, cp);
        return t;
    }

    /** Cross-fade + slide when a value changes; first fill is instant. */
    private void setValue(final TextView tv, final String value, final int color) {
        Object cur = tv.getTag();
        if (cur == null) {
            tv.setText(value);
            tv.setTextColor(color);
            tv.setTag(value);
            return;
        }
        if (value.equals(cur)) return;
        tv.setTag(value);
        tv.animate().cancel();
        tv.animate().alpha(0f).translationY(-dp(4)).setDuration(120L)
                .withEndAction(() -> {
                    tv.setText(value);
                    tv.setTextColor(color);
                    tv.setTranslationY(dp(4));
                    tv.animate().alpha(1f).translationY(0f).setDuration(180L)
                            .setInterpolator(new DecelerateInterpolator()).start();
                }).start();
    }

    private void applyHealth(boolean moduleActive, Service service) {
        int c;
        String label;
        if (service == Service.BINDING) {
            c = ACCENT; label = "Checking\u2026";
        } else if (service == Service.FAILED) {
            c = BAD; label = "Issue detected";
        } else if (!moduleActive) {
            c = WARN; label = "Module off";
        } else if (service == Service.BOUND) {
            c = WARN; label = "Hook not effective";
        } else {
            c = OK; label = "Healthy";
        }
        pillDot.setState(c, service == Service.BINDING || c == OK,
                service == Service.BINDING ? PERIOD_FAST : PERIOD_SLOW);
        setValue(pillText, label, c);
        tintPill(c);
    }

    private void applyPillTint(int c) {
        pillBg.setColor(withAlpha(c, 0x26));
        pillBg.setStroke(dp(1), withAlpha(c, 0x59));
    }

    private void tintPill(final int to) {
        if (to == pillTarget) return;
        pillTarget = to;
        if (pillAnim != null) pillAnim.cancel();
        pillAnim = ValueAnimator.ofObject(new ArgbEvaluator(), pillShown, to);
        pillAnim.setDuration(350L);
        pillAnim.addUpdateListener(a -> {
            pillShown = (Integer) a.getAnimatedValue();
            applyPillTint(pillShown);
        });
        pillAnim.start();
    }

    private void setDetail(Service s, String reason) {
        final String key = s.name() + "|" + reason;
        if (key.equals(detailKey)) return;
        final boolean animate = detailKey != null && detailText.isLaidOut() && detailText.getWidth() > 0;
        detailKey = key;

        final int from = detailText.getHeight();
        if (detailAnim != null) detailAnim.cancel();
        final LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) detailText.getLayoutParams();
        lp.height = LayoutParams.WRAP_CONTENT;

        int tint;
        CharSequence content;
        boolean mono = false;
        if (s == Service.BINDING) {
            tint = ACCENT;
            content = "Connecting to isolated process\u2026";
        } else if (s == Service.BLOCKED) {
            tint = OK;
            content = "Policy works: the bind was accepted but the app zygote fork "
                    + "was denied for this app, as intended.";
        } else if (s == Service.BOUND) {
            tint = lastActive ? WARN : OK;
            mono = true;
            String body = reason.isEmpty() ? "Connected" : reason;
            content = lastActive
                    ? "Service connected, so the hook did not block it. Enable the "
                      + "module for System Framework (android) in LSPosed and reboot.\n\n" + body
                    : body;
        } else {
            tint = BAD;
            SpannableStringBuilder sb = new SpannableStringBuilder(reason.isEmpty() ? "Unknown error" : reason);
            int start = sb.length() + 1;
            sb.append("\nTap to retry");
            sb.setSpan(new ForegroundColorSpan(ACCENT), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            content = sb;
        }
        detailBg.setColor(withAlpha(tint, 0x1C));
        detailBg.setStroke(dp(1), withAlpha(tint, 0x44));
        detailText.setTypeface(mono ? Typeface.MONOSPACE : Typeface.create(Typeface.DEFAULT, 400, false));
        detailText.setTextSize(mono ? 11f : 12.5f);
        detailText.setTextColor(s == Service.FAILED ? TEXT_PRIMARY : TEXT_SECONDARY);
        detailText.setLineSpacing(dp(2), 1f);
        detailText.setText(content);
        detailText.setClickable(s == Service.FAILED);
        detailText.setLayoutParams(lp);

        if (!animate) return;

        detailText.measure(
                View.MeasureSpec.makeMeasureSpec(detailText.getWidth(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        final int to = detailText.getMeasuredHeight();

        detailText.animate().cancel();
        detailText.setAlpha(0f);
        detailText.animate().alpha(1f).setDuration(260L).start();
        if (to == from) return;

        lp.height = from;
        detailText.setLayoutParams(lp);
        detailAnim = ValueAnimator.ofInt(from, to);
        detailAnim.setDuration(280L);
        detailAnim.setInterpolator(new DecelerateInterpolator());
        detailAnim.addUpdateListener(a -> {
            lp.height = (Integer) a.getAnimatedValue();
            detailText.setLayoutParams(lp);
        });
        detailAnim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                lp.height = LayoutParams.WRAP_CONTENT;
                detailText.setLayoutParams(lp);
            }
        });
        detailAnim.start();
    }

    /* ── Count-up number ──────────────────────────────────────────────── */

    private final class Counter {
        final TextView tv;
        int shown = 0;
        int target = 0;
        ValueAnimator anim;

        Counter(TextView tv) {
            this.tv = tv;
            tv.setText("0");
        }

        void set(int to) {
            if (to == target) return;
            target = to;
            if (anim != null) anim.cancel();
            anim = ValueAnimator.ofInt(shown, to);
            anim.setDuration(Math.min(900L, 250L + Math.abs(to - shown) * 12L));
            anim.setInterpolator(new DecelerateInterpolator());
            anim.addUpdateListener(a -> {
                shown = (Integer) a.getAnimatedValue();
                tv.setText(String.valueOf(shown));
            });
            anim.start();
        }
    }

    /* ── Status dot with expanding halo ───────────────────────────────── */

    private static final class PulseDot extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float coreR;
        private int color = ACCENT;
        private int target = ACCENT;
        private boolean pulsing = true;
        private boolean attached;
        private long period = PERIOD_SLOW;
        private float phase = 1f;
        private ValueAnimator pulse;
        private ValueAnimator tint;

        PulseDot(Context c, float coreRadiusPx) {
            super(c);
            coreR = coreRadiusPx;
        }

        void setState(int newColor, boolean pulseOn, long periodMs) {
            this.pulsing = pulseOn;
            this.period = periodMs;
            syncPulse();
            if (newColor == target) return;
            target = newColor;
            if (tint != null) tint.cancel();
            tint = ValueAnimator.ofObject(new ArgbEvaluator(), color, newColor);
            tint.setDuration(350L);
            tint.addUpdateListener(a -> {
                color = (Integer) a.getAnimatedValue();
                invalidate();
            });
            tint.start();
        }

        private void syncPulse() {
            if (pulsing && attached) {
                if (pulse == null) {
                    pulse = ValueAnimator.ofFloat(0f, 1f);
                    pulse.setRepeatCount(ValueAnimator.INFINITE);
                    pulse.setInterpolator(new LinearInterpolator());
                    pulse.addUpdateListener(a -> {
                        phase = (Float) a.getAnimatedValue();
                        invalidate();
                    });
                }
                pulse.setDuration(period);
                if (!pulse.isStarted()) pulse.start();
            } else {
                if (pulse != null) {
                    pulse.cancel();
                    pulse = null;
                }
                phase = 1f;
                invalidate();
            }
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            attached = true;
            syncPulse();
        }

        @Override
        protected void onDetachedFromWindow() {
            attached = false;
            syncPulse();
            if (tint != null) tint.cancel();
            color = target;
            super.onDetachedFromWindow();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float maxR = Math.min(getWidth(), getHeight()) / 2f;

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(withAlpha(color, 0x30));
            canvas.drawCircle(cx, cy, coreR * 1.8f, paint);

            if (pulsing) {
                float eased = 1f - (1f - phase) * (1f - phase);
                paint.setColor(withAlpha(color, (int) (0x66 * (1f - phase))));
                canvas.drawCircle(cx, cy, coreR + (maxR - coreR) * eased, paint);
            }

            paint.setColor(color);
            canvas.drawCircle(cx, cy, coreR, paint);
        }
    }

    /* ── Divider that shimmers while binding ──────────────────────────── */

    private static final class SweepLine extends View {
        private final Paint base = new Paint();
        private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean active;
        private boolean attached;
        private float segW;
        private float phase;
        private ValueAnimator anim;

        SweepLine(Context c) {
            super(c);
            base.setColor(0x1FFFFFFF);
        }

        void setActive(boolean on) {
            active = on;
            sync();
        }

        private void sync() {
            if (active && attached) {
                if (anim == null) {
                    anim = ValueAnimator.ofFloat(0f, 1f);
                    anim.setDuration(1100L);
                    anim.setRepeatCount(ValueAnimator.INFINITE);
                    anim.setInterpolator(new LinearInterpolator());
                    anim.addUpdateListener(a -> {
                        phase = (Float) a.getAnimatedValue();
                        invalidate();
                    });
                }
                if (!anim.isStarted()) anim.start();
            } else {
                if (anim != null) {
                    anim.cancel();
                    anim = null;
                }
                invalidate();
            }
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            segW = w * 0.38f;
            bar.setShader(new LinearGradient(0f, 0f, segW, 0f,
                    new int[]{withAlpha(ACCENT, 0), ACCENT, withAlpha(ACCENT, 0)},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            attached = true;
            sync();
        }

        @Override
        protected void onDetachedFromWindow() {
            attached = false;
            sync();
            super.onDetachedFromWindow();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            canvas.drawRect(0, 0, w, h, base);
            if (active && anim != null) {
                float x = -segW + (w + segW) * phase;
                canvas.save();
                canvas.translate(x, 0f);
                canvas.drawRect(0, 0, segW, h, bar);
                canvas.restore();
            }
        }
    }
}
