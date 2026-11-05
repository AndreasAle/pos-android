package com.conwebs.pos;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sends ESC/POS bytes straight to a thermal receipt printer.
 *
 * The server already renders every receipt as ESC/POS (the bytes it used to hand
 * to the RawBT app), and nearly every 58/80 mm printer speaks it — over
 * Bluetooth serial or raw TCP on port 9100. So the app needs no driver per
 * brand: it only has to open the socket and write.
 */
class ReceiptPrinter {

    static final String TYPE_BLUETOOTH = "bt";
    static final String TYPE_NETWORK   = "lan";
    static final String TYPE_SYSTEM    = "system";

    static final int PERMISSION_REQUEST = 2001;

    private static final String PREFS     = "POSPrinter";
    private static final String KEY_TYPE  = "type";
    private static final String KEY_ADDR  = "address";
    private static final String KEY_NAME  = "name";
    /** Per printer address: which {@link #openSocket} method connected last time. */
    private static final String KEY_BT_METHOD = "bt_method_";

    /** Serial Port Profile — what Bluetooth receipt printers expose. */
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private static final int NETWORK_PORT       = 9100;
    private static final int CONNECT_TIMEOUT_MS = 5000;

    /** Cheap printers drop the tail of the job if the socket closes at once. */
    private static final long DRAIN_MS = 600;

    /** Close the kept-open Bluetooth link after this long without a receipt. */
    private static final long IDLE_CLOSE_MS = 15 * 60 * 1000;

    /** Where the cashier sees what the printer is doing; always called on the UI thread. */
    interface Status {
        /** A step is under way. */
        void progress(String text);

        /** The job is over: a short confirmation, or null to just clear. */
        void finished(String text);
    }

    private final Activity activity;
    private final SharedPreferences prefs;
    private final Status status;

    /** Run once Bluetooth permission comes back granted. */
    private Runnable afterPermission;

    /** Only one job at a time, so two taps never interleave on the paper. */
    private final Object lock = new Object();

    /** A receipt is between the tap and the paper; further taps are ignored. */
    private final AtomicBoolean busy = new AtomicBoolean(false);

    /**
     * The link stays open between receipts: opening an RFCOMM socket is what
     * costs the seconds, so only the first receipt after a pause pays for it.
     * Guarded by {@link #lock}.
     */
    private BluetoothSocket btSocket;
    private String btSocketAddress;

    /** For the timing shown after each receipt. */
    private volatile long jobStarted;
    private volatile long fetchMs = -1;
    private volatile long lastConnectMs = -1;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable idleClose = () -> new Thread(this::disconnect, "printer-idle").start();

    /**
     * Which printer this is. The register can drive two at once — receipts at
     * the counter and tickets in the kitchen — each with its own saved choice
     * and its own open link.
     */
    enum Role {
        RECEIPT(PREFS,             "Printer Struk", "Struk",       "🖨"),
        KITCHEN("POSPrinterKitchen", "Printer Dapur", "Tiket dapur", "🍳");

        final String prefsName;
        final String title;
        final String what;
        final String icon;

        Role(String prefsName, String title, String what, String icon) {
            this.prefsName = prefsName;
            this.title     = title;
            this.what      = what;
            this.icon      = icon;
        }
    }

    final Role role;

    ReceiptPrinter(Activity activity, Status status, Role role) {
        this.activity = activity;
        this.role     = role;
        this.prefs    = activity.getSharedPreferences(role.prefsName, Activity.MODE_PRIVATE);
        this.status   = status;
    }

    String type()    { return prefs.getString(KEY_TYPE, null); }
    String address() { return prefs.getString(KEY_ADDR, null); }
    String name()    { return prefs.getString(KEY_NAME, null); }

    boolean isConfigured() { return type() != null; }

    boolean usesSystemPrint() { return TYPE_SYSTEM.equals(type()); }

    // ── Printing ──────────────────────────────────────────────────────────────

    /**
     * Claim the printer for one receipt and tell the cashier at once. False
     * when a receipt is already on its way — the tap is then ignored.
     */
    boolean begin() {
        if (!busy.compareAndSet(false, true)) {
            activity.runOnUiThread(() ->
                Toast.makeText(activity, role.what + " sebelumnya masih dicetak…", Toast.LENGTH_SHORT).show());
            return false;
        }

        jobStarted    = System.currentTimeMillis();
        fetchMs       = -1;
        lastConnectMs = -1;

        activity.runOnUiThread(() -> status.progress(role.icon + " Menyiapkan " + role.what.toLowerCase() + "…"));

        // Connect while the receipt is still being fetched from the server,
        // instead of one after the other.
        warmUp();
        return true;
    }

    /** How long the receipt took to come from the server, for the timing note. */
    void fetched(long ms) {
        fetchMs = ms;
    }

    /** "✓ Struk tercetak · 1,2 dtk (server 0,4 · printer 0,7)" */
    private String doneText() {
        if (jobStarted == 0) return "✓ " + role.what + " tercetak";

        StringBuilder s = new StringBuilder("✓ " + role.what + " tercetak · ")
            .append(seconds(System.currentTimeMillis() - jobStarted)).append(" dtk");

        if (fetchMs >= 0 || lastConnectMs >= 0) {
            s.append("  (");
            if (fetchMs >= 0) s.append("server ").append(seconds(fetchMs));
            if (fetchMs >= 0 && lastConnectMs >= 0) s.append(" · ");
            if (lastConnectMs >= 0) s.append("sambung printer ").append(seconds(lastConnectMs));
            s.append(")");
        }

        jobStarted = 0;
        return s.toString();
    }

    private static String seconds(long ms) {
        return String.format(java.util.Locale.ROOT, "%.1f", ms / 1000.0).replace('.', ',');
    }

    /** End a job that never reached the printer, e.g. the server fetch failed. */
    void abort(String message) {
        busy.set(false);
        activity.runOnUiThread(() -> {
            status.finished(null);
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
        });
    }

    /** Print on a background thread; errors come back as a dialog with a retry. */
    void print(byte[] data) {
        if (!isConfigured() || usesSystemPrint()) {
            busy.set(false);
            activity.runOnUiThread(() -> status.finished(null));
            return;
        }

        busy.set(true);
        activity.runOnUiThread(() -> status.progress(role.icon + " Mencetak ke " + name() + "…"));

        new Thread(() -> {
            try {
                synchronized (lock) {
                    send(data);
                }
                busy.set(false);
                String done = doneText();
                activity.runOnUiThread(() -> status.finished(done));
            } catch (Exception e) {
                busy.set(false);
                activity.runOnUiThread(() -> {
                    status.finished(null);
                    showFailure(data, e);
                });
            }
        }, "receipt-printer").start();
    }

    /**
     * Open the Bluetooth link ahead of the first receipt, so it prints as fast
     * as the ones after it. Silent: a printer that is off just gets connected
     * later, when a receipt actually needs it.
     */
    void warmUp() {
        if (!TYPE_BLUETOOTH.equals(type()) || !hasBluetoothPermission()) return;

        new Thread(() -> {
            synchronized (lock) {
                try {
                    bluetoothSocket();
                    scheduleIdleClose();
                } catch (IOException ignored) {
                }
            }
        }, "printer-warmup").start();
    }

    /** Drop the kept-open link: printer changed, app closing, or idle too long. */
    void disconnect() {
        synchronized (lock) {
            closeQuietly(btSocket);
            btSocket = null;
            btSocketAddress = null;
        }
    }

    private void scheduleIdleClose() {
        handler.removeCallbacks(idleClose);
        handler.postDelayed(idleClose, IDLE_CLOSE_MS);
    }

    private void send(byte[] data) throws IOException {
        if (TYPE_BLUETOOTH.equals(type())) {
            sendBluetooth(data);
        } else if (TYPE_NETWORK.equals(type())) {
            sendNetwork(data);
        }
    }

    /** Write over the kept-open link, reconnecting once if the printer dropped it. */
    private void sendBluetooth(byte[] data) throws IOException {
        boolean reused = btSocket != null && address().equals(btSocketAddress);

        try {
            write(bluetoothSocket(), data);
        } catch (IOException e) {
            disconnect();
            // A link from earlier may have died while idle (printer switched
            // off and on). A fresh one that fails is a real failure.
            if (!reused) throw e;
            write(bluetoothSocket(), data);
        }

        scheduleIdleClose();
    }

    private void write(BluetoothSocket socket, byte[] data) throws IOException {
        OutputStream out = socket.getOutputStream();
        out.write(data);
        out.flush();
    }

    /** The open link to the chosen printer, connecting if there is none. Holds {@link #lock}. */
    @SuppressLint("MissingPermission")
    private BluetoothSocket bluetoothSocket() throws IOException {
        if (btSocket != null && btSocket.isConnected() && address().equals(btSocketAddress)) {
            return btSocket;
        }

        closeQuietly(btSocket);
        btSocket = null;

        if (!hasBluetoothPermission()) {
            throw new IOException("Izin Bluetooth belum diberikan");
        }

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();

        if (adapter == null) {
            throw new IOException("Tablet ini tidak punya Bluetooth");
        }
        if (!adapter.isEnabled()) {
            throw new IOException("Bluetooth tablet sedang mati");
        }

        BluetoothDevice device = adapter.getRemoteDevice(address());
        // We never scan, so this only matters if some other app is. On Android 12+
        // it needs BLUETOOTH_SCAN, which we don't ask for — skip it then.
        try {
            adapter.cancelDiscovery();
        } catch (SecurityException ignored) {
        }

        long started = System.currentTimeMillis();

        // A way that fails can take several seconds to time out, so try the one
        // that worked for this printer last time before the others.
        int remembered = prefs.getInt(KEY_BT_METHOD + address(), -1);
        int[] order = remembered >= 0
            ? new int[]{remembered, 0, 1, 2}
            : new int[]{0, 1, 2};

        boolean[] tried = new boolean[3];

        for (int method : order) {
            if (tried[method]) continue;
            tried[method] = true;

            BluetoothSocket socket = null;
            try {
                socket = openSocket(device, method);
                socket.connect();

                if (method != remembered) {
                    prefs.edit().putInt(KEY_BT_METHOD + address(), method).apply();
                }

                lastConnectMs = System.currentTimeMillis() - started;
                btSocket = socket;
                btSocketAddress = address();
                return socket;
            } catch (Exception e) {
                closeQuietly(socket);
            }
        }

        throw new IOException("Printer tidak menjawab — pastikan menyala dan dekat tablet");
    }

    /**
     * The three ways receipt printers accept a serial link, cheapest first:
     * 0 — insecure SPP: no pairing-key handshake, what most thermal printers expect;
     * 1 — secure SPP: the textbook way;
     * 2 — raw channel 1: for printers that publish no SDP record at all.
     */
    @SuppressLint("MissingPermission")
    private BluetoothSocket openSocket(BluetoothDevice device, int method) throws Exception {
        switch (method) {
            case 0:  return device.createInsecureRfcommSocketToServiceRecord(SPP_UUID);
            case 1:  return device.createRfcommSocketToServiceRecord(SPP_UUID);
            default:
                Method m = device.getClass().getMethod("createRfcommSocket", int.class);
                return (BluetoothSocket) m.invoke(device, 1);
        }
    }

    private void sendNetwork(byte[] data) throws IOException {
        String[] parts = address().split(":");
        int port = parts.length > 1 ? Integer.parseInt(parts[1]) : NETWORK_PORT;

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(parts[0], port), CONNECT_TIMEOUT_MS);
            OutputStream out = socket.getOutputStream();
            out.write(data);
            out.flush();
            drain(data.length);
        } catch (IOException e) {
            throw new IOException("Printer " + address() + " tidak terjangkau — cek WiFi dan IP printer");
        }
    }

    /**
     * flush() only hands bytes to the network stack. Wait in proportion before
     * closing, or cheap printers lose the tail of a long receipt. (The Bluetooth
     * link stays open, so it needs no wait.)
     */
    private void drain(int bytes) {
        try {
            Thread.sleep(Math.min(DRAIN_MS + bytes / 4, 5000));
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void closeQuietly(BluetoothSocket socket) {
        if (socket == null) return;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private void showFailure(byte[] data, Exception e) {
        new AlertDialog.Builder(activity)
            .setTitle(role.what + " gagal dicetak")
            .setMessage(e.getMessage() + "\n\nPrinter: " + name())
            .setPositiveButton("Coba Lagi", (d, w) -> print(data))
            .setNeutralButton("Ganti Printer", (d, w) -> choosePrinter(false, () -> print(data)))
            .setNegativeButton("Tutup", null)
            .show();
    }

    // ── Choosing a printer ────────────────────────────────────────────────────

    /**
     * Ask how the receipt should go out; {@code then} runs once one is saved.
     *
     * @param allowSystem offer Android's print dialog — not when the caller holds
     *                    raw ESC/POS bytes, which that dialog cannot print
     */
    void choosePrinter(boolean allowSystem, Runnable then) {
        String current = isConfigured() ? " (sekarang: " + name() + ")" : "";

        String[] options = allowSystem
            ? new String[]{
                "Printer Bluetooth",
                "Printer WiFi / LAN (IP)",
                "Printer sistem Android (dialog cetak)",
            }
            : new String[]{
                "Printer Bluetooth",
                "Printer WiFi / LAN (IP)",
            };

        new AlertDialog.Builder(activity)
            .setTitle("Pilih " + role.title + current)
            .setItems(options, (d, which) -> {
                if (which == 0) chooseBluetooth(then);
                else if (which == 1) chooseNetwork(then);
                else {
                    save(TYPE_SYSTEM, "", "Printer sistem Android");
                    if (then != null) then.run();
                }
            })
            .setNegativeButton("Batal", null)
            .show();
    }

    @SuppressLint("MissingPermission")
    private void chooseBluetooth(Runnable then) {
        if (!hasBluetoothPermission()) {
            afterPermission = () -> chooseBluetooth(then);
            activity.requestPermissions(
                new String[]{Manifest.permission.BLUETOOTH_CONNECT}, PERMISSION_REQUEST);
            return;
        }

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();

        if (adapter == null) {
            Toast.makeText(activity, "Tablet ini tidak punya Bluetooth.", Toast.LENGTH_LONG).show();
            return;
        }
        if (!adapter.isEnabled()) {
            Toast.makeText(activity, "Nyalakan Bluetooth tablet dulu.", Toast.LENGTH_LONG).show();
            return;
        }

        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        List<BluetoothDevice> devices = new ArrayList<>(bonded);

        if (devices.isEmpty()) {
            new AlertDialog.Builder(activity)
                .setTitle("Belum ada printer terpasang")
                .setMessage("Nyalakan printer, lalu pasangkan (pair) lewat Setelan → Bluetooth "
                    + "di tablet. PIN printer biasanya 0000 atau 1234.\n\n"
                    + "Setelah itu kembali ke sini dan pilih printernya.")
                .setPositiveButton("OK", null)
                .show();
            return;
        }

        String[] labels = new String[devices.size()];
        for (int i = 0; i < devices.size(); i++) {
            String n = devices.get(i).getName();
            labels[i] = (n == null ? "(tanpa nama)" : n) + "\n" + devices.get(i).getAddress();
        }

        new AlertDialog.Builder(activity)
            .setTitle("Pilih printer Bluetooth")
            .setItems(labels, (d, which) -> {
                BluetoothDevice dev = devices.get(which);
                String n = dev.getName() == null ? dev.getAddress() : dev.getName();
                save(TYPE_BLUETOOTH, dev.getAddress(), n);
                // A receipt waiting to print is test enough.
                if (then != null) then.run();
                else testPrint();
            })
            .setNegativeButton("Batal", null)
            .show();
    }

    private void chooseNetwork(Runnable then) {
        EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("192.168.1.100");
        input.setPadding(56, 40, 56, 40);

        if (TYPE_NETWORK.equals(type())) {
            input.setText(address());
        }

        new AlertDialog.Builder(activity)
            .setTitle("Printer WiFi / LAN")
            .setMessage("Masukkan IP printer (lihat hasil self-test printer).\n"
                + "Port 9100 dipakai otomatis; tulis 192.168.1.100:9101 kalau beda.")
            .setView(input)
            .setPositiveButton("Simpan", (d, w) -> {
                String addr = normaliseNetworkAddress(input.getText().toString());
                if (addr == null) {
                    Toast.makeText(activity, "IP printer tidak valid. Contoh: 192.168.1.100",
                        Toast.LENGTH_LONG).show();
                    return;
                }
                save(TYPE_NETWORK, addr, "Printer LAN " + addr);
                if (then != null) then.run();
                else testPrint();
            })
            .setNegativeButton("Batal", null)
            .show();
    }

    /** "http://192.168.1.100/ " → "192.168.1.100"; null when there is no usable host or port. */
    private static String normaliseNetworkAddress(String input) {
        String addr = input.trim()
            .replaceFirst("^[a-zA-Z]+://", "")
            .replaceAll("/.*$", "");

        if (addr.isEmpty() || addr.contains(" ")) return null;

        String[] parts = addr.split(":");
        if (parts.length > 2) return null;

        if (parts.length == 2) {
            try {
                int port = Integer.parseInt(parts[1]);
                if (port < 1 || port > 65535) return null;
            } catch (NumberFormatException e) {
                return null;
            }
        }

        return addr;
    }

    private void save(String type, String address, String name) {
        // No need to drop the old link here: bluetoothSocket() sees the address
        // changed and reconnects. Closing it from another thread could cut off
        // a receipt still on its way.
        prefs.edit()
            .putString(KEY_TYPE, type)
            .putString(KEY_ADDR, address)
            .putString(KEY_NAME, name)
            .apply();
        Toast.makeText(activity, role.title + ": " + name, Toast.LENGTH_SHORT).show();
    }

    /** A short slip so the cashier knows at once whether the pairing works. */
    void testPrint() {
        String text = "\u001b@"           // initialise
            + "\u001ba\u0001"             // centre
            + role.title.toUpperCase() + "\n"
            + "Tes printer berhasil\n"
            + name() + "\n\n\n\n"
            + "\u001dV\u0001";            // partial cut, ignored by printers without a cutter
        print(text.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    // ── Permission ────────────────────────────────────────────────────────────

    /** Android 12 made Bluetooth a runtime permission; before that it is install-time. */
    private boolean hasBluetoothPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
            || activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
               == PackageManager.PERMISSION_GRANTED;
    }

    /** Both printers share the request code; only the one that asked reacts. */
    void onPermissionResult(int[] grantResults) {
        Runnable next = afterPermission;
        if (next == null) return;
        afterPermission = null;

        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            next.run();
        } else {
            Toast.makeText(activity, "Tanpa izin Bluetooth, " + role.what.toLowerCase()
                + " tidak bisa dicetak.", Toast.LENGTH_LONG).show();
        }
    }

    /** End a job quietly: the order turned out to have nothing to print. */
    void skip() {
        busy.set(false);
        jobStarted = 0;
        activity.runOnUiThread(() -> status.finished(null));
    }
}
