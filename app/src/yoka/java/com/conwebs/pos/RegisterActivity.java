package com.conwebs.pos;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Dns;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Create an owner account without scrolling a web page.
 *
 * It talks to the same endpoint as the website's register form (POST /register,
 * same fields, same validation), so accounts, e-mail verification and the trial
 * stay entirely server-side. On success the new login session is handed to the
 * WebView and the app carries on at the e-mail verification page.
 */
public class RegisterActivity extends Activity {

    private static final Pattern TOKEN = Pattern.compile("name=\"_token\"\\s+value=\"([^\"]+)\"");

    /** IPv4 first, like MainActivity: an advertised-but-dead IPv6 route must not stall sign-up. */
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
        .dns(host -> {
            List<InetAddress> all = new ArrayList<>(Dns.SYSTEM.lookup(host));
            all.sort((a, b) -> Boolean.compare(a instanceof Inet6Address, b instanceof Inet6Address));
            return all;
        })
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build();

    private EditText name, phone, email, password;
    private TextView nameError, phoneError, emailError, passwordError, formError, toggle;
    private Button   submit;
    private boolean  busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        setContentView(R.layout.activity_register);
        YokaFlow.hideSystemUi(this);

        name     = findViewById(R.id.name);
        phone    = findViewById(R.id.phone);
        email    = findViewById(R.id.email);
        password = findViewById(R.id.password);
        nameError     = findViewById(R.id.nameError);
        phoneError    = findViewById(R.id.phoneError);
        emailError    = findViewById(R.id.emailError);
        passwordError = findViewById(R.id.passwordError);
        formError     = findViewById(R.id.formError);
        toggle        = findViewById(R.id.togglePassword);
        submit        = findViewById(R.id.submit);

        findViewById(R.id.back).setOnClickListener(v -> finish());
        findViewById(R.id.haveAccount).setOnClickListener(v -> {
            YokaFlow.openPage(this, YokaFlow.PATH_LOGIN);
            finish();
        });

        toggle.setOnClickListener(v -> {
            boolean show = password.getInputType() == (InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            password.setInputType(InputType.TYPE_CLASS_TEXT | (show
                ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                : InputType.TYPE_TEXT_VARIATION_PASSWORD));
            password.setTypeface(android.graphics.Typeface.DEFAULT);
            password.setSelection(password.getText().length());
            toggle.setText(show ? "Sembunyikan" : "Tampilkan");
        });

        // A password field falls back to monospace; keep it in the form's font.
        password.setTypeface(android.graphics.Typeface.DEFAULT);
        password.setImeOptions(EditorInfo.IME_ACTION_DONE);
        password.setOnEditorActionListener((v, actionId, e) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit();
                return true;
            }
            return false;
        });
        submit.setOnClickListener(v -> submit());

        buildLegalText();
    }

    private void buildLegalText() {
        String lead  = "Dengan mendaftar, Anda menyetujui ";
        String terms = "Syarat Layanan";
        String mid   = " dan ";
        String priv  = "Kebijakan Privasi";

        SpannableString s = new SpannableString(lead + terms + mid + priv);
        int a = lead.length();
        int b = a + terms.length() + mid.length();

        s.setSpan(link(YokaFlow.PATH_TERMS), a, a + terms.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(link(YokaFlow.PATH_PRIVACY), b, b + priv.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        TextView legal = findViewById(R.id.legal);
        legal.setText(s);
        legal.setMovementMethod(LinkMovementMethod.getInstance());
        legal.setHighlightColor(0);
    }

    private ClickableSpan link(String path) {
        return new ClickableSpan() {
            @Override
            public void onClick(View widget) {
                YokaFlow.openPage(RegisterActivity.this, path);
            }
        };
    }

    // ── Submit ────────────────────────────────────────────────────────────────

    private void submit() {
        if (busy) return;
        clearErrors();

        final String n = name.getText().toString().trim();
        final String e = email.getText().toString().trim();
        final String p = password.getText().toString();
        String digits  = phone.getText().toString().replaceAll("\\D", "").replaceFirst("^0+", "");

        boolean ok = true;
        if (n.isEmpty())                      { fieldError(nameError, name, "Nama wajib diisi."); ok = false; }
        if (digits.length() < 8)              { fieldError(phoneError, null, "Nomor HP belum lengkap."); ok = false; }
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(e).matches()) {
            fieldError(emailError, email, "Email tidak valid."); ok = false;
        }
        if (p.length() < 8)                   { fieldError(passwordError, password, "Password minimal 8 karakter."); ok = false; }
        if (!ok) return;

        setBusy(true);
        // The site stores the number with a leading 0, like its own form does.
        final String phoneValue = "0" + digits;

        new Thread(() -> {
            Result r;
            try {
                r = register(n, phoneValue, e, p);
            } catch (IOException ex) {
                r = Result.failure("Tidak dapat terhubung ke server. Periksa koneksi internet lalu coba lagi.");
            } catch (Exception ex) {
                r = Result.failure("Pendaftaran gagal. Coba lagi sebentar lagi.");
            }
            final Result result = r;
            runOnUiThread(() -> finished(result));
        }, "register").start();
    }

    private static class Result {
        boolean ok;
        String  general;
        Map<String, String> fields = new LinkedHashMap<>();
        Map<String, String> cookies;

        static Result failure(String message) {
            Result r = new Result();
            r.general = message;
            return r;
        }
    }

    /** GET /register for the CSRF token and session, then POST the form as JSON-expecting. */
    private Result register(String n, String phoneValue, String e, String p) throws Exception {
        String base = BuildConfig.DEFAULT_URL;
        String ua   = System.getProperty("http.agent") + " YokaPOSApp/" + BuildConfig.VERSION_NAME;
        Map<String, String> jar = new LinkedHashMap<>();

        String token;
        Request get = new Request.Builder().url(base + "/register")
            .header("User-Agent", ua).header("Accept", "text/html").build();
        try (Response res = HTTP.newCall(get).execute()) {
            collect(res, jar);
            if (res.isRedirect()) {
                // Already signed in on this device: nothing to create.
                Result done = new Result();
                done.ok = true;
                done.cookies = jar;
                return done;
            }
            String html = res.body() != null ? res.body().string() : "";
            Matcher m = TOKEN.matcher(html);
            if (!m.find()) return Result.failure("Pendaftaran belum bisa dimulai. Coba lagi.");
            token = m.group(1);
        }

        FormBody form = new FormBody.Builder()
            .add("_token", token)
            .add("name", n)
            .add("phone", phoneValue)
            .add("email", e)
            .add("password", p)
            .add("password_confirmation", p)
            .build();

        Request post = new Request.Builder().url(base + "/register").post(form)
            .header("User-Agent", ua)
            .header("Accept", "application/json")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", base + "/register")
            .header("Cookie", cookieHeader(jar))
            .build();

        try (Response res = HTTP.newCall(post).execute()) {
            collect(res, jar);
            int code = res.code();
            String body = res.body() != null ? res.body().string() : "";

            if (code == 422) return validation(body);
            if (code == 429) return Result.failure("Terlalu banyak percobaan. Tunggu sebentar lalu coba lagi.");
            if (code == 419) return Result.failure("Sesi pendaftaran habis. Silakan coba lagi.");
            if (code >= 500) return Result.failure("Server sedang bermasalah. Coba lagi sebentar lagi.");

            // 2xx, or the redirect to the e-mail verification page.
            Result done = new Result();
            done.ok = code < 400;
            done.cookies = jar;
            if (!done.ok) done.general = "Pendaftaran gagal (kode " + code + ").";
            return done;
        }
    }

    private Result validation(String body) {
        Result r = new Result();
        try {
            JSONObject errors = new JSONObject(body).optJSONObject("errors");
            if (errors != null) {
                for (java.util.Iterator<String> it = errors.keys(); it.hasNext(); ) {
                    String key = it.next();
                    JSONArray msgs = errors.optJSONArray(key);
                    if (msgs != null && msgs.length() > 0) r.fields.put(key, msgs.getString(0));
                }
            }
        } catch (Exception ignored) {
            // Fall through to the generic message.
        }
        if (r.fields.isEmpty()) r.general = "Data pendaftaran belum valid. Periksa kembali isian Anda.";
        return r;
    }

    private static void collect(Response res, Map<String, String> jar) {
        for (String raw : res.headers("Set-Cookie")) {
            int eq = raw.indexOf('=');
            if (eq > 0) jar.put(raw.substring(0, eq), raw);
        }
    }

    private static String cookieHeader(Map<String, String> jar) {
        StringBuilder sb = new StringBuilder();
        for (String raw : jar.values()) {
            int semi = raw.indexOf(';');
            if (sb.length() > 0) sb.append("; ");
            sb.append(semi > 0 ? raw.substring(0, semi) : raw);
        }
        return sb.toString();
    }

    // ── Result handling ───────────────────────────────────────────────────────

    private void finished(Result r) {
        setBusy(false);

        if (r.ok) {
            // Hand the new login to the WebView, then continue on the site.
            CookieManager cm = CookieManager.getInstance();
            for (String raw : r.cookies.values()) cm.setCookie(BuildConfig.DEFAULT_URL, raw);
            cm.flush();

            YokaFlow.openPage(this, YokaFlow.PATH_VERIFY);
            finish();
            return;
        }

        if (r.fields.containsKey("name"))     fieldError(nameError, name, r.fields.get("name"));
        if (r.fields.containsKey("phone"))    fieldError(phoneError, null, r.fields.get("phone"));
        if (r.fields.containsKey("password")) fieldError(passwordError, password, r.fields.get("password"));
        if (r.fields.containsKey("email")) {
            String msg = r.fields.get("email");
            // "sudah ada/terdaftar/digunakan": point the user at sign-in.
            boolean taken = msg != null && msg.toLowerCase().matches(".*(sudah|taken|terdaftar|digunakan).*");
            fieldError(emailError, email, taken ? "Email ini sudah terdaftar. Silakan masuk." : msg);
        }
        if (r.general != null) {
            formError.setText(r.general);
            formError.setVisibility(View.VISIBLE);
        }
    }

    private void fieldError(TextView label, EditText input, String message) {
        label.setText(message);
        label.setVisibility(View.VISIBLE);
        if (input != null) input.setBackgroundResource(R.drawable.input_bg_error);
        else findViewById(R.id.phoneBox).setBackgroundResource(R.drawable.input_bg_error);
    }

    private void clearErrors() {
        for (TextView t : new TextView[]{nameError, phoneError, emailError, passwordError, formError}) {
            t.setVisibility(View.GONE);
        }
        for (EditText t : new EditText[]{name, email, password}) {
            t.setBackgroundResource(R.drawable.input_bg);
        }
        findViewById(R.id.phoneBox).setBackgroundResource(R.drawable.input_bg);
    }

    private void setBusy(boolean b) {
        busy = b;
        submit.setEnabled(!b);
        submit.setAlpha(b ? 0.6f : 1f);
        submit.setText(b ? "Memproses..." : "Buat Akun & Verifikasi Email");
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) YokaFlow.hideSystemUi(this);
    }
}
