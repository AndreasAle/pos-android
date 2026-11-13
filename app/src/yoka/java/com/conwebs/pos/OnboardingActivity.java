package com.conwebs.pos;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

/** Four short slides on what Yoka POS does, then on to the welcome screen. */
public class OnboardingActivity extends Activity {

    private static final int[]    ART   = {R.drawable.ill_sell, R.drawable.ill_stock,
                                           R.drawable.ill_kitchen, R.drawable.ill_report};
    private static final String[] TITLE = {
        "Jualan jadi mudah",
        "Stok dan resep terkontrol",
        "Dapur ikut tersambung",
        "Laporan selalu satu angka",
    };
    private static final String[] BODY = {
        "Catat pesanan, terima tunai dan QRIS, lalu cetak struk dalam hitungan detik.",
        "Stok bahan berkurang otomatis di setiap penjualan, lengkap dengan peringatan stok menipis.",
        "Tiket dapur tercetak otomatis di printer kedua, jadi tidak ada pesanan yang terlewat.",
        "Penjualan, shift kasir, dan saldo sama di tablet maupun di laptop, kapan pun dibuka.",
    };

    private ViewPager2   pager;
    private LinearLayout dots;
    private Button       next;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_onboarding);
        YokaFlow.hideSystemUi(this);

        pager = findViewById(R.id.pager);
        dots  = findViewById(R.id.dots);
        next  = findViewById(R.id.next);

        pager.setAdapter(new SlideAdapter());
        buildDots();
        select(0);

        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                select(position);
            }
        });

        next.setOnClickListener(v -> {
            int i = pager.getCurrentItem();
            if (i < TITLE.length - 1) {
                pager.setCurrentItem(i + 1, true);
            } else {
                finishOnboarding();
            }
        });
        findViewById(R.id.skip).setOnClickListener(v -> finishOnboarding());
    }

    private void finishOnboarding() {
        YokaFlow.setOnboarded(this);
        startActivity(new Intent(this, WelcomeActivity.class));
        finish();
    }

    private void buildDots() {
        for (int i = 0; i < TITLE.length; i++) {
            View d = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                (int) (24 * getResources().getDisplayMetrics().density),
                (int) (8 * getResources().getDisplayMetrics().density));
            lp.setMargins(6, 0, 6, 0);
            dots.addView(d, lp);
        }
    }

    private void select(int position) {
        for (int i = 0; i < dots.getChildCount(); i++) {
            dots.getChildAt(i).setBackgroundResource(
                i == position ? R.drawable.dot_on : R.drawable.dot_off);
            // The inactive dot is a small circle; shrink it to match.
            ViewGroup.LayoutParams lp = dots.getChildAt(i).getLayoutParams();
            lp.width = (int) ((i == position ? 24 : 8) * getResources().getDisplayMetrics().density);
            dots.getChildAt(i).setLayoutParams(lp);
        }
        next.setText(position == TITLE.length - 1 ? "Mulai" : "Lanjut");
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) YokaFlow.hideSystemUi(this);
    }

    private static class SlideAdapter extends RecyclerView.Adapter<SlideAdapter.Holder> {

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_onboarding, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            h.art.setImageResource(ART[position]);
            h.title.setText(TITLE[position]);
            h.body.setText(BODY[position]);
        }

        @Override
        public int getItemCount() {
            return TITLE.length;
        }

        static class Holder extends RecyclerView.ViewHolder {
            final ImageView art;
            final TextView  title;
            final TextView  body;

            Holder(View v) {
                super(v);
                art   = v.findViewById(R.id.illustration);
                title = v.findViewById(R.id.title);
                body  = v.findViewById(R.id.body);
            }
        }
    }
}
