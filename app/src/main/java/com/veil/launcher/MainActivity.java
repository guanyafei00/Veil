package com.veil.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.RectF;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.InputType;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.AnimationUtils;
import android.view.animation.LayoutAnimationController;
import android.widget.FrameLayout;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * tvOS style home: black, a pinned dock row, an app grid, and one glass highlight that springs between tiles.
 */
public class MainActivity extends Activity implements AppAdapter.TileListener {

    /** Tiles per row and dock slots (res/values/config.xml, columns). Set in onCreate. */
    static int COLUMNS = 4;
    private static final long MINUTE_MS = 60_000L;
    private String liveTvPkg;
    private String liveTvAction;
    private String inputAction;
    private String sourcePanelService;
    private String sourcePanelAction;

    private AppAdapter adapter;
    private RecyclerView grid;
    private FrameLayout root;
    private GlassView glass;
    private TextView heroName;
    private boolean heroShown;
    private TextView greeting;
    private View btnInputs;
    private TextView btnWifi;
    private TextView shamsiDate;
    private boolean shamsi;
    private String vpnApp;
    private SpringAnimation springX, springY;
    private float glassTargetX, glassTargetY;
    // How far the grid has scrolled the glass. springY works without it, in grid content space, so a
    // scroll moves the glass in lockstep with its tile instead of becoming a moving spring target.
    private float glassScrollShift;
    private boolean glassShown;
    /** The glow's current corner radius, tracked so a radius-pref change updates it on the next focus. */
    private float glassRadiusPx = -1f;
    // Static so they survive the activity being destroyed while another app is open (low-memory TVs,
    // or Developer options → "Don't keep activities"); they only reset when the process dies.
    private static int lastFocused = 0;
    /** App of the last focused tile, so focus follows it when the app list changes. */
    private static String lastFocusedKey;
    private float density;
    /** config.xml columns, kept so the owner's dock-size pick (PrefsStore) can fall back to it. */
    private int defaultColumns;
    /** config.xml show_home_settings_shortcut, kept for rebuilds when the dock size changes. */
    private boolean showHomeSettingsShortcut;
    /** What the current grid/decoration was built with, so panel picks force a rebuild on change. */
    private int builtTint = Integer.MIN_VALUE, builtRadiusPct = Integer.MIN_VALUE;
    private boolean builtDockVisible = true, builtDockBarOn = true;
    private DockDecoration dockDecoration;
    /** The focused dock app's banner art, shown full-bleed behind the desktop (Apple TV style). */
    private android.widget.ImageView heroArt;
    /**
     * Off = pure-wallpaper first page: the layout waits below the fold and a Down press slides it
     * up (an Up on the first row, or Back, slides it back). With the dock bar off the revealed
     * rows are the same layout minus the pinned row.
     */
    private boolean dockVisible = true;
    /** Off = no dock bar at all: no pinned row, the icons keep the same layout and behaviour. */
    private boolean dockBarOn = true;
    /** Icon corner radius as a fraction, mirrored from the prefs for the plate and the glass. */
    private float radiusFrac;
    /** The wallpaper view, kept so a new picture replaces it instead of stacking. */
    private android.widget.ImageView wallpaperView;
    /** The incoming picture during a cross-fade; settles into wallpaperView when the fade ends. */
    private android.widget.ImageView wallFadeView;
    /** The live Apple aerial video surface behind the UI; the desktop "航拍视频真播" background. */
    private SurfaceView aerialVideoView;
    /** Streams the aerial clips; toggles wallpaperView so the video shows through when playing. */
    private AerialWallpaperPlayer aerialVideo;
    private boolean wallTransitioning;
    /** The Ken Burns breath running on wallpaperView; paused while the desktop is away. */
    private android.animation.ValueAnimator breatheAnim;
    private final java.util.Random rnd = new java.util.Random();
    private long wallpaperStamp = -1;
    /** The aerial-rotation switch state as of the last resume: catching it newly ON rotates now. */
    private boolean aerialWasOn;
    /** The Bing-rotation switch state as of the last resume: catching it newly ON rotates now. */
    private boolean bingWasOn;
    /** Weather clock (inspired by AT4K): the line under the date, fed by Open-Meteo. */
    private TextView weatherText;
    /** The app whose icon the owner is replacing (icon management); set before the picture picker opens. */
    private String pendingIconPkg;
    /** Result code for the icon picture picker (the wallpaper picker lives in the settings panel). */
    private static final int REQUEST_ICON = 43;
    /** Result code for the system's one-time screen-capture consent (app content previews). */
    private static final int REQUEST_CAPTURE = 44;
    /** The app tile waiting for the capture consent, so it can open right after the dialog. */
    private AppAdapter.Entry pendingLaunchEntry;
    /** The top glass shelf (frosted wallpaper) and the clock block it spans. */
    private View topBar, glassShelf;
    /** The rendered frost for the shelf; rebuilt when the wallpaper or the shelf height changes. */
    private Bitmap topFrost;
    /** The wallpaper the current frost was rendered from (a new picture must re-render it). */
    private Bitmap topFrostWall;
    private int topFrostH = -1;
    /** The shelf's bottom edge in grid coordinates: rows fade out as they cross it. */
    private int glassBottomInGrid = -1;
    /** True once the owner has entered the grid in pure-wallpaper mode (Down brought the rows up). */
    private boolean gridEntered;
    /** The running reveal/hide slide, so a new one cancels the old instead of fighting it. */
    private android.animation.ValueAnimator gridSlideAnim;
    /** Bumped per hero-art request so an older decode cannot overwrite a newer one. */
    private int heroGen;
    private android.media.projection.MediaProjectionManager projectionManager;
    /** Module cards (res/values/config.xml, module_cards): discovered on resume, shown only after the owner's Show. */
    private boolean moduleCards;
    /** hidden_apps as the adapter sees them (config.xml or a theme's config.json): a hidden app gets no card either. */
    private Set<String> hiddenApps = Collections.emptySet();
    /** The owner's dock order and hidden list (PrefsStore), over the config values. */
    private PrefsStore prefs;
    private List<String> userDock = Collections.emptyList();
    private Set<String> userHidden = Collections.emptySet();
    /**
     * The owner's own icon order for the free grid (PrefsStore). Empty = alphabetical default.
     * Only the apps the owner actually moved are in it; the rest keep their alphabetical place.
     */
    private List<String> userIconOrder = Collections.emptyList();
    /**
     * Move mode: the app the owner picked up is null when nothing is being moved. While it is
     * set, the D-pad picks the drop target instead of moving the focus freely, and OK drops it.
     */
    private String movingPkg;
    /** The tile the remote points at as the drop target while moving (-1 = none). */
    private int moveTargetPos = -1;
    /** Folders the owner created (AT4K-like), password lock included. */
    private List<Folder> userFolders = new ArrayList<>();
    private List<Cards.Card> cards = Collections.emptyList();
    /** The grid has been laid out and focused since the last resume; the card prompt waits for this. */
    private boolean tilesReady;
    private Dialog cardPrompt;
    /** Set when a decision could not be saved: no more prompts until the next resume, instead of asking in a loop. */
    private boolean promptsPaused;
    /** The Inputs or Module cards dropdown while one is open, so onPause can close it. */
    private PopupWindow openPopup;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /**
     * Background IO for the home screen's file work (multi-model review P0): the aerial wallpaper
     * copy, the JPEG decode and the tile scan used to run on the main thread and janked the grid
     * on every resume and package change. One thread: the tasks are short and must stay in order.
     */
    private final java.util.concurrent.ExecutorService io = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tvhome-io");
        t.setPriority(Thread.MIN_PRIORITY + 1);   // never compete with the UI thread
        return t;
    });
    /** Bumped per wallpaper request so an older decode cannot overwrite a newer one. */
    private int wallpaperGen;
    /** Last remote/touch interaction: the screensaver counts its idle minutes from here. */
    private long lastInteractionAt;
    /** The screensaver's slow idle check (the owner's minutes are long; 15 s is plenty). */
    private final Runnable screensaverTick = new Runnable() {
        @Override
        public void run() {
            maybeStartScreensaver();
            handler.postDelayed(this, 15_000L);
        }
    };
    /** Re-checks the aerial rotation cadence while the desktop process stays alive. */
    private Runnable aerialTicker;
    /** Refreshes the network pill when the connection changes: at boot Wi-Fi often connects after Home is shown. */
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    /** Shows the Inputs pill once the TV registers an input: at boot HDMI inputs can appear after Home is shown. */
    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override
        public void onInputAdded(String inputId) {
            updateInputsButton();
        }

        @Override
        public void onInputRemoved(String inputId) {
            updateInputsButton();
        }
    };
    /** Listens for any VPN coming up or going down, even one this app's traffic is excluded from. */
    private final ConnectivityManager.NetworkCallback vpnCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    private final IntentFilter packageFilter = new IntentFilter();
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Updates send REMOVED followed by ADDED; keep the tile until the new APK is ready.
            if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())
                    && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
            reloadApps(false);
        }
    };
    /**
     * Network self-test and one-tap expansions over adb:
     * {@code am broadcast -a com.veil.launcher.NETCHECK} runs the wallpaper-source sweep,
     * {@code am broadcast -a com.veil.launcher.EXPAND_AERIAL} / {@code .EXPAND_W360} pull one
     * round of pictures. The report lands in logcat (TvHome.NetCheck). The owner can trigger the
     * same sweep from the settings panel.
     */
    private final IntentFilter netCheckFilter = new IntentFilter("com.veil.launcher.NETCHECK");
    {
        // The same receiver also carries the one-tap expansion actions.
        netCheckFilter.addAction("com.veil.launcher.EXPAND_AERIAL");
        netCheckFilter.addAction("com.veil.launcher.EXPAND_W360");
    }
    private final BroadcastReceiver netCheckReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;
            if (action.contains("EXPAND_AERIAL")) {
                AerialExpander.expand(MainActivity.this, (added, total, error, detail) -> {
                    android.util.Log.d("TvHome.NetCheck",
                            "aerial expand: added=" + added + " total=" + total
                                    + " error=" + error + " detail=" + detail);
                    postUi(() -> Toast.makeText(MainActivity.this,
                            "航拍扩充：新增 " + added + " 张，共 " + total + " 张"
                                    + (error == null ? "" : " [" + error + "]"),
                            Toast.LENGTH_LONG).show());
                });
                return;
            }
            if (action.contains("EXPAND_W360")) {
                Wall360.expand(MainActivity.this, (added, total, error, detail) -> {
                    android.util.Log.d("TvHome.NetCheck",
                            "w360 expand: added=" + added + " total=" + total
                                    + " error=" + error + " detail=" + detail);
                    postUi(() -> Toast.makeText(MainActivity.this,
                            "360 扩充：新增 " + added + " 张，共 " + total + " 张"
                                    + (error == null ? "" : " [" + error + "]"),
                            Toast.LENGTH_LONG).show());
                });
                return;
            }
            NetCheck.run(MainActivity.this, report -> {
                android.util.Log.d("TvHome.NetCheck", "===== adb report =====\n" + report);
                postUi(() -> Toast.makeText(MainActivity.this,
                        "网络自检完成，详情见 logcat（TvHome.NetCheck）", Toast.LENGTH_LONG).show());
            });
        }
    };
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateGreeting();
            updateWifiLabel();
            updateWeather();
            handler.postDelayed(this, MINUTE_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        density = getResources().getDisplayMetrics().density;
        // config.xml, overlaid by config.json from a pushed theme (see Config and tools/theme.sh).
        Config cfg = Config.load(this);
        defaultColumns = cfg.columns;
        showHomeSettingsShortcut = cfg.showHomeSettingsShortcut;
        liveTvPkg = cfg.liveTvPackage;
        liveTvAction = cfg.liveTvAction;
        inputAction = cfg.inputAction;
        sourcePanelService = cfg.sourcePanelService;
        sourcePanelAction = cfg.sourcePanelAction;

        root = findViewById(R.id.root);
        root.setBackgroundColor(cfg.backgroundColor);
        // Desktop aerial video background: a full-screen surface behind the UI. The static still
        // (wallpaperView) sits on top of it and is hidden only once a frame is actually playing,
        // so the desktop shows the moving Apple-TV-style aerial instead of a frozen picture — and
        // falls back to the still if the network or codec fails. The surface punches a transparent
        // hole in the window, so it shows through regardless of root's background colour.
        aerialVideoView = new SurfaceView(this);
        aerialVideoView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(aerialVideoView, 0);   // backmost child; the video composites behind the window UI
        aerialVideo = new AerialWallpaperPlayer(this, aerialVideoView, playing -> {
            if (wallpaperView != null) {
                wallpaperView.setVisibility(playing ? View.INVISIBLE : View.VISIBLE);
            }
        });
        if (cfg.wallpaper != null) showWallpaper(cfg.wallpaper);
        glass = findViewById(R.id.glass);
        topBar = findViewById(R.id.topBar);
        glassShelf = findViewById(R.id.glassShelf);
        // The top shelf is retired (the owner's call): the grey block over the wallpaper looked
        // wrong, and icons still faded out too early. Rows now scroll freely to the top and the
        // whole bar hides while they occupy its zone (syncTopBar) — no block, no early fade.
        glassShelf.setVisibility(View.GONE);
        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        heroName = findViewById(R.id.heroName);
        greeting = findViewById(R.id.greeting);
        btnWifi = findViewById(R.id.btnWifi);
        weatherText = findViewById(R.id.weather);
        shamsiDate = findViewById(R.id.shamsiDate);
        shamsi = cfg.shamsiDate;
        if (shamsi) {
            shamsiDate.setVisibility(View.VISIBLE);
            TextView date = findViewById(R.id.date);
            date.setTextSize(11f);
            date.setPadding(0, (int) (4 * density), 0, 0);
        }
        vpnApp = cfg.vpnApp;
        moduleCards = cfg.moduleCards;
        // The owner's dock order and hidden list (long-press menu, restored backup) sit over the config values.
        prefs = new PrefsStore(this);
        migrateV29();
        migrateV30();
        // The rotation rewrites the wallpaper file (multi-model review P0: IO off the main
        // thread); the refresh that must follow it runs once the copy is done.
        io.execute(() -> {
            if (maybeRotateAerialWallpaper()) postUi(MainActivity.this::refreshWallpaper);
        });
        startAerialTicker();
        aerialWasOn = prefs.isAerialRotateOn();
        bingWasOn = prefs.isBingRotateOn();
        userDock = prefs.loadDock(cfg.dockApps);
        userHidden = prefs.loadHidden(new HashSet<>(cfg.hiddenApps));
        userIconOrder = prefs.loadIconOrder();
        userFolders = prefs.loadFolders();
        hiddenApps = new HashSet<>(userHidden);
        COLUMNS = prefs.loadColumns(defaultColumns);   // the owner's dock-size pick (settings panel)
        applyAppearance();
        applyTimeFormat();
        adoptBundledWallpaper();

        grid = findViewById(R.id.grid);
        grid.setLayoutManager(new GridLayoutManager(this, COLUMNS));
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);
        dockVisible = prefs.isDockVisible();
        dockBarOn = prefs.isDockBarOn();
        radiusFrac = prefs.loadIconRadius() / 100f;
        dockDecoration = new DockDecoration(dockBarOn ? COLUMNS : 0, density,
                prefs.loadDockColor(), radiusFrac);
        dockDecoration.setFrostEnabled(prefs.isDockFrostOn());
        grid.addItemDecoration(dockDecoration);
        grid.setLayoutAnimation(new LayoutAnimationController(
                AnimationUtils.loadAnimation(this, R.anim.tile_enter), 0.06f));
        adapter = new AppAdapter(this, this, COLUMNS, userDock, userHidden, userFolders, showHomeSettingsShortcut,
                radiusFrac);
        adapter.setDockRowShown(dockBarOn);
        adapter.setIconOrder(userIconOrder);
        grid.setAdapter(adapter);

        // tvOS layout: the rows always rest in the same place — the pinned dock row's slot when
        // the dock bar is on, the first app row's when it is off (the owner's call: "no dock bar"
        // must not turn the desktop into a full-screen icon wall). Only the first page's resting
        // state differs: with the layout hidden the whole grid waits below the fold until Down
        // slides it up (a translation, not a scroll — a short app list must be able to come up too).
        int rowsBelowDock = cfg.rowsBelowDock;
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int h = b - t;
            // Item height follows the tile size, which follows the column count (AppAdapter.tileWidthPx).
            int inner = (r - l) - grid.getPaddingLeft() - grid.getPaddingRight();
            int tileH = AppAdapter.tileHeightPx(AppAdapter.tileWidthPx(inner, COLUMNS, density));
            int itemH = AppAdapter.itemHeightPx(tileH, density);
            // The dock band is one row plus its decoration offsets; without the dock bar a plain
            // row takes the same slot, so the bottom edge of the icon area never moves.
            int rowSlot = dockBarOn ? itemH + (int) ((6 + 12) * density) : itemH;
            int gridRows = itemH * rowsBelowDock;
            updateGlassOffset();
            int want = Math.max(0, h - rowSlot - gridRows - (int) (30 * density));
            if (grid.getPaddingTop() != want) {
                grid.setPadding(grid.getPaddingLeft(), want, grid.getPaddingRight(), grid.getPaddingBottom());
            }
            if (!dockVisible) {
                if (!gridEntered && grid.getTranslationY() != h) grid.setTranslationY(h);
            } else if (grid.getTranslationY() != 0f) {
                grid.setTranslationY(0f);
            }
            fadeUnderGlass();
        });
        // The clock block grows and shrinks (weather, the Shamsi date): the glass shelf follows it,
        // and its bottom edge is where grid rows start dissolving.
        topBar.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (b - t != ot - ol) {
                updateShelfHeight();
                updateGlassOffset();
                fadeUnderGlass();
            }
        });

        // Keep the glass glued to its tile while the grid scrolls.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                // The row fade runs on every scroll frame, highlight or not: skipping it left
                // rows and the glass shelf stuck in their last state (the owner's report).
                if (glassShown) {
                    glassTargetY -= dy;
                    glassScrollShift -= dy;
                    glass.setTranslationY(glass.getTranslationY() - dy);
                }
                fadeUnderGlass();
            }

            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                // The dock band keeps its last blurred crop while scrolling (stretched: it stays
                // blurred) and re-renders the real blur once the scroll settles.
                if (dockDecoration != null) dockDecoration.setScrolling(newState != RecyclerView.SCROLL_STATE_IDLE);
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    fadeUnderGlass();
                    rv.invalidate();   // one draw with the settled frost crop
                }
            }
        });

        // Liquid feel: soft springs on the highlight's position.
        springX = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_X);
        springY = new SpringAnimation(glass, new FloatPropertyCompat<View>("glassContentY") {
            @Override
            public float getValue(View v) {
                return v.getTranslationY() - glassScrollShift;
            }

            @Override
            public void setValue(View v, float value) {
                v.setTranslationY(value + glassScrollShift);
            }
        });
        for (SpringAnimation s : new SpringAnimation[]{springX, springY}) {
            s.setSpring(new SpringForce().setStiffness(260f).setDampingRatio(0.58f));
        }

        btnInputs = findViewById(R.id.btnInputs);
        View btnSettings = findViewById(R.id.btnSettings);
        btnInputs.setOnClickListener(v -> showInputs());
        btnWifi.setOnClickListener(v -> openNetworkPill());
        btnWifi.setOnLongClickListener(v -> {
            open(new Intent(Settings.ACTION_WIFI_SETTINGS));
            return true;
        });
        // The pill opens the launcher's own panel (lock, wallpaper, dock size, backup...); the system
        // settings stay one long-press away, and in the panel under "系统设置".
        btnSettings.setOnClickListener(v -> startActivity(new Intent(this, HomeSettingsActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        btnSettings.setOnLongClickListener(v -> {
            if (!moduleCards) return false;   // off: a long press does nothing, as before the feature
            showModuleCards();
            return true;
        });

        // Keep focus on the top bar at its ends and at the top: Android's focus finder would otherwise
        // jump to a tile far below when there is nothing in that direction.
        View.OnKeyListener pillKeys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                // Pure-wallpaper first page: the whole grid sits below the fold, so the default
                // focus search finds nothing — bring the first row up by hand instead.
                if (!dockVisible) {
                    revealGrid();
                    return true;
                }
                return false;   // the dock row (or a laid-out row) is the next stop
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                // The left end is Inputs, or Wi-Fi on a box without inputs.
                return v.getId() == R.id.btnInputs
                        || (v.getId() == R.id.btnWifi && btnInputs.getVisibility() != View.VISIBLE);
            }
            // The right end is Settings, the last pill.
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == R.id.btnSettings;
        };
        btnInputs.setOnKeyListener(pillKeys);
        btnWifi.setOnKeyListener(pillKeys);
        btnSettings.setOnKeyListener(pillKeys);

        // Hide the glass when focus leaves the tiles.
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            boolean onTile = newF != null && newF.getId() == R.id.card;
            if (!onTile) hideGlass();
        });

        packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packageFilter.addDataScheme("package");

        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        try {
            if (tim != null) tim.registerCallback(inputCallback, handler);
        } catch (Exception noTvInputService) {
            // boxes without a TV input service; the Inputs pill stays hidden
            android.util.Log.d("TvHome", "no TV input service: " + noTvInputService);
        }
        if (cm != null) {
            cm.registerDefaultNetworkCallback(networkCallback);
            cm.registerNetworkCallback(new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build(), vpnCallback);
        }
    }

    /**
     * A theme's full-screen picture behind everything. Decoded once, downsampled to the screen so a 4K
     * JPEG costs a 1080p bitmap at most (about 8 MB), which is the price of a wallpaper on a 1 GB TV.
     * The view is kept: a new picture replaces the bitmap instead of stacking views.
     * <p>The decode itself is background work (multi-model review P0): only the view updates here.
     */
    private void showWallpaper(File file) {
        final int gen = ++wallpaperGen;
        io.execute(() -> {
            final android.graphics.Bitmap bmp = decodeWallpaper(file);
            if (bmp == null) return;
            postUi(() -> {
                if (gen != wallpaperGen) {   // a newer request already won; drop this one
                    bmp.recycle();
                    return;
                }
                applyWallpaper(bmp);
            });
        });
    }

    /** Bounds + downsampled decode of the wallpaper file (ARGB_8888: full colour depth). */
    private android.graphics.Bitmap decodeWallpaper(File file) {
        try {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(file.getPath(), opts);
            int sample = 1;
            while (opts.outWidth / (sample * 2) >= dm.widthPixels && opts.outHeight / (sample * 2) >= dm.heightPixels) sample *= 2;
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sample;
            // ARGB_8888 on purpose (owner's picture-quality call): RGB_565's missing low
            // bits banded the sky gradients on the big screen. One 1080p bitmap is ~8 MB,
            // fine on this box — the blur renders still downsample to 565 internally.
            opts.inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888;
            return android.graphics.BitmapFactory.decodeFile(file.getPath(), opts);
        } catch (Exception | OutOfMemoryError e) {
            // A bad or huge picture must never take the home screen down; the plain colour stays.
            android.util.Log.w("TvHome", "wallpaper decode failed for " + file + ": " + e);
            return null;
        }
    }

    /** Main thread: puts a decoded wallpaper on screen (the view half of showWallpaper). */
    private void applyWallpaper(android.graphics.Bitmap bmp) {
        if (wallpaperView == null) {
            wallpaperView = new android.widget.ImageView(this);
            wallpaperView.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            root.addView(wallpaperView, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            wallpaperView.setImageBitmap(bmp);
            // The dock band frost crops straight from this bitmap (inspired by Tigris).
            if (dockDecoration != null) dockDecoration.setWallpaperBitmap(bmp);
            updateShelfFrost(bmp);
            startBreathe();
            return;
        }
        // A picture is already up: cross-fade. The new one scales in from 1.05, the old one
        // drifts out to 0.97, and the settled picture takes over the Ken Burns breath.
        finishWallTransition();
        if (wallFadeView == null) {
            wallFadeView = new android.widget.ImageView(this);
            wallFadeView.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            root.addView(wallFadeView, root.indexOfChild(wallpaperView) + 1,
                    new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));
        }
        wallFadeView.setImageBitmap(bmp);
        wallFadeView.setAlpha(0f);
        wallFadeView.setScaleX(1.05f);
        wallFadeView.setScaleY(1.05f);
        wallFadeView.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(420).start();
        wallpaperView.animate().alpha(0f).scaleX(0.97f).scaleY(0.97f).setDuration(420)
                .withEndAction(this::finishWallTransition).start();
        wallTransitioning = true;
        // The dock band frost crops straight from this bitmap (inspired by Tigris).
        if (dockDecoration != null) dockDecoration.setWallpaperBitmap(bmp);
        updateShelfFrost(bmp);
    }

    /** Settles a running cross-fade: the incoming picture becomes the wallpaper again. */
    private void finishWallTransition() {
        if (!wallTransitioning) return;
        wallTransitioning = false;
        if (wallpaperView != null && wallFadeView != null) {
            wallpaperView.animate().cancel();
            wallpaperView.setAlpha(1f);
            wallpaperView.setScaleX(1f);
            wallpaperView.setScaleY(1f);
            android.graphics.drawable.Drawable d = wallFadeView.getDrawable();
            wallpaperView.setImageBitmap(d instanceof android.graphics.drawable.BitmapDrawable
                    ? ((android.graphics.drawable.BitmapDrawable) d).getBitmap() : null);
        }
        if (wallFadeView != null) {
            wallFadeView.animate().cancel();
            wallFadeView.setImageDrawable(null);
            wallFadeView.setAlpha(0f);
        }
        startBreathe();
    }

    /**
     * The slow Ken Burns breath: the picture scales 1.00 → 1.06 and drifts a percent toward a
     * random corner over ~96 seconds, then reverses — endless, paused while the desktop is away.
     * The amplitude stays small on purpose: the glass shelf and dock frost are static renders,
     * and a 6% centre zoom stays invisible behind their blur.
     */
    private void startBreathe() {
        if (breatheAnim != null) {
            breatheAnim.cancel();
            breatheAnim = null;
        }
        if (wallpaperView == null) return;
        wallpaperView.setScaleX(1f);
        wallpaperView.setScaleY(1f);
        wallpaperView.setTranslationX(0f);
        wallpaperView.setTranslationY(0f);
        final float w = wallpaperView.getWidth(), h = wallpaperView.getHeight();
        if (w <= 0 || h <= 0) {
            // Not laid out yet (first picture of the process): retry once the view has a size.
            wallpaperView.post(this::startBreathe);
            return;
        }
        final float dx = (rnd.nextBoolean() ? 1f : -1f) * w * 0.012f;
        final float dy = (rnd.nextBoolean() ? 1f : -1f) * h * 0.012f;
        final android.widget.ImageView view = wallpaperView;
        android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(96_000L);
        a.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        a.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        a.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        a.addUpdateListener(v -> {
            float f = v.getAnimatedFraction();
            float s = 1f + 0.06f * f;
            view.setScaleX(s);
            view.setScaleY(s);
            view.setTranslationX(dx * f);
            view.setTranslationY(dy * f);
        });
        a.start();
        breatheAnim = a;
    }

    /** The wallpaper bitmap as it is shown, or null when there is none. */
    private Bitmap currentWallpaper() {
        if (wallpaperView == null) return null;
        android.graphics.drawable.Drawable d = wallpaperView.getDrawable();
        if (d instanceof android.graphics.drawable.BitmapDrawable) {
            return ((android.graphics.drawable.BitmapDrawable) d).getBitmap();
        }
        return null;
    }

    /** The shelf is retired (GONE): every frost render for it is a no-op now. */
    private void updateShelfFrost(Bitmap wallpaper) {
        if (glassShelf == null || glassShelf.getVisibility() != View.VISIBLE) return;
        int bg = prefs.loadTopBarColor();
        if (bg != -1) {
            // The owner's pick: a solid colour instead of the glass.
            if (topFrost != null) {
                topFrost = null;
                topFrostWall = null;
                topFrostH = -1;
            }
            glassShelf.setBackgroundColor(bg);
            return;
        }
        int h = glassShelf.getHeight();
        if (wallpaper == null || h <= 0) {
            if (topFrost != null) {
                topFrost = null;
                topFrostWall = null;
                topFrostH = -1;
            }
            // No picture to blur: a soft dark gradient still reads as a shelf.
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0xA60D0D10, 0x8C0A0A0C, 0x00000000});
            glassShelf.setBackground(g);
            return;
        }
        if (topFrost != null && topFrostH == h && topFrostWall == wallpaper) return;   // still good
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        RectF band = new RectF(0, 0, dm.widthPixels, h);
        Bitmap frost = FrostGlass.render(wallpaper, band, dm.widthPixels, dm.heightPixels);
        if (frost == null) {
            glassShelf.setBackgroundColor(0x8C0D0D10);
            return;
        }
        if (topFrost != null && !topFrost.isRecycled()) topFrost.recycle();
        topFrost = frost;
        topFrostWall = wallpaper;
        topFrostH = h;
        // Nearly opaque where it shows: rows sliding under read as if behind real glass.
        android.graphics.drawable.BitmapDrawable bd = new android.graphics.drawable.BitmapDrawable(
                getResources(), frost);
        bd.setAlpha(236);
        glassShelf.setBackground(bd);
    }

    /** Shelf retired (GONE): height upkeep is a no-op. */
    private void updateShelfHeight() {
        if (glassShelf == null || topBar == null || glassShelf.getVisibility() != View.VISIBLE) return;
        int[] loc = new int[2];
        topBar.getLocationOnScreen(loc);
        int h = loc[1] + topBar.getHeight();
        if (h > 0 && glassShelf.getHeight() != h) {
            ViewGroup.LayoutParams lp = glassShelf.getLayoutParams();
            lp.height = h;
            glassShelf.setLayoutParams(lp);
        }
        updateShelfFrost(currentWallpaper());
    }

    /** The shelf's bottom edge, measured in the grid's own coordinates (the fade line). */
    private void updateGlassOffset() {
        if (topBar == null || grid == null) return;
        Rect rb = new Rect(0, 0, topBar.getWidth(), topBar.getHeight());
        root.offsetDescendantRectToMyCoords(topBar, rb);
        Rect rg = new Rect(0, 0, grid.getWidth(), grid.getHeight());
        root.offsetDescendantRectToMyCoords(grid, rg);
        glassBottomInGrid = rb.bottom - rg.top;
    }

    /**
     * Rows no longer dissolve under the top bar (the owner's calls: "icons vanish before they
     * even reach the bar", then "drop the grey block, hide the clock/weather/wifi instead").
     * The tiles stay at full alpha all the way up; the WHOLE top bar — clock, weather, date,
     * the Input/Wi-Fi/Settings pills — fades out while any row's top edge is inside the bar's
     * zone and comes back once the rows leave it. The icons own the top of the screen while
     * they are up there; the widgets get it back the moment they don't.
     */
    private void fadeUnderGlass() {
        if (grid == null || glassBottomInGrid < 0) return;
        float gridShift = grid.getTranslationY();   // the pure-wallpaper page waits below the fold
        boolean overlap = false;
        for (int i = 0; i < grid.getChildCount(); i++) {
            View child = grid.getChildAt(i);
            float childTop = child.getTop() + child.getTranslationY() + gridShift;
            if (childTop < glassBottomInGrid) {   // this row's top is inside the bar's zone
                overlap = true;
                break;
            }
        }
        syncTopBar(overlap);
    }

    private boolean topBarHidden;

    /** Hides/shows the whole top bar as rows enter/leave its zone (fade, then unfocusable). */
    private void syncTopBar(boolean overlap) {
        if (topBarHidden == overlap) return;
        topBarHidden = overlap;
        if (overlap) {
            topBar.animate().alpha(0f).setDuration(140)
                    .withEndAction(() -> {
                        if (topBarHidden) topBar.setVisibility(View.INVISIBLE);
                    }).start();
        } else {
            topBar.setVisibility(View.VISIBLE);
            topBar.animate().alpha(1f).setDuration(160).start();
        }
    }

    /**
     * Focus scrolling for grid tiles. With the top shelf retired and the bar hiding itself when
     * rows enter its zone (syncTopBar), the focused row may park at the very top of the screen:
     * the icons are fully visible the whole way up (no fade), the widgets step aside, and the
     * bar returns when the content scrolls back down. Only the bottom is clamped, so the last
     * row never leaves the screen.
     * <p>The scroll keeps an explicit duration: RecyclerView's smoothScrollBy defaults to
     * 25 ms per inch, which on a TV teleports rows in ~25 ms. ~300 ms for one row reads as motion.
     */
    private void scrollTileUnderGlass(View item) {
        if (glassBottomInGrid < 0) {
            // No glass line measured yet (pre-layout): fall back to plain visibility scrolling.
            float pad = 6f * density;
            Rect want = new Rect(0, -(int) (pad * 2), item.getWidth(), item.getHeight() + (int) pad);
            grid.requestChildRectangleOnScreen(item, want, false);
            return;
        }
        int lineTop = (int) (8 * density);   // the focused row may rest at the very top
        int bottomLimit = grid.getHeight() - grid.getPaddingBottom() - (int) (8 * density);
        int delta;
        if (item.getTop() < lineTop) {
            delta = item.getTop() - lineTop;                 // negative: content moves down, row lands on the line
        } else {
            int itemBottom = item.getTop() + item.getHeight();
            delta = Math.max(0, itemBottom - bottomLimit);   // positive: content moves up, bottom clears the edge
        }
        if (Math.abs(delta) > 1) {
            int dur = Math.min(600, 220 + Math.abs(delta) / 2);
            grid.smoothScrollBy(0, delta, new android.view.animation.DecelerateInterpolator(), dur);
        }
    }

    /** Focus rests on the leftmost top-bar pill (the pure-wallpaper first page's resting spot). */
    private void focusTopBar() {
        if (btnInputs != null && btnInputs.getVisibility() == View.VISIBLE) btnInputs.requestFocus();
        else if (btnWifi != null) btnWifi.requestFocus();
    }

    /**
     * Down on the top bar in pure-wallpaper mode: the grid waits below the fold, so the default
     * focus search finds nothing. Slide it up into its resting place and focus the first row —
     * the same layout the owner sees with the dock bar on, minus nothing (the dock row comes
     * along when the dock bar is on).
     */
    private void revealGrid() {
        gridEntered = true;
        if (grid.getTranslationY() > 1f) {
            if (gridSlideAnim != null) gridSlideAnim.cancel();
            final float from = grid.getTranslationY();
            android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(from, 0f);
            va.setDuration(300);
            // The dock band keeps its last blurred crop while the grid slides (stretched: it
            // stays blurred) and re-renders the real blur once the slide settles.
            if (dockDecoration != null) dockDecoration.setScrolling(true);
            va.addUpdateListener(a -> {
                grid.setTranslationY((Float) a.getAnimatedValue());
                fadeUnderGlass();
            });
            va.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator a) {
                    gridSlideAnim = null;
                    if (dockDecoration != null) dockDecoration.setScrolling(false);
                }
            });
            gridSlideAnim = va;
            va.start();
        }
        focusTile(0);
    }

    /**
     * The inverse of {@link #revealGrid()}: Up on the first row (or Back) slides the grid back
     * below the fold and the first page is pure wallpaper again. The owner's ask: the revealed
     * icons must be able to return down instead of owning the screen for good.
     * <p>The slide re-runs the glass fade every frame: without it a shelf that was showing while
     * rows crossed the top bar stayed stuck on screen after the grid had retreated (the owner's
     * "the frosted bar doesn't go away by itself"), and the faded rows kept their old alpha.
     */
    private void hideGrid() {
        gridEntered = false;
        if (grid.getTranslationY() < 1f) {
            if (gridSlideAnim != null) gridSlideAnim.cancel();
            final float from = grid.getTranslationY();
            final float to = grid.getHeight();
            android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(from, to);
            va.setDuration(300);
            if (dockDecoration != null) dockDecoration.setScrolling(true);
            va.addUpdateListener(a -> {
                grid.setTranslationY((Float) a.getAnimatedValue());
                fadeUnderGlass();
            });
            va.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator a) {
                    gridSlideAnim = null;
                    if (dockDecoration != null) dockDecoration.setScrolling(false);
                }
            });
            gridSlideAnim = va;
            va.start();
        }
        fadeUnderGlass();   // the grid may already have been down: settle the glass either way
        focusTopBar();
    }
    /** Main-thread post that stops at a destroyed activity: background work must not touch its views. */
    private void postUi(Runnable r) {
        handler.post(() -> {
            if (isDestroyed()) return;
            r.run();
        });
    }

    /** One-shot v2.9 migration: dock goes to the new 5-app default on 5 columns, the saved dock
     *  colour is dropped so the frosted-glass default shows again (the white band the owner saw
     *  was the -1 sentinel being painted as an opaque-white colour — fixed with the sentinel). */
    private void migrateV29() {
        if (prefs.isMigratedV29()) return;
        prefs.clearDock();
        prefs.clearDockColor();
        prefs.saveColumns(5);   // the owner asked for five dock icons
        prefs.setMigratedV29();
        android.util.Log.d("TvHome", "v2.9 migration: dock reset, 5 columns, colour default");
    }

    /** One-shot v3.0 migration: hero art (app content big pictures behind the desktop) is
     *  force-disabled once — the owner found it ugly and wants wallpaper-only. The switch in
     *  the panel stays, so it can be turned back on later. */
    private void migrateV30() {
        if (prefs.isMigratedV30()) return;
        prefs.setHeroArtOn(false);
        prefs.setMigratedV30();
        android.util.Log.d("TvHome", "v3.0 migration: hero art off (wallpaper only)");
    }

    /**
     * Apple aerial wallpaper rotation (Apple TV's own screensaver stills, cached in files/aerials/):
     * the next still shows on the owner's chosen cadence — every 5/15/30/… minutes up to a day
     * (default). Returns true when the wallpaper file was actually rewritten. Rotation is silent
     * if the folder is empty, so the manual wallpaper keeps working.
     */
    private boolean maybeRotateAerialWallpaper() {
        if (!prefs.isAerialRotateOn()) return false;
        long now = System.currentTimeMillis();
        long interval = prefs.loadAerialIntervalMin() * 60_000L;
        if (interval < 5 * 60_000L) interval = 5 * 60_000L;   // the stated floor
        if (now - prefs.loadAerialLastAt() < interval) return false;
        if (!rotateAerialOnce()) return false;   // a failed copy retries on the next tick
        prefs.saveAerialLastAt(now);
        return true;
    }

    /** Copies the next cached still over the wallpaper slot. True when the file changed. */
    private boolean rotateAerialOnce() {
        try {
            File base = Config.dir(this);
            if (base == null) return false;
            File dir = new File(base, "aerials");
            File[] stills = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".jpg"));
            if (stills == null || stills.length == 0) return false;
            java.util.Arrays.sort(stills);
            File next = stills[(prefs.loadAerialIndex() & 0x7fffffff) % stills.length];
            File out = new File(base, Config.WALLPAPER_NAME);
            try (java.io.FileInputStream in = new java.io.FileInputStream(next);
                 java.io.FileOutputStream fo = new java.io.FileOutputStream(out)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
            }
            prefs.saveAerialIndex(prefs.loadAerialIndex() + 1);
            android.util.Log.d("TvHome", "aerial wallpaper -> " + next.getName());
            return true;
        } catch (Exception e) {
            android.util.Log.d("TvHome", "aerial rotate failed: " + e.getMessage());
            return false;
        }
    }

    /** Picks the wallpaper back up after the settings panel wrote or removed it (file timestamp changed). */
    private void refreshWallpaper() {
        File wall = Config.load(this).wallpaper;
        if (wall == null) {
            if (wallpaperView != null) {
                root.removeView(wallpaperView);
                wallpaperView = null;
            }
            if (wallFadeView != null) {
                root.removeView(wallFadeView);
                wallFadeView = null;
            }
            wallTransitioning = false;
            if (breatheAnim != null) {
                breatheAnim.cancel();
                breatheAnim = null;
            }
            wallpaperStamp = -1;
            if (dockDecoration != null) dockDecoration.setWallpaperBitmap(null);
            updateShelfFrost(null);
            return;
        }
        long stamp = wall.lastModified() * 31 + wall.length();   // length too: same-second writes must refresh
        if (wallpaperView == null || stamp != wallpaperStamp) {
            wallpaperStamp = stamp;
            showWallpaper(wall);
        }
        updateAerialVideo();   // the wallpaper (and its source) may have changed: re-evaluate the video
    }

    /**
     * Starts or stops the desktop aerial video. The moving Apple aerial plays whenever the owner's
     * wallpaper is the Apple aerial source (auto/apple) with auto-rotate on — that is the
     * "航拍视频真播" the owner asked for, showing live without any trip into Settings. When it is
     * off, the static still stays (this is a no-op if the player is already in the right state).
     */
    private void updateAerialVideo() {
        if (aerialVideo == null) return;
        String src = prefs.loadAerialSource();
        boolean want = prefs.isAerialRotateOn()
                && ("auto".equals(src) || "apple".equals(src));
        if (want) {
            if (!aerialVideo.isActive()) aerialVideo.start();
        } else {
            aerialVideo.stop();
        }
    }

    /** Applies the owner's dock-size pick (settings panel): rebuilds the grid when it changed. */
    private void applyColumnsPref() {
        int want = prefs.loadColumns(defaultColumns);
        int tint = prefs.loadDockColor();
        int radiusPct = prefs.loadIconRadius();
        boolean dockShown = prefs.isDockVisible();
        boolean barOn = prefs.isDockBarOn();
        // The colour, corner-radius, dock-visibility and dock-bar picks also live in the
        // decoration/adapter, so any of them changing (panel edits land while the activity is
        // away) forces a rebuild.
        if (want == COLUMNS && tint == builtTint && radiusPct == builtRadiusPct
                && dockShown == builtDockVisible && barOn == builtDockBarOn) return;
        if (want < 1 || want > 8) want = defaultColumns;   // a corrupt pref falls back to the config value
        COLUMNS = want;
        dockVisible = dockShown;
        dockBarOn = barOn;
        radiusFrac = radiusPct / 100f;
        grid.setLayoutManager(new GridLayoutManager(this, COLUMNS));
        grid.removeItemDecoration(dockDecoration);
        dockDecoration = new DockDecoration(dockBarOn ? COLUMNS : 0, density, tint, radiusFrac);
        dockDecoration.setFrostEnabled(prefs.isDockFrostOn());
        if (wallpaperView != null && wallpaperView.getDrawable() instanceof android.graphics.drawable.BitmapDrawable) {
            dockDecoration.setWallpaperBitmap(((android.graphics.drawable.BitmapDrawable) wallpaperView.getDrawable()).getBitmap());
        }
        grid.addItemDecoration(dockDecoration);
        adapter = new AppAdapter(this, this, COLUMNS, userDock, userHidden, userFolders, showHomeSettingsShortcut,
                radiusFrac);
        adapter.setDockRowShown(dockBarOn);
        adapter.setIconOrder(userIconOrder);
        grid.setAdapter(adapter);
        builtTint = tint;
        builtRadiusPct = radiusPct;
        builtDockVisible = dockVisible;
        builtDockBarOn = dockBarOn;
    }

    /**
     * First run with no wallpaper of any kind: adopt the bundled aerial-style still (a lone
     * island in a calm sea) instead of the plain black background. Runs once — as soon as the
     * wallpaper file exists, or any rotation source takes over, this never copies again.
     */
    private void adoptBundledWallpaper() {
        io.execute(() -> {
            try {
                File dir = Config.dir(this);
                if (dir == null) return;
                File wall = new File(dir, Config.WALLPAPER_NAME);
                if (wall.isFile() || prefs.isWallpaperReset()) return;
                try (java.io.InputStream in = getAssets().open("default_wallpaper.jpg");
                     java.io.FileOutputStream fo = new java.io.FileOutputStream(wall)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
                }
                android.util.Log.d("TvHome", "bundled wallpaper adopted");
                postUi(MainActivity.this::refreshWallpaper);
            } catch (Exception e) {
                android.util.Log.d("TvHome", "bundled wallpaper copy skipped: " + e);
            }
        });
    }

    // ---- Bing daily wallpaper (inspired by Tigris): one fetch a day into the stock folder --------

    /** Stock size the multi-market backfill aims for; one pass adds at most up to this. */
    private static final int BING_STOCK_TARGET = 48;
    // The market list itself moved to BingStock.MARKETS — it is needed by more than one caller
    // (the daily backfill and the aerial/360 fallbacks).

    /**
     * On resume, once a day: pull Bing's picture of the day into files/bing/ as stock (the
     * owner's call: many cached pictures, not just today's). When rotation is off the newest
     * stock picture is also copied over the wallpaper slot, which is the old one-picture
     * behaviour; when the aerial rotation owns the slot the stock just quietly grows.
     */
    private void maybeUpdateBingWallpaper() {
        if (!prefs.isBingWallpaperOn()) return;
        final String today = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US)
                .format(new java.util.Date());
        if (today.equals(prefs.loadBingDate())) return;   // already today's picture
        io.execute(() -> {
            try {
                String api = httpGet("https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=1");
                if (api == null) return;
                org.json.JSONObject o = new org.json.JSONObject(api);
                org.json.JSONObject img = o.getJSONArray("images").getJSONObject(0);
                String path = img.optString("url", "");
                if (path.isEmpty()) return;
                byte[] jpg = fetchBingPicture(img, path);
                if (jpg == null || jpg.length < 10_000) return;   // a real wallpaper is never tiny
                File dir = Config.dir(this);
                if (dir == null) return;
                File stock = new File(dir, "bing");
                if (!stock.exists() && !stock.mkdirs()) return;
                File out = new File(stock, "bing_" + today + ".jpg");
                try (java.io.FileOutputStream fo = new java.io.FileOutputStream(out)) {
                    fo.write(jpg);
                }
                prefs.saveBingDate(today);
                // The slot only moves when Bing owns it: the aerial rotation wins otherwise.
                if (!prefs.isAerialRotateOn()) {
                    copyFile(out, new File(dir, Config.WALLPAPER_NAME));
                    postUi(MainActivity.this::refreshWallpaper);
                }
            } catch (Throwable e) {
                // Quiet by design: keep yesterday's picture, retry on the next resume — but leave a trace.
                android.util.Log.d("TvHome", "bing wallpaper update failed: " + e);
            }
        });
    }

    /**
     * Backfills the Bing stock across Bing's markets: the archive API only serves the last
     * ~8 days per market, but every market picks its own pictures — so one pass over many
     * markets yields dozens of unique images instead of the 9 the single-market API shows.
     * Deduped by urlbase (globally unique per picture), capped per pass so a slow network
     * cannot stall the resume; the whole pass repeats at most once a day.
     * <p>
     * The heavy lifting lives in {@link BingStock}, which also scales the 4K cuts down while
     * decoding — the old inline code decoded the full 3840x2160 bitmap (33 MB as ARGB_8888)
     * before resizing it, which is an OutOfMemoryError on this box.
     */
    private void maybeFillBingStock() {
        if (!prefs.isBingWallpaperOn()) return;
        io.execute(() -> {
            try {
                File dir = Config.dir(MainActivity.this);
                if (dir == null) return;
                File stock = new File(dir, "bing");
                File[] have = stock.listFiles((d, n) -> n.toLowerCase().endsWith(".jpg"));
                int count = have == null ? 0 : have.length;
                String today = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
                        .format(new java.util.Date());
                if (count >= BING_STOCK_TARGET && today.equals(prefs.loadBingStockDate())) return;
                int want = Math.max(1, BING_STOCK_TARGET - count);
                int saved = BingStock.pullInto(MainActivity.this, stock, "bing_", want,
                        BingStock.MARKETS.length);
                if (saved <= 0) return;
                prefs.saveBingStockDate(today);
                final int total = count + saved;
                android.util.Log.d("TvHome", "bing stock backfilled: " + saved + " -> " + total);
                postUi(() -> Toast.makeText(this,
                        getString(R.string.bing_stock_done, total), Toast.LENGTH_SHORT).show());
                postUi(MainActivity.this::refreshWallpaper);
            } catch (Throwable e) {
                android.util.Log.d("TvHome", "bing stock backfill failed: " + e);
            }
        });
    }

    /** Bing stock rotation: the next cached picture into the wallpaper slot on the chosen cadence. */
    private boolean maybeRotateBingWallpaper() {
        if (!prefs.isBingWallpaperOn() || !prefs.isBingRotateOn()) return false;
        if (prefs.isAerialRotateOn() || prefs.isW360RotateOn()) return false;   // another rotation owns the slot
        long now = System.currentTimeMillis();
        long interval = prefs.loadBingIntervalMin() * 60_000L;
        if (interval < 5 * 60_000L) interval = 5 * 60_000L;   // the stated floor
        if (now - prefs.loadBingLastAt() < interval) return false;
        if (!rotateBingOnce()) return false;   // a failed copy retries on the next tick
        prefs.saveBingLastAt(now);
        return true;
    }

    /** Copies the next cached Bing stock picture over the wallpaper slot. True when the file changed. */
    private boolean rotateBingOnce() {
        try {
            File base = Config.dir(this);
            if (base == null) return false;
            File[] stock = new File(base, "bing").listFiles((d, name) -> name.toLowerCase().endsWith(".jpg"));
            if (stock == null || stock.length == 0) return false;
            java.util.Arrays.sort(stock);
            File next = stock[(prefs.loadBingIndex() & 0x7fffffff) % stock.length];
            File out = new File(base, Config.WALLPAPER_NAME);
            copyFile(next, out);
            prefs.saveBingIndex(prefs.loadBingIndex() + 1);
            android.util.Log.d("TvHome", "bing wallpaper -> " + next.getName());
            return true;
        } catch (Exception e) {
            android.util.Log.d("TvHome", "bing rotate failed: " + e.getMessage());
            return false;
        }
    }

    /** 360 gallery rotation: the next cached picture into the wallpaper slot on the chosen cadence. */
    private boolean maybeRotateW360Wallpaper() {
        if (!prefs.isW360RotateOn()) return false;
        if (prefs.isAerialRotateOn() || prefs.isBingRotateOn()) return false;   // another rotation owns the slot
        long now = System.currentTimeMillis();
        long interval = prefs.loadW360IntervalMin() * 60_000L;
        if (interval < 5 * 60_000L) interval = 5 * 60_000L;   // the stated floor
        if (now - prefs.loadW360LastAt() < interval) return false;
        if (!rotateW360Once()) return false;   // a failed copy retries on the next tick
        prefs.saveW360LastAt(now);
        return true;
    }

    /** Copies the next cached 360 stock picture over the wallpaper slot. True when the file changed. */
    private boolean rotateW360Once() {
        try {
            File base = Config.dir(this);
            if (base == null) return false;
            File[] stock = new File(base, "w360").listFiles((d, name) -> name.toLowerCase().endsWith(".jpg"));
            if (stock == null || stock.length == 0) return false;
            java.util.Arrays.sort(stock);
            File next = stock[(prefs.loadW360Index() & 0x7fffffff) % stock.length];
            File out = new File(base, Config.WALLPAPER_NAME);
            copyFile(next, out);
            prefs.saveW360Index(prefs.loadW360Index() + 1);
            android.util.Log.d("TvHome", "w360 wallpaper -> " + next.getName());
            return true;
        } catch (Exception e) {
            android.util.Log.d("TvHome", "w360 rotate failed: " + e.getMessage());
            return false;
        }
    }

    /** Plain byte-copy used by the wallpaper rotation paths (aerial and Bing stock). */
    private static void copyFile(File src, File dst) throws java.io.IOException {
        try (java.io.FileInputStream in = new java.io.FileInputStream(src);
             java.io.FileOutputStream fo = new java.io.FileOutputStream(dst)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
        }
    }

    /**
     * The owner's 4K call: the archive API hands back a 1920x1080 URL by default, but the same
     * picture almost always exists as a UHD (3840x2160) cut. Try that first and fall back to
     * the plain URL on the rare picture that has no UHD version (or a dead link).
     */
    private byte[] fetchBingPicture(org.json.JSONObject img, String plainUrl) {
        String base = img.optString("urlbase", "");
        if (!base.isEmpty()) {
            byte[] uhd = httpGetBytes("https://www.bing.com" + base + "_UHD.jpg");
            if (uhd != null && uhd.length >= 10_000) return uhd;
        }
        String url = plainUrl.startsWith("http") ? plainUrl : "https://www.bing.com" + plainUrl;
        return httpGetBytes(url);
    }

    private byte[] httpGetBytes(String url) {
        try {
            return Http.getBytes(url, 10_000, 15_000, null, 20 * 1024 * 1024);
        } catch (Exception e) {
            // Expected when the box is offline; debug level so a real problem is still traceable.
            android.util.Log.d("TvHome", "GET bytes failed for " + url + ": " + e);
            return null;
        }
    }

    /** Status bar colours (inspired by AT4K): a background tint and text colour over the defaults. */
    private void applyAppearance() {
        // The dock frost switch may have been flipped in the panel: re-apply it on the way back.
        if (dockDecoration != null) dockDecoration.setFrostEnabled(prefs.isDockFrostOn());
        // The shelf's background lives in updateShelfFrost: glass by default, the owner's colour
        // when one was picked (it simply replaces the frost bitmap).
        updateShelfFrost(currentWallpaper());
        int tc = prefs.loadTopTextColor();
        int main = tc == -1 ? 0xFFFFFFFF : tc;
        int dim = tc == -1 ? 0xB3FFFFFF : (tc & 0x00FFFFFF) | 0xB3000000;
        int soft = tc == -1 ? 0xA6FFFFFF : (tc & 0x00FFFFFF) | 0xA6000000;
        int faint = tc == -1 ? 0x8CFFFFFF : (tc & 0x00FFFFFF) | 0x8C000000;
        int weatherCol = tc == -1 ? 0xE6FFFFFF : (tc & 0x00FFFFFF) | 0xE6000000;
        greeting.setTextColor(faint);
        // clock/ampm/date are TextClocks declared only in the layout, so resolve them here.
        ((TextView) findViewById(R.id.clock)).setTextColor(main);
        ((TextView) findViewById(R.id.ampm)).setTextColor(dim);
        ((TextView) findViewById(R.id.date)).setTextColor(soft);
        ((TextView) findViewById(R.id.shamsiDate)).setTextColor(weatherCol);
        weatherText.setTextColor(weatherCol);
        // The pills (Inputs / Wi-Fi / Settings) ship a white-else-dark selector of their own; the
        // owner's text colour must reach them too, keeping the dark state the focused pill needs.
        android.content.res.ColorStateList pillText = new android.content.res.ColorStateList(
                new int[][]{{android.R.attr.state_focused}, {}},
                new int[]{0xFF111111, main});
        ((TextView) findViewById(R.id.btnInputs)).setTextColor(pillText);
        btnWifi.setTextColor(pillText);
        ((TextView) findViewById(R.id.btnSettings)).setTextColor(pillText);
    }

    /** The owner's 24-hour clock pick (inspired by AT4K): overrides the 12-hour system format. */
    private void applyTimeFormat() {
        android.widget.TextClock clockView = findViewById(R.id.clock);
        android.widget.TextClock ampmView = findViewById(R.id.ampm);
        if (prefs.isForce24h()) {
            clockView.setFormat12Hour("HH:mm");
            ampmView.setVisibility(View.GONE);   // an AM/PM pill makes no sense next to a 24h clock
        } else {
            clockView.setFormat12Hour("h:mm");
            ampmView.setVisibility(View.VISIBLE);
        }
    }

    // ---- weather clock (Open-Meteo, the launcher's only networked feature) ----------------------

    private static final long WEATHER_REFRESH_MS = 30 * MINUTE_MS;
    private static final long WEATHER_RETRY_MS = 15_000;

    /** Shows the cached line right away; refetches in the background when the cache is stale. */
    private void updateWeather() {
        if (weatherText == null) return;
        if (!prefs.isWeatherOn()) {
            weatherText.setVisibility(View.GONE);
            return;
        }
        weatherText.setVisibility(View.VISIBLE);
        org.json.JSONObject loc = prefs.loadWeatherLoc();
        if (loc == null) return;
        String city = loc.optString("city", "");
        String cached = prefs.loadWeatherCache();
        long age = System.currentTimeMillis() - prefs.loadWeatherTime();
        if (cached != null && age < WEATHER_REFRESH_MS) {
            applyWeather(cached, city);
            return;
        }
        if (cached != null && age < WEATHER_RETRY_MS) {
            applyWeather(cached, city);   // a fetch just failed; show the cache, don't hammer
            return;
        }
        if (cached == null) weatherText.setText(R.string.weather_loading);
        double lat = loc.optDouble("lat", 0), lon = loc.optDouble("lon", 0);
        final String url = "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                + "&current_weather=true&daily=temperature_2m_max,temperature_2m_min&timezone=auto";
        new Thread(() -> {
            String fresh = httpGet(url);
            if (fresh != null) prefs.saveWeatherCache(fresh);
            final String data = fresh != null ? fresh : cached;
            final String cachedCity = city;
            if (data != null) postUi(() -> applyWeather(data, cachedCity));
        }, "weather").start();
    }

    private void applyWeather(String json, String city) {
        try {
            org.json.JSONObject o = new org.json.JSONObject(json);
            org.json.JSONObject cur = o.getJSONObject("current_weather");
            boolean f = prefs.isWeatherFahrenheit();
            double t = cur.getDouble("temperature");
            org.json.JSONArray hi = o.getJSONObject("daily").getJSONArray("temperature_2m_max");
            org.json.JSONArray lo = o.getJSONObject("daily").getJSONArray("temperature_2m_min");
            double hi0 = hi.getDouble(0), lo0 = lo.getDouble(0);
            if (f) {   // the owner's ℃/℉ pick (inspired by AT4K)
                t = t * 9 / 5 + 32;
                hi0 = hi0 * 9 / 5 + 32;
                lo0 = lo0 * 9 / 5 + 32;
            }
            String line = String.format(Locale.getDefault(), "%.0f°%s %s ↑%.0f° ↓%.0f°",
                    t, f ? "F" : "C", weatherName(cur.getInt("weathercode")), hi0, lo0);
            if (city != null && !city.isEmpty()) line += " · " + city;
            weatherText.setText(line);
            weatherText.setVisibility(View.VISIBLE);
        } catch (Exception badJson) {
            android.util.Log.w("TvHome", "weather payload unusable: " + badJson);
            weatherText.setVisibility(View.GONE);
        }
    }

    /** WMO weather codes → a short Chinese label. */
    private static String weatherName(int code) {
        if (code == 0) return "晴";
        if (code <= 3) return "多云";
        if (code == 45 || code == 48) return "雾";
        if (code <= 57) return "毛毛雨";
        if (code <= 67) return "雨";
        if (code <= 77) return "雪";
        if (code <= 82) return "阵雨";
        if (code <= 86) return "阵雪";
        return "雷雨";
    }

    /** Tiny GET helper; null on any failure so the caller can fall back to the cache. */
    private static String httpGet(String url) {
        String body = Http.getStringOrNull(url, 8000, 8000);
        if (body == null) {
            // Expected when the box is offline; debug level so a real problem is still traceable.
            android.util.Log.d("TvHome", "GET failed for " + url);
        }
        return body;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packageReceiver, packageFilter, Context.RECEIVER_NOT_EXPORTED);
            registerReceiver(netCheckReceiver, netCheckFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(packageReceiver, packageFilter);
            registerReceiver(netCheckReceiver, netCheckFilter);
        }
        tilesReady = false;
        promptsPaused = false;
        applyColumnsPref();
        applyAppearance();
        applyTimeFormat();
        // The aerial toggle was just switched on in the settings panel: rotate right now instead of
        // waiting for the next cold start, so the change is visible the moment the owner is back.
        // The copy is IO (multi-model review P0); refreshWallpaper() then runs on the main thread.
        boolean aerialOn = prefs.isAerialRotateOn();
        if (aerialOn && !aerialWasOn) {
            io.execute(() -> {
                if (maybeRotateAerialWallpaper()) postUi(MainActivity.this::refreshWallpaper);
            });
        }
        aerialWasOn = aerialOn;
        // The Bing rotation switched on in the panel: backfill a thin stock, rotate right away.
        // Also backfills while the switch is already on: the multi-market pass repeats at most
        // once a day and tops the stock up to target, so the visible count keeps growing.
        boolean bingRotateOn = prefs.isBingRotateOn();
        if (bingRotateOn) {
            io.execute(() -> {
                maybeFillBingStock();
                if (maybeRotateBingWallpaper()) postUi(MainActivity.this::refreshWallpaper);
            });
        }
        bingWasOn = bingRotateOn;
        // The 360 rotation switched on in the panel: rotate right away instead of waiting a day.
        if (prefs.isW360RotateOn()) {
            io.execute(() -> {
                if (maybeRotateW360Wallpaper()) postUi(MainActivity.this::refreshWallpaper);
            });
        }
        refreshWallpaper();
        // The aerial video follows the wallpaper source: (re)start it when the owner returns with
        // the Apple aerial rotation on, pause it otherwise.
        updateAerialVideo();
        // Back from an app: the content preview for the app we just left is written now.
        PreviewCapture.stopAndSave();
        updateWeather();
        maybeUpdateBingWallpaper();
        reloadApps(true);
        updateGreeting();
        updateWifiLabel();
        if (breatheAnim != null) breatheAnim.resume();   // the desktop is back: the breath returns
        updateInputsButton();
        handler.postDelayed(minuteTick, MINUTE_MS);
        // Back on the desktop (from an app or the panel): the idle count starts over.
        lastInteractionAt = System.currentTimeMillis();
        handler.postDelayed(screensaverTick, 15_000L);
    }

    /** Rebuilds the tiles (and the module cards), then puts focus back where it was. */
    private void reloadApps(boolean animate) {
        // Capture the app before rebinding can move focus and overwrite its key.
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        final boolean restoreFocus = animate || grid.hasFocus();
        if (restoreFocus) {
            glassShown = false;
            glass.setAlpha(0f);
            heroShown = false;
            heroName.setAlpha(0f);
            hideHeroArt();
        }
        // The tile scan is background work (multi-model review P0), so everything that reads the
        // new tiles moved into the completion: nothing after reloadTiles may read them directly.
        reloadTiles(() -> {
            if (animate) grid.scheduleLayoutAnimation();

            if (restoreFocus) {
                // Follow the same app, or the nearest remaining slot when it was removed.
                restoreFocus();
            } else {
                // A package change must not steal focus from a top button or the Inputs popup.
                int pos = adapter.positionOf(returnKey);
                lastFocused = Math.max(0, Math.min(pos >= 0 ? pos : returnSlot, adapter.getItemCount() - 1));
                lastFocusedKey = adapter.keyAt(lastFocused);
                if (pos < 0) hideHero();
                // A newly installed app may have brought a card with it.
                tilesReady = true;
                showCardPrompt();
            }
        });
    }

    /**
     * Rebuilds the tiles; the module cards the owner chose to show stand in for their app's tile.
     * Every step into the card code runs under CardFeature: if it fails, the tiles are the ordinary
     * ones and cards stay off for this process.
     * <p>The package query, the card discovery and the icon/banner loads are IO (multi-model
     * review P0), so they run on the background thread; the adapter swaps its list in on the main
     * thread and only then does {@code onDone} run.
     */
    private void reloadTiles() {
        reloadTiles(null);
    }

    private void reloadTiles(Runnable onDone) {
        // Read these before reload(): rebinding can move focus to the first tile and overwrite them.
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        // Adopt the owner's dock order, hidden list and folders (long-press edits, or a restored backup).
        userDock = prefs.loadDock(userDock);
        userHidden = prefs.loadHidden(userHidden);
        userIconOrder = prefs.loadIconOrder();
        userFolders = prefs.loadFolders();
        adapter.setEdits(userDock, userHidden, userFolders);
        adapter.setIconOrder(userIconOrder);
        hiddenApps = new HashSet<>(userHidden);
        final List<Cards.Card> none = Collections.emptyList();
        Runnable done = () -> {
            lastFocusedKey = returnKey;
            lastFocused = returnSlot;
            if (onDone != null) onDone.run();
        };
        if (!moduleCards) {
            cards = none;
            adapter.reload(none, done);
            return;
        }
        // The application context is all the background scan needs (PackageManager, files dir):
        // it must not hold the activity alive while it runs (multi-model review P1).
        final Context appCtx = getApplicationContext();
        final Set<String> hiddenSnap = hiddenApps;
        io.execute(() -> {
            final List<Cards.Card> found = CardFeature.run(() ->
                    Cards.discover(appCtx, hiddenSnap, Cards.load(Cards.storeFile(appCtx))), none);
            final List<Cards.Card> shown = found.isEmpty() ? none : CardFeature.run(() -> Cards.shown(found), none);
            handler.post(() -> {
                cards = shown;
                adapter.reload(shown, done);
            });
        });
    }

    /**
     * Return to the app we left from, or the same slot if that app is gone. Always refocus: after
     * reload() the view that holds focus may no longer show the app it had.
     */
    private void restoreFocus() {
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        // Pure-wallpaper first page: the rows wait below the fold until the owner asks for them
        // with Down, so the focus simply rests on the top bar meanwhile.
        if (!dockVisible && !gridEntered) {
            grid.post(() -> {
                focusTopBar();
                tilesReady = true;
                showCardPrompt();
            });
            return;
        }
        grid.post(() -> {
            int pos = adapter.positionOf(returnKey);
            focusTile(pos >= 0 ? pos : returnSlot);
            tilesReady = true;
            showCardPrompt();
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(packageReceiver);
        unregisterReceiver(netCheckReceiver);
        handler.removeCallbacks(minuteTick);
        handler.removeCallbacks(screensaverTick);   // an app or the panel is in front: no idle count
        // The desktop is not on screen: the breath rests (the screen is off or showing an app).
        if (breatheAnim != null) breatheAnim.pause();
        // The desktop video is off screen too: pause it so it isn't decoding behind another app.
        if (aerialVideo != null) aerialVideo.pause();
        // Nothing of ours stays on screen over another app; an undecided prompt simply returns next time.
        if (cardPrompt != null) cardPrompt.dismiss();
        if (openPopup != null) openPopup.dismiss();
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        lastInteractionAt = System.currentTimeMillis();   // any remote key or touch: the owner is here
    }

    /**
     * The owner's screensaver: after the chosen idle minutes on the desktop, hand the screen to
     * ScreensaverActivity (aerial stills + the Bing picture, clock on top, any key ends it).
     * Never while a menu or a popup is open — the owner is busy, not away.
     */
    private void maybeStartScreensaver() {
        if (!prefs.isScreensaverOn() || ScreensaverActivity.showing) return;
        if (cardPrompt != null || openPopup != null) return;
        long idle = System.currentTimeMillis() - lastInteractionAt;
        if (idle < prefs.loadScreensaverDelayMin() * 60_000L) return;
        lastInteractionAt = System.currentTimeMillis();   // one start per idle period
        try {
            startActivity(new Intent(this, ScreensaverActivity.class));
        } catch (Exception e) {
            android.util.Log.w("TvHome", "screensaver did not start: " + e);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) showCardPrompt();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (aerialVideo != null) aerialVideo.release();
        if (aerialTicker != null) handler.removeCallbacks(aerialTicker);
        handler.removeCallbacks(screensaverTick);
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        try {
            if (tim != null) tim.unregisterCallback(inputCallback);
        } catch (Exception noTvInputService) {
            // nothing was registered
            android.util.Log.d("TvHome", "TV input callback was not registered: " + noTvInputService);
        }
        if (cm != null) {
            cm.unregisterNetworkCallback(networkCallback);
            cm.unregisterNetworkCallback(vpnCallback);
        }
    }

    @Override
    public void onBackPressed() {
        // Move mode first: Back puts the picked-up app back where it was.
        if (movingPkg != null) {
            finishMove(-1);
            return;
        }
        // Pure wallpaper with the grid revealed: Back slides it back down, like Up on the first row.
        if (!dockVisible && gridEntered) {
            hideGrid();
            return;
        }
        // Home screen: Back goes nowhere.
    }

    // ---- glass highlight -------------------------------------------------------------------------

    @Override
    public void onTileFocused(int position, View card) {
        // Pure wallpaper, grid still below the fold: a tile holding focus off-screen would strand
        // the D-pad (and marked the grid "entered", which killed the Down reveal). Bounce the
        // focus back to the top bar — this was the "pure wallpaper, Down does nothing" bug.
        if (!dockVisible && grid.getTranslationY() > 1f) {
            focusTopBar();
            return;
        }
        lastFocused = position;
        lastFocusedKey = adapter.keyAt(position);
        // Move mode: the tile under the remote is where the picked-up app would land. The
        // glass highlight already sits on the focused tile, so no extra paint is needed.
        if (movingPkg != null) moveTargetPos = position;
        // Tile rect in root coordinates, using layout positions (ignores the scale animation).
        Rect r = new Rect(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        float pad = 6f * density;        // matches GlassView's glow inset
        float w = card.getWidth() * AppAdapter.FOCUS_SCALE + 2 * pad;
        float h = card.getHeight() * AppAdapter.FOCUS_SCALE + 2 * pad;
        float x = r.exactCenterX() - w / 2f;
        float y = r.exactCenterY() - h / 2f;

        ViewGroup.LayoutParams lp = glass.getLayoutParams();
        if (lp.width != (int) w || lp.height != (int) h) {
            lp.width = (int) w;
            lp.height = (int) h;
            glass.setLayoutParams(lp);
        }
        // The corner radius gets its own guard: the owner's icon-radius pick changes it while
        // the size stays the same, and the glow must follow the pick the moment it changes
        // (it used to sit inside the size check above and never updated — the owner's report).
        float wantRadius = AppAdapter.cornerRadius(card.getWidth(), card.getHeight(), radiusFrac)
                * AppAdapter.FOCUS_SCALE + pad;
        if (Math.abs(glassRadiusPx - wantRadius) > 0.5f) {
            glassRadiusPx = wantRadius;
            glass.setRadius(wantRadius);
        }
        glassTargetX = x;
        glassTargetY = y;
        if (!glassShown) {
            springX.cancel();
            springY.cancel();
            glass.setTranslationX(x);
            glass.setTranslationY(y);
            glass.animate().alpha(1f).setDuration(180).start();
            glassShown = true;
        } else {
            springX.animateToFinalPosition(x);
            springY.animateToFinalPosition(y - glassScrollShift);
        }
        View item = (View) card.getParent();
        if (dockBarOn && position < COLUMNS) {
            // Dock: park it at the bottom of the screen and show the top shelf for this app.
            int decoratedTop = item.getTop() - (int) (6 * density);
            int dy = decoratedTop - grid.getPaddingTop();
            // Same explicit duration as the grid clamp: the framework default teleports.
            if (Math.abs(dy) > 1) {
                int dur = Math.min(600, 220 + Math.abs(dy) / 2);
                grid.smoothScrollBy(0, dy, new android.view.animation.DecelerateInterpolator(), dur);
            }
            showHero(position, r.top - dy);
        } else {
            // A grid tile: the tile's own label names it, so only the artwork takes over the
            // wallpaper — the app's captured content, its banner, or nothing at all. Never the icon.
            hideHeroName();
            heroArtFor(position);
            // Park the focused tile fully below the glass line: it rides at full alpha with a
            // tile of breathing room, the rows above it dissolve into the shelf, and the tile
            // itself never climbs into the clock / capsule row.
            scrollTileUnderGlass(item);
        }
        gridEntered = true;
        fadeUnderGlass();
    }

    // ---- top shelf -------------------------------------------------------------------------------

    private void showHero(int position, float dockTopInRoot) {
        String label = adapter.labelAt(position);
        float y = dockTopInRoot - 14 * density - 26 * density - heroName.getHeight();
        boolean changed = !label.contentEquals(heroName.getText());

        if (!heroShown) {
            heroName.setText(label);
            heroName.setY(y);
            heroName.setTranslationX(0f);
            heroName.animate().alpha(1f).setDuration(260).start();
            heroShown = true;
        } else if (changed) {
            heroName.animate().alpha(0f).translationX(-12 * density).setDuration(120).withEndAction(() -> {
                heroName.setText(label);
                heroName.setY(y);
                heroName.setTranslationX(12 * density);
                heroName.animate().alpha(1f).translationX(0f).setDuration(200).start();
            }).start();
        } else {
            heroName.setY(y);
        }
        // Apple TV style: the focused app's own artwork takes over the wallpaper (dimmed, so the
        // clock and labels stay readable). Apps without artwork keep the wallpaper.
        heroArtFor(position);
    }

    /**
     * The focused app's big picture: its captured content first (a real screen from inside the
     * app — the owner's ask), then its manifest banner, then nothing. A bare icon is never used.
     * The preview is a file, so it decodes on the IO thread behind a generation guard.
     */
    private void heroArtFor(int position) {
        if (!prefs.isHeroArtOn() || adapter == null) {
            hideHeroArt();
            return;
        }
        File pv = adapter.previewAt(position);
        final android.graphics.drawable.Drawable banner = adapter.bannerAt(position);
        if (pv == null) {
            if (banner != null) showHeroArt(banner);
            else hideHeroArt();
            return;
        }
        final int gen = ++heroGen;
        io.execute(() -> {
            final Bitmap bmp = decodePreview(pv);
            postUi(() -> {
                if (gen != heroGen) return;   // focus moved on: this art is stale
                if (bmp != null) showHeroArt(new android.graphics.drawable.BitmapDrawable(
                        getResources(), bmp));
                else if (banner != null) showHeroArt(banner);
                else hideHeroArt();
            });
        });
    }

    /** Bounds + downsampled decode of a captured preview (RGB_565: it is a dimmed backdrop). */
    private Bitmap decodePreview(File file) {
        try {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(file.getPath(), o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= 720 && o.outHeight / (sample * 2) >= 720) sample *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            return android.graphics.BitmapFactory.decodeFile(file.getPath(), o);
        } catch (Exception | OutOfMemoryError e) {
            android.util.Log.w("TvHome", "preview decode failed for " + file + ": " + e);
            return null;
        }
    }

    /** Full-bleed app artwork behind the desktop, sitting just above the wallpaper. */
    private void showHeroArt(android.graphics.drawable.Drawable art) {
        if (heroArt == null) {
            heroArt = new android.widget.ImageView(this);
            heroArt.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            heroArt.setColorFilter(0x40000000, android.graphics.PorterDuff.Mode.SRC_OVER);
            heroArt.setAlpha(0f);
            heroArt.setScaleX(0.94f);
            heroArt.setScaleY(0.94f);
            int at = root.indexOfChild(wallpaperView);
            root.addView(heroArt, at >= 0 ? at + 1 : 0,
                    new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));
        }
        if (heroArt.getDrawable() != art) heroArt.setImageDrawable(art);
        // Scales up into place while it fades in — the artwork arrives instead of just appearing.
        heroArt.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(320)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.3f)).start();
        // No shelf blur here: the glass only lives where a row crosses the top bar, and the
        // hero artwork mode has no rows there — the top bar sits straight on the dimmed artwork.
    }

    private void hideHeroArt() {
        if (heroArt == null || heroArt.getAlpha() == 0f) return;
        // Shrinks slightly as it fades out — the mirror of the way it arrives.
        heroArt.animate().alpha(0f).scaleX(0.98f).scaleY(0.98f).setDuration(220).start();
        updateShelfFrost(currentWallpaper());   // the glass goes back to blurring the wallpaper
    }

    /** Starts the slow ticker that rotates the aerial wallpaper on the chosen cadence. */
    private void startAerialTicker() {
        if (aerialTicker != null) return;
        aerialTicker = new Runnable() {
            @Override
            public void run() {
                // The copy and the stamp writes are IO (multi-model review P0): off the main thread.
                io.execute(() -> {
                    boolean changed = maybeRotateAerialWallpaper()
                            || maybeRotateBingWallpaper() || maybeRotateW360Wallpaper();
                    if (changed) postUi(MainActivity.this::refreshWallpaper);
                });
                handler.postDelayed(this, 30_000L);   // a 5-minute minimum makes 30 s checks plenty
            }
        };
        handler.postDelayed(aerialTicker, 30_000L);
    }

    private void hideHero() {
        hideHeroArt();
        hideHeroName();
    }

    /** Just the big app name above the dock: a grid tile names itself, so it steps aside. */
    private void hideHeroName() {
        if (!heroShown) return;
        heroShown = false;
        heroName.animate().alpha(0f).setDuration(160).start();
    }

    private void hideGlass() {
        if (!glassShown) return;
        glassShown = false;
        glass.animate().alpha(0f).setDuration(160).start();
    }

    // ---- navigation ------------------------------------------------------------------------------

    @Override
    public boolean onTileVerticalKey(int position, int keyCode) {
        int count = adapter.getItemCount();
        int target;
        // Move mode: the remote must stay inside the free grid, so the drop target can only
        // be an app icon. All four directions are handled here (the default focus search
        // would walk out of the grid into the top bar).
        if (movingPkg != null) {
            int start = adapter.freeGridStart();
            int end = adapter.freeGridEnd();
            if (start < 0 || end < 0) return true;
            int here = Math.max(start, Math.min(position, end));
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    target = here > start ? here - 1 : here;
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    target = here < end ? here + 1 : here;
                    break;
                case KeyEvent.KEYCODE_DPAD_UP:
                    target = here - COLUMNS;
                    if (target < start) target = here;   // the first row stays put
                    break;
                default:
                    target = here + COLUMNS;
                    if (target > end) target = here;    // the last row stays put
                    break;
            }
            if (target != here) focusTile(target);
            return true;
        }
        // Horizontal: swallow the key at a row's ends so focus never wraps to the next row or
        // escapes up into the top bar. Mid-row moves fall through to the default focus search.
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            return position % COLUMNS == 0;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            return position % COLUMNS == COLUMNS - 1 || position == count - 1;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (position < COLUMNS) {
                // Pure-wallpaper first page: Up on the first row slides the grid back below the
                // fold, so the desktop returns to pure wallpaper instead of staying covered.
                if (!dockVisible && gridEntered) {
                    hideGrid();
                    return true;
                }
                return false;          // dock row: let focus move to the top bar
            }
            target = position - COLUMNS;
        } else {
            int lastRowStart = ((count - 1) / COLUMNS) * COLUMNS;
            if (position >= lastRowStart) return true;     // already on the last row: stay put
            target = Math.min(position + COLUMNS, count - 1);
        }
        focusTile(target);
        return true;
    }

    private void focusTile(int position) {
        if (adapter.getItemCount() == 0) {
            btnWifi.requestFocus();
            return;
        }
        final int pos = Math.max(0, Math.min(position, adapter.getItemCount() - 1));
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        if (vh != null) {
            View card = vh.itemView.findViewById(R.id.card);
            // Already focused (e.g. back from an app): no focus change fires, so restore the glass by hand.
            if (card.isFocused()) onTileFocused(pos, card);
            else card.requestFocus();
            return;
        }
        // Not laid out yet: scroll there, then focus once the scroll settles.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return;
                rv.removeOnScrollListener(this);
                RecyclerView.ViewHolder h = rv.findViewHolderForAdapterPosition(pos);
                View target = h != null ? h.itemView.findViewById(R.id.card) : rv.getChildAt(0);
                if (target != null) target.requestFocus();
            }
        });
        grid.getLayoutManager().startSmoothScroll(new CalmScroller(this, pos));
    }

    /**
     * A calm focus scroller for the not-laid-out-yet fallback: the framework default moves at
     * 25 ms per inch, which on a TV teleports rows in ~25 ms (the owner's "icons just vanish").
     * ~330 ms per inch gives them time to dissolve through the fade zone on their way.
     */
    private static class CalmScroller extends androidx.recyclerview.widget.LinearSmoothScroller {
        private static final float MS_PER_INCH = 330f;

        CalmScroller(Context c, int position) {
            super(c);
            setTargetPosition(position);
        }

        @Override
        protected float calculateSpeedPerPixel(android.util.DisplayMetrics dm) {
            return MS_PER_INCH / dm.densityDpi;
        }
    }

    // ---- long-press menu ----------------------------------------------------------------------

    /**
     * The owner's edit menu on a tile (inspired by AT4K's live editing menu): pin to the dock or
     * unpin, hide or show again, the system app info page, or add the app to a folder. A folder tile
     * gets its own menu (open, rename, lock, password, delete). Edits persist in PrefsStore.
     */
    @Override
    public void onTileLongPress(int position, View card) {
        final Folder folder = adapter.folderAt(position);
        if (folder != null) {
            showFolderMenu(folder);
            return;
        }
        final String pkg = adapter.pkgAt(position);
        final String label = adapter.labelAt(position);
        if (pkg == null) return;   // quick tiles (Default home) carry no app to edit
        boolean docked = adapter.isDockedAt(position);
        boolean hidden = userHidden.contains(pkg);

        List<CharSequence> options = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (docked) {
            options.add(getString(R.string.long_press_unpin));
            actions.add(() -> pinApp(pkg, false));
        } else {
            options.add(getString(R.string.long_press_pin));
            actions.add(() -> pinApp(pkg, true));
        }
        if (hidden) {
            options.add(getString(R.string.long_press_show));
            actions.add(() -> hideApp(pkg, false));
        } else {
            options.add(getString(R.string.long_press_hide));
            actions.add(() -> hideApp(pkg, true));
        }
        options.add(getString(R.string.long_press_info));
        actions.add(() -> {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(i);
            } catch (ActivityNotFoundException ignored) {
                android.util.Log.d("TvHome", "no picture picker on this box: " + ignored);
            }
        });
        options.add(getString(R.string.add_to_folder));
        actions.add(() -> showFolderPicker(pkg));
        options.add(getString(R.string.icon_change));
        actions.add(() -> showIconMenu(pkg, label));
        // Move: the owner picks the app up, walks the remote to the wanted slot and drops it.
        if (adapter.groupAt(position) == 3 && adapter.isPlainAppAt(position)) {
            options.add(getString(R.string.long_press_move));
            actions.add(() -> startMove(pkg));
        }
        // Delete: uninstalls the app from the box (the system asks for the confirmation).
        // "Hide" stays the reversible one — this one is not.
        options.add(getString(R.string.long_press_delete));
        actions.add(() -> confirmDeleteApp(pkg, label));

        Dialogs.show(new AlertDialog.Builder(this, R.style.TvHomeDialog)
                .setTitle(label)
                .setItems(options.toArray(new CharSequence[0]), (d, which) -> actions.get(which).run())
                .create());
    }

    // ---- move mode (the owner's own icon order) --------------------------------------------------

    /**
     * Picks an app up for moving. The tile fades out (it is "in hand"), and the remote now
     * picks the drop target: the tile under the focus is where the app would land, OK drops
     * it, Back puts it back. The result is written to the owner's icon order and survives a
     * restart.
     */
    private void startMove(String pkg) {
        movingPkg = pkg;
        moveTargetPos = -1;
        adapter.setMovingPkg(pkg);
        fadeTile(pkg, true);
        // Put the focus on the tile that was just picked up. The remote's arrow keys are
        // intercepted by the tile's own key listener, so the focus has to be ON a tile for
        // them to be caught at all: if it stayed wherever it was (the top bar, or another
        // app), the arrow keys would run the default focus search and walk out of the grid.
        // The menu dialog still holds the focus while this runs, so the request has to wait
        // for the dialog to go away — asked too early, the system drops it silently.
        final int pos = adapter.positionOfPkg(pkg);
        if (pos >= 0) {
            grid.postDelayed(() -> {
                if (movingPkg != null) focusTile(pos);
            }, 150);
        }
        Toast.makeText(this, getString(R.string.move_hint), Toast.LENGTH_LONG).show();
    }

    /**
     * Fades the picked-up tile (or brings it back). Done on the view itself: a rebind of the
     * focused tile would cost the focus, and the remote must keep walking.
     */
    private void fadeTile(String pkg, boolean faded) {
        int pos = adapter.positionOfPkg(pkg);
        if (pos < 0) return;
        androidx.recyclerview.widget.RecyclerView.ViewHolder vh =
                grid.findViewHolderForAdapterPosition(pos);
        if (vh == null) return;
        View card = vh.itemView.findViewById(R.id.card);
        if (card != null) card.setAlpha(faded ? 0.3f : 1f);
    }

    /** Drops the picked-up app onto the tile under the focus, or puts it back (Back). */
    private void finishMove(int targetPos) {
        String pkg = movingPkg;
        movingPkg = null;
        moveTargetPos = -1;
        adapter.setMovingPkg(null);
        if (pkg != null) fadeTile(pkg, false);
        if (pkg == null) return;
        if (targetPos < 0) {
            Toast.makeText(this, R.string.move_cancelled, Toast.LENGTH_SHORT).show();
            return;
        }
        // The target must be a plain app in the same group: a folder, a module card or the
        // dock row is not a slot the app can be dropped into.
        if (!adapter.isPlainAppAt(targetPos) || adapter.groupAt(targetPos) != 3) {
            Toast.makeText(this, R.string.move_bad_target, Toast.LENGTH_LONG).show();
            return;
        }
        String target = adapter.pkgAt(targetPos);
        if (target == null || target.equals(pkg)) {
            Toast.makeText(this, R.string.move_cancelled, Toast.LENGTH_SHORT).show();
            return;
        }
        // Swap the two in the owner's order: the free grid's current order is the base, and
        // the two packages trade places in it.
        List<String> order = new ArrayList<>(adapter.freeGridPackages());
        int from = order.indexOf(pkg);
        int to = order.indexOf(target);
        if (from < 0 || to < 0) {
            Toast.makeText(this, R.string.move_failed, Toast.LENGTH_LONG).show();
            return;
        }
        order.remove(from);
        order.add(to, pkg);
        userIconOrder = order;
        prefs.saveIconOrder(order);
        adapter.setIconOrder(order);
        reloadTiles();
        Toast.makeText(this, getString(R.string.move_done, target), Toast.LENGTH_SHORT).show();
    }

    // ---- delete (uninstall) an app ---------------------------------------------------------------

    /**
     * Uninstalls an app. The system shows its own confirmation screen, so the owner always
     * sees what is about to go and can back out — this never removes anything on its own.
     * (Hiding an app is the reversible thing; this is the real one.)
     */
    private void confirmDeleteApp(String pkg, String label) {
        Dialogs.show(new AlertDialog.Builder(this, R.style.TvHomeDialog)
                .setTitle(getString(R.string.long_press_delete))
                .setMessage(getString(R.string.delete_confirm_msg, label))
                .setPositiveButton(R.string.delete_confirm_yes, (d, which) -> deleteApp(pkg))
                .setNegativeButton(android.R.string.cancel, null)
                .create());
    }

    /** Starts the system uninstaller for this package. */
    private void deleteApp(String pkg) {
        try {
            Intent i = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (ActivityNotFoundException noUninstaller) {
            android.util.Log.d("TvHome", "no uninstaller on this box: " + noUninstaller);
            Toast.makeText(this, R.string.delete_no_uninstaller, Toast.LENGTH_LONG).show();
        }
    }

    // ---- icon management (inspired by AT4K): replace an app's icon with a picture, or put it back ----

    /** Where a custom icon lives: icon_&lt;package&gt;.png in the app's files dir; exists = replaced. */
    private File customIconFile(String pkg) {
        File dir = Config.dir(this);
        return dir != null ? new File(dir, "icon_" + pkg + ".png") : null;
    }

    private void showIconMenu(String pkg, String label) {
        File file = customIconFile(pkg);
        List<CharSequence> options = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        options.add(getString(R.string.icon_pick));
        actions.add(() -> {
            pendingIconPkg = pkg;
            Intent i = new Intent(Intent.ACTION_GET_CONTENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("image/*");
            try {
                startActivityForResult(Intent.createChooser(i, getString(R.string.icon_pick)), REQUEST_ICON);
            } catch (ActivityNotFoundException unavailable) {
                Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
            }
        });
        if (file != null && file.isFile()) {
            options.add(getString(R.string.icon_reset));
            actions.add(() -> {
                if (file.delete()) {
                    reloadTiles();
                    Toast.makeText(this, R.string.icon_reset_done, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, R.string.icon_failed, Toast.LENGTH_LONG).show();
                }
            });
        }
        Dialogs.show(new AlertDialog.Builder(this, R.style.TvHomeDialog)
                .setTitle(label + " \u00B7 " + getString(R.string.icon_change))
                .setItems(options.toArray(new CharSequence[0]), (d, which) -> actions.get(which).run())
                .setNegativeButton(android.R.string.cancel, null)
                .create());
    }

    /**
     * OK on an app tile: with content previews on, the screen is mirrored while the app is in
     * front so its own screen can become the hero art. The system's one-time capture consent is
     * asked on the first launch; a refusal simply leaves the banner art as it was.
     */
    @Override
    public void onTileLaunch(AppAdapter.Entry e) {
        // Move mode: OK drops the picked-up app on the tile under the focus instead of
        // opening it. The target is the focused tile, which is what the remote points at.
        if (movingPkg != null) {
            int pos = adapter.positionOfPkg(e.pkg);
            finishMove(pos >= 0 ? pos : -1);
            return;
        }
        if (!prefs.isPreviewOn() || projectionManager == null) {
            adapter.startApp(e);
            return;
        }
        if (PreviewCapture.ready()) {
            PreviewCapture.start(e.pkg);
            adapter.startApp(e);
            return;
        }
        // Android 6 and below (and TVs whose MediaProjection consent dialog cannot surface)
        // must never block the app launch on the screen-capture consent: start the app
        // directly. The content preview simply stays off on those devices.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            adapter.startApp(e);
            return;
        }
        pendingLaunchEntry = e;
        try {
            startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_CAPTURE);
        } catch (Exception ex) {
            android.util.Log.w("TvHome", "capture consent unavailable: " + ex);
            pendingLaunchEntry = null;
            adapter.startApp(e);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CAPTURE) {
            AppAdapter.Entry target = pendingLaunchEntry;
            pendingLaunchEntry = null;
            if (resultCode == RESULT_OK && data != null) {
                try {
                    MediaProjection projection = projectionManager.getMediaProjection(resultCode, data);
                    if (projection != null) PreviewCapture.init(this, projection);
                } catch (Exception ex) {
                    android.util.Log.w("TvHome", "capture consent failed: " + ex);
                }
            } else {
                // Refused once: stop asking on every launch — the panel can turn it back on.
                if (prefs.isPreviewOn()) {
                    prefs.setPreviewOn(false);
                    Toast.makeText(this, R.string.preview_declined, Toast.LENGTH_LONG).show();
                }
            }
            if (target != null) {
                if (PreviewCapture.ready()) PreviewCapture.start(target.pkg);
                adapter.startApp(target);
            }
            return;
        }
        if (requestCode != REQUEST_ICON || resultCode != RESULT_OK
                || data == null || data.getData() == null || pendingIconPkg == null) return;
        File out = customIconFile(pendingIconPkg);
        pendingIconPkg = null;
        if (out == null) {
            Toast.makeText(this, R.string.icon_failed, Toast.LENGTH_LONG).show();
            return;
        }
        try (java.io.InputStream in = getContentResolver().openInputStream(data.getData());
             java.io.FileOutputStream fo = new java.io.FileOutputStream(out)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
            reloadTiles();
            Toast.makeText(this, R.string.icon_done, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, R.string.icon_failed, Toast.LENGTH_LONG).show();
        }
    }

    /** Pin to the dock's first slot or take it out of the pinned row, then save the order. */
    private void pinApp(String pkg, boolean on) {
        userDock.remove(pkg);
        if (on) userDock.add(0, pkg);
        prefs.saveDock(userDock);
        reloadTiles();
    }

    /** Hide the app from the home screen or show it again, then save the list. */
    private void hideApp(String pkg, boolean on) {
        if (on) userHidden.add(pkg);
        else userHidden.remove(pkg);
        prefs.saveHidden(userHidden);
        reloadTiles();
    }

    // ---- folders (inspired by AT4K) ------------------------------------------------------------

    /**
     * OK on a folder tile (AT4K's folder open): a locked folder first asks for its 4-digit password;
     * a wrong code keeps it closed with "密码错误", exactly like AT4K.
     */
    @Override
    public void onFolderOpen(Folder folder) {
        if (!folder.isLocked()) {
            showFolderContents(folder);
            return;
        }
        askPassword(getString(R.string.enter_password_to_open), entered -> {
            if (folder.password.equals(entered)) showFolderContents(folder);
            else Toast.makeText(this, R.string.password_wrong, Toast.LENGTH_SHORT).show();
        });
    }

    /** The folder tile's menu: open, rename, lock/unlock, set or change the password, delete. */
    private void showFolderMenu(Folder folder) {
        List<CharSequence> options = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        options.add(getString(R.string.open_folder));
        actions.add(() -> onFolderOpen(folder));
        options.add(getString(R.string.rename_folder));
        actions.add(() -> showRenameDialog(folder));
        if (folder.isLocked()) {
            options.add(getString(R.string.unlock_folder));
            actions.add(() -> {
                folder.password = "";
                saveFolders();
                Toast.makeText(this, R.string.folder_unlocked, Toast.LENGTH_SHORT).show();
            });
        } else {
            options.add(getString(R.string.lock_folder));
            actions.add(() -> showSetPasswordDialog(folder));
        }
        options.add(getString(R.string.change_password));
        actions.add(() -> showChangePasswordDialog(folder));
        options.add(getString(R.string.delete_folder));
        actions.add(() -> {
            if (folder.isLocked()) {
                askPassword(getString(R.string.enter_password_to_delete), entered -> {
                    if (folder.password.equals(entered)) deleteFolder(folder);
                    else Toast.makeText(this, R.string.password_wrong, Toast.LENGTH_SHORT).show();
                });
            } else {
                deleteFolder(folder);
            }
        });

        Dialogs.show(new AlertDialog.Builder(this, R.style.TvHomeDialog)
                .setTitle(folder.name)
                .setItems(options.toArray(new CharSequence[0]), (d, which) -> actions.get(which).run())
                .create());
    }

    /** Apps inside a folder: OK launches one, the extra entry takes apps back out. */
    private void showFolderContents(Folder folder) {
        if (folder.apps.isEmpty()) {
            Dialogs.show(new AlertDialog.Builder(this, R.style.TvHomeDialog)
                    .setTitle(folder.name)
                    .setMessage(R.string.folder_empty)
                    .setPositiveButton(android.R.string.ok, null)
                    .create());
            return;
        }
        List<String> pkgs = new ArrayList<>(folder.apps);
        String[] labels = new String[pkgs.size()];
        for (int i = 0; i < pkgs.size(); i++) labels[i] = labelFor(pkgs.get(i));
        List<CharSequence> items = new ArrayList<>();
        for (String s : labels) items.add(s);
        items.add(getString(R.string.remove_from_folder));
        Dialogs.show(new AlertDialog.Builder(this, R.style.TvHomeDialog)
                .setTitle(folder.name)
                .setItems(items.toArray(new CharSequence[0]), (d, which) -> {
                    if (which >= pkgs.size()) {
                        showRemoveFromFolder(folder);
                        return;
                    }
                    launchPackage(pkgs.get(which));
                })
                .create());
    }

    /** Takes one app out of a folder and back onto the grid. */
    private void showRemoveFromFolder(Folder folder) {
        List<String> pkgs = new ArrayList<>(folder.apps);
        String[] labels = new String[pkgs.size()];
        for (int i = 0; i < pkgs.size(); i++) labels[i] = labelFor(pkgs.get(i));
        Dialogs.show(new AlertDialog.Builder(this, R.style.TvHomeDialog)
                .setTitle(R.string.remove_from_folder)
                .setItems(labels, (d, which) -> {
                    folder.apps.remove(pkgs.get(which));
                    saveFolders();
                })
                .create());
    }

    /** "Add to folder": the owner's folders plus a new-folder entry at the end (AT4K's picker). */
    private void showFolderPicker(String pkg) {
        List<CharSequence> items = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        for (Folder f : userFolders) {
            items.add(f.name);
            actions.add(() -> {
                if (!f.apps.contains(pkg)) f.apps.add(pkg);
                saveFolders();
            });
        }
        items.add(getString(R.string.new_folder));
        actions.add(() -> showNewFolderDialog(pkg));
        Dialogs.show(new AlertDialog.Builder(this, R.style.TvHomeDialog)
                .setTitle(R.string.choose_folder)
                .setItems(items.toArray(new CharSequence[0]), (d, which) -> actions.get(which).run())
                .create());
    }

    /** Creates a folder and puts the given app in it at once. */
    private void showNewFolderDialog(String pkg) {
        final EditText input = new EditText(this);
        input.setSingleLine();
        input.setHint(R.string.new_folder);
        Dialogs.show(new AlertDialog.Builder(this, R.style.TvHomeDialog)
                .setTitle(R.string.new_folder)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (d, which) -> {
                    String name = input.getText().toString().trim();
                    Folder f = new Folder(nextFolderId(), name.isEmpty() ? getString(R.string.new_folder) : name);
                    f.apps.add(pkg);
                    userFolders.add(f);
                    saveFolders();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create());
    }

    /** Renames a folder (empty input keeps the old name). */
    private void showRenameDialog(Folder folder) {
        final EditText input = new EditText(this);
        input.setSingleLine();
        input.setText(folder.name);
        input.setSelection(input.getText().length());
        Dialogs.show(new AlertDialog.Builder(this, R.style.TvHomeDialog)
                .setTitle(R.string.rename_folder)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (d, which) -> {
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty()) {
                        folder.name = name;
                        saveFolders();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create());
    }

    /** Locks a folder: a new 4-digit password, saved with "密码已保存". */
    private void showSetPasswordDialog(Folder folder) {
        askPassword(getString(R.string.set_password_hint), entered -> {
            folder.password = entered;
            saveFolders();
            Toast.makeText(this, R.string.password_saved, Toast.LENGTH_SHORT).show();
        });
    }

    /** Changes the password of an already locked folder (the old code is asked for first). */
    private void showChangePasswordDialog(Folder folder) {
        if (!folder.isLocked()) {
            showSetPasswordDialog(folder);
            return;
        }
        askPassword(getString(R.string.enter_password_hint), old -> {
            if (!folder.password.equals(old)) {
                Toast.makeText(this, R.string.password_wrong, Toast.LENGTH_SHORT).show();
                return;
            }
            askPassword(getString(R.string.set_password_hint), entered -> {
                folder.password = entered;
                saveFolders();
                Toast.makeText(this, R.string.password_saved, Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void deleteFolder(Folder folder) {
        userFolders.remove(folder);
        saveFolders();
        Toast.makeText(this, R.string.folder_deleted, Toast.LENGTH_SHORT).show();
    }

    /** Asks for a 4-digit password and hands the code to done; cancel or empty input does nothing. */
    private void askPassword(String title, PasswordDone done) {
        final EditText input = new EditText(this);
        input.setSingleLine();
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setHint(R.string.enter_password_hint);
        Dialogs.show(new AlertDialog.Builder(this, R.style.TvHomeDialog)
                .setTitle(title)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (d, which) -> {
                    String code = input.getText().toString().trim();
                    if (code.length() == 4) done.run(code);
                    else Toast.makeText(this, R.string.password_wrong, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create());
    }

    private interface PasswordDone {
        void run(String code);
    }

    private void saveFolders() {
        prefs.saveFolders(userFolders);
        reloadTiles();
    }

    /** A stable new folder id ("f1", "f2", ...), never reused while the list can still contain it. */
    private String nextFolderId() {
        int max = 0;
        for (Folder f : userFolders) {
            if (f.id.length() > 1 && f.id.charAt(0) == 'f') {
                try {
                    max = Math.max(max, Integer.parseInt(f.id.substring(1)));
                } catch (NumberFormatException ignored) {
                    // a hand-edited folder id that is not "f<number>": it just never gets that number
                    android.util.Log.d("TvHome", "folder id without a number: " + f.id);
                }
            }
        }
        return "f" + (max + 1);
    }

    /** Label for a package that is inside a folder (its tile is not on the grid). */
    private String labelFor(String pkg) {
        PackageManager pm = getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(probe, 0)) {
            if (ri.activityInfo != null && pkg.equals(ri.activityInfo.packageName)) {
                return String.valueOf(ri.loadLabel(pm));
            }
        }
        return pkg;
    }

    private void launchPackage(String pkg) {
        PackageManager pm = getPackageManager();
        Intent i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) {
            i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).setPackage(pkg)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        try {
            startActivity(i);
        } catch (Exception ex) {
            Toast.makeText(this, getString(R.string.cant_open, pkg), Toast.LENGTH_SHORT).show();
        }
    }

    // ---- top bar ---------------------------------------------------------------------------------

    private void updateGreeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int res;
        if (hour < 5) res = R.string.good_night;
        else if (hour < 12) res = R.string.good_morning;
        else if (hour < 17) res = R.string.good_afternoon;
        else if (hour < 22) res = R.string.good_evening;
        else res = R.string.good_night;
        greeting.setText(res);
        if (shamsi) shamsiDate.setText(Shamsi.today());
    }

    /** Shows the Wi-Fi network name on the button when we can read it, otherwise the connection type. */
    private void updateWifiLabel() {
        String text = getString(R.string.wifi);
        boolean vpn = false, online = false;
        int icon = R.drawable.ic_wifi;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            boolean wifi = false, ethernet = false;
            // All networks, not just the active one: with a VPN up the active network is the VPN itself.
            for (Network net : cm != null ? cm.getAllNetworks() : new Network[0]) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                if (caps == null) continue;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    vpn = true;
                    continue;
                }
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                online = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) ethernet = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = true;
            }
            if (!online) {
                text = getString(R.string.offline);
                vpn = false;
            } else if (ethernet) {
                text = getString(R.string.ethernet);
                icon = R.drawable.ic_ethernet;
            } else if (wifi) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
                String ssid = info != null ? info.getSSID() : null;
                if (ssid != null) {
                    ssid = ssid.replace("\"", "").trim();
                    if (!ssid.isEmpty() && !ssid.contains("unknown") && !ssid.equals("0x")) text = ssid;
                }
            }
        } catch (Exception ignored) {
            // A restricted SSID (some ROMs hide it): the pill keeps the plain connection state.
            android.util.Log.d("TvHome", "network name unreadable: " + ignored);
        }
        // With a VPN app configured, a VPN that is down matters too: show a red "VPN off" capsule then.
        boolean vpnDown = !vpn && online && !vpnApp.isEmpty();
        if (vpn || vpnDown) {
            // Network name, then the green lock-and-VPN (or red "VPN off") capsule drawn in place of the word.
            String badge = getString(vpn ? R.string.vpn : R.string.vpn_off);
            VpnBadge span = new VpnBadge(vpn ? getDrawable(R.drawable.ic_lock) : null, density);
            // The pill has a maximum width and ellipsizes at the end, which would cut the badge off after
            // a long network name, so shorten the name to whatever room the badge leaves. The total paddings
            // include the Wi-Fi icon.
            TextPaint paint = btnWifi.getPaint();
            float room = btnWifi.getMaxWidth() - btnWifi.getTotalPaddingStart() - btnWifi.getTotalPaddingEnd()
                    - span.getSize(paint, badge, 0, badge.length(), null) - paint.measureText(" ") - 2 * density;
            text = TextUtils.ellipsize(text, paint, Math.max(0, room), TextUtils.TruncateAt.END).toString();
            SpannableString label = new SpannableString(text + " " + badge);
            label.setSpan(span, text.length() + 1, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            btnWifi.setText(label);
        } else {
            btnWifi.setText(text);
        }
        btnWifi.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
    }

    /** The network pill opens the VPN app when one is configured and installed, otherwise Wi-Fi settings. */
    private void openNetworkPill() {
        Intent i = vpnApp.isEmpty() ? null : getPackageManager().getLeanbackLaunchIntentForPackage(vpnApp);
        if (i == null && !vpnApp.isEmpty()) i = getPackageManager().getLaunchIntentForPackage(vpnApp);
        open(i != null ? i : new Intent(Settings.ACTION_WIFI_SETTINGS));
    }

    /** Hides the Inputs pill when it would have nothing to list: streaming boxes have no HDMI-in, AV or tuner. */
    private void updateInputsButton() {
        boolean any = liveTvIntent() != null || !sourcePanelService.isEmpty();
        if (!any) {
            // Boxes with no TV input service (e.g. the Phicomm N1's tablet ROM) hand back a manager
            // whose service binder is null: getTvInputList() NPEs even though tim != null. The Inputs
            // pill is cosmetic — never let it take the home screen down.
            try {
                TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
                if (tim != null) {
                    for (TvInputInfo info : tim.getTvInputList()) {
                        if (info.isPassthroughInput()) {
                            any = true;
                            break;
                        }
                    }
                }
            } catch (Exception noTvInputService) {
                // stay hidden
            }
        }
        btnInputs.setVisibility(any ? View.VISIBLE : View.GONE);
    }

    /** The Live TV app's launch intent: its leanback entry point, or the configured action if it has none. */
    private Intent liveTvIntent() {
        if (liveTvPkg.isEmpty()) return null;
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg);
        if (i != null || liveTvAction.isEmpty()) return i;
        Intent byAction = new Intent(liveTvAction).setPackage(liveTvPkg);
        return getPackageManager().resolveActivity(byAction, 0) != null ? byAction : null;
    }

    /** The TV's own source-selection panel, for TVs that have no TvInputManager (TCL Android TV 9). */
    private void openSourcePanel() {
        Intent i = new Intent(sourcePanelAction);
        ComponentName c = ComponentName.unflattenFromString(sourcePanelService);
        if (c != null) i.setComponent(c);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Android 8+ throws IllegalStateException for a background startService; the
                // foreground form is the only one that gets through (the panel service is
                // expected to finish right away).
                startForegroundService(i);
            } else {
                startService(i);
            }
        } catch (Exception e) {
            android.util.Log.w("TvHome", "source panel unavailable: " + e);
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    /** Switches to a passthrough input (HDMI, AV). The standard way is ACTION_VIEW on its channel URI,
     *  which the Live TV app handles; where nothing takes that, the configured `input_action` is sent
     *  with the same URI instead (XGIMI's HDMI player only answers com.xgimi.action.hdmiPlayer). */
    private void openInput(String inputId) {
        Uri uri = TvContract.buildChannelUriForPassthroughInput(inputId);
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.cursor.item/channel")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(view);
            return;
        } catch (ActivityNotFoundException e) {
            if (inputAction.isEmpty()) {
                Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                return;
            }
        }
        open(new Intent(inputAction).setDataAndType(uri, "vnd.android.cursor.item/channel"));
    }

    /** Dropdown menu under the Inputs pill: HDMI ports (with connection state), AV and Live TV. */
    private void showInputs() {
        View anchor = findViewById(R.id.btnInputs);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);

        // Compact: as wide as the Inputs pill (a touch wider so "HDMI 1" fits), hanging right below it.
        int width = Math.max(anchor.getWidth(), (int) (150 * density));
        final PopupWindow popup = glassPopup(panel, width);

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        boolean sawNonHdmi = false;
        String tunerId = null;
        boolean tunerIsHw = false;
        List<TvInputInfo> inputs = new ArrayList<>();
        try {
            if (tim != null) inputs.addAll(tim.getTvInputList());
        } catch (Exception noTvInputService) {
            // service binder is null on boxes with no TV input; the popup just lists Live TV
            android.util.Log.d("TvHome", "TV input list unavailable: " + noTvInputService);
        }
        if (!inputs.isEmpty()) {
            // HDMI ports first in port order, then AV and the rest.
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this)));
            });
            // Some TVs (Sony) report every HDMI port as connected whether or not anything is plugged in,
            // so drop the HDMI status then. A TV with every port really in use loses it too.
            int hdmiPorts = 0, hdmiConnected = 0;
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput() || info.getType() != TvInputInfo.TYPE_HDMI) continue;
                hdmiPorts++;
                if (tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_CONNECTED) hdmiConnected++;
            }
            boolean hdmiStateBogus = hdmiPorts > 1 && hdmiConnected == hdmiPorts;
            for (TvInputInfo info : inputs) {
                if (info.getType() == TvInputInfo.TYPE_TUNER
                        && (info.getServiceInfo().applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                    // Prefer a hardware tuner: the framework names those ".../HW<n>". System apps such as
                    // AirPlay also register software inputs of type TUNER and may sort first (seen on TCL G05).
                    boolean hw = info.getId().matches(".*/HW\\d+$");
                    if (tunerId == null || (hw && !tunerIsHw) || (hw == tunerIsHw && info.getId().compareTo(tunerId) < 0)) {
                        tunerId = info.getId();
                        tunerIsHw = hw;
                    }
                }
                if (!info.isPassthroughInput()) continue;
                CharSequence l = info.loadLabel(this);
                String label = l == null ? info.getId() : l.toString();
                boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                if (hdmi && !label.toUpperCase(Locale.ROOT).contains("HDMI")) label = "HDMI " + label;
                final String id = info.getId();
                int state = hdmi && hdmiStateBogus ? -1 : tim.getInputState(id);
                if (!hdmi && rows.getChildCount() > 0 && !sawNonHdmi) addSeparator(rows);
                if (!hdmi) sawNonHdmi = true;
                addInputRow(rows, popup, hdmi ? R.drawable.ic_input : R.drawable.ic_av, label, state,
                        () -> openInput(id));
            }
        }
        // Nothing to list where there is no TvInputManager: the TV's own source panel switches inputs.
        if (rows.getChildCount() == 0 && !sourcePanelService.isEmpty()) {
            addInputRow(rows, popup, R.drawable.ic_input, getString(R.string.inputs), -1, this::openSourcePanel);
        }
        Intent liveTv = liveTvIntent();
        if (liveTv != null && tunerId != null) {
            liveTv = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(TvContract.buildChannelUriForPassthroughInput(tunerId), "vnd.android.cursor.item/channel")
                    .setPackage(liveTvPkg);
        }
        if (liveTv != null) {
            if (rows.getChildCount() > 0) addSeparator(rows);
            final Intent liveTvIntent = liveTv;
            addInputRow(rows, popup, R.drawable.ic_livetv, getString(R.string.live_tv), -1, () -> open(liveTvIntent));
        }
        // Desktop aerial video, reachable straight from the 信源 menu — the owner's parents no
        // longer need to open Settings to get the moving Apple-TV-style aerial on the desktop.
        if (rows.getChildCount() > 0) addSeparator(rows);
        addInputRow(rows, popup, R.drawable.ic_livetv, getString(R.string.aerial_video), -1, () -> {
            prefs.setAerialRotateOn(true);
            prefs.saveAerialSource("apple");
            updateAerialVideo();
        });
        if (rows.getChildCount() == 0) {
            Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return;
        }

        // Fit the widest row: TVs that report a status on every port (Sony) would otherwise squeeze the names out.
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max(width, panel.getMeasuredWidth()));

        dropIn(popup, panel, anchor, Gravity.START);
        rows.getChildAt(0).requestFocus();
    }

    private PopupWindow glassPopup(View panel, int width) {
        final PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);
        popup.setOnDismissListener(() -> {
            if (openPopup == popup) openPopup = null;
        });
        openPopup = popup;
        return popup;
    }

    /** Drop in: fade and unfold from the top edge, hanging under the anchor pill. */
    private void dropIn(PopupWindow popup, View panel, View anchor, int gravity) {
        panel.setAlpha(0f);
        panel.setPivotY(0f);
        panel.setScaleY(0.92f);
        panel.setTranslationY(-8 * density);
        popup.showAsDropDown(anchor, 0, (int) (10 * density), gravity);
        panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
    }

    private void addSeparator(LinearLayout rows) {
        View line = new View(this);
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) density);
        int m = (int) (6 * density), h = (int) (12 * density);
        lp.setMargins(h, m, h, m);
        rows.addView(line, lp);
    }

    private void addInputRow(LinearLayout rows, PopupWindow popup, int iconRes, String title, int state, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView status = row.findViewById(R.id.status);
        View dot = row.findViewById(R.id.dot);
        // This TV reports "standby" for every port whether or not something is plugged in, so only
        // definite states are shown.
        if (state == TvInputManager.INPUT_STATE_CONNECTED) {
            status.setText(R.string.connected);
            dot.setVisibility(View.VISIBLE);
        } else if (state == TvInputManager.INPUT_STATE_DISCONNECTED) {
            status.setText(R.string.not_connected);
            dot.setVisibility(View.GONE);
        } else {
            status.setVisibility(View.GONE);
            dot.setVisibility(View.GONE);
        }
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        rows.addView(row);
    }

    // ---- module cards ----------------------------------------------------------------------------

    /**
     * Asks about the next card that has no decision for its current signing key. One prompt at a
     * time, only over a laid out grid with window focus (not under a popup or another app), and
     * the next one follows as soon as this one is answered. Nothing is shown until the owner says Show.
     */
    private void showCardPrompt() {
        if (!moduleCards || !tilesReady || promptsPaused || cardPrompt != null || !hasWindowFocus() || isFinishing()) return;
        if (cards.isEmpty() || !CardFeature.working()) return;
        CardFeature.run(() -> {
            showCardPromptNow();
            return null;
        }, null);
    }

    private void showCardPromptNow() {
        final Cards.Card card = Cards.firstPending(cards);
        if (card == null) return;

        View panel = getLayoutInflater().inflate(R.layout.dialog_card_prompt, root, false);
        ((TextView) panel.findViewById(R.id.title)).setText(getString(R.string.card_prompt_title, card.appLabel));
        ((TextView) panel.findViewById(R.id.body)).setText(card.state == Cards.State.RESIGNED
                ? getString(R.string.card_prompt_resigned, card.appLabel)
                : card.state == Cards.State.RELABELLED
                ? getString(R.string.card_prompt_relabelled, card.appLabel)
                : getString(R.string.card_prompt_body, card.appLabel));
        TextView names = panel.findViewById(R.id.names);
        if (Cards.fold(card.label).equals(Cards.fold(card.appLabel))) names.setVisibility(View.GONE);
        else names.setText(getString(R.string.card_prompt_names, card.label, card.appLabel));
        ((TextView) panel.findViewById(R.id.identity)).setText(
                getString(R.string.card_prompt_identity, card.pkg, Cards.shortDigest(card.digest)));
        TextView keep = panel.findViewById(R.id.btnKeep);
        TextView show = panel.findViewById(R.id.btnShow);

        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(panel);
        dialog.setCanceledOnTouchOutside(false);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout((int) (440 * density), ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setDimAmount(0.6f);
        }
        // One decision per prompt: the buttons and Back (cancel) all end here.
        final boolean[] decided = {false};
        View.OnClickListener choose = v -> {
            if (decided[0]) return;
            decided[0] = true;
            cardPrompt = null;
            dialog.dismiss();
            decideCard(card, v.getId() == R.id.btnShow);   // guards its own card calls; the tile reload stays outside
            restoreFocus();   // the tiles changed under the prompt; this also chains the next prompt
        };
        keep.setOnClickListener(choose);
        show.setOnClickListener(choose);
        dialog.setOnCancelListener(d -> choose.onClick(keep));
        dialog.setOnDismissListener(d -> {
            if (cardPrompt == dialog) cardPrompt = null;
        });
        // Left and Right move between the two buttons; Up and Down stay put; the ends don't wrap.
        View.OnKeyListener keys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return v.getId() == R.id.btnKeep;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == R.id.btnShow;
        };
        keep.setOnKeyListener(keys);
        show.setOnKeyListener(keys);
        cardPrompt = dialog;
        dialog.show();
        keep.requestFocus();
    }

    /** Writes the owner's decision and rebuilds the tiles; focus is left where it is. */
    private void decideCard(Cards.Card card, boolean show) {
        boolean saved = CardFeature.run(() -> Cards.decide(Cards.storeFile(this), card, show), false);
        if (!saved) {
            Toast.makeText(this, R.string.card_not_saved, Toast.LENGTH_SHORT).show();
            promptsPaused = true;
        }
        reloadTiles();
    }

    /** Dropdown under the Settings pill (hold OK): every discovered card, its package and key; OK toggles Shown and Hidden. */
    private void showModuleCards() {
        if (cards.isEmpty() || !CardFeature.working()) {
            Toast.makeText(this, R.string.no_module_cards, Toast.LENGTH_SHORT).show();
            return;
        }
        CardFeature.run(() -> {
            showModuleCardsNow();
            return null;
        }, null);
    }

    private void showModuleCardsNow() {
        View anchor = findViewById(R.id.btnSettings);
        View panel = getLayoutInflater().inflate(R.layout.popup_cards, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        final PopupWindow popup = glassPopup(panel, (int) (400 * density));
        List<Cards.Card> listed = new ArrayList<>(cards);
        Collections.sort(listed, Cards::byLabel);
        for (Cards.Card card : listed) {
            View row = getLayoutInflater().inflate(R.layout.item_card_row, rows, false);
            row.setTag(card.pkg);
            ((TextView) row.findViewById(R.id.title)).setText(card.label);
            TextView app = row.findViewById(R.id.app);
            if (Cards.fold(card.label).equals(Cards.fold(card.appLabel))) app.setVisibility(View.GONE);
            else app.setText(getString(R.string.card_app_name, card.appLabel));
            ((TextView) row.findViewById(R.id.pkg)).setText(card.pkg);
            ((TextView) row.findViewById(R.id.digest)).setText(Cards.longDigest(card.digest));
            final String pkg = card.pkg;
            row.setOnClickListener(v -> CardFeature.run(() -> {
                // The list stays open; the decision is for the card as it is now, not as it was listed.
                Cards.Card now = cardFor(pkg);
                if (now == null) return null;
                decideCard(now, now.state != Cards.State.SHOWN);
                // Every row, not only this one: a toggle can move another card across the limit of 8.
                updateCardStates(rows);
                return null;
            }, null));
            rows.addView(row);
        }
        updateCardStates(rows);
        // Scroll inside the panel instead of running off the bottom of a 720p screen.
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int[] at = new int[2];
        anchor.getLocationInWindow(at);
        int room = root.getHeight() - (at[1] + anchor.getHeight()) - (int) (34 * density);
        popup.setHeight(Math.min(panel.getMeasuredHeight(), Math.max(room, (int) (120 * density))));
        dropIn(popup, panel, anchor, Gravity.END);
        rows.getChildAt(0).requestFocus();
    }

    private Cards.Card cardFor(String pkg) {
        for (Cards.Card c : cards) if (c.pkg.equals(pkg)) return c;
        return null;
    }

    /** Writes the state of every row in the Module cards list from the current card list. */
    private void updateCardStates(LinearLayout rows) {
        List<Cards.Card> drawn = Cards.shown(cards);
        for (int i = 0; i < rows.getChildCount(); i++) {
            View row = rows.getChildAt(i);
            Cards.Card card = row.getTag() instanceof String ? cardFor((String) row.getTag()) : null;
            ((TextView) row.findViewById(R.id.state)).setText(card != null ? stateText(card, drawn.contains(card)) : "");
        }
    }

    /** The row's state: a card the owner set to Shown but which is beyond the eight drawn says so. */
    private String stateText(Cards.Card card, boolean drawn) {
        switch (card.state) {
            case SHOWN:
                return getString(drawn ? R.string.card_state_shown : R.string.card_state_over_limit);
            case HIDDEN:
                return getString(R.string.card_state_hidden);
            case RESIGNED:
                return getString(R.string.card_state_new_key);
            case RELABELLED:
                return getString(R.string.card_state_new_label);
            default:
                return getString(R.string.card_state_new);
        }
    }
}
