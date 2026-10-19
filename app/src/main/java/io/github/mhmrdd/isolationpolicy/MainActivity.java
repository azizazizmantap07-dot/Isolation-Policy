package io.github.mhmrdd.isolationpolicy;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Main activity with a full liquid-glass / glassmorphism UI.
 * All panels use translucent fills, soft borders, rounded geometry and
 * platform blur where available (API 31+).
 */
public class MainActivity extends Activity {

    private static final long FILTER_DEBOUNCE_MS = 60L;
    private static final long TEST_BIND_TIMEOUT_MS = 6000L;

    /* ── Liquid-glass palette ─────────────────────────────────────────── */
    private static final int BG_DEEP          = Color.parseColor("#FF0B1220");
    private static final int GLASS_FILL       = Color.parseColor("#28FFFFFF");
    private static final int GLASS_FILL_STRONG= Color.parseColor("#38FFFFFF");
    private static final int GLASS_BORDER     = Color.parseColor("#55FFFFFF");
    private static final int GLASS_BORDER_SOFT= Color.parseColor("#33FFFFFF");
    private static final int ACCENT           = Color.parseColor("#FF5AC8FA");
    private static final int ACCENT_SOFT      = Color.parseColor("#805AC8FA");
    private static final int TEXT_PRIMARY     = Color.parseColor("#FFF5F7FA");
    private static final int TEXT_SECONDARY   = Color.parseColor("#FFB8C0CC");
    private static final int TEXT_DIM         = Color.parseColor("#FF6B7380");
    private static final int RIPPLE           = Color.parseColor("#40FFFFFF");

    private StatusCard mStatus;
    private EditText mSearch;
    private RecyclerView mList;
    private ProgressBar mProgress;
    private Button mApply;

    private final Set<String> mDenied = new TreeSet<String>();
    private final Adapter mAdapter = new Adapter();
    private int mEligibleCount = 0;
    private StatusCard.Service mTestState = StatusCard.Service.BINDING;
    private String mTestDetail = "";
    private final Handler mUi = new Handler(Looper.getMainLooper());
    private final HandlerThread mBgThread = new HandlerThread("isolpolicy-bg");
    private Handler mBg;
    private String mFilter = "";
    private final Runnable mApplyFilter = new Runnable() {
        @Override public void run() { mAdapter.setFilter(mFilter); }
    };
    private ServiceConnection mTestConnection;
    private Runnable mTestTimeout;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applyWindowChrome();
        setContentView(buildLayout());

        mBgThread.start();
        mBg = new Handler(mBgThread.getLooper());

        loadDenied();
        refreshStatus();
        mBg.post(new ScanRunnable());
        runTest();
    }

    @Override
    protected void onDestroy() {
        cancelPendingTest();
        mBgThread.quitSafely();
        super.onDestroy();
    }

    /* ── Window-level liquid glass ────────────────────────────────────── */

    private void applyWindowChrome() {
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.parseColor("#CC0B1220"));

        // Edge-to-edge: we will apply insets ourselves so the Apply button
        // is never clipped by the gesture / navigation bar.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            w.setDecorFitsSystemWindows(false);
        } else {
            w.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }

        // Background blur of the wallpaper / previous content (API 31+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                w.setBackgroundBlurRadius(48);
            } catch (Throwable ignored) {
                // Some devices restrict blur radius
            }
        }

        // Soft status-bar icons on the dark glass background
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            View decor = w.getDecorView();
            int flags = decor.getSystemUiVisibility();
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            decor.setSystemUiVisibility(flags);
        }
    }

    /* ── Drawable helpers ─────────────────────────────────────────────── */

    private static GradientDrawable glassPanel(float radiusDp, int fill, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(radiusDp);
        d.setColor(fill);
        d.setStroke(Math.max(1, (int) (1.2f)), stroke);
        return d;
    }

    private static Drawable glassButton(float radiusDp) {
        GradientDrawable base = glassPanel(radiusDp, GLASS_FILL_STRONG, GLASS_BORDER);
        return new RippleDrawable(
                ColorStateList.valueOf(RIPPLE),
                base,
                null
        );
    }

    private static Drawable glassRow(float radiusDp) {
        GradientDrawable base = glassPanel(radiusDp, GLASS_FILL, GLASS_BORDER_SOFT);
        return new RippleDrawable(
                ColorStateList.valueOf(RIPPLE),
                base,
                null
        );
    }

    private static void applyRoundedOutline(View v, final float radiusPx) {
        v.setClipToOutline(true);
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radiusPx);
            }
        });
    }

    private static void tryBlur(View v, float radius) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                v.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP));
            } catch (Throwable ignored) {}
        }
    }

    /* ── Preferences ──────────────────────────────────────────────────── */

    @SuppressWarnings("deprecation")
    private SharedPreferences prefs() {
        try {
            return getSharedPreferences(Constants.PREFS_NAME, MODE_WORLD_READABLE);
        } catch (SecurityException ignored) {
            return getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE);
        }
    }

    private void loadDenied() {
        mDenied.clear();
        Set<String> stored = prefs().getStringSet(Constants.KEY_DENIED, null);
        if (stored != null) mDenied.addAll(stored);
        mDenied.add(Constants.APPLICATION_ID);
    }

    private void saveDenied() {
        Set<String> out = new HashSet<String>(mDenied);
        out.add(Constants.APPLICATION_ID);
        prefs().edit().putStringSet(Constants.KEY_DENIED, out).apply();
        if (BuildConfig.DEBUG) {
            android.util.Log.i(Constants.TAG, "wrote policy " + Constants.PREFS_NAME + " size=" + out.size());
        }
    }

    /* ── Background scan ──────────────────────────────────────────────── */

    private class ScanRunnable implements Runnable {
        @Override
        public void run() {
            PackageManager pm = getPackageManager();
            List<ApplicationInfo> all = pm.getInstalledApplications(0);
            List<Item> built = new ArrayList<Item>(all.size());
            int eligible = 0;
            String selfPkg = Constants.APPLICATION_ID;
            for (ApplicationInfo ai : all) {
                boolean isSelf = selfPkg.equals(ai.packageName);
                boolean isSystem = ai.uid < 10000
                        || ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                            && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0);
                Item.Kind k = isSelf ? Item.Kind.SELF
                        : isSystem ? Item.Kind.SYSTEM
                        : Item.Kind.USER;
                String label;
                try { label = ai.loadLabel(pm).toString(); } catch (Throwable t) { label = ai.packageName; }
                Drawable icon = null;
                try { icon = ai.loadIcon(pm); } catch (Throwable ignored) {}
                built.add(new Item(ai.packageName, label, icon, k));
                if (k == Item.Kind.USER) eligible++;
            }
            Collections.sort(built, ITEM_COMPARATOR);
            final int eligibleCount = eligible;
            mUi.post(new ApplyScanResult(built, eligibleCount));
        }
    }

    private class ApplyScanResult implements Runnable {
        private final List<Item> mResult;
        private final int mEligible;
        ApplyScanResult(List<Item> r, int e) { this.mResult = r; this.mEligible = e; }
        @Override
        public void run() {
            mEligibleCount = mEligible;
            mAdapter.setItems(mResult);
            refreshStatus();
            if (mProgress != null) mProgress.setVisibility(View.GONE);
            if (mList != null) mList.setVisibility(View.VISIBLE);
        }
    }

    private void rescan() {
        if (mProgress != null) mProgress.setVisibility(View.VISIBLE);
        if (mList != null) mList.setVisibility(View.GONE);
        mAdapter.setItems(Collections.<Item>emptyList());
        mEligibleCount = 0;
        refreshStatus();
        mBg.post(new ScanRunnable());
    }

    private void refreshStatus() {
        mStatus.update(ModuleStatus.isActive(), mTestState, mTestDetail,
                mEligibleCount, Math.max(mDenied.size() - 1, 0));
    }

    /* ── Isolated-service test ────────────────────────────────────────── */

    private void runTest() {
        mTestState = StatusCard.Service.BINDING;
        mTestDetail = "";
        refreshStatus();
        cancelPendingTest();
        final ServiceConnection conn = new ServiceConnection() {
            private boolean done;
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                if (done) return;
                done = true;
                deliverTestResult(service);
                cancelPendingTest();
            }
            @Override public void onServiceDisconnected(ComponentName name) {}
            @Override
            public void onNullBinding(ComponentName name) {
                if (done) return;
                done = true;
                mTestState = StatusCard.Service.FAILED;
                mTestDetail = "Null binding";
                refreshStatus();
                cancelPendingTest();
            }
        };
        mTestConnection = conn;
        Intent intent = new Intent(this, IsolationPolicyService.class);
        boolean bound;
        try {
            bound = bindIsolatedService(intent, Context.BIND_AUTO_CREATE,
                    "isolationpolicy", getMainExecutor(), conn);
        } catch (Throwable t) {
            bound = false;
            mTestState = StatusCard.Service.FAILED;
            mTestDetail = "Bind threw " + t.getClass().getSimpleName();
            refreshStatus();
        }
        if (!bound) {
            mTestConnection = null;
            mTestState = StatusCard.Service.FAILED;
            mTestDetail = "bindIsolatedService returned false";
            refreshStatus();
            return;
        }
        mTestTimeout = new Runnable() {
            @Override
            public void run() {
                if (mTestConnection != conn) return;
                try { unbindService(conn); } catch (Throwable ignored) {}
                mTestConnection = null;
                mTestTimeout = null;
                // bindIsolatedService() returned true but nothing connected. With the
                // module active this is exactly what the hook is meant to cause for our
                // own (always-denied) package, so report it as a successful block.
                if (ModuleStatus.isActive()) {
                    mTestState = StatusCard.Service.BLOCKED;
                    mTestDetail = "";
                } else {
                    mTestState = StatusCard.Service.FAILED;
                    mTestDetail = "Connection timed out (module not active, hook not loaded)";
                }
                refreshStatus();
            }
        };
        mUi.postDelayed(mTestTimeout, TEST_BIND_TIMEOUT_MS);
    }

    private void cancelPendingTest() {
        if (mTestTimeout != null) {
            mUi.removeCallbacks(mTestTimeout);
            mTestTimeout = null;
        }
        if (mTestConnection != null) {
            try { unbindService(mTestConnection); } catch (Throwable ignored) {}
            mTestConnection = null;
        }
    }

    private void deliverTestResult(IBinder service) {
        if (service == null) {
            mTestState = StatusCard.Service.FAILED;
            mTestDetail = "Null service";
            refreshStatus();
            return;
        }
        try {
            IIsolationPolicyService server = IIsolationPolicyService.Stub.asInterface(service);
            String report = server.getReport();
            mTestState = StatusCard.Service.BOUND;
            mTestDetail = report != null ? report : "";
        } catch (RemoteException e) {
            mTestState = StatusCard.Service.FAILED;
            mTestDetail = "Transact failed: " + e;
        }
        refreshStatus();
    }

    /* ── Layout construction (liquid glass) ───────────────────────────── */

    private View buildLayout() {
        final float density = getResources().getDisplayMetrics().density;
        final int pad = dp(14);
        final float cardRadius = 20f * density;
        final float btnRadius  = 16f * density;
        final float rowRadius  = 14f * density;

        // Root – deep translucent background
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG_DEEP);
        // Base horizontal padding; vertical padding will be driven by window insets
        // so the Apply button is never clipped by the navigation / gesture bar.
        final int basePad = pad;
        root.setPadding(basePad, basePad, basePad, basePad);

        // Apply system bar insets (status bar + navigation / gesture bar)
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int top;
                int bottom;
                int left;
                int right;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    android.graphics.Insets bars = insets.getInsets(
                            WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars()
                            | WindowInsets.Type.displayCutout());
                    top = bars.top;
                    bottom = bars.bottom;
                    left = bars.left;
                    right = bars.right;
                } else {
                    top = insets.getSystemWindowInsetTop();
                    bottom = insets.getSystemWindowInsetBottom();
                    left = insets.getSystemWindowInsetLeft();
                    right = insets.getSystemWindowInsetRight();
                }
                v.setPadding(
                        basePad + left,
                        basePad + top,
                        basePad + right,
                        basePad + bottom);
                return insets;
            }
        });
        root.requestApplyInsets();

        // ── Status glass card ──────────────────────────────────────────
        mStatus = new StatusCard(this);
        mStatus.setOnRetryListener(new Runnable() {
            @Override public void run() { runTest(); }
        });
        root.addView(mStatus, new LinearLayout.LayoutParams(MATCH, WRAP));

        // ── Search row (glass input + glass button) ────────────────────
        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);

        mSearch = new EditText(this);
        mSearch.setHint("filter by name or package");
        mSearch.setTextColor(TEXT_PRIMARY);
        mSearch.setHintTextColor(TEXT_DIM);
        mSearch.setTextSize(14f);
        mSearch.setSingleLine(true);
        mSearch.setPadding(dp(16), dp(12), dp(16), dp(12));
        mSearch.setBackground(glassPanel(btnRadius, GLASS_FILL, GLASS_BORDER_SOFT));
        applyRoundedOutline(mSearch, btnRadius);
        mSearch.addTextChangedListener(new SearchWatcher());
        searchRow.addView(mSearch, new LinearLayout.LayoutParams(0, WRAP, 1f));

        Button refresh = new Button(this);
        refresh.setText("Refresh");
        refresh.setTextSize(13f);
        refresh.setTextColor(TEXT_PRIMARY);
        refresh.setAllCaps(false);
        refresh.setPadding(dp(18), dp(10), dp(18), dp(10));
        refresh.setBackground(glassButton(btnRadius));
        refresh.setOnClickListener(new RefreshClick());
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(WRAP, WRAP);
        rp.leftMargin = dp(10);
        searchRow.addView(refresh, rp);

        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(MATCH, WRAP);
        sp.topMargin = dp(12);
        root.addView(searchRow, sp);

        // ── List glass container ───────────────────────────────────────
        FrameLayout listArea = new FrameLayout(this);
        listArea.setBackground(glassPanel(cardRadius, GLASS_FILL, GLASS_BORDER_SOFT));
        applyRoundedOutline(listArea, cardRadius);
        listArea.setPadding(dp(6), dp(6), dp(6), dp(6));

        mList = new RecyclerView(this);
        mList.setLayoutManager(new LinearLayoutManager(this));
        mList.setAdapter(mAdapter);
        mList.setHasFixedSize(true);
        mList.setClipToPadding(false);
        mList.setPadding(0, dp(4), 0, dp(4));
        mList.setVisibility(View.GONE);
        // Subtle vertical spacing between glass rows
        mList.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(android.graphics.Rect outRect, View view,
                                       RecyclerView parent, RecyclerView.State state) {
                outRect.bottom = dp(6);
            }
        });
        listArea.addView(mList, new FrameLayout.LayoutParams(MATCH, MATCH));

        mProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleLarge);
        mProgress.setIndeterminate(true);
        mProgress.setIndeterminateTintList(ColorStateList.valueOf(ACCENT));
        FrameLayout.LayoutParams pgp = new FrameLayout.LayoutParams(WRAP, WRAP);
        pgp.gravity = Gravity.CENTER;
        listArea.addView(mProgress, pgp);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, 0, 1f);
        lp.topMargin = dp(12);
        root.addView(listArea, lp);

        // ── Apply glass button ─────────────────────────────────────────
        mApply = new Button(this);
        mApply.setText("Apply changes");
        mApply.setTextSize(15f);
        mApply.setTextColor(TEXT_PRIMARY);
        mApply.setAllCaps(false);
        mApply.setTypeface(mApply.getTypeface(), Typeface.BOLD);
        mApply.setPadding(dp(20), dp(14), dp(20), dp(14));
        mApply.setBackground(glassButton(btnRadius));
        mApply.setOnClickListener(new ApplyClick());
        LinearLayout.LayoutParams brp = new LinearLayout.LayoutParams(MATCH, WRAP);
        brp.topMargin = dp(12);
        brp.bottomMargin = dp(4);  // small extra gap above the navigation/gesture bar
        root.addView(mApply, brp);

        return root;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    /* ── Data model ───────────────────────────────────────────────────── */

    private static final class Item {
        enum Kind { USER, SYSTEM, SELF }
        final String pkg;
        final String label;
        final String labelLower;
        final String pkgLower;
        final Drawable icon;
        final Kind kind;
        Item(String pkg, String label, Drawable icon, Kind kind) {
            this.pkg = pkg;
            this.label = label;
            this.labelLower = label.toLowerCase();
            this.pkgLower = pkg.toLowerCase();
            this.icon = icon;
            this.kind = kind;
        }
    }

    private final java.util.Comparator<Item> ITEM_COMPARATOR = new java.util.Comparator<Item>() {
        @Override
        public int compare(Item a, Item b) {
            int oa = order(a);
            int ob = order(b);
            if (oa != ob) return oa - ob;
            int byLabel = a.labelLower.compareTo(b.labelLower);
            if (byLabel != 0) return byLabel;
            return a.pkg.compareTo(b.pkg);
        }

        private int order(Item it) {
            if (it.kind == Item.Kind.SELF) return 0;
            if (it.kind == Item.Kind.SYSTEM) return 3;
            return mDenied.contains(it.pkg) ? 1 : 2;
        }
    };

    /* ── RecyclerView adapter ─────────────────────────────────────────── */

    private class Adapter extends RecyclerView.Adapter<RowVH> {
        private final List<Item> mAll = new ArrayList<Item>();
        private final List<Item> mShown = new ArrayList<Item>();
        private String mAdapterFilter = "";

        void setItems(List<Item> items) {
            mAll.clear();
            mAll.addAll(items);
            recompute();
        }

        void setFilter(String f) {
            mAdapterFilter = f == null ? "" : f.toLowerCase();
            recompute();
        }

        private void recompute() {
            mShown.clear();
            if (mAdapterFilter.isEmpty()) {
                mShown.addAll(mAll);
            } else {
                for (Item it : mAll) {
                    if (it.labelLower.contains(mAdapterFilter) || it.pkgLower.contains(mAdapterFilter)) {
                        mShown.add(it);
                    }
                }
            }
            notifyDataSetChanged();
        }

        @Override
        public RowVH onCreateViewHolder(ViewGroup parent, int viewType) {
            return new RowVH(buildRowView(parent.getContext()));
        }

        @Override
        public void onBindViewHolder(RowVH holder, int position) {
            holder.bind(mShown.get(position));
        }

        @Override
        public int getItemCount() { return mShown.size(); }
    }

    private View buildRowView(Context ctx) {
        final float density = ctx.getResources().getDisplayMetrics().density;
        final float rowRadius = 14f * density;

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int padH = (int) (12 * density);
        int padV = (int) (10 * density);
        row.setPadding(padH, padV, padH, padV);
        row.setLayoutParams(new RecyclerView.LayoutParams(MATCH, WRAP));
        row.setBackground(glassRow(rowRadius));
        applyRoundedOutline(row, rowRadius);

        ImageView icon = new ImageView(ctx);
        icon.setId(android.R.id.icon);
        int iconSize = (int) (42 * density);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(iconSize, iconSize);
        ip.rightMargin = (int) (12 * density);
        // Soft circular mask for icons
        icon.setClipToOutline(true);
        icon.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        row.addView(icon, ip);

        LinearLayout vbox = new LinearLayout(ctx);
        vbox.setOrientation(LinearLayout.VERTICAL);
        TextView label = new TextView(ctx);
        label.setId(android.R.id.text1);
        label.setTextSize(15f);
        label.setTextColor(TEXT_PRIMARY);
        label.setTypeface(label.getTypeface(), Typeface.BOLD);
        TextView pkg = new TextView(ctx);
        pkg.setId(android.R.id.text2);
        pkg.setTextSize(11.5f);
        pkg.setTextColor(TEXT_SECONDARY);
        pkg.setTypeface(Typeface.MONOSPACE);
        vbox.addView(label);
        vbox.addView(pkg);
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(0, WRAP, 1f);
        row.addView(vbox, vp);

        CheckBox cb = new CheckBox(ctx);
        cb.setId(android.R.id.checkbox);
        // Tint the checkbox to the accent colour
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cb.setButtonTintList(ColorStateList.valueOf(ACCENT));
        }
        row.addView(cb);

        return row;
    }

    private class RowVH extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView label;
        final TextView pkg;
        final CheckBox cb;
        Item bound;

        RowVH(View v) {
            super(v);
            icon = v.findViewById(android.R.id.icon);
            label = v.findViewById(android.R.id.text1);
            pkg = v.findViewById(android.R.id.text2);
            cb = v.findViewById(android.R.id.checkbox);
            cb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton btn, boolean on) {
                    if (bound == null || bound.kind != Item.Kind.USER) return;
                    if (on) mDenied.add(bound.pkg); else mDenied.remove(bound.pkg);
                }
            });
            v.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if (cb.isEnabled()) cb.setChecked(!cb.isChecked());
                }
            });
        }

        void bind(Item it) {
            bound = it;
            icon.setImageDrawable(it.icon);
            String prefix = it.kind == Item.Kind.SELF ? "[self] "
                    : it.kind == Item.Kind.SYSTEM ? "[oem] "
                    : "";
            label.setText(prefix + it.label);
            label.setTextColor(it.kind == Item.Kind.USER ? TEXT_PRIMARY : TEXT_DIM);
            pkg.setText(it.pkg);
            pkg.setTextColor(it.kind == Item.Kind.USER ? TEXT_SECONDARY : TEXT_DIM);
            cb.setOnCheckedChangeListener(null);
            if (it.kind == Item.Kind.SELF) {
                cb.setChecked(true);
                cb.setEnabled(false);
            } else if (it.kind == Item.Kind.SYSTEM) {
                cb.setChecked(false);
                cb.setEnabled(false);
            } else {
                cb.setChecked(mDenied.contains(it.pkg));
                cb.setEnabled(true);
            }
            cb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton btn, boolean on) {
                    if (bound == null || bound.kind != Item.Kind.USER) return;
                    if (on) mDenied.add(bound.pkg); else mDenied.remove(bound.pkg);
                }
            });
        }
    }

    /* ── Click / text listeners ───────────────────────────────────────── */

    private class ApplyClick implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            saveDenied();
            refreshStatus();
        }
    }

    private class SearchWatcher implements TextWatcher {
        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        @Override public void afterTextChanged(Editable s) {}
        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            mFilter = s.toString();
            mUi.removeCallbacks(mApplyFilter);
            mUi.postDelayed(mApplyFilter, FILTER_DEBOUNCE_MS);
        }
    }

    private class RefreshClick implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            rescan();
        }
    }
}
