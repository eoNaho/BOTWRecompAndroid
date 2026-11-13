// BOTWRecompAndroid on Android: SDL's activity runs the runtime (libmain.so, runtime/main.c). Before the native code starts
// this sets up what the desktop finds next to its executable (runtime/platform/android.c, runtime/paths.c):
// - the shipped resources (assets/res: the start screen's font, the icon) copied to <internal files>/res;
// - the Vulkan driver: the first driver in <external files>/drivers (a .so, or an adrenotools .zip: Turnip), copied to
//   internal storage (dlopen refuses external storage) and named in LARECOMP_VK_DRIVER; none there = the system's;
// - LARECOMP_NATIVE_LIB_DIR: where adrenotools finds its hook libraries;
// - the game code (botwgame.so + botwgame.so.id, built from the player's own game files: android/build_gamelib.sh) from
//   <external files>/extracted to <internal files>/res, where runtime/gamelib.c looks for it next to "the executable".
// - the game code's builder (docs/las/ANDROID-PORT.md, Fase 6): assets/gen (the generator, its Python's standard library,
//   zig's headers) unpacked to <internal files>/gen once per app version, named in LARECOMP_GEN_DIR; its programs
//   (libpymain.so, libzig.so) are in nativeLibraryDir;
// - the system's pickers for the start screen (runtime/ui/launcher.mm): the game files folder (exefs/ and romfs/ are
//   found in it; exefs/ is copied to its version's extracted/ - <external files>/extracted for 1.0.0, v160/ and v182/
//   inside it for the others, runtime/paths.h - and romfs.bin built there straight from romfs/) and the Vulkan driver
//   (a .zip copied to the drivers folder).
// The data root (game files, saves, settings) is <external files>: /sdcard/Android/data/com.botwrecomp.android/files.
// While in front it reports the battery and thermal state once a second for the performance overlay
// (runtime/ui/perf_overlay.mm): apps can't read those in /sys.
package com.botwrecompandroid;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.content.IntentFilter;
import android.content.res.AssetManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;
import android.view.Gravity;
import android.widget.ScrollView;
import android.widget.TextView;

import org.libsdl.app.SDLActivity;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class BOTWRecompAndroidActivity extends SDLActivity {
    private static final String TAG = "BOTWRecompAndroid";
    private static String bootState;
    private static native void nativeMemoryPressure(int level, long available, long total, long threshold, boolean low, int pss);

    private final Handler stats = new Handler(Looper.getMainLooper());
    private final Runnable report = new Runnable() {
        @Override
        public void run() {
            reportDeviceStats();
            stats.postDelayed(this, 1000);
        }
    };

    private static native void nativeDeviceStats(float battTempC, float battPct, float watts, int thermal, float headroom, int plugged);
    // the start screen (runtime/ui/launcher.mm)
    private static native int nativeGameVersion(byte[] head);
    private static native void nativeImportSearched(int folders);  // (the search's folders so far, every 10: #373)
    private static native void nativeImportLooking();  // (the picked folder is being searched: busy, and said so)
    private static native String nativeImportBegin(String name, int version);
    private static native void nativeImportProgress(long done, long total);
    private static native void nativeImportArchiveStage(String stage);
    private static native void nativeImportLog(String message);
    private static native void nativeImportArchiveOutput(String path);
    private static native void nativeImportDone(int result);
    private static native void nativeImportDetail(String what);  // (a failure's cause, shown under it)
    private static String ncaHeaderFingerprint(File header) throws IOException {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            try (java.io.InputStream input = new java.io.FileInputStream(header)) {
                byte[] buffer = new byte[4096];
                int n;
                while ((n = input.read(buffer)) != -1) digest.update(buffer, 0, n);
            }
            return android.util.Base64.encodeToString(digest.digest(), android.util.Base64.NO_WRAP);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is unavailable.", e);
        }
    }

    private static native String nativeExtractNca(String ncaPath, String keysPath, String ticketDir, String exefsDir, String romfsPath, int mode, String basePath, boolean image);
    private static native int nativeNcaKind(String ncaPath, String keysPath);
    private native String nativeBuildRomfs(String[] paths, long[] sizes);
    private static native boolean nativeCheckRomfs(int version, String[] paths, String versionTxt);
    private static native boolean nativeCheckRomfsImage(int version, int fd);  // (fd: closed by the native side)
    private static native String nativeImageLoose(int fd);
    private static native void nativeDriverPicked(String name);
    private static native void nativeModImportDone(String message);
    private static native void nativeLogFolderPicked(String name);

    private static native String nativeSaveDir();  // (this version's save_0, absolute)
    private static native void nativeSaveDone(int result, String detail);
    private static final int REQ_GAME = 0x4c41, REQ_DRIVER = 0x4c42, REQ_MOD = 0x4c46, REQ_LOG = 0x4c47,
            REQ_DRIVER_RECOVER = 0x4c48, REQ_SAVE_IN = 0x4c49, REQ_SAVE_OUT = 0x4c4a, REQ_SAVE_IN_TREE = 0x4c4c,
            REQ_DLC = 0x4c4d, REQ_CONTAINER_INSPECT = 0x4c4e, REQ_PROD_KEYS = 0x4c4f,
            REQ_CONTAINER_IMPORT = 0x4c50, REQ_CONTAINER_DLC = 0x4c51;
    private native String nativeBuildDlc(String[] paths, long[] sizes);
    private static native String nativeInstallDlcImage(String path);
    private static native boolean nativeHasDlcImage();
    private static native void nativeDlcDone(int result, String detail);
    private static BOTWRecompAndroidActivity self;
    private WatchMapServer watchMap;

    @Override
    protected String[] getLibraries() {
        return new String[] {"SDL3", "shaderc_shared", "main"};
    }

    @Override
    protected void onCreate(Bundle state) {
        try {
            File res = new File(getFilesDir(), "res");
            copyAssets("res", res);
            File games = new File(getExternalFilesDir(null), "extracted");
            copyIfChanged(new File(games, "botwgame.so"), new File(res, "botwgame.so"));
            copyIfChanged(new File(games, "botwgame.so.id"), new File(res, "botwgame.so.id"));
            setenv("LARECOMP_NATIVE_LIB_DIR", getApplicationInfo().nativeLibraryDir);
            setenv("LARECOMP_LAST_EXIT", lastExit());
            setenv("LARECOMP_BOOT_STATE", bootState());
            File gen = new File(getFilesDir(), "gen");
            unpackGen(gen);
            setenv("LARECOMP_GEN_DIR", gen.getAbsolutePath());
            if (getIntent() != null && SYSTEM_DRIVER.equals(getIntent().getAction())) dropCustomDriver();
            File driver = customDriver();
            setenv("LARECOMP_VK_DRIVER", driver != null ? driver.getAbsolutePath() : "");
            // testing: the runtime's LARECOMP_* switches from the launch intent, separated by commas (adb shell drops
            // the quotes around spaces), e.g. LARECOMP_VK_DRIVER= is the system driver:
            // adb shell am start -n com.botwrecomp.android/com.botwrecompandroid.BOTWRecompAndroidActivity --es env LARECOMP_NO_LAUNCHER=1,LARECOMP_VK_DRIVER=
            String env = getIntent() != null ? getIntent().getStringExtra("env") : null;
            // Opened again from the recent apps, Android hands back that test launch's intent: keep only which game
            // version it ran (a scripted input left in it ignored the touch screen - the menu looked frozen)
            boolean recents = getIntent() != null && (getIntent().getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0;
            if (env != null) {  // (a piece without '=' continues the previous value: LARECOMP_GPUPROF_AT=1500,1750)
                String name = null, value = null;
                for (String kv : env.trim().split("[,\\s]+")) {
                    int eq = kv.indexOf('=');
                    if (eq > 0) {
                        if (name != null && (!recents || KEEP_FROM_RECENTS.contains(name))) setenv(name, value);
                        name = kv.substring(0, eq);
                        value = kv.substring(eq + 1);
                    } else if (name != null) value += "," + kv;
                }
                if (name != null && (!recents || KEEP_FROM_RECENTS.contains(name))) setenv(name, value);
                // (debugging) the game code built on the phone, out where adb can read it: a crash's host addresses
                if (env.contains("LARECOMP_EXPORT_GAMELIB")) {
                    File lib = new File(res, "botwgame.so");
                    if (lib.exists()) try (InputStream in = new FileInputStream(lib)) {
                        copy(in, new File(getExternalFilesDir(null), "botwgame-export.so"));
                    }
                }
            }
        } catch (IOException | ErrnoException e) {
            Log.e(TAG, "setup: " + e);
        }
        self = this;
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        super.onCreate(state);
        // SDL's own immersive full screen (no status / navigation bar, the camera cutout used): SDL's onCreate asks
        // for the windowed style, and keeps the bars hidden once its full screen mode is on
        setWindowStyle(true);
        watchMap = new WatchMapServer(this);
        if (getIntent() != null && WatchMapServer.ACTION.equals(getIntent().getAction()))
            stats.post(() -> watchMap.showSettings());
    }

    @Override
    protected void onResume() {
        super.onResume();
        stats.post(report);
        if (watchMap != null) watchMap.resume();
    }

    @Override
    protected void onPause() {
        stats.removeCallbacks(report);
        if (watchMap != null) watchMap.pause();
        super.onPause();
    }

    // Battery temperature, charge and power draw, and Android's thermal state (runtime/platform/android.c).
    private void reportDeviceStats() {
        try {
            Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            float temp = -1, pct = -1, watts = 0;
            int plugged = -1;
            if (b != null) {
                temp = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -10) / 10f;
                plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
                int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                if (level >= 0 && scale > 0) pct = level * 100f / scale;
                int mv = b.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
                BatteryManager bm = getSystemService(BatteryManager.class);
                long now = bm != null ? Math.abs(bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)) : 0;
                if (now > 0 && now < 20000) now *= 1000;  // (some devices report mA instead of uA)
                watts = now * (float) mv / 1e9f;
            }
            PowerManager pm = getSystemService(PowerManager.class);
            int thermal = pm != null ? pm.getCurrentThermalStatus() : -1;
            float headroom = pm != null ? pm.getThermalHeadroom(10) : -1;
            nativeDeviceStats(temp, pct, watts, thermal, Float.isNaN(headroom) ? -1 : headroom, plugged);
        } catch (Throwable t) {  // (also before libmain is loaded: UnsatisfiedLinkError)
            Log.w(TAG, "device stats: " + t);
        }
    }

    // ---- the game code's builder: assets/gen, unpacked only when its bundled content changes (runtime/gamebuild.c)
    private void unpackGen(File dir) throws IOException {
        String[] top = getAssets().list("gen");
        if (top == null || top.length == 0) return;  // (an APK without it: android/deps/build_gen_deps.sh not run)
        String stamp;
        try (InputStream in = getAssets().open("gen.id")) {
            stamp = "sha256:" + new String(readAll(in), java.nio.charset.StandardCharsets.US_ASCII).trim();
        } catch (IOException missingFingerprint) {
            // Compatibility with APKs built before gen.id was introduced.
            try {
                stamp = "install:" + getPackageManager().getPackageInfo(getPackageName(), 0).lastUpdateTime;
            } catch (Exception e) {
                throw new IOException("Could not identify the bundled game builder", e);
            }
        }
        File st = new File(dir, ".stamp");
        if (st.exists() && stamp.equals(readSmall(st))) return;
        deleteTree(dir);
        copyAssetTree("gen", dir);
        try (OutputStream o = new FileOutputStream(st)) {
            o.write(stamp.getBytes());
        }
        Log.i(TAG, "unpacked the game code builder to " + dir);
    }

    private void copyAssetTree(String from, File to) throws IOException {
        AssetManager am = getAssets();
        String[] names = am.list(from);
        if (names == null || names.length == 0) {  // a file (or an empty folder)
            try (InputStream in = am.open(from)) {
                to.getParentFile().mkdirs();
                copy(in, to);
            } catch (IOException e) {
                to.mkdirs();
            }
            return;
        }
        to.mkdirs();
        for (String n : names) copyAssetTree(from + "/" + n, new File(to, n));
    }

    // ---- the game code build's foreground service (BuildService; runtime/ui/launcher.mm build_game_code)
    public static void buildServiceStart() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            self.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            self.runOnUiThread(() -> self.requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 0x4c4b));
        BuildService.start(self);
    }

    public static void buildServiceStop() { BuildService.stop(self); }

    public static void buildServiceProgress(int percent) { BuildService.progress(self, percent); }

    // ---- pickers (called from the start screen, on SDL's thread)
    private static volatile boolean importInPlace;  // (the next game folder picked: its romfs/ used where it is)

    public static void pickGameFolder() {
        importInPlace = false;
        self.runOnUiThread(() -> {
            try {
                self.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_GAME);
            } catch (Exception e) {
                Log.e(TAG, "folder picker: " + e);
                nativeImportDone(1);
            }
        });
    }

    // First on-device import milestone: inspect a user's NSP/XCI directory through SAF, without staging the whole file.
    public static void inspectSwitchContainer() {
        self.runOnUiThread(() -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            try {
                self.startActivityForResult(i, REQ_CONTAINER_INSPECT);
            } catch (Exception e) {
                Log.e(TAG, "container picker", e);
                self.showContainerInspection("Could not open the file picker: " + e.getMessage());
            }
        });
    }

    public static void pickProdKeys() {
        self.runOnUiThread(() -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            try {
                self.startActivityForResult(i, REQ_PROD_KEYS);
            } catch (Exception e) {
                Log.e(TAG, "prod.keys picker", e);
                self.showSwitchImportResult("Could not open the file picker: " + e.getMessage());
            }
        });
    }

    public static void deleteProdKeys() {
        self.runOnUiThread(() -> {
            File keys = self.prodKeysFile();
            if (!keys.exists() || keys.delete()) self.showSwitchImportResult("Saved prod.keys removed.");
            else self.showSwitchImportResult("Could not remove the saved prod.keys.");
        });
    }

    public static void pickSwitchArchive() {
        self.runOnUiThread(() -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            try {
                self.startActivityForResult(i, REQ_CONTAINER_IMPORT);
            } catch (Exception e) {
                Log.e(TAG, "NSP/XCI picker", e);
                self.showSwitchImportResult("Could not open the file picker: " + e.getMessage());
            }
        });
    }

    public static void pickSwitchDlcArchive() {
        self.runOnUiThread(() -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            try { self.startActivityForResult(i, REQ_CONTAINER_DLC); }
            catch (Exception e) { self.showSwitchImportResult("Could not open the file picker: " + e.getMessage()); }
        });
    }

    // Game files used in place (Game Files > Game files: Use them in place): the native side reads the picked romfs/ by
    // its path (runtime/services/fs.c, romfs.link), which Android allows only with the access to all files. Without it,
    // the system's page for it opens and the setup waits for the folder to be chosen again.
    public static void pickGameFolderInPlace() {
        if (android.os.Build.VERSION.SDK_INT >= 30 && !android.os.Environment.isExternalStorageManager()) {
            self.runOnUiThread(() -> {
                try {
                    self.startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:" + self.getPackageName())));
                } catch (Exception e) {
                    self.startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                }
            });
            nativeImportDetail("Allow BOTWRecompAndroid to access all files (the page Android just opened), then choose the folder again");
            nativeImportDone(5);
            return;
        }
        pickGameFolder();
        importInPlace = true;
    }

    // A folder the file picker gave (its document id: "primary:Download/BOTW", "1A2B-3C4D:Games/BOTW", "raw:/storage/...")
    // as a path the native side can open, or null (a cloud provider, or no such folder)
    private static String documentPath(String id) {
        if (id == null) return null;
        String path;
        if (id.startsWith("raw:")) path = id.substring(4);
        else {
            int c = id.indexOf(':');
            if (c < 0) return null;
            String vol = id.substring(0, c), rel = id.substring(c + 1);
            File base = "primary".equalsIgnoreCase(vol) ? android.os.Environment.getExternalStorageDirectory() : new File("/storage/" + vol);
            path = rel.isEmpty() ? base.getPath() : new File(base, rel).getPath();
        }
        return new File(path).isDirectory() ? path : null;
    }

    public static void pickDriver() {
        self.runOnUiThread(() -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            try {
                self.startActivityForResult(i, REQ_DRIVER);
            } catch (Exception e) {
                Log.e(TAG, "file picker: " + e);
            }
        });
    }

    // Back to the system's Vulkan driver: the chosen package and its unpacked copy go (from the next start on)
    public static void useSystemDriver() {
        new Thread(() -> {
            self.dropCustomDriver();
            nativeDriverPicked("-");
        }, "driver").start();
    }

    private void dropCustomDriver() {
        deleteTree(new File(getExternalFilesDir(null), "drivers"));
        deleteTree(new File(getFilesDir(), "drivers"));
    }

    // The app icon's long-press shortcut (res/xml/shortcuts.xml): the system driver, settings out of reach or not - a
    // custom driver that draws garbage (Turnip_v26.3.0-R6 on an Adreno 830: stripes, the menus unreadable) or crashes.
    // Before the driver loads when it starts the app; a running one (a single instance) drops it and starts again.
    static final String SYSTEM_DRIVER = "com.botwrecompandroid.action.SYSTEM_DRIVER";
    // (android/run_ver.sh: the game version a test launch ran, and its own start without the launcher)
    static final List<String> KEEP_FROM_RECENTS = Arrays.asList("LARECOMP_DATA_ROOT", "LARECOMP_GAME_LIB",
            "LARECOMP_SAVE_SEED", "LARECOMP_NO_LAUNCHER");

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && WatchMapServer.ACTION.equals(intent.getAction()) && watchMap != null)
            watchMap.showSettings();
        if (intent != null && SYSTEM_DRIVER.equals(intent.getAction())) {
            Log.i(TAG, "shortcut: back to the system driver");
            dropCustomDriver();
            restartApp();
        }
    }

    // a new process: the driver is loaded once, at start
    public static void restartApp() {
        self.runOnUiThread(() -> {
            Intent i = self.getPackageManager().getLaunchIntentForPackage(self.getPackageName());
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                self.startActivity(i);
            }
            System.exit(0);
        });
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        if (req == REQ_CONTAINER_DLC) {
            Uri file = result == Activity.RESULT_OK && data != null ? data.getData() : null;
            if (file != null) new Thread(() -> importSwitchDlcArchive(file), "switch-dlc-import").start();
            return;
        }
        if (req == REQ_PROD_KEYS) {
            Uri file = result == Activity.RESULT_OK && data != null ? data.getData() : null;
            if (file != null) new Thread(() -> saveProdKeys(file), "prod-keys").start();
            return;
        }
        if (req == REQ_CONTAINER_IMPORT) {
            List<Uri> files = new ArrayList<>();
            if (result == Activity.RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    for (int i = 0; i < data.getClipData().getItemCount(); i++) files.add(data.getClipData().getItemAt(i).getUri());
                } else if (data.getData() != null) files.add(data.getData());
            }
            if (!files.isEmpty()) new Thread(() -> importSwitchArchive(files), "switch-import").start();
            return;
        }
        if (req == REQ_CONTAINER_INSPECT) {
            Uri file = result == Activity.RESULT_OK && data != null ? data.getData() : null;
            if (file != null) new Thread(() -> inspectSwitchContainer(file), "container-inspector").start();
            return;
        }
        if (req == REQ_GAME) {
            Uri tree = result == Activity.RESULT_OK && data != null ? data.getData() : null;
            if (tree == null) nativeImportDone(1);
            else new Thread(() -> importGame(tree), "import").start();
            return;
        }
        if (req == REQ_MOD) {
            Uri tree = result == Activity.RESULT_OK && data != null ? data.getData() : null;
            if (tree == null) nativeModImportDone("2|");
            else new Thread(() -> importMod(tree), "mod-import").start();
            return;
        }
        if (req == REQ_DRIVER) {
            Uri f = result == Activity.RESULT_OK && data != null ? data.getData() : null;
            if (f != null) new Thread(() -> importDriver(f), "driver").start();
            return;
        }
        if (req == REQ_DRIVER_RECOVER) {
            Uri f = result == Activity.RESULT_OK && data != null ? data.getData() : null;
            if (f == null) showDriverError(null);
            else new Thread(() -> {
                if (!installDriver(this, f).isEmpty()) restartApp();
                else runOnUiThread(() -> showDriverError(getString(R.string.driver_not_a_package)));
            }, "driver").start();
            return;
        }
        if (req == REQ_DLC) {
            Uri tree = result == Activity.RESULT_OK && data != null ? data.getData() : null;
            if (tree == null) nativeDlcDone(DLC_CANCELLED, "");
            else new Thread(() -> importDlc(tree), "dlc").start();
            return;
        }
        if (req == REQ_SAVE_IN || req == REQ_SAVE_OUT || req == REQ_SAVE_IN_TREE) {
            Uri u = result == Activity.RESULT_OK && data != null ? data.getData() : null;
            if (u == null) nativeSaveDone(SAVE_CANCELLED, "");
            else new Thread(() -> {
                if (req == REQ_SAVE_IN) importSaveZip(u);
                else if (req == REQ_SAVE_IN_TREE) importSave(u);
                else exportSaveZip(u);
            }, "saves").start();
            return;
        }
        if (req == REQ_LOG) {
            Uri tree = result == Activity.RESULT_OK && data != null ? data.getData() : null;
            if (tree != null) {
                try {
                    getContentResolver().takePersistableUriPermission(tree,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    getSharedPreferences("botwrecomp", MODE_PRIVATE).edit().putString("logTree", tree.toString()).apply();
                } catch (Exception e) {
                    Log.e(TAG, "log folder permission", e);
                }
            }
            nativeLogFolderPicked(logFolderName());  // ("": cancelled, or no write access)
            return;
        }
        super.onActivityResult(req, result, data);
    }

    private void inspectSwitchContainer(Uri uri) {
        String result;
        try (android.os.ParcelFileDescriptor fd = getContentResolver().openFileDescriptor(uri, "r")) {
            if (fd == null) throw new IOException("Android could not open the selected file.");
            result = SwitchContainerInspector.inspect(fd);
        } catch (Exception e) {
            Log.w(TAG, "container inspection failed", e);
            result = "Inspection failed\n\n" + (e.getMessage() == null ? e.toString() : e.getMessage());
        }
        final String report = result;
        runOnUiThread(() -> showContainerInspection(report));
    }

    private void showContainerInspection(String report) {
        TextView text = new TextView(this);
        text.setText(report);
        text.setTextIsSelectable(true);
        text.setTextSize(13);
        text.setGravity(Gravity.START);
        text.setPadding(24, 16, 24, 16);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(text);
        new AlertDialog.Builder(this)
                .setTitle("NSP/XCI container inspector")
                .setMessage("Lists container entries only. NCA decryption and extraction are not implemented yet.")
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private File switchImportDir() { return new File(getFilesDir(), "switch-import"); }
    private File prodKeysFile() { return new File(switchImportDir(), "prod.keys"); }

    private void showSwitchImportResult(String message) {
        new AlertDialog.Builder(this)
                .setTitle("NSP/XCI import")
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void saveProdKeys(Uri uri) {
        File dir = switchImportDir();
        File staged = new File(dir, "prod.keys.importing");
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Could not create private key storage.");
            long bytes = 0;
            try (InputStream in = getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(staged)) {
                if (in == null) throw new IOException("Android could not open prod.keys.");
                byte[] buf = new byte[1 << 16];
                for (int n; (n = in.read(buf)) > 0; ) {
                    bytes += n;
                    if (bytes > 4 * 1024 * 1024) throw new IOException("prod.keys is unexpectedly large.");
                    out.write(buf, 0, n);
                }
            }
            String keys = new String(java.nio.file.Files.readAllBytes(staged.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            if (!keys.matches("(?s).*(^|\\n)\\s*header_key\\s*[,=]\\s*[0-9a-fA-F]{64}\\s*(?:\\r?\\n|$).*"))
                throw new IOException("prod.keys is missing a valid 64-digit header_key.");
            File dest = prodKeysFile();
            Os.rename(staged.getAbsolutePath(), dest.getAbsolutePath());
            runOnUiThread(() -> showSwitchImportResult("prod.keys saved in the app's private storage."));
        } catch (Exception e) {
            staged.delete();
            Log.e(TAG, "save prod.keys", e);
            String why = e.getMessage() == null ? e.toString() : e.getMessage();
            runOnUiThread(() -> showSwitchImportResult("Could not save prod.keys.\n\n" + why));
        }
    }

    // SDL synchronizes on the Activity when its surface changes. Keep lengthy imports off that monitor.
    private static final Object IMPORT_LOCK = new Object();

    private void importSwitchArchive(List<Uri> uris) {
        synchronized (IMPORT_LOCK) { importSwitchArchiveLocked(uris); }
    }

    private void importSwitchArchiveLocked(List<Uri> uris) {
        File root = getExternalFilesDir(null);
        File work = root == null ? null : new File(root, "switch-import-work");
        boolean nativeStarted = false, completed = false;
        try {
            File keys = prodKeysFile();
            if (!keys.isFile()) throw new IOException("Choose your prod.keys first. It will be kept in the app's private storage.");
            if (work == null) throw new IOException("The app's game-data storage is unavailable.");
            nativeImportLog("Game import started: " + uris.size() + " archive(s) selected.");
            nativeImportLooking();
            nativeStarted = true;
            BuildService.start(this);
            BuildService.progress(this, 0);
            deleteTree(work);
            File ncasDir = new File(work, "nca");
            File candidate = new File(work, "candidate");
            File ready = new File(work, "ready");
            if (!work.mkdirs()) throw new IOException("Could not create temporary import storage.");

            File baseNca = new File(work, "base.nca"), updateNca = new File(work, "update.nca");
            final int[] identified = {-1};
            nativeImportArchiveStage("Reading the selected game archives...");
            int[] counts = new int[4];
            java.util.Set<String> seenHeaders = new java.util.HashSet<>();
            for (Uri uri : uris) {
                try (android.os.ParcelFileDescriptor input = getContentResolver().openFileDescriptor(uri, "r")) {
                    if (input == null) throw new IOException("Android could not open a selected NSP/XCI.");
                    SwitchContainerInspector.processNcas(input, ncasDir, header -> {
                        int kind = nativeNcaKind(header.getAbsolutePath(), keys.getAbsolutePath());
                        if (kind == -2) throw new IOException("Could not read an NCA header; check header_key in prod.keys and the selected archive.");
                        if (kind >= 0 && kind < counts.length && seenHeaders.add(ncaHeaderFingerprint(header))) counts[kind]++;
                        return false;
                    }, nca -> false, null);
                }
            }
            nativeImportLog("Archive contents: " + counts[0] + " base program(s), " + counts[1] +
                    " update program(s), " + counts[2] + " DLC data pack(s), " + counts[3] + " DLC unlock(s).");
            if (counts[0] == 0) throw new IOException("The base game NSP/XCI is required, including when installing an update.");
            if (counts[0] > 1 || counts[1] > 1)
                throw new IOException("More than one base or update program was found. Select one base and one update version.");
            if (uris.size() > 1 && counts[1] == 0)
                throw new IOException("No BOTW update program was found in the selected files. The previous game was kept.");
            seenHeaders.clear();
            for (Uri uri : uris) {
                try (android.os.ParcelFileDescriptor input = getContentResolver().openFileDescriptor(uri, "r")) {
                    if (input == null) throw new IOException("Android could not open a selected NSP/XCI.");
                    SwitchContainerInspector.processNcas(input, ncasDir, header -> {
                        int kind = nativeNcaKind(header.getAbsolutePath(), keys.getAbsolutePath());
                        if (kind == -2) throw new IOException("Could not read an NCA header; check header_key in prod.keys and the selected archive.");
                        identified[0] = kind;
                        return (kind == 0 || kind == 1) && seenHeaders.add(ncaHeaderFingerprint(header));
                    }, nca -> {
                        int kind = identified[0];
                        File dest = kind == 0 ? baseNca : kind == 1 ? updateNca : null;
                        if (dest != null) {
                            if (dest.exists()) throw new IOException("More than one " + (kind == 0 ? "base" : "update") + " program was found; select one version.");
                            if (!nca.renameTo(dest)) throw new IOException("Could not stage the game NCA.");
                        }
                        return false;
                    }, BOTWRecompAndroidActivity::nativeImportProgress);
                }
            }
            if (!baseNca.isFile()) throw new IOException("The base game NSP/XCI is required, including when installing an update.");
            if (uris.size() > 1 && !updateNca.isFile())
                throw new IOException("No BOTW update program was found in the selected files. The previous game was kept.");
            nativeImportLog("Game archive scan: base found, update " + (updateNca.isFile() ? "found." : "not found; importing base only."));
            long inputBytes = baseNca.length() + (updateNca.isFile() ? updateNca.length() : 0);
            long free = new android.os.StatFs(root.getPath()).getAvailableBytes();
            long required = inputBytes > Long.MAX_VALUE - (512L << 20) ? Long.MAX_VALUE : inputBytes + (512L << 20);
            if (free < required) throw new IOException("Not enough free space for extraction and the game image. Estimated additional space: " +
                    android.text.format.Formatter.formatFileSize(this, required) + "; available: " +
                    android.text.format.Formatter.formatFileSize(this, free));
            File exefs = new File(candidate, "exefs"), image = new File(candidate, "romfs.bin");
            if (!exefs.mkdirs()) throw new IOException("Could not create temporary extraction folders.");
            nativeImportArchiveStage("Extracting game data; this can take several minutes...");
            String info = nativeExtractNca((updateNca.isFile() ? updateNca : baseNca).getAbsolutePath(), keys.getAbsolutePath(),
                    ncasDir.getAbsolutePath(), exefs.getAbsolutePath(), image.getAbsolutePath(), updateNca.isFile() ? 2 : 0,
                    updateNca.isFile() ? baseNca.getAbsolutePath() : null, true);
            if (info == null || !info.startsWith("OK|")) {
                if (info != null && info.startsWith("KEY|")) throw new IOException("prod.keys is missing " + info.substring(4) + ".");
                if (info != null && info.startsWith("RIGHTS|")) throw new IOException("The matching title ticket is missing.");
                throw new IOException(info != null && info.startsWith("ERROR|") ? info.substring(6) : "Could not extract the selected game.");
            }
            File main = new File(exefs, "main"), npdm = new File(exefs, "main.npdm");
            if (!main.isFile() || !npdm.isFile() || !image.isFile())
                throw new IOException("The selected game and update did not produce a complete ExeFS and RomFS.");
            byte[] head = new byte[0x48];
            try (InputStream in = new FileInputStream(main)) {
                int got = 0;
                while (got < head.length) { int n = in.read(head, got, head.length - got); if (n <= 0) break; got += n; }
                if (got != head.length) throw new IOException("The extracted game executable is incomplete.");
            }
            int selectedVersion = nativeGameVersion(head);
            if (selectedVersion == 0) throw new IOException("The extracted game version is not supported.");
            nativeImportLog("Extracted game version: " + selectedVersion + ".");
            if (updateNca.isFile() && selectedVersion == 100)
                throw new IOException("The selected update produced the 1.0.0 executable. The previous game was kept.");
            int imageFd = android.os.ParcelFileDescriptor.open(image, android.os.ParcelFileDescriptor.MODE_READ_ONLY).detachFd();
            if (!nativeCheckRomfsImage(selectedVersion, imageFd))
                throw new IOException("The extracted RomFS does not match the game executable, or is incomplete.");
            if (!ready.mkdirs() || !exefs.renameTo(new File(ready, "exefs")) || !image.renameTo(new File(ready, "romfs.bin")))
                throw new IOException("Could not stage the extracted game files.");
            deleteTree(ncasDir);  // one NCA at a time was staged and deleted while scanning the archive
            File readyExefs = new File(ready, "exefs"), readyImage = new File(ready, "romfs.bin");
            nativeImportArchiveOutput(ready.getAbsolutePath());
            int moviesFd = android.os.ParcelFileDescriptor.open(readyImage, android.os.ParcelFileDescriptor.MODE_READ_ONLY).detachFd();
            String failed = nativeImageLoose(moviesFd);
            if (failed != null) throw new IOException(failed);
            // Keep an earlier installation until the new game image has been built successfully.
            File dest = new File(nativeImportBegin("Imported game archive", selectedVersion));
            if (!readyExefs.renameTo(new File(dest, "exefs")) ||
                    !readyImage.renameTo(new File(dest, "romfs.bin")) ||
                    !new File(ready, "romfs").renameTo(new File(dest, "romfs")))
                throw new IOException("Could not move the finished game data into its installation folder.");
            nativeImportLog("Game data installed for version " + selectedVersion + "; starting setup.");
            BuildService.progress(this, 100);
            nativeImportDone(0);  // the existing setup now builds/loads the game code as usual
            completed = true;
            return;
        } catch (Exception e) {
            Log.e(TAG, "NSP/XCI import", e);
            String detail = e.getMessage() == null ? e.toString() : e.getMessage();
            nativeImportLog("Game archive import failed: " + detail);
            if (nativeStarted) {
                nativeImportDetail(detail);
                nativeImportDone(5);
            } else {
                runOnUiThread(() -> showSwitchImportResult(detail));
            }
        } finally {
            romfsPaths.clear();
            if (work != null) deleteTree(work);
            if (nativeStarted && !completed) BuildService.stop(this);
        }
    }

    private void importSwitchDlcArchive(Uri uri) {
        synchronized (IMPORT_LOCK) { importSwitchDlcArchiveLocked(uri); }
    }

    private void importSwitchDlcArchiveLocked(Uri uri) {
        File root = getExternalFilesDir(null);
        File work = root == null ? null : new File(root, "switch-dlc-import-work");
        boolean nativeStarted = false;
        try {
            File keys = prodKeysFile();
            if (!keys.isFile()) throw new IOException("Choose prod.keys first.");
            if (work == null) throw new IOException("The app's game-data storage is unavailable.");
            nativeImportLog("DLC archive import started.");
            nativeImportLooking();
            nativeStarted = true;
            BuildService.start(this);
            nativeImportArchiveStage("Reading the selected game archives...");
            deleteTree(work);
            File ncas = new File(work, "nca"), exefs = new File(work, "exefs"), image = new File(work, "aoc.bin.part");
            if (!exefs.mkdirs()) throw new IOException("Could not create temporary DLC storage.");
            final String[] error = {"The archive does not contain the BOTW DLC pack 01007EF00011F001."};
            final boolean[] found = {false};
            final boolean[] unlockFound = {false};
            try (android.os.ParcelFileDescriptor input = getContentResolver().openFileDescriptor(uri, "r")) {
                if (input == null) throw new IOException("Android could not open the selected archive.");
                SwitchContainerInspector.processNcas(input, ncas, header -> {
                    int kind = nativeNcaKind(header.getAbsolutePath(), keys.getAbsolutePath());
                    if (kind == -2) throw new IOException("Could not read an NCA header; check header_key in prod.keys and the selected archive.");
                    if (kind == 3) {
                        unlockFound[0] = true;
                        error[0] = "This archive contains the F002 unlock. First install the larger DLC pack (F001).";
                    }
                    return kind == 2;
                }, nca -> {
                    long free = new android.os.StatFs(root.getPath()).getAvailableBytes();
                    if (nca.length() > free) throw new IOException("Not enough free space for the DLC image.");
                    nativeImportArchiveStage("Extracting game data; this can take several minutes...");
                    String info = nativeExtractNca(nca.getAbsolutePath(), keys.getAbsolutePath(), ncas.getAbsolutePath(),
                            exefs.getAbsolutePath(), image.getAbsolutePath(), 1, null, true);
                    if (info != null && info.startsWith("KEY|")) error[0] = "prod.keys is missing " + info.substring(4) + ".";
                    else if (info != null && info.startsWith("RIGHTS|")) error[0] = "The matching DLC ticket is missing.";
                    else if (info != null && info.startsWith("ERROR|")) error[0] = info.substring(6);
                    if (info != null && info.startsWith("OK|")) {
                        if (!image.isFile()) throw new IOException("The DLC image was not written.");
                        found[0] = true;
                        return true;
                    }
                    return false;
                }, BOTWRecompAndroidActivity::nativeImportProgress);
            }
            if (!found[0] && unlockFound[0] && nativeHasDlcImage()) {
                nativeImportLog("DLC F002 selected; F001 image is already installed and both add-ons are exposed by the runtime.");
                nativeDlcDone(DLC_INSTALLED, "");
                runOnUiThread(() -> showSwitchImportResult("DLC F001 is already installed. The app makes both DLCs available; the F002 file does not need a separate installation."));
                return;
            }
            if (!found[0]) throw new IOException(error[0]);
            nativeImportLog("DLC F001 data NCA extracted; validating image.");
            String failed = nativeInstallDlcImage(image.getAbsolutePath());
            if (failed != null) throw new IOException(failed);
            nativeImportLog("DLC image installed.");
            nativeDlcDone(DLC_INSTALLED, "");
            runOnUiThread(() -> showSwitchImportResult("DLC installed. Use an updated game version (1.6.0 or newer) to access both DLCs."));
        } catch (Exception e) {
            Log.e(TAG, "DLC archive import", e);
            String why = e.getMessage() == null ? e.toString() : e.getMessage();
            nativeImportLog("DLC archive import failed: " + why);
            nativeDlcDone(DLC_FAILED, why);
            runOnUiThread(() -> showSwitchImportResult(why));
        } finally {
            if (work != null) deleteTree(work);
            if (nativeStarted) { nativeImportDone(1); BuildService.stop(this); }
        }
    }

    // the run before's kept as botwrecomp-log-previous.txt, both openable on the phone and sendable with a bug report
    private static final String LOG_NAME = "botwrecomp-log.txt", LOG_PREVIOUS = "botwrecomp-log-previous.txt";

    public static void pickLogFolder() {
        self.runOnUiThread(() -> {
            try {
                self.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_LOG);
            } catch (Exception e) {
                Log.e(TAG, "log folder picker", e);
                nativeLogFolderPicked("");
            }
        });
    }

    private static Uri logTree() {  // the picked folder while the app may still write there (and it's still there)
        String s = self.getSharedPreferences("botwrecomp", MODE_PRIVATE).getString("logTree", null);
        if (s == null) return null;
        Uri tree = Uri.parse(s);
        boolean allowed = false;
        for (android.content.UriPermission p : self.getContentResolver().getPersistedUriPermissions())
            allowed |= p.getUri().equals(tree) && p.isWritePermission();
        if (!allowed) return null;
        Uri dir = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        try (Cursor c = self.getContentResolver().query(dir, new String[] {DocumentsContract.Document.COLUMN_DOCUMENT_ID},
                                                        null, null, null)) {
            return c != null && c.moveToFirst() ? tree : null;
        } catch (Exception e) {  // (the folder was deleted)
            return null;
        }
    }

    // The folder's name for the settings page ("Download/BOTWRecomp"); "" with none
    public static String appVersion() {  // versionName ("0.2.0-beta"), for the start screen's corner
        try {
            return self.getPackageManager().getPackageInfo(self.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    // A file the SDL dialog picked, as the content:// URI it hands back (the start screen's Import fonts,
    // runtime/ui/launcher.mm import_fonts): its name - the URI ends in a document id - and a copy of it at dst, an
    // absolute path (fopen can't open the URI; issue #122). "" / false when it can't be read.
    public static String documentName(String uri) {
        try (Cursor c = self.getContentResolver().query(Uri.parse(uri), new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        } catch (Exception ignored) {
        }
        return "";
    }

    public static boolean copyDocument(String uri, String dst) {
        try (InputStream in = self.getContentResolver().openInputStream(Uri.parse(uri))) {
            if (in == null) return false;
            copy(in, new File(dst));
            return true;
        } catch (Exception e) {
            Log.e(TAG, "copy " + uri + " to " + dst, e);
            return false;
        }
    }

    public static String logFolderName() {
        Uri tree = logTree();
        if (tree == null) return "";
        String id = DocumentsContract.getTreeDocumentId(tree);  // e.g. "primary:Download/BOTWRecomp"
        int colon = id.indexOf(':');
        String path = colon >= 0 ? id.substring(colon + 1) : id;
        return path.isEmpty() ? "/" : path;
    }

    // A file descriptor to write the log to (native code owns it), -1 without a usable folder. first: the run's first
    // open - the last run's log becomes the previous one and a new file starts; otherwise this run's is appended to.
    public static int openLog(boolean first) {
        Uri tree = logTree();
        if (tree == null) return -1;
        try {
            ContentResolver cr = self.getContentResolver();
            String treeId = DocumentsContract.getTreeDocumentId(tree);
            List<Doc> kids = self.children(tree, treeId);
            Doc cur = named(kids, LOG_NAME), prev = named(kids, LOG_PREVIOUS);
            if (first && cur != null) {
                if (prev != null) DocumentsContract.deleteDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, prev.id));
                DocumentsContract.renameDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, cur.id), LOG_PREVIOUS);
                cur = null;
            }
            Uri file = cur != null ? DocumentsContract.buildDocumentUriUsingTree(tree, cur.id)
                : DocumentsContract.createDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, treeId), "text/plain", LOG_NAME);
            if (file == null) return -1;
            android.os.ParcelFileDescriptor pfd = cr.openFileDescriptor(file, first ? "wt" : "wa");
            return pfd == null ? -1 : pfd.detachFd();
        } catch (Exception e) {
            Log.w(TAG, "log file in the picked folder", e);
            return -1;
        }
    }

    // ---- saves (the start screen's Settings > Saves): this version's save_0 lives in Android/data/, out of file managers'
    // reach (issue #49: edited there for cheats, the changes didn't stick), and players bring saves from emulators
    // (issue #40, the same layout: slots 0/ to 5/ with game_data.sav, option.sav). Export writes it as one .zip where
    // the player saves it; import makes the save in a .zip - or, from a folder, an emulator's - this version's, the one
    // before kept beside it.
    private static final int SAVE_IMPORTED = 0, SAVE_EXPORTED = 1, SAVE_CANCELLED = 2, SAVE_NONE = 3, SAVE_FAILED = 4;

    public static void pickSaveImport() {
        pick(REQ_SAVE_IN, () -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");  // (zip types vary by provider: application/zip, application/x-zip-compressed...)
            return i;
        });
    }

    public static void pickSaveImportFolder() { pick(REQ_SAVE_IN_TREE, () -> new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)); }

    public static void pickSaveExport() {
        pick(REQ_SAVE_OUT, () -> {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/zip");
            i.putExtra(Intent.EXTRA_TITLE, saveZipName());
            return i;
        });
    }

    private static void pick(int req, java.util.function.Supplier<Intent> intent) {
        self.runOnUiThread(() -> {
            try {
                self.startActivityForResult(intent.get(), req);
            } catch (Exception e) {
                Log.e(TAG, "save picker", e);
                nativeSaveDone(SAVE_FAILED, e.toString());
            }
        });
    }

    // "BOTW 1.8.2 save 2026-10-27 09.40.zip": the game version from the save's folder (v182/save/save_0; 1.0.0's has none)
    private static String saveZipName() {
        String dir = nativeSaveDir(), ver = "1.0.0";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("/v(\\d)(\\d)(\\d)/save/").matcher(dir);
        if (m.find()) ver = m.group(1) + "." + m.group(2) + "." + m.group(3);
        return "BOTW " + ver + " save " +
                new java.text.SimpleDateFormat("yyyy-MM-dd HH.mm", java.util.Locale.ROOT).format(new java.util.Date()) + ".zip";
    }

    // The new save (tmp, complete) in place of this version's, that one kept as save_0.before-import[-n]
    private void installSave(File tmp) throws IOException {
        File dst = new File(nativeSaveDir()), parent = dst.getParentFile();
        File keep = new File(parent, dst.getName() + ".before-import");
        for (int n = 2; keep.exists() && n < 100; n++) keep = new File(parent, dst.getName() + ".before-import-" + n);
        boolean had = dst.exists();
        if (had && !dst.renameTo(keep)) throw new IOException("can't move the current save aside");
        if (!tmp.renameTo(dst)) {
            if (had) keep.renameTo(dst);
            throw new IOException("can't put the imported save in place");
        }
        Log.i(TAG, "save imported into " + dst + (had ? ", the one before kept as " + keep.getName() : ""));
        nativeSaveDone(SAVE_IMPORTED, had ? keep.getName() : "");
    }

    private File importTmp() {
        File dst = new File(nativeSaveDir());
        File tmp = new File(dst.getParentFile(), dst.getName() + ".importing");
        deleteTree(tmp);
        return tmp;
    }

    // A .zip's save: the folder (in it) holding a slot folder 0/ to 7/ with game_data.sav - at the zip's top (one
    // exported here) or below it (a zipped emulator folder). Read twice: the entries' names, then that folder's files.
    private void importSaveZip(Uri u) {
        try {
            String prefix = null;
            java.util.regex.Pattern slot = java.util.regex.Pattern.compile("(?:^|.*/)[0-7]/game_data\\.sav$");
            try (java.util.zip.ZipInputStream z = new java.util.zip.ZipInputStream(getContentResolver().openInputStream(u))) {
                for (java.util.zip.ZipEntry e; (e = z.getNextEntry()) != null; ) {
                    String n = e.getName().replace('\\', '/');
                    if (!slot.matcher(n).matches()) continue;
                    String p = n.substring(0, n.length() - "0/game_data.sav".length());
                    if (prefix == null || p.length() < prefix.length()) prefix = p;
                }
            }
            if (prefix == null) {
                nativeSaveDone(SAVE_NONE, "");
                return;
            }
            File tmp = importTmp();
            String root = tmp.getCanonicalPath() + File.separator;
            int files = 0;
            try (java.util.zip.ZipInputStream z = new java.util.zip.ZipInputStream(getContentResolver().openInputStream(u))) {
                for (java.util.zip.ZipEntry e; (e = z.getNextEntry()) != null; ) {
                    String n = e.getName().replace('\\', '/');
                    if (!n.startsWith(prefix) || n.length() == prefix.length()) continue;
                    File f = new File(tmp, n.substring(prefix.length()));
                    if (!f.getCanonicalPath().startsWith(root)) throw new IOException("a bad path in the zip: " + n);
                    if (e.isDirectory()) {
                        f.mkdirs();
                        continue;
                    }
                    f.getParentFile().mkdirs();
                    try (OutputStream o = new FileOutputStream(f)) {
                        byte[] buf = new byte[1 << 16];
                        for (int r; (r = z.read(buf)) > 0; ) o.write(buf, 0, r);
                    }
                    files++;
                }
            }
            Log.i(TAG, "save zip: " + files + " files from \"" + prefix + "\"");
            installSave(tmp);
        } catch (Exception e) {
            Log.e(TAG, "save import (zip)", e);
            nativeSaveDone(SAVE_FAILED, e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    private void zipTree(java.util.zip.ZipOutputStream z, File dir, String prefix) throws IOException {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        java.util.Arrays.sort(kids);
        for (File k : kids) {
            String n = prefix + k.getName();
            // (a file another app put in the save - a file manager with root, a save editor: #77 - keeps its owner,
            // and this app can't open it)
            if (!k.canRead())
                throw new IOException(n + " can't be read: another app put it there, and it keeps that app's owner. " +
                        "Bring a save in with Import saves instead (the folder there now is kept beside it).");
            if (k.isDirectory()) {
                z.putNextEntry(new java.util.zip.ZipEntry(n + "/"));
                z.closeEntry();
                zipTree(z, k, n + "/");
                continue;
            }
            z.putNextEntry(new java.util.zip.ZipEntry(n));
            try (InputStream in = new FileInputStream(k)) {
                byte[] buf = new byte[1 << 16];
                for (int r; (r = in.read(buf)) > 0; ) z.write(buf, 0, r);
            }
            z.closeEntry();
        }
    }

    private void exportSaveZip(Uri u) {
        try {
            File src = new File(nativeSaveDir());
            if (!src.isDirectory()) {
                nativeSaveDone(SAVE_NONE, "");
                return;
            }
            try (OutputStream o = getContentResolver().openOutputStream(u, "wt")) {
                if (o == null) throw new IOException("can't write there");
                java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(o);
                zipTree(z, src, "");  // (the slots at the zip's top, as the save folder holds them)
                z.finish();
            }
            String name = u.getLastPathSegment();
            try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst()) name = c.getString(0);
            } catch (Exception ignored) {
            }
            Log.i(TAG, "save exported from " + src + " as " + name);
            nativeSaveDone(SAVE_EXPORTED, name);
        } catch (Exception e) {
            Log.e(TAG, "save export", e);
            nativeSaveDone(SAVE_FAILED, e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    private Doc treeRoot(Uri tree) {
        Doc root = new Doc();
        root.id = DocumentsContract.getTreeDocumentId(tree);
        root.dir = true;
        return root;
    }

    // The folder holding a save: a slot folder (0/ to 7/) with game_data.sav in it - the picked folder or a few levels
    // below it (an emulator's save path, a save_0/ inside, an exported folder's parent)
    private Doc findSave(Uri tree, Doc dir, int depth) {
        List<Doc> kids = children(tree, dir.id);
        for (Doc k : kids)
            if (k.dir && k.name != null && k.name.matches("[0-7]") && named(children(tree, k.id), "game_data.sav") != null)
                return dir;
        if (depth >= 4) return null;
        for (Doc k : kids) {
            if (!k.dir) continue;
            Doc s = findSave(tree, k, depth + 1);
            if (s != null) return s;
        }
        return null;
    }

    private void copySaveTree(Uri tree, Doc dir, File to) throws IOException {
        if (!to.mkdirs() && !to.isDirectory()) throw new IOException("can't make " + to);
        ContentResolver cr = getContentResolver();
        for (Doc k : children(tree, dir.id)) {
            File dst = new File(to, k.name);
            if (k.dir) {
                copySaveTree(tree, k, dst);
                continue;
            }
            try (InputStream in = cr.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, k.id));
                 OutputStream o = new FileOutputStream(dst)) {
                byte[] buf = new byte[1 << 16];
                for (int r; (r = in.read(buf)) > 0; ) o.write(buf, 0, r);
            }
        }
    }

    private void importSave(Uri tree) {
        try {
            Doc s = findSave(tree, treeRoot(tree), 0);
            if (s == null) {
                nativeSaveDone(SAVE_NONE, "");
                return;
            }
            File tmp = importTmp();
            copySaveTree(tree, s, tmp);  // (all of it first: a copy cut short leaves the save as it was)
            installSave(tmp);
        } catch (Exception e) {
            Log.e(TAG, "save import", e);
            nativeSaveDone(SAVE_FAILED, e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    // ---- the DLC (the start screen's Settings > DLC; runtime/services/fs.c open_aoc_storage): the Expansion Pass's data
    // is the RomFS of its larger pack (01007EF00011F001: the Master Trials' and the Champions' Ballad's content, 7,094
    // files); the other (01007EF00011F002) holds System/AocVersion.txt alone, the Ballad's unlock. The picked folder's
    // pack - System/AocVersion.txt with Pack/ beside it, the picked folder or a few levels below - becomes dlc/aoc.bin
    // as the game's own romfs.bin is made (nativeBuildDlc), the movies loose beside it.
    private static final int DLC_INSTALLED = 0, DLC_CANCELLED = 2, DLC_NONE = 3, DLC_FAILED = 4, DLC_BALLAD_ONLY = 5;

    public static void pickDlcFolder() {
        self.runOnUiThread(() -> {
            try {
                self.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_DLC);
            } catch (Exception e) {
                Log.e(TAG, "DLC folder picker", e);
                nativeDlcDone(DLC_FAILED, e.toString());
            }
        });
    }

    private boolean dlcBalladOnly;

    // The pack's romfs: a folder with System/AocVersion.txt and Pack/ (the game's own romfs has System/Version.txt)
    private Doc findDlc(Uri tree, Doc dir, int depth) {
        List<Doc> kids = children(tree, dir.id);
        Doc sys = named(kids, "System"), pack = named(kids, "Pack");
        if (sys != null && sys.dir && named(children(tree, sys.id), "AocVersion.txt") != null) {
            if (pack != null && pack.dir) return dir;
            dlcBalladOnly = true;  // (the Ballad's pack: its unlock only)
        }
        if (depth >= 4 || pack != null) return null;  // (a romfs: not into its ~7,000 files)
        for (Doc k : kids) {
            if (!k.dir) continue;
            Doc d = findDlc(tree, k, depth + 1);
            if (d != null) return d;
        }
        return null;
    }

    private void importDlc(Uri tree) {
        synchronized (IMPORT_LOCK) { importDlcLocked(tree); }
    }

    private void importDlcLocked(Uri tree) {
        try {
            dlcBalladOnly = false;
            Doc d = findDlc(tree, treeRoot(tree), 0);
            if (d == null) {
                nativeDlcDone(dlcBalladOnly ? DLC_BALLAD_ONLY : DLC_NONE, "");
                return;
            }
            romfsTree = tree;
            romfsFiles.clear();
            romfsPaths.clear();
            listFiles(tree, d, "");
            Log.i(TAG, "DLC: " + romfsFiles.size() + " files");
            long[] sizes = new long[romfsFiles.size()];
            for (int i = 0; i < sizes.length; i++) sizes[i] = romfsFiles.get(i).size;
            String failed;
            try {
                failed = nativeBuildDlc(romfsPaths.toArray(new String[0]), sizes);
            } finally {
                closeRomfsClient();
                romfsFiles.clear();
                romfsPaths.clear();
            }
            if (failed != null) nativeDlcDone(DLC_FAILED, failed);
            else nativeDlcDone(DLC_INSTALLED, Integer.toString(sizes.length));
        } catch (Exception e) {
            Log.e(TAG, "DLC import", e);
            nativeDlcDone(DLC_FAILED, e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    // A document in the picked tree: id, name, folder or not, size
    private static final class Doc {
        String id, name;
        boolean dir;
        long size;
    }

    // A folder's entries, each name once: a Huawei phone's file provider listed every file of the game twice (issue
    // #31: 79,356 files, twice 1.0.0's, a 28.84 GB image of a 14.44 GB folder, and the game then failed to allocate)
    private int listedTwice;  // (entries left out, since importGame began)

    private List<Doc> children(Uri tree, String id) {
        List<Doc> out = new ArrayList<>();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        Uri u = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id);
        String[] cols = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                         DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE};
        try (Cursor c = getContentResolver().query(u, cols, null, null, null)) {
            while (c != null && c.moveToNext()) {
                Doc d = new Doc();
                d.id = c.getString(0);
                d.name = c.getString(1);
                d.dir = DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2));
                d.size = c.isNull(3) ? -1 : c.getLong(3);  // (-1: the provider doesn't say - listFiles refuses it)
                if (d.name != null && !seen.add(d.name)) { listedTwice++; continue; }
                out.add(d);
            }
        }
        return out;
    }

    private static Doc named(List<Doc> l, String name) {
        for (Doc d : l) if (d.name != null && d.name.equals(name)) return d;
        return null;
    }

    // exefs: the folder with main and main.npdm; romfs: the folder with Pack - in the picked folder or a few
    // levels below it (as runtime/ui/launcher.mm find_game)
    private Doc exefs, romfs;
    // or a romfs.bin made already (on a PC: hactool's --romfs, from the game's own files) - one file to copy or use in
    // place instead of ~41,000 (a phone's file provider choked on them), and the folder that holds it
    private Doc romfsImage, romfsImageDir;
    private boolean packages;
    // A game code built already (botwgame.so with its .id, and .game: the game version it's for - a package made on a
    // PC from the same files), used instead of building it on the phone when it's for this version and this app
    private Doc preLib, preId, preGame;

    private int searched;  // (folders listed by find, since importGame began)

    private void find(Uri tree, Doc dir, int depth) {
        if (depth > 4 || (exefs != null && (romfs != null || romfsImage != null))) return;
        List<Doc> kids = children(tree, dir.id);
        if (++searched % 10 == 0) nativeImportSearched(searched);
        if (preLib == null && named(kids, "botwgame.so") != null && named(kids, "botwgame.so.id") != null &&
            named(kids, "botwgame.so.game") != null) {
            preLib = named(kids, "botwgame.so");
            preId = named(kids, "botwgame.so.id");
            preGame = named(kids, "botwgame.so.game");
        }
        if (exefs == null && named(kids, "main") != null && named(kids, "main.npdm") != null) exefs = dir;
        Doc rc = named(kids, "Pack");
        boolean anyRomfs = rc != null && rc.dir;
        if (romfs == null && anyRomfs) {  // (the game's has System/Version.txt; the DLC's, also with Pack, has
            Doc sys = named(kids, "System");  //  AocVersion.txt there instead - taken for the game's: issue #87)
            if (sys != null && sys.dir && named(children(tree, sys.id), "Version.txt") != null) romfs = dir;
        }
        Doc ri = named(kids, "romfs.bin");
        if (romfsImage == null && ri != null && !ri.dir) {
            romfsImage = ri;
            romfsImageDir = dir;
        }
        // (romfs/'s own folders hold no exefs: through the file provider, listing their ~40000 files took long enough
        // for the setup to look stuck - issue #17)
        if (anyRomfs || dir == exefs) return;
        for (Doc k : kids) {
            String n = k.name == null ? "" : k.name.toLowerCase();
            if (n.endsWith(".nsp") || n.endsWith(".xci") || n.endsWith(".nca") || n.endsWith(".nsz") || n.endsWith(".xcz")) packages = true;
            if (k.dir && !"Pack".equals(k.name)) find(tree, k, depth + 1);
        }
    }

    private long treeBytes(Uri tree, Doc dir) {
        long n = 0;
        for (Doc k : children(tree, dir.id)) n += k.dir ? treeBytes(tree, k) : Math.max(k.size, 0);
        return n;
    }

    // romfs/'s files (relative paths): nativeBuildRomfs builds romfs.bin straight from them, no copy of the folder
    // (LASRecompAndroid's)
    private Uri romfsTree;
    private final List<Doc> romfsFiles = new ArrayList<>();
    private final List<String> romfsPaths = new ArrayList<>();

    private void listFiles(Uri tree, Doc dir, String prefix) throws IOException {
        for (Doc k : children(tree, dir.id)) {
            String rel = prefix + k.name;
            if (k.dir) {
                listFiles(tree, k, rel + "/");
                continue;
            }
            if (k.size < 0) throw new IOException("The file provider did not report a size for " + k.name +
                    ". Copy the extracted game files to a local folder and select it again.");
            romfsFiles.add(k);
            romfsPaths.add(rel);
        }
    }

    // (called by nativeBuildRomfs) file i's data as a descriptor the native side reads and closes; -1 when it can't be
    // opened, romfsOpenError saying why. Three tries: a setup stopped once in ~41000 opens with "can't read" (#17).
    // Through one client of the file provider for the whole image (ContentResolver's openFileDescriptor took and dropped
    // the provider for each file, and its descriptors held it until the garbage collector came by); unstable: the
    // provider dying fails an open (a new client, tried again), not this app.
    private String romfsOpenError;
    private android.content.ContentProviderClient romfsClient;

    @SuppressWarnings("unused")
    private int openRomfsFile(int i) {
        Uri u = DocumentsContract.buildDocumentUriUsingTree(romfsTree, romfsFiles.get(i).id);
        for (int attempt = 1; ; attempt++) {
            try {
                if (romfsClient == null) romfsClient = getContentResolver().acquireUnstableContentProviderClient(u);
                if (romfsClient == null) throw new IOException("no file provider for " + u.getAuthority());
                android.os.ParcelFileDescriptor pfd = romfsClient.openFile(u, "r");
                if (pfd == null) throw new IOException("the file provider gave no file");
                return pfd.detachFd();
            } catch (Exception e) {
                String why = e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
                Log.w(TAG, "opening " + romfsPaths.get(i) + ", try " + attempt + ": " + why);
                if (e instanceof android.os.RemoteException) closeRomfsClient();  // (the provider died: a new one)
                if (attempt == 3) {
                    romfsOpenError = why;
                    return -1;
                }
                try {
                    Thread.sleep(attempt * 1000L);
                } catch (InterruptedException ie) {
                    return -1;
                }
            }
        }
    }

    private void closeRomfsClient() {
        if (romfsClient != null) romfsClient.close();
        romfsClient = null;
    }

    private long copied, total, reported;

    private void copyTree(Uri tree, Doc dir, File to) throws IOException {
        to.mkdirs();
        ContentResolver cr = getContentResolver();
        for (Doc k : children(tree, dir.id)) {
            File dst = new File(to, k.name);
            if (k.dir) {
                copyTree(tree, k, dst);
                continue;
            }
            try (InputStream in = cr.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, k.id));
                 OutputStream o = new FileOutputStream(dst)) {
                byte[] buf = new byte[1 << 20];
                for (int r; (r = in.read(buf)) > 0; ) {
                    o.write(buf, 0, r);
                    copied += r;
                    if (copied - reported > (16 << 20)) {
                        reported = copied;
                        nativeImportProgress(copied, total);
                    }
                }
            }
        }
    }

    private String readText(Uri tree, Doc d) {
        try (InputStream in = getContentResolver().openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, d.id))) {
            byte[] b = new byte[64];
            int n = in.read(b);
            return n > 0 ? new String(b, 0, n, java.nio.charset.StandardCharsets.UTF_8) : "";
        } catch (Exception e) {
            return "";
        }
    }

    private void importGame(Uri tree) {
        synchronized (IMPORT_LOCK) { importGameLocked(tree); }
    }

    private void importGameLocked(Uri tree) {
        int result;
        nativeImportLooking();
        try {
            exefs = romfs = romfsImage = romfsImageDir = null;
            preLib = preId = preGame = null;
            listedTwice = 0;
            searched = 0;
            packages = false;
            Doc root = new Doc();
            root.id = DocumentsContract.getTreeDocumentId(tree);
            root.dir = true;
            find(tree, root, 0);
            if (exefs == null || (romfs == null && romfsImage == null)) {
                nativeImportDone(packages ? 3 : 2);
                return;
            }
            Doc main = named(children(tree, exefs.id), "main");
            byte[] head = new byte[0x48];
            try (InputStream in = getContentResolver().openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, main.id))) {
                int got = 0;
                for (int r; got < head.length && (r = in.read(head, got, head.length - got)) > 0; ) got += r;
            }
            int version = nativeGameVersion(head);  // (1.0.0, 1.6.0, 1.8.2: each set up in its own folder)
            if (version == 0) {
                nativeImportDone(4);
                return;
            }
            if (romfsImage != null) {  // (a romfs.bin beside exefs/: taken over a romfs/ folder, one file instead of thousands)
                importImage(tree, version);
                return;
            }
            // (listed before nativeImportBegin removes an earlier setup: a folder that can't be used leaves it alone)
            romfsTree = tree;
            romfsFiles.clear();
            romfsPaths.clear();
            listFiles(tree, romfs, "");
            if (listedTwice > 0) Log.w(TAG, "the file provider listed " + listedTwice + " entries twice: once each kept");
            int vt = romfsPaths.indexOf("System/Version.txt");  // (its version against the exefs/'s, and its file count)
            if (!nativeCheckRomfs(version, romfsPaths.toArray(new String[0]), vt >= 0 ? readText(tree, romfsFiles.get(vt)) : "")) {
                romfsFiles.clear();
                romfsPaths.clear();
                nativeImportDone(6);
                return;
            }
            long romfsBytes = 0;
            for (Doc d : romfsFiles) romfsBytes = Math.addExact(romfsBytes, d.size);
            String name = DocumentsContract.getTreeDocumentId(tree);
            File games = new File(nativeImportBegin(name.substring(name.lastIndexOf(':') + 1), version));
            copied = reported = 0;
            total = Math.addExact(romfsBytes, treeBytes(tree, exefs));
            copyTree(tree, exefs, new File(games, "exefs"));
            if (preLib != null && Integer.toString(version).equals(readText(tree, preGame).trim())) {
                // (the native side checks its id against this app's and builds it on the phone when they differ)
                copyDoc(tree, preId, new File(games, "botwgame.so.id"));
                copyDoc(tree, preLib, new File(games, "botwgame.so"));
                Log.i(TAG, "game code from the picked folder: " + preLib.size + " bytes");
            }
            nativeImportProgress(copied, total);
            if (importInPlace) {  // the romfs/ where it is: its path noted, no image built (runtime/services/fs.c)
                String at = documentPath(romfs.id);
                romfsFiles.clear();
                romfsPaths.clear();
                if (at == null || !new File(at, "System/Version.txt").canRead()) {
                    nativeImportDetail("this folder can't be used in place (" + (at == null ? romfs.id : at) +
                            "): choose Copy them, or a folder on the phone's storage");
                    nativeImportDone(5);
                    return;
                }
                try (OutputStream o = new FileOutputStream(new File(games, "romfs.link"))) {
                    o.write((at + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                Log.i(TAG, "game data used in place: " + at);
                nativeImportDone(0);
                return;
            }
            long[] sizes = new long[romfsFiles.size()];
            for (int i = 0; i < sizes.length; i++) sizes[i] = romfsFiles.get(i).size;
            romfsOpenError = null;
            String failed;
            try {
                failed = nativeBuildRomfs(romfsPaths.toArray(new String[0]), sizes);
            } finally {
                closeRomfsClient();
            }
            romfsFiles.clear();
            romfsPaths.clear();
            if (failed != null) {
                if (romfsOpenError != null) nativeImportDetail(failed + " - " + romfsOpenError);  // (in place of the native side's)
                Log.e(TAG, "game data image: " + failed);
                nativeImportDone(5);
                return;
            }
            result = 0;
        } catch (Exception e) {
            Log.e(TAG, "game files: " + e);
            nativeImportDetail(e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : ""));
            result = 5;
        }
        nativeImportDone(result);
    }

    // A document's data as a descriptor the native side reads and closes
    private int docFd(Uri tree, Doc d) throws IOException {
        android.os.ParcelFileDescriptor pfd =
                getContentResolver().openFileDescriptor(DocumentsContract.buildDocumentUriUsingTree(tree, d.id), "r");
        if (pfd == null) throw new IOException("Could not open " + d.name);
        return pfd.detachFd();
    }

    // The game files from exefs/ and a romfs.bin made already (importGame found them): the image checked from its own
    // tables (its version against exefs/'s, its file count), then copied whole - or, used in place, its path noted in
    // romfs.link (runtime/services/fs.c opens it) - and the movies taken out of it (opened by path: hle/movie.c).
    // Called from importGame's try: its exceptions end the setup there. (nativeImportDone here on every other end.)
    private void importImage(Uri tree, int version) throws IOException {
        if (!nativeCheckRomfsImage(version, docFd(tree, romfsImage))) {
            nativeImportDone(6);
            return;
        }
        String at = null;
        if (importInPlace) {  // (checked before nativeImportBegin removes an earlier setup)
            String dir = documentPath(romfsImageDir.id);
            at = dir == null ? null : new File(dir, romfsImage.name).getPath();
            if (at == null || !new File(at).canRead()) {
                nativeImportDetail("this romfs.bin can't be used in place (" + (at == null ? romfsImage.id : at) +
                        "): choose Copy them, or a folder on the phone's storage");
                nativeImportDone(5);
                return;
            }
        }
        String name = DocumentsContract.getTreeDocumentId(tree);
        File games = new File(nativeImportBegin(name.substring(name.lastIndexOf(':') + 1), version));
        copied = reported = 0;
        total = Math.addExact(at == null ? Math.max(romfsImage.size, 0) : 0, treeBytes(tree, exefs));
        copyTree(tree, exefs, new File(games, "exefs"));
        if (preLib != null && Integer.toString(version).equals(readText(tree, preGame).trim())) {
            copyDoc(tree, preId, new File(games, "botwgame.so.id"));
            copyDoc(tree, preLib, new File(games, "botwgame.so"));
            Log.i(TAG, "game code from the picked folder: " + preLib.size + " bytes");
        }
        nativeImportProgress(copied, total);
        int movies;
        if (at != null) {
            try (OutputStream o = new FileOutputStream(new File(games, "romfs.link"))) {
                o.write((at + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            Log.i(TAG, "game data used in place: " + at);
            movies = docFd(tree, romfsImage);
        } else {
            File img = new File(games, "romfs.bin");
            try (InputStream in = getContentResolver().openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, romfsImage.id));
                 OutputStream o = new FileOutputStream(img)) {
                if (in == null) throw new IOException("Could not open " + romfsImage.name);
                byte[] buf = new byte[1 << 20];
                for (int r; (r = in.read(buf)) > 0; ) {
                    o.write(buf, 0, r);
                    copied += r;
                    if (copied - reported > (16 << 20)) {
                        reported = copied;
                        nativeImportProgress(copied, total);
                    }
                }
            }
            if (romfsImage.size >= 0 && img.length() != romfsImage.size)
                throw new IOException("romfs.bin copied " + img.length() + " of its " + romfsImage.size + " bytes: check free storage");
            Log.i(TAG, "game data copied: " + img.length() + " bytes");
            movies = android.os.ParcelFileDescriptor.open(img, android.os.ParcelFileDescriptor.MODE_READ_ONLY).detachFd();
        }
        nativeImportProgress(total, total);
        String failed = nativeImageLoose(movies);
        if (failed != null) {
            nativeImportDone(5);
            return;
        }
        nativeImportDone(0);
    }

    private void importDriver(Uri f) {
        nativeDriverPicked(installDriver(this, f));
    }

    // A driver package (.zip or .adpkg - a zip too -, or a bare .so) into <external files>/drivers, in place of any
    // there (customDriver loads it at the next start). Its name, or "" (not a driver package, or the copy failed).
    // Also DriverPickActivity's.
    static String installDriver(Context ctx, Uri f) {
        String name = "driver.zip";
        try (Cursor c = ctx.getContentResolver().query(f, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) name = c.getString(0);
        } catch (Exception ignored) {
        }
        if (!DRIVER_FILE.matcher(name).find()) return "";
        try {
            File dir = new File(ctx.getExternalFilesDir(null), "drivers");
            deleteTree(dir);
            dir.mkdirs();
            File dst = new File(dir, name);
            try (InputStream in = ctx.getContentResolver().openInputStream(f)) {
                copy(in, dst);
            }
            if (driverKind(dst) == 0) {  // (named like one, but neither a zip nor a library)
                deleteTree(dir);
                return "";
            }
            return name;
        } catch (Exception e) {
            Log.e(TAG, "driver: " + e);
            return "";
        }
    }

    // ---- a graphics driver error that stops the app (runtime/platform/android.c plat_fatal_notice): the driver is
    // loaded before the start screen, so the Settings rows that choose it can't be reached when it fails. This dialog
    // installs a driver package, or goes back to the system's driver, and starts the app again; else it closes. Blocks
    // the calling (native) thread until the player closes it. LAS Recomp for Android 1c05238, from
    // LASRecomp-Android-Plus (MIT); the app icon's long-press "Choose driver" (DriverPickActivity) does the same.
    private static final Object dlgLock = new Object();
    private static boolean dlgDone;
    private static String dlgText;

    public static boolean driverErrorDialog(String text) {
        BOTWRecompAndroidActivity a = self;
        if (a == null) return false;
        synchronized (dlgLock) { dlgDone = false; dlgText = text; }
        a.runOnUiThread(() -> a.showDriverError(null));
        synchronized (dlgLock) {
            while (!dlgDone) {
                try { dlgLock.wait(); } catch (InterruptedException e) { break; }
            }
        }
        return true;
    }

    private void showDriverError(String extra) {
        // (the native message is in English: what to do comes first, in the phone's language - issue #13)
        String msg = getString(R.string.driver_error_intro) + "\n" + dlgText + (extra != null ? "\n\n" + extra : "");
        AlertDialog.Builder b = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.driver_error_title).setMessage(msg).setCancelable(false)
            .setPositiveButton(R.string.driver_install, (d, w) -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try {
                    startActivityForResult(i, REQ_DRIVER_RECOVER);
                } catch (Exception e) {
                    Log.e(TAG, "file picker: " + e);
                    showDriverError(getString(R.string.driver_not_a_package));
                }
            })
            .setNegativeButton(R.string.driver_close, (d, w) -> {
                synchronized (dlgLock) { dlgDone = true; dlgLock.notifyAll(); }
            });
        File[] custom = new File(getExternalFilesDir(null), "drivers").listFiles((dir, n) -> DRIVER_FILE.matcher(n).find());
        if (custom != null && custom.length > 0)
            b.setNeutralButton(R.string.driver_system, (d, w) -> { dropCustomDriver(); restartApp(); });
        b.show();
    }

    // driver packages by name, and by content: 1 a zip (adrenotools' .zip / .adpkg), 2 a library (ELF), 0 neither
    private static final java.util.regex.Pattern DRIVER_FILE =
            java.util.regex.Pattern.compile("\\.(so|zip|adpkg)$", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static int driverKind(File f) throws IOException {
        byte[] m = new byte[4];
        try (InputStream in = new FileInputStream(f)) {
            if (in.read(m) != 4) return 0;
        }
        if (m[0] == 'P' && m[1] == 'K' && m[2] == 3 && m[3] == 4) return 1;
        if (m[0] == 0x7f && m[1] == 'E' && m[2] == 'L' && m[3] == 'F') return 2;
        return 0;
    }

    // (by size and date: the game code is ~100 MB)
    private static void copyIfChanged(File src, File dst) throws IOException {
        if (!src.exists()) return;
        if (dst.exists() && dst.length() == src.length() && dst.lastModified() >= src.lastModified()) return;
        try (InputStream in = new FileInputStream(src)) {
            copy(in, dst);
        }
        Log.i(TAG, "copied " + src + " -> " + dst);
    }

    // How the previous run ended (ApplicationExitInfo), for the log: a setup that vanished with nothing in the log was
    // the system closing the app - for memory or something else (issue #18). "" when unknown.
    private static final String[] EXIT_REASONS = {"unknown", "exit self", "signaled", "low memory", "crash", "native crash",
            "not responding", "initialization failure", "permission change", "excessive resource usage", "user requested",
            "user stopped", "dependency died", "other", "freezer", "package state change", "package updated"};

    private String lastExit() {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            List<android.app.ApplicationExitInfo> l = am.getHistoricalProcessExitReasons(getPackageName(), 0, 1);
            if (l.isEmpty()) return "";
            android.app.ApplicationExitInfo e = l.get(0);
            int r = e.getReason();
            return String.format(java.util.Locale.ROOT, "%s (reason %d, status %d) at %tF %<tT, pss %d MB, rss %d MB: %s",
                    r >= 0 && r < EXIT_REASONS.length ? EXIT_REASONS[r] : "?", r, e.getStatus(), e.getTimestamp(),
                    e.getPss() / 1024, e.getRss() / 1024, e.getDescription());
        } catch (Exception e) {
            return "";
        }
    }

    // A device reboot may leave no process crash report. Preserve the previous boot count
    // once per process so activity recreation does not masquerade as a new test session.
    private synchronized String bootState() {
        if (bootState != null) return bootState;
        int current = -1;
        try { current = android.provider.Settings.Global.getInt(getContentResolver(),
                android.provider.Settings.Global.BOOT_COUNT, -1); }
        catch (Exception ignored) { /* Some firmware restricts access to this counter. */ }
        android.content.SharedPreferences p = getSharedPreferences("crash_diagnostics", MODE_PRIVATE);
        int previous = p.getInt("boot_count", -1);
        bootState = "boot count " + current + ", previous session " + previous + ", uptime "
                + android.os.SystemClock.elapsedRealtime() / 1000 + " s; "
                + (current >= 0 && previous >= 0 ? (current > previous ? "system rebooted between sessions"
                   : current == previous ? "same system boot" : "boot counter reset") : "reboot status unknown");
        if (!p.edit().putInt("boot_count", current).commit())
            Log.w(TAG, "could not persist boot counter for crash diagnostics");
        return bootState;
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            android.app.ActivityManager.MemoryInfo info = new android.app.ActivityManager.MemoryInfo();
            am.getMemoryInfo(info);
            android.os.Debug.MemoryInfo process = new android.os.Debug.MemoryInfo();
            android.os.Debug.getMemoryInfo(process);
            nativeMemoryPressure(level, info.availMem, info.totalMem, info.threshold, info.lowMemory, process.getTotalPss());
        } catch (Exception | UnsatisfiedLinkError e) {
            Log.w(TAG, "memory pressure level " + level + " (details unavailable)");
        }
    }

    private static void setenv(String name, String value) throws ErrnoException {
        Os.setenv(name, value, true);
    }

    private void copyAssets(String from, File to) throws IOException {
        AssetManager am = getAssets();
        String[] names = am.list(from);
        if (names == null) return;
        to.mkdirs();
        for (String n : names) {
            try (InputStream in = am.open(from + "/" + n)) {
                copy(in, new File(to, n));
            }
        }
    }

    // The first driver in <external files>/drivers, in internal storage (copied again only when it changed). A zip's
    // library is the one its meta.json names (adrenotools' libraryName: packages with support libraries beside it, as
    // RADV-Xclipse's), else one named like a Vulkan driver, else its only / last .so.
    private File customDriver() throws IOException {
        File src = new File(getExternalFilesDir(null), "drivers");
        src.mkdirs();
        File[] files = src.listFiles((d, n) -> DRIVER_FILE.matcher(n).find());
        if (files == null || files.length == 0) return null;
        Arrays.sort(files);
        File f = files[0];
        File dir = new File(getFilesDir(), "drivers/" + DRIVER_FILE.matcher(f.getName()).replaceAll(""));
        File stamp = new File(dir, ".source");  // (the package's size:date, then the library chosen in it)
        String id = f.length() + ":" + f.lastModified();
        String[] was = stamp.exists() ? readSmall(stamp).split("\n") : new String[0];
        File lib = was.length == 2 ? new File(dir, was[1]) : null;
        if (lib != null && id.equals(was[0]) && lib.exists()) return lib;
        deleteTree(dir);
        dir.mkdirs();
        int kind = driverKind(f);
        if (kind == 2) {
            try (InputStream in = new FileInputStream(f)) {
                lib = copy(in, new File(dir, f.getName().toLowerCase().endsWith(".so") ? f.getName() : "libvulkan_custom.so"));
            }
        } else if (kind == 1) {
            lib = null;
            File named = null, vulkan = null;
            String libraryName = null;
            try (ZipInputStream z = new ZipInputStream(new FileInputStream(f))) {
                for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                    String n = new File(e.getName()).getName();
                    if (e.isDirectory()) continue;
                    if (n.equals("meta.json")) {
                        try {
                            libraryName = new org.json.JSONObject(new String(readAll(z))).optString("libraryName", null);
                        } catch (org.json.JSONException bad) {
                            Log.w(TAG, "driver meta.json: " + bad);
                        }
                    } else if (n.endsWith(".so")) {
                        lib = copyNoClose(z, new File(dir, n));
                        if (n.contains("vulkan")) vulkan = lib;
                    }
                }
            }
            if (libraryName != null) named = new File(dir, new File(libraryName).getName());
            if (named != null && named.exists()) lib = named;
            else if (vulkan != null) lib = vulkan;
        } else {
            Log.w(TAG, "Vulkan driver: " + f.getName() + " is neither a zip nor a library");
            lib = null;
        }
        if (lib != null) {
            try (OutputStream o = new FileOutputStream(stamp)) {
                o.write((id + "\n" + lib.getName()).getBytes());
            }
        }
        Log.i(TAG, "Vulkan driver: " + f.getName() + " -> " + lib);
        return lib;
    }

    private static String readSmall(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            return new String(readAll(in));
        }
    }

    // (InputStream.readAllBytes is Android 13+: on 11 and 12 the app died at launch, issue #3)
    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) o.write(buf, 0, n);
        return o.toByteArray();
    }

    private static File copy(InputStream in, File dst) throws IOException {
        return copyNoClose(in, dst);
    }

    private static File copyNoClose(InputStream in, File dst) throws IOException {
        try (OutputStream o = new FileOutputStream(dst)) {
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) o.write(buf, 0, n);
        }
        return dst;
    }

    // ---- game mods (the menu's Mods page, runtime/ui/mods_tab.mm; runtime/mods.c; LASRecompAndroid's): a mod's folder
    // - the one with romfs/ in it, or a few levels above it - copied into <dir>/<its name>/ (dir: the running game
    // version's mods/): romfs/ and the notes (.txt) beside it. Answers nativeModImportDone "1|<name>" imported,
    // "0|<reason>" failed, "2|" cancelled.
    private static String modsDir;
    private static volatile boolean modInPlace;  // (its folder noted in <dir>/<name>.link, not copied: runtime/mods.c)

    public static void importModInPlace(String dir) {
        if (android.os.Build.VERSION.SDK_INT >= 30 && !android.os.Environment.isExternalStorageManager()) {
            self.runOnUiThread(() -> self.startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + self.getPackageName()))));
            nativeModImportDone("0|Allow BOTWRecompAndroid to access all files (the page Android just opened), then import the mod again.");
            return;
        }
        importMod(dir);
        modInPlace = true;
    }

    public static void importMod(String dir) {
        modInPlace = false;
        modsDir = dir;
        self.runOnUiThread(() -> {
            try {
                self.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_MOD);
            } catch (Exception e) {
                nativeModImportDone("0|" + e.getMessage());
            }
        });
    }

    // the folder holding romfs/: dir itself or one below it (3 levels at most)
    private Doc modFolder(Uri tree, Doc dir, int depth) {
        List<Doc> kids = children(tree, dir.id);
        Doc r = named(kids, "romfs");
        if (r != null && r.dir) return dir;
        if (depth >= 3) return null;
        for (Doc k : kids) {
            if (!k.dir) continue;
            Doc f = modFolder(tree, k, depth + 1);
            if (f != null) return f;
        }
        return null;
    }

    private void copyDocs(Uri tree, Doc dir, File to) throws IOException {
        if (!to.isDirectory() && !to.mkdirs()) throw new IOException("Could not create " + to.getName());
        for (Doc k : children(tree, dir.id)) {
            if (k.name == null || k.name.contains("/")) continue;
            if (k.dir) copyDocs(tree, k, new File(to, k.name));
            else copyDoc(tree, k, new File(to, k.name));
        }
    }

    private void copyDoc(Uri tree, Doc d, File to) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, d.id))) {
            if (in == null) throw new IOException("Could not open " + d.name);
            copy(in, to);
        }
    }

    private void importMod(Uri tree) {
        File stage = null;
        try {
            Doc root = new Doc();
            root.id = DocumentsContract.getTreeDocumentId(tree);
            root.dir = true;
            try (Cursor c = getContentResolver().query(DocumentsContract.buildDocumentUriUsingTree(tree, root.id),
                    new String[] {DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst()) root.name = c.getString(0);
            }
            Doc mod = modFolder(tree, root, 0);
            if (mod == null) {
                nativeModImportDone("0|No romfs folder in the selected folder.");
                return;
            }
            String name = mod.name == null ? "mod" : mod.name.replaceAll("[^A-Za-z0-9 ._()\\-]", "_").trim();
            if (name.isEmpty() || name.startsWith(".")) name = "mod";
            if (name.length() > 80) name = name.substring(0, 80);
            File mods = new File(modsDir != null ? modsDir : new File(getExternalFilesDir(null), "mods").getPath());
            if (!mods.isDirectory() && !mods.mkdirs()) throw new IOException("Could not create the mods folder.");
            if (modInPlace) {  // its folder where it is: noted, not copied
                String at = documentPath(mod.id);
                if (at == null || !new File(at, "romfs").isDirectory())
                    throw new IOException("This folder can't be used in place; choose Game files: Copy them, or a folder on the phone's storage.");
                deleteTree(new File(mods, name));  // (a copy imported before: the noted folder from now on)
                try (OutputStream o = new FileOutputStream(new File(mods, name + ".link"))) {
                    o.write((at + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                nativeModImportDone("1|" + name);
                return;
            }
            new File(mods, name + ".link").delete();  // (one noted before: the copy from now on)
            stage = new File(mods, ".import-" + name);
            deleteTree(stage);
            Doc romfsDoc = named(children(tree, mod.id), "romfs");
            copyDocs(tree, romfsDoc, new File(stage, "romfs"));
            for (Doc k : children(tree, mod.id))  // (its notes: the menu shows one as its description)
                if (!k.dir && k.name != null && k.name.toLowerCase().endsWith(".txt")) copyDoc(tree, k, new File(stage, k.name));
            File dst = new File(mods, name);
            deleteTree(dst);
            if (!stage.renameTo(dst)) throw new IOException("Could not move the mod into place.");
            stage = null;
            nativeModImportDone("1|" + name);
        } catch (Exception e) {
            Log.e(TAG, "mod import", e);
            nativeModImportDone("0|" + e.getMessage());
        } finally {
            if (stage != null) deleteTree(stage);
        }
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
