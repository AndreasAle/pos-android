package com.conwebs.pos;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.view.KeyEvent;
import android.view.View;
import android.print.PrintManager;
import android.text.InputType;
import android.view.MotionEvent;
import android.util.Base64;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Kiosk-style shell around the Laravel POS.
 *
 * Everything the cashier does runs against the same server and the same
 * database as the desktop app — this only supplies a full-screen browser that
 * keeps its session, so a tablet behaves like a dedicated register.
 */
public class MainActivity extends Activity {

    private static final String PREFS_NAME  = "POSConfig";
    private static final String KEY_URL     = "server_base_url";
    private static final String DEFAULT_URL = "https://pos.conweb.id";

    /** Milliseconds within which a second Back press exits the app. */
    private static final long EXIT_WINDOW_MS = 2000;

    private WebView      webView;
    private LinearLayout loadingLayout;
    private LinearLayout errorLayout;
    private TextView     serverUrlText;

    private String  baseUrl;
    private long    lastBackPress = 0;

    private ValueCallback<Uri[]> fileChooserCallback;
    private static final int FILE_CHOOSER_REQUEST = 1001;

    /** The register's print view: /receipt/{order}/print. */
    private static final Pattern RECEIPT_PRINT = Pattern.compile("/receipt/\\d+/print/?$");

    /** The kitchen ticket: /receipt/{order}/kitchen. */
    private static final Pattern KITCHEN_PRINT = Pattern.compile("/receipt/\\d+/kitchen/?$");

    /** The ESC/POS payload the server embeds for RawBT in that view. */
    private static final Pattern RAWBT_PAYLOAD = Pattern.compile("rawbt:base64,([A-Za-z0-9+/=]+)");

    /** Marks our own fetches and system-print loads so they are not intercepted again. */
    private static final String NATIVE_FLAG = "native_app=1";

    private ReceiptPrinter printer;
    /** Second printer for kitchen tickets: only the order's kitchen items, no prices. */
    private ReceiptPrinter kitchenPrinter;
    private KioskManager   kiosk;
    private AdminPin       adminPin;

    private TextView printStatus;
    private final Runnable hidePrintStatus = () -> printStatus.setVisibility(View.GONE);

    /** Copied once on the UI thread; the WebView may not be touched from the fetch thread. */
    private volatile String userAgent;

    /** Held so the off-screen page is not collected while Android lays out the print. */
    private WebView printView;

    @SuppressLint({"SetJavaScriptEnabled", "ClickableViewAccessibility"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

        setContentView(R.layout.activity_main);

        webView       = findViewById(R.id.webView);
        loadingLayout = findViewById(R.id.loadingLayout);
        errorLayout   = findViewById(R.id.errorLayout);
        serverUrlText = findViewById(R.id.serverUrlText);

        Button retryBtn     = findViewById(R.id.retryButton);
        Button changeUrlBtn = findViewById(R.id.changeUrlButton);

        printStatus = findViewById(R.id.printStatus);
        ReceiptPrinter.Status statusPill = new ReceiptPrinter.Status() {
            @Override
            public void progress(String text) {
                printStatus.removeCallbacks(hidePrintStatus);
                printStatus.setText(text);
                printStatus.setBackgroundResource(R.drawable.print_status_bg);
                printStatus.setVisibility(View.VISIBLE);
            }

            @Override
            public void finished(String text) {
                printStatus.removeCallbacks(hidePrintStatus);
                if (text == null) {
                    printStatus.setVisibility(View.GONE);
                    return;
                }
                printStatus.setText(text);
                printStatus.setVisibility(View.VISIBLE);
                printStatus.postDelayed(hidePrintStatus, 3500);
            }
        };
        printer        = new ReceiptPrinter(this, statusPill, ReceiptPrinter.Role.RECEIPT);
        kitchenPrinter = new ReceiptPrinter(this, statusPill, ReceiptPrinter.Role.KITCHEN);
        setupWebView();

        kiosk    = new KioskManager(this);
        adminPin = new AdminPin(this);
        handleRecoveryIntent(getIntent());

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        baseUrl = prefs.getString(KEY_URL, null);

        if (baseUrl == null) {
            showUrlDialog(true);
        } else {
            openPos();
        }

        // Long-press the loading screen to reach settings without a menu bar.
        loadingLayout.setLongClickable(true);
        loadingLayout.setOnLongClickListener(v -> {
            showSettingsMenu();
            return true;
        });

        changeUrlBtn.setOnClickListener(v -> showSettingsMenu());

        // A dropped Wi-Fi is the usual reason this screen shows; let the admin
        // fix it without leaving the kiosk.
        Button wifiBtn = findViewById(R.id.wifiButton);
        wifiBtn.setOnClickListener(v -> requireAdmin(() -> kiosk.openWifi(this)));

        retryBtn.setOnClickListener(v -> {
            errorLayout.setVisibility(View.GONE);
            loadingLayout.setVisibility(View.VISIBLE);
            openPos();
        });
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    private void openPos() {
        serverUrlText.setText(baseUrl);
        webView.setVisibility(View.GONE);
        // Land on the PIN pad, not the back-office login. A cashier signs in
        // with a short PIN; if a session is already open the server sends us
        // straight through to the register.
        webView.loadUrl(baseUrl + "/kasir");
    }

    /**
     * Normalise whatever the operator typed into a bare origin.
     *
     * They may paste a full POS or login link; the app appends its own paths, so
     * trailing segments have to come off or the result is /pos/pos.
     */
    private String normaliseBaseUrl(String input) {
        String url = input.trim();

        if (url.isEmpty()) {
            return null;
        }

        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }

        url = url.replaceAll("/+$", "");
        url = url.replaceAll("/(kasir|pos|login|dashboard)$", "");

        return url.replaceAll("/+$", "");
    }

    private void showUrlDialog(boolean isFirstLaunch) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String saved = prefs.getString(KEY_URL, DEFAULT_URL);

        EditText input = new EditText(this);
        input.setText(saved);
        input.setSelectAllOnFocus(true);
        input.setSingleLine(true);
        input.setHint("https://pos.conweb.id");
        input.setPadding(56, 40, 56, 40);

        String title = isFirstLaunch ? "Setup Awal — Alamat Server" : "Ganti Alamat Server";
        String message = isFirstLaunch
            ? "Masukkan alamat server POS.\n\nContoh: https://pos.conweb.id\n\n"
              + "Untuk jaringan lokal, pakai IP komputer server, misal http://192.168.1.10:8000"
            : "Masukkan alamat server POS yang baru:";

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setView(input)
            .setCancelable(!isFirstLaunch)
            .setPositiveButton("Simpan & Buka", (dialog, which) -> {
                String url = normaliseBaseUrl(input.getText().toString());

                if (url == null) {
                    Toast.makeText(this, "Alamat server tidak boleh kosong.", Toast.LENGTH_LONG).show();
                    return;
                }

                prefs.edit().putString(KEY_URL, url).apply();
                baseUrl = url;

                errorLayout.setVisibility(View.GONE);
                loadingLayout.setVisibility(View.VISIBLE);
                openPos();

                Toast.makeText(this, "Server: " + url, Toast.LENGTH_LONG).show();
            });

        if (!isFirstLaunch) {
            builder.setNegativeButton("Batal", null);
        }

        builder.show();
    }

    // ── Admin menu & kiosk ────────────────────────────────────────────────────

    /** Five quick taps in the top-left corner open the admin menu. */
    private static final int  ADMIN_TAPS        = 5;
    private static final long ADMIN_TAP_WINDOW  = 3000;
    private static final int  ADMIN_CORNER_DP   = 72;

    private int  adminTapCount = 0;
    private long adminTapFirst = 0;

    /**
     * Watch for the hidden gesture without swallowing the taps: the register's
     * own menu button lives in that corner and must keep working.
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_UP) {
            float corner = ADMIN_CORNER_DP * getResources().getDisplayMetrics().density;

            if (ev.getX() < corner && ev.getY() < corner) {
                long now = System.currentTimeMillis();

                if (now - adminTapFirst > ADMIN_TAP_WINDOW) {
                    adminTapFirst = now;
                    adminTapCount = 0;
                }

                if (++adminTapCount >= ADMIN_TAPS) {
                    adminTapCount = 0;
                    adminTapFirst = 0;
                    showSettingsMenu();
                }
            }
        }

        return super.dispatchTouchEvent(ev);
    }

    /** The admin menu, behind the admin PIN. */
    private void showSettingsMenu() {
        requireAdmin(this::showAdminMenu);
    }

    /**
     * Run {@code action} once the admin PIN is entered. A kiosk tablet must have
     * a PIN, so one is created first if missing; a tablet that is not a kiosk
     * and never set one keeps the old open menu.
     */
    private void requireAdmin(Runnable action) {
        if (adminPin.isSet()) {
            askPin("Masukkan PIN admin", pin -> {
                if (adminPin.check(pin)) {
                    action.run();
                } else {
                    Toast.makeText(this, "PIN admin salah.", Toast.LENGTH_LONG).show();
                }
            });
        } else if (kiosk.isDeviceOwner()) {
            createPin(action);
        } else {
            action.run();
        }
    }

    private void createPin(Runnable then) {
        askPin("Buat PIN admin (min. " + AdminPin.MIN_LENGTH + " angka)", first -> {
            if (first.length() < AdminPin.MIN_LENGTH) {
                Toast.makeText(this, "PIN minimal " + AdminPin.MIN_LENGTH + " angka.",
                    Toast.LENGTH_LONG).show();
                return;
            }
            askPin("Ulangi PIN admin", second -> {
                if (!first.equals(second)) {
                    Toast.makeText(this, "PIN tidak sama, coba lagi.", Toast.LENGTH_LONG).show();
                    return;
                }
                adminPin.set(first);
                Toast.makeText(this, "PIN admin tersimpan.", Toast.LENGTH_SHORT).show();
                if (then != null) then.run();
            });
        });
    }

    private interface PinCallback {
        void onPin(String pin);
    }

    private void askPin(String title, PinCallback callback) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setSingleLine(true);
        input.setPadding(56, 40, 56, 40);

        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("OK", (d, w) -> callback.onPin(input.getText().toString().trim()))
            .setNegativeButton("Batal", null)
            .create();

        // Keyboard up at once, so the admin can just type.
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        dialog.show();
        input.requestFocus();
    }

    private void showAdminMenu() {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();

        String printerLabel = printer.isConfigured()
            ? "Pilih printer struk  (" + printer.name() + ")"
            : "Pilih printer struk";

        labels.add(printerLabel);
        actions.add(() -> printer.choosePrinter(true, null));

        labels.add("Tes cetak printer");
        actions.add(() -> {
            if (printer.usesSystemPrint() || !printer.isConfigured()) {
                printer.choosePrinter(false, null);
            } else {
                printer.testPrint();
            }
        });

        labels.add(kitchenPrinter.isConfigured()
            ? "🍳 Pilih printer dapur  (" + kitchenPrinter.name() + ")"
            : "🍳 Pilih printer dapur");
        actions.add(() -> kitchenPrinter.choosePrinter(false, null));

        if (kitchenPrinter.isConfigured()) {
            labels.add("🍳 Tes cetak printer dapur");
            actions.add(kitchenPrinter::testPrint);
        }

        labels.add("Pengaturan WiFi");
        actions.add(() -> kiosk.openWifi(this));

        labels.add("Ganti alamat server");
        actions.add(() -> showUrlDialog(false));

        if (kiosk.isDeviceOwner()) {
            if (kiosk.isEnabled()) {
                labels.add("🔓 Keluar Mode Kiosk (sementara)");
                actions.add(this::confirmKioskOff);
            } else {
                labels.add("🔒 Aktifkan Mode Kiosk");
                actions.add(() -> {
                    kiosk.enable(this);
                    Toast.makeText(this, "Mode kiosk aktif.", Toast.LENGTH_LONG).show();
                });
            }
        }

        labels.add(adminPin.isSet() ? "Ganti PIN admin" : "Buat PIN admin");
        actions.add(() -> createPin(null));

        if (kiosk.isDeviceOwner()) {
            labels.add("⚠ Lepas kiosk permanen");
            actions.add(this::confirmRelease);
        }

        // Inside the kiosk there is nowhere to exit to.
        if (!kiosk.isEnabled()) {
            labels.add("Keluar aplikasi");
            actions.add(this::finish);
        }

        String title = kiosk.isDeviceOwner()
            ? "Menu Admin — Kiosk " + (kiosk.isEnabled() ? "AKTIF" : "NONAKTIF")
            : "Pengaturan";

        new AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
            .setNegativeButton("Tutup", null)
            .show();
    }

    private void confirmKioskOff() {
        new AlertDialog.Builder(this)
            .setTitle("Keluar Mode Kiosk?")
            .setMessage("Tombol Home, Back, dan aplikasi lain akan bisa dipakai lagi.\n\n"
                + "Mode kiosk TIDAK aktif sendiri — setelah maintenance, buka menu "
                + "admin lagi dan pilih \"Aktifkan Mode Kiosk\".")
            .setPositiveButton("Keluar Kiosk", (d, w) -> {
                kiosk.disable(this);
                Toast.makeText(this, "Mode kiosk dimatikan.", Toast.LENGTH_LONG).show();
            })
            .setNegativeButton("Batal", null)
            .show();
    }

    private void confirmRelease() {
        new AlertDialog.Builder(this)
            .setTitle("Lepas kiosk permanen?")
            .setMessage("Aplikasi ini berhenti menjadi pemilik perangkat. Tablet kembali "
                + "normal sepenuhnya.\n\nUntuk memakai mode kiosk lagi, tablet harus "
                + "di-setup ulang lewat ADB (biasanya perlu factory reset).")
            .setPositiveButton("Lepas Permanen", (d, w) -> {
                kiosk.release(this);
                Toast.makeText(this, "Kiosk dilepas. Tablet kembali normal.", Toast.LENGTH_LONG).show();
            })
            .setNegativeButton("Batal", null)
            .show();
    }

    /**
     * Forgotten admin PIN, from a computer the tablet already trusts for ADB:
     *
     *   adb shell am start -n com.conwebs.pos/.MainActivity --ez reset_admin_pin true
     *
     * Clears the PIN; the next admin visit asks for a new one at the tablet.
     */
    private void handleRecoveryIntent(Intent intent) {
        if (intent != null && intent.getBooleanExtra("reset_admin_pin", false)) {
            adminPin.clear();
            intent.removeExtra("reset_admin_pin");
            Toast.makeText(this, "PIN admin dihapus. Buat PIN baru di menu admin.",
                Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleRecoveryIntent(intent);
    }

    // ── Receipt printing ──────────────────────────────────────────────────────

    /**
     * Take over the register's print view.
     *
     * The page would call window.print(), which a WebView silently ignores, or
     * bounce to the RawBT app. Instead the app fetches the ESC/POS version of the
     * same receipt and writes it to the printer itself. Runs on the WebView's
     * network thread, so the fetch may block here.
     */
    private WebResourceResponse interceptReceipt(WebResourceRequest request) {
        if (!"GET".equals(request.getMethod())) return null;

        String kitchenUrl = printUrl(request.getUrl(), KITCHEN_PRINT);
        if (kitchenUrl != null) {
            handleKitchen(kitchenUrl);
            return infoPage();
        }

        String receiptUrl = receiptPrintUrl(request.getUrl());
        if (receiptUrl == null) return null;

        handleReceipt(receiptUrl);

        // Only the register's hidden iframe gets here; the page is never seen.
        return infoPage();
    }

    /**
     * The print view's address without its query, or null for any other page.
     * Our own fetches carry NATIVE_FLAG and are let through.
     */
    private String receiptPrintUrl(Uri uri) {
        return printUrl(uri, RECEIPT_PRINT);
    }

    private String printUrl(Uri uri, Pattern which) {
        String path = uri.getPath();

        if (path == null || !which.matcher(path).find()) return null;
        if (uri.toString().contains(NATIVE_FLAG)) return null;

        return uri.getScheme() + "://" + uri.getAuthority() + path.replaceAll("/+$", "");
    }

    /**
     * Print the kitchen ticket on the kitchen printer. The register asks for it
     * after every sale; an order with no kitchen items comes back without a
     * payload and nothing is printed. Any thread.
     */
    private void handleKitchen(String kitchenUrl) {
        if (!kitchenPrinter.isConfigured()) {
            runOnUiThread(() -> {
                Toast.makeText(this, "Pilih printer dapur dulu.", Toast.LENGTH_LONG).show();
                // Raw ticket bytes cannot go through Android's print dialog.
                kitchenPrinter.choosePrinter(false, () -> handleKitchen(kitchenUrl));
            });
            return;
        }

        if (!kitchenPrinter.begin()) return;

        new Thread(() -> {
            try {
                long t0 = System.currentTimeMillis();
                byte[] data = fetchPayload(kitchenUrl + "?" + NATIVE_FLAG, "<title>Dapur");
                kitchenPrinter.fetched(System.currentTimeMillis() - t0);

                if (data == null) {
                    kitchenPrinter.skip();
                } else {
                    kitchenPrinter.print(data);
                }
            } catch (Exception e) {
                kitchenPrinter.abort("Gagal mengambil tiket dapur: " + e.getMessage());
            }
        }, "kitchen-fetch").start();
    }

    /** Print a receipt, asking which printer first if none is chosen yet. Any thread. */
    private void handleReceipt(String receiptUrl) {
        if (!printer.isConfigured()) {
            runOnUiThread(() -> printer.choosePrinter(true, () -> handleReceipt(receiptUrl)));
            return;
        }

        if (printer.usesSystemPrint()) {
            printReceiptUrl(receiptUrl);
            return;
        }

        // Shows "Menyiapkan struk…" right away; false means one is already printing.
        if (printer.begin()) {
            printReceiptUrl(receiptUrl);
        }
    }

    /** Print one receipt by its print-view URL, with whichever printer is chosen. */
    private void printReceiptUrl(String receiptUrl) {
        if (printer.usesSystemPrint()) {
            runOnUiThread(() -> {
                Toast.makeText(this, "🖨 Membuka dialog cetak…", Toast.LENGTH_SHORT).show();
                systemPrint(receiptUrl + "?via=browser&" + NATIVE_FLAG);
            });
            return;
        }

        byte[] ready = receiptCache.remove(receiptUrl);
        if (ready != null) {
            printer.fetched(0);
            printer.print(ready);
            return;
        }

        new Thread(() -> {
            try {
                long t0 = System.currentTimeMillis();
                byte[] data = fetchEscPos(receiptUrl + "?via=rawbt&" + NATIVE_FLAG);
                printer.fetched(System.currentTimeMillis() - t0);
                printer.print(data);
            } catch (Exception e) {
                printer.abort("Gagal mengambil struk dari server: " + e.getMessage());
            }
        }, "receipt-fetch").start();
    }

    private byte[] fetchEscPos(String url) throws Exception {
        return fetchPayload(url, null);
    }

    /**
     * The ESC/POS payload embedded in a print page.
     *
     * @param emptyMarker text that proves the page is the right one even with no
     *                    payload (a kitchen ticket for an order without food);
     *                    then null is returned instead of an error. Without it,
     *                    a missing payload means the session expired and the
     *                    server sent the login page.
     */
    private byte[] fetchPayload(String url, String emptyMarker) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("User-Agent", userAgent);

        String cookie = CookieManager.getInstance().getCookie(url);
        if (cookie != null) conn.setRequestProperty("Cookie", cookie);

        int status = conn.getResponseCode();
        if (status == 401 || status == 419) {
            conn.disconnect();
            throw new Exception("sesi login habis, silakan login ulang");
        }
        if (status != HttpURLConnection.HTTP_OK) {
            conn.disconnect();
            throw new Exception("server menjawab kode " + status);
        }

        try (InputStream in = conn.getInputStream()) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) != -1) buf.write(chunk, 0, n);

            String body = buf.toString("UTF-8");
            Matcher m = RAWBT_PAYLOAD.matcher(body);
            if (!m.find()) {
                if (emptyMarker != null && body.contains(emptyMarker)) return null;
                throw new Exception("data struk tidak ditemukan (sesi habis?)");
            }

            return Base64.decode(m.group(1), Base64.DEFAULT);
        }
        // No disconnect(): with the body read to the end, the connection goes
        // back to the keep-alive pool, so the next receipt skips the TCP and
        // TLS handshakes (several hundred ms on a shop's Wi-Fi).
    }

    /** Receipts fetched ahead of the tap, by print-view URL. */
    private final Map<String, byte[]> receiptCache = new ConcurrentHashMap<>();

    /**
     * Fetch the receipt the moment the sale succeeds, while the cashier is
     * still looking at the success screen, so the Cetak tap only has to write
     * to the printer.
     */
    private void prefetchReceipt(String receiptShowUrl) {
        if (!printer.isConfigured() || printer.usesSystemPrint()) return;

        String printUrl = receiptPrintUrl(Uri.parse(receiptShowUrl.replaceAll("/+$", "") + "/print"));
        if (printUrl == null) return;

        printer.warmUp();

        new Thread(() -> {
            try {
                byte[] data = fetchEscPos(printUrl + "?via=rawbt&" + NATIVE_FLAG);
                if (receiptCache.size() > 10) receiptCache.clear();
                receiptCache.put(printUrl, data);
            } catch (Exception ignored) {
                // The tap fetches it again and reports any error then.
            }
        }, "receipt-prefetch").start();
    }

    /** A rawbt: link from any page — print it ourselves, no RawBT needed. */
    private void printRawbtLink(String link) {
        Matcher m = RAWBT_PAYLOAD.matcher(Uri.decode(link));
        if (!m.find()) return;

        byte[] data = Base64.decode(m.group(1), Base64.DEFAULT);

        if (!printer.isConfigured() || printer.usesSystemPrint()) {
            // Raw printer bytes cannot go through Android's print dialog.
            printer.choosePrinter(false, () -> printer.print(data));
        } else {
            printer.print(data);
        }
    }

    /** Android's own print dialog — office printers, WiFi printers, Save as PDF. */
    private void systemPrint(String url) {
        printView = new WebView(this);
        printView.getSettings().setJavaScriptEnabled(true);
        printView.setWebViewClient(new WebViewClient() {
            /** onPageFinished can fire more than once; one dialog per receipt. */
            private boolean started;

            @Override
            public void onPageFinished(WebView view, String loaded) {
                if (started) return;
                started = true;
                PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
                if (pm != null) {
                    String job = "Struk POS";
                    pm.print(job, view.createPrintDocumentAdapter(job), null);
                }
            }
        });
        printView.loadUrl(url);
    }

    private WebResourceResponse infoPage() {
        String html = "<!doctype html><title>Struk</title>";

        return new WebResourceResponse("text/html", "utf-8",
            new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
    }

    /** Reached from page JavaScript as window.PosApp. */
    private class PosBridge {
        @JavascriptInterface
        public void choosePrinter() {
            runOnUiThread(() -> printer.choosePrinter(true, null));
        }

        /** Called by the register as soon as a sale succeeds. */
        @JavascriptInterface
        public void prefetchReceipt(String receiptShowUrl) {
            if (receiptShowUrl != null && !receiptShowUrl.isEmpty()) {
                MainActivity.this.prefetchReceipt(receiptShowUrl);
            }
        }

        /** window.print() on any page, e.g. product labels — the WebView itself ignores it. */
        @JavascriptInterface
        public void printPage() {
            runOnUiThread(() -> {
                PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
                if (pm != null) {
                    String job = "POS Kasir";
                    pm.print(job, webView.createPrintDocumentAdapter(job), null);
                }
            });
        }
    }

    // ── WebView ───────────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setTextZoom(100);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMediaPlaybackRequiresUserGesture(false);
        // The receipt opens with target="_blank"; without this the tap does nothing.
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setUserAgentString(s.getUserAgentString() + " POSKasirApp/2.7");
        userAgent = s.getUserAgentString();

        webView.addJavascriptInterface(new PosBridge(), "PosApp");

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, true);

        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                loadingLayout.setVisibility(View.GONE);
                errorLayout.setVisibility(View.GONE);
                webView.setVisibility(View.VISIBLE);
                CookieManager.getInstance().flush();
                // A WebView drops window.print() on the floor; send it to Android's dialog.
                view.evaluateJavascript(
                    "window.print = function () { PosApp.printPage(); };", null);
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return interceptReceipt(request);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    webView.setVisibility(View.GONE);
                    loadingLayout.setVisibility(View.GONE);
                    errorLayout.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();

                if ("rawbt".equals(scheme)) {
                    printRawbtLink(uri.toString());
                    return true;
                }

                // RawBT mode sends the whole register to the print view. Print
                // from here and stay put, so the cashier never leaves the till.
                String receiptUrl = receiptPrintUrl(uri);
                if (receiptUrl != null) {
                    handleReceipt(receiptUrl);
                    return true;
                }

                String kitchenUrl = printUrl(uri, KITCHEN_PRINT);
                if (kitchenUrl != null) {
                    handleKitchen(kitchenUrl);
                    return true;
                }

                if ("intent".equals(scheme)) {
                    try {
                        startActivity(Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME));
                    } catch (Exception ignored) {
                    }
                    return true;
                }

                // Hand phone and mail links to the system; keep everything else
                // inside the app so the session is never lost mid-transaction.
                if ("tel".equals(scheme) || "mailto".equals(scheme) || "whatsapp".equals(scheme)) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, uri));
                        return true;
                    } catch (Exception ignored) {
                        return true;
                    }
                }

                return false;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            /**
             * Route target="_blank" — the receipt and print views — back into the
             * main WebView instead of dropping the tap on the floor.
             */
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog,
                                          boolean isUserGesture, Message resultMsg) {
                WebView proxy = new WebView(view.getContext());
                proxy.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                        // "Cetak" on the receipt page opens the print view in a
                        // new tab; print it instead of swapping the page out.
                        String receiptUrl = receiptPrintUrl(request.getUrl());
                        String kitchenUrl = printUrl(request.getUrl(), KITCHEN_PRINT);
                        if (receiptUrl != null) {
                            handleReceipt(receiptUrl);
                        } else if (kitchenUrl != null) {
                            handleKitchen(kitchenUrl);
                        } else {
                            webView.loadUrl(request.getUrl().toString());
                        }
                        return true;
                    }
                });

                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(proxy);
                resultMsg.sendToTarget();

                return true;
            }

            /** Lets the product image picker work when the owner uses the tablet. */
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileChooserCallback != null) {
                    fileChooserCallback.onReceiveValue(null);
                }

                fileChooserCallback = callback;

                try {
                    startActivityForResult(params.createIntent(), FILE_CHOOSER_REQUEST);
                } catch (Exception e) {
                    fileChooserCallback = null;
                    Toast.makeText(MainActivity.this, "Tidak ada aplikasi pemilih berkas.",
                        Toast.LENGTH_SHORT).show();
                    return false;
                }

                return true;
            }
        });

        // Receipt PDFs and report exports arrive as downloads.
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            try {
                String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);

                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                request.setMimeType(mimeType);
                request.addRequestHeader("User-Agent", userAgent);
                // Carry the session across, otherwise the server answers with the
                // login page and the file saves as HTML.
                request.addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url));
                request.setDescription("Mengunduh " + fileName);
                request.setTitle(fileName);
                request.allowScanningByMediaScanner();
                request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);

                DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);

                if (dm != null) {
                    dm.enqueue(request);
                    Toast.makeText(this, "Mengunduh " + fileName + " ke folder Download",
                        Toast.LENGTH_LONG).show();
                }
            } catch (Exception e) {
                Toast.makeText(this, "Gagal mengunduh berkas.", Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_REQUEST) {
            if (fileChooserCallback != null) {
                fileChooserCallback.onReceiveValue(
                    WebChromeClient.FileChooserParams.parseResult(resultCode, data));
                fileChooserCallback = null;
            }
            return;
        }

        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == ReceiptPrinter.PERMISSION_REQUEST) {
            printer.onPermissionResult(grantResults);
            kitchenPrinter.onPermissionResult(grantResults);
            return;
        }

        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    // ── Back button ───────────────────────────────────────────────────────────

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode != KeyEvent.KEYCODE_BACK) {
            return super.onKeyDown(keyCode, event);
        }

        if (webView.canGoBack()) {
            webView.goBack();
            return true;
        }

        // The register is the whole tablet; Back has nowhere to go.
        if (kiosk.isEnabled()) {
            return true;
        }

        // Two presses to leave: a stray tap must not close the register in the
        // middle of a queue.
        long now = System.currentTimeMillis();

        if (now - lastBackPress < EXIT_WINDOW_MS) {
            finish();
        } else {
            lastBackPress = now;
            Toast.makeText(this, "Tekan sekali lagi untuk keluar aplikasi", Toast.LENGTH_SHORT).show();
        }

        return true;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onPause() {
        super.onPause();
        // Persist the session cookie now; Android may kill the app at any point.
        CookieManager.getInstance().flush();
        printer.stopKeepAlive();
        kitchenPrinter.stopKeepAlive();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUI();
        printer.warmUp();
        kitchenPrinter.warmUp();
        printer.startKeepAlive();
        kitchenPrinter.startKeepAlive();
        // Re-pin after a reboot, after Wi-Fi settings, or whenever Android let go.
        kiosk.enforce(this);
    }

    @Override
    protected void onDestroy() {
        new Thread(printer::disconnect, "printer-close").start();
        new Thread(kitchenPrinter::disconnect, "kitchen-printer-close").start();
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUI();
    }

    private void hideSystemUI() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_FULLSCREEN
        );
    }
}
