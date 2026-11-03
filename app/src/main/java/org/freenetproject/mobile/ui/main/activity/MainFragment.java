package org.freenetproject.mobile.ui.main.activity;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.PreferenceManager;

import org.freenetproject.mobile.BuildConfig;
import org.freenetproject.mobile.R;

import java.util.Calendar;
import org.freenetproject.mobile.services.node.Manager;
import org.freenetproject.mobile.ui.about.activity.AboutActivity;
import org.freenetproject.mobile.ui.bootstrap.activity.BootstrapActivity;
import org.freenetproject.mobile.ui.acknowledgement.activity.AcknowledgementActivity;
import org.freenetproject.mobile.ui.acknowledgement.activity.AcknowledgementFragment;
import org.freenetproject.mobile.ui.main.viewmodel.MainViewModel;
import org.freenetproject.mobile.ui.settings.activity.SettingsActivity;
import org.freenetproject.mobile.ui.sites.SitesActivity;

import org.json.JSONException;

import java.io.IOException;
import java.net.URLEncoder;
import java.security.NoSuchAlgorithmException;

public class MainFragment extends Fragment {
    private static final String PC_COMMAND = "adb forward tcp:7509 tcp:7509";
    public static final String RIVER_JOINED = "river_joined";
    private static final String RIVER_URL = Manager.WEB_URL
            + "v1/contract/web/raAqMhMG7KUpXBU2SxgCQ3Vh4PYjttxdSWd9ftV7RLv/";

    private static final long COLOR_MILLIS = 450;
    private static final long PULSE_MILLIS = 900;

    private ValueAnimator colorAnimator;
    private ValueAnimator pulse;
    private final Handler uptimeHandler = new Handler(Looper.getMainLooper());
    private Runnable uptimeTick;
    private SharedPreferences.OnSharedPreferenceChangeListener appearanceListener;

    private static int actionIcon(Manager.Status status) {
        switch (status) {
            case STARTED:
            case PAUSED:
                return R.drawable.ic_baseline_power_settings_new_24;
            case ERROR:
                return R.drawable.ic_baseline_replay_24;
            default:
                return R.drawable.ic_baseline_play_circle_outline_24;
        }
    }

    private int statusColor(Manager.Status status) {
        int color;
        switch (status) {
            case STARTED:
                TypedValue accent = new TypedValue();
                requireContext().getTheme().resolveAttribute(
                        androidx.appcompat.R.attr.colorAccent, accent, true);
                return accent.data;
            case ERROR:
                color = R.color.colorError;
                break;
            case PAUSED:
            case STARTING_UP:
                color = R.color.colorWarning;
                break;
            default:
                color = android.R.color.white;
                break;
        }
        return requireContext().getColor(color);
    }

    @Override
    public void onDestroyView() {
        if (colorAnimator != null) {
            colorAnimator.cancel();
            colorAnimator = null;
        }
        if (pulse != null) {
            pulse.cancel();
            pulse = null;
        }
        if (uptimeTick != null) {
            uptimeHandler.removeCallbacks(uptimeTick);
        }
        if (appearanceListener != null) {
            PreferenceManager.getDefaultSharedPreferences(requireContext())
                    .unregisterOnSharedPreferenceChangeListener(appearanceListener);
        }
        super.onDestroyView();
    }


    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState
    ) {
        SharedPreferences prefs = getContext().getSharedPreferences(
                getContext().getPackageName(), Context.MODE_PRIVATE
        );

        if (!prefs.getBoolean(AcknowledgementFragment.ACKNOWLEDGEMENT_KEY, false)) {
            startActivity(new Intent(getContext(), AcknowledgementActivity.class));
        }

        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_main, container, false);
    }

    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        new ViewModelProvider(requireActivity()).get(MainViewModel.class);

        Manager manager = Manager.getInstance();
        updateSharedPreferences(manager, view);
        updateSettings(manager, view);
        updateAbout(manager, view);
        view.findViewById(R.id.pc_button).setOnClickListener(v -> showPcHelp());
        updateUptime(Manager.getInstance(), view);
        applyAppearance(view);

        // Live update without leaving Settings
        appearanceListener = (prefs, key) -> {
            if (isAdded() && getView() != null) {
                applyAppearance(getView());
            }
        };
        PreferenceManager.getDefaultSharedPreferences(requireContext())
                .registerOnSharedPreferenceChangeListener(appearanceListener);
        Button river = view.findViewById(R.id.river_button);
        river.setOnClickListener(v -> openRiver(river));
        manager.getStatus().observe(getViewLifecycleOwner(), status ->
                river.setEnabled(status.equals(Manager.Status.STARTED)));
        Button web = view.findViewById(R.id.web_button);
        web.setOnClickListener(v -> startActivity(new Intent(getContext(), BootstrapActivity.class)));
        manager.getStatus().observe(getViewLifecycleOwner(), status ->
                web.setEnabled(status.equals(Manager.Status.STARTED)));
        view.findViewById(R.id.sites_button).setOnClickListener(v ->
                startActivity(new Intent(getActivity(), SitesActivity.class)));

        Button videoCall = view.findViewById(R.id.video_call_button);
        videoCall.setOnClickListener(v -> startActivity(new Intent(getContext(), VideoCallActivity.class)));
        manager.getStatus().observe(getViewLifecycleOwner(), status ->
                videoCall.setEnabled(status.equals(Manager.Status.STARTED)));
        updateControls(manager, view);
        updateStatus(manager, view);
        updateStatusDetail(manager, view);
    }

    /** The first time it joins the public room with an invite; then it just opens River. */
    private void openRiver(Button river) {
        SharedPreferences prefs = requireContext().getSharedPreferences(
                requireContext().getPackageName(), Context.MODE_PRIVATE);
        if (prefs.getBoolean(RIVER_JOINED, false)) {
            showRiver(RIVER_URL);
            return;
        }
        river.setEnabled(false);
        Toast.makeText(getContext(), R.string.river_joining, Toast.LENGTH_LONG).show();
        new Thread(() -> {
            String url = null;
            String problem = null;
            try {
                url = RIVER_URL + "?invitation=" + URLEncoder.encode(RiverInvite.request(), "UTF-8");
            } catch (IOException | JSONException | NoSuchAlgorithmException e) {
                problem = e.getMessage();
            }
            String invited = url;
            String failure = problem;
            river.post(() -> {
                river.setEnabled(Manager.getInstance().isRunning());
                if (!isAdded()) {
                    return;
                }
                if (invited == null) {
                    Toast.makeText(getContext(), getString(R.string.river_join_failed, failure),
                            Toast.LENGTH_LONG).show();
                    showRiver(RIVER_URL); // River still opens, without joining the room
                    return;
                }
                prefs.edit().putBoolean(RIVER_JOINED, true).apply();
                showRiver(invited);
            });
        }, "freenet-river-invite").start();
    }

    /** The USB cable always works; the network only with host mode, which has no password. */
    private void showPcHelp() {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.use_from_pc)
                .setMessage(getString(R.string.pc_help, PC_COMMAND, Manager.WEB_URL)
                        + "\n\n" + networkHelp())
                .setNegativeButton(android.R.string.ok, null)
                .setNeutralButton(R.string.host_copy_address, hostAddress() == null ? null
                        : (d, w) -> copyToClipboard("Freenet", hostAddress()))
                .setPositiveButton(R.string.pc_copy, (d, w) -> copyToClipboard("adb", PC_COMMAND))
                .show();
    }

    private String networkHelp() {
        if (!PreferenceManager.getDefaultSharedPreferences(requireContext())
                .getBoolean("host_mode", false)) {
            return getString(R.string.pc_network_off);
        }
        String url = hostAddress();
        return url == null
                ? getString(R.string.host_mode_no_network)
                : getString(R.string.pc_network, url);
    }

    /** The address the PC uses, or null when host mode is off or there is no local network. */
    private String hostAddress() {
        if (!PreferenceManager.getDefaultSharedPreferences(requireContext())
                .getBoolean("host_mode", false)) {
            return null;
        }
        String found = Manager.localAddress();
        return found == null ? null : "http://" + found + ":" + Manager.WEB_PORT + "/";
    }

    private void copyToClipboard(String label, String text) {
        requireContext().getSystemService(ClipboardManager.class)
                .setPrimaryClip(ClipData.newPlainText(label, text));
        Toast.makeText(requireContext(), R.string.host_address_copied, Toast.LENGTH_SHORT).show();
    }

    private void showRiver(String url) {
        startActivity(new Intent(getContext(), BootstrapActivity.class)
                .putExtra(BootstrapActivity.EXTRA_URL, url));
    }

    private void updateControls(Manager m, View view) {
        Button controlButton = view.findViewById(R.id.control_button);
        controlButton.setOnClickListener(view1 -> {
            // Nothing to do while it is still starting up or stopping.
            if (m.isTransitioning()) {
                return;
            }

            if (m.isRunning() || m.isPaused()) {
                m.stopService(view.getContext());
            } else {
                // After an error the service is still up, so it would not start the node again.
                m.startService(view.getContext());
                if (m.hasError()) {
                    Context appContext = view.getContext().getApplicationContext();
                    new Thread(() -> m.startNode(appContext), "freenet-start").start();
                }
            }
        });

        View ring = view.findViewById(R.id.control_ring);
        m.getStatus().observe(getViewLifecycleOwner(), status -> {
            controlButton.setEnabled(!m.isTransitioning());
            controlButton.setBackgroundResource(actionIcon(status));
            animateControl(controlButton, ring, statusColor(status), m.isTransitioning());
        });
    }

    /**
     * The colour slides from the old one to the new one, and the ring breathes while the node
     * is starting up or stopping, so the button shows what is happening without any text.
     */
    private void animateControl(View button, View ring, int color, boolean working) {
        boolean animations = PreferenceManager.getDefaultSharedPreferences(requireContext())
                .getBoolean("dev_animations", true);
        ColorStateList tint = ColorStateList.valueOf(color);
        if (!animations) {
            stopPulse(ring);
            button.setBackgroundTintList(tint);
            ring.setBackgroundTintList(tint);
            return;
        }
        ColorStateList previous = ring.getBackgroundTintList();
        int from = previous == null ? color : previous.getDefaultColor();
        if (colorAnimator != null) {
            colorAnimator.cancel();
        }
        colorAnimator = ValueAnimator.ofObject(new ArgbEvaluator(), from, color);
        colorAnimator.setDuration(COLOR_MILLIS);
        colorAnimator.addUpdateListener(step -> {
            ColorStateList now = ColorStateList.valueOf((Integer) step.getAnimatedValue());
            button.setBackgroundTintList(now);
            ring.setBackgroundTintList(now);
        });
        colorAnimator.start();
        if (working) {
            startPulse(ring);
        } else {
            stopPulse(ring);
        }
    }

    private void startPulse(View ring) {
        if (pulse != null && pulse.isRunning()) {
            return;
        }
        pulse = ValueAnimator.ofFloat(1f, 1.18f);
        pulse.setDuration(PULSE_MILLIS);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.setRepeatMode(ValueAnimator.REVERSE);
        pulse.addUpdateListener(step -> {
            float scale = (Float) step.getAnimatedValue();
            ring.setScaleX(scale);
            ring.setScaleY(scale);
            ring.setAlpha(1.18f - scale + 0.82f);
        });
        pulse.start();
    }

    private void stopPulse(View ring) {
        if (pulse != null) {
            pulse.cancel();
            pulse = null;
        }
        ring.setScaleX(1f);
        ring.setScaleY(1f);
        ring.setAlpha(1f);
    }

    private void applyAppearance(View view) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());

        // Logo style
        ImageView logo = view.findViewById(R.id.imageView);
        String logoStyle = prefs.getString("logo_style", "freenet");
        logo.setVisibility(View.VISIBLE);
        switch (logoStyle) {
            case "galaxy":  logo.setImageResource(R.drawable.ic_logo_galaxy); break;
            case "shield":  logo.setImageResource(R.drawable.ic_logo_shield); break;
            case "star":    logo.setImageResource(R.drawable.ic_baseline_star_24); break;
            case "none":    logo.setVisibility(View.GONE); break;
            case "custom": {
                String path = prefs.getString(
                        org.freenetproject.mobile.ui.settings.activity.SettingsActivity.PREF_LOGO_CUSTOM_PATH, "");
                if (path != null && !path.isEmpty()) {
                    Bitmap bmp = BitmapFactory.decodeFile(path);
                    if (bmp != null) { logo.setImageBitmap(bmp); break; }
                }
                logo.setImageResource(R.drawable.ic_freenet_logo); break;
            }
            default: logo.setImageResource(R.drawable.ic_freenet_logo); break;
        }

        // Logo size from settings (dp)
        int logoSizeDp;
        switch (prefs.getString("logo_size", "medium")) {
            case "small":  logoSizeDp = 56;  break;
            case "large":  logoSizeDp = 120; break;
            case "xlarge": logoSizeDp = 160; break;
            default:       logoSizeDp = 84;  break;
        }
        int logoSizePx = Math.round(logoSizeDp * getResources().getDisplayMetrics().density);
        android.view.ViewGroup.LayoutParams lp = logo.getLayoutParams();
        lp.width = logoSizePx;
        lp.height = Math.round(logoSizeDp * 0.57f * getResources().getDisplayMetrics().density);
        logo.setLayoutParams(lp);

        // Custom app name (shown in the title TextView)
        TextView titleText = view.findViewById(R.id.textView);
        if (titleText != null) {
            String customName = prefs.getString("app_custom_name", "").trim();
            titleText.setText(customName.isEmpty() ? getString(R.string.freenet) : customName);
        }

        // Button visibility
        applyBtnVisibility(view, R.id.river_button, prefs.getBoolean("btn_river", true));
        applyBtnVisibility(view, R.id.pc_button, prefs.getBoolean("btn_pc", true));
        applyBtnVisibility(view, R.id.web_button, prefs.getBoolean("btn_web", true));
        applyBtnVisibility(view, R.id.sites_button, prefs.getBoolean("btn_sites", true));

        // Hide extra_buttons row if both buttons hidden
        boolean riverVisible = prefs.getBoolean("btn_river", true);
        boolean pcVisible = prefs.getBoolean("btn_pc", true);
        view.findViewById(R.id.extra_buttons)
                .setVisibility(riverVisible || pcVisible ? View.VISIBLE : View.GONE);

        // Seasonal overlay
        SeasonalOverlayView overlay = view.findViewById(R.id.seasonal_overlay);
        if (overlay != null) overlay.setSeason(detectSeason(requireContext()));
    }

    private static SeasonalOverlayView.Season detectSeason(Context ctx) {
        String pref = PreferenceManager.getDefaultSharedPreferences(ctx)
                .getString("app_theme", "auto");
        switch (pref) {
            case "natale":       return SeasonalOverlayView.Season.SNOW;
            case "halloween":    return SeasonalOverlayView.Season.HALLOWEEN;
            case "pesce_aprile": return SeasonalOverlayView.Season.FISH;
            case "compleanno":   return SeasonalOverlayView.Season.BIRTHDAY;
            case "capodanno":    return SeasonalOverlayView.Season.NEW_YEAR;
            case "estate":       return SeasonalOverlayView.Season.SUMMER;
            case "primavera":    return SeasonalOverlayView.Season.SPRING;
            case "none":         return SeasonalOverlayView.Season.NONE;
            default: // auto — segue il calendario
                Calendar c = Calendar.getInstance();
                int month = c.get(Calendar.MONTH);
                int day   = c.get(Calendar.DAY_OF_MONTH);
                if (month == Calendar.OCTOBER  && day == 31)              return SeasonalOverlayView.Season.HALLOWEEN;
                if (month == Calendar.APRIL    && day == 1)               return SeasonalOverlayView.Season.FISH;
                if (month == Calendar.DECEMBER && day >= 24 && day <= 26) return SeasonalOverlayView.Season.SNOW;
                if (month == Calendar.JANUARY  && day == 1)               return SeasonalOverlayView.Season.NEW_YEAR;
                return SeasonalOverlayView.Season.NONE;
        }
    }

    private static void applyBtnVisibility(View view, int id, boolean visible) {
        View btn = view.findViewById(id);
        if (btn != null) btn.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void updateUptime(Manager m, View view) {
        TextView uptimeView = view.findViewById(R.id.node_uptime);
        m.getStatus().observe(getViewLifecycleOwner(), status -> {
            if (uptimeTick != null) {
                uptimeHandler.removeCallbacks(uptimeTick);
                uptimeTick = null;
            }
            if (status == Manager.Status.STARTED) {
                uptimeTick = new Runnable() {
                    @Override
                    public void run() {
                        long ms = m.uptimeMs();
                        if (ms <= 0) {
                            uptimeView.setVisibility(View.GONE);
                            return;
                        }
                        long s = ms / 1000;
                        long h = s / 3600;
                        long min = (s % 3600) / 60;
                        long sec = s % 60;
                        uptimeView.setText(String.format(
                                java.util.Locale.ROOT, "%02d:%02d:%02d", h, min, sec));
                        uptimeView.setTextColor(timerColor());
                        boolean showTimer = PreferenceManager.getDefaultSharedPreferences(requireContext())
                                .getBoolean("show_timer", true);
                        uptimeView.setVisibility(showTimer ? View.VISIBLE : View.GONE);
                        uptimeHandler.postDelayed(this, 1000);
                    }
                };
                uptimeHandler.post(uptimeTick);
            } else {
                uptimeView.setVisibility(View.GONE);
            }
        });
    }

    private int timerColor() {
        String val = PreferenceManager.getDefaultSharedPreferences(requireContext())
                .getString("timer_color", "secondary");
        switch (val) {
            case "white": return Color.WHITE;
            case "cyan":  return Color.parseColor("#2DD4BF");
            case "green": return Color.parseColor("#4ADE80");
            case "pink":  return Color.parseColor("#F472B6");
            default: {
                TypedValue accent = new TypedValue();
                requireContext().getTheme().resolveAttribute(
                        androidx.appcompat.R.attr.colorAccent, accent, true);
                return accent.data;
            }
        }
    }

    private void updateStatus(Manager m, View view) {
        TextView statusText = view.findViewById(R.id.node_status);
        m.getStatus().observe(getViewLifecycleOwner(), status -> {
            statusText.setTextColor(statusColor(status));
            if (status.equals(Manager.Status.STARTED)) {
                statusText.setText(R.string.node_running);
            } else if (status.equals(Manager.Status.STARTING_UP)) {
                statusText.setText(R.string.node_starting_up);
            } else if (status.equals(Manager.Status.PAUSED)) {
                statusText.setText(R.string.node_paused);
            } else if (status.equals(Manager.Status.STOPPING)) {
                statusText.setText(R.string.node_shutting_down);
            } else if (status.equals(Manager.Status.ERROR)) {
                statusText.setText(R.string.error_starting_up);
            } else {
                statusText.setText(getString(R.string.app_version, BuildConfig.VERSION_NAME));
            }
        });
    }

    private void updateStatusDetail(Manager m, View view) {
        TextView detailText = view.findViewById(R.id.node_status_detail);
        m.getStatus().observe(getViewLifecycleOwner(), status -> {
            detailText.setOnClickListener(null);
            if (status.equals(Manager.Status.STARTED)) {
                detailText.setText(R.string.tap_to_navigate);
                detailText.setOnClickListener(view12 -> {
                   startActivity(
                           new Intent(getContext(), BootstrapActivity.class)
                   );
                });
            } else if (status.equals(Manager.Status.STARTING_UP)) {
                detailText.setText(R.string.may_take_a_while);
            } else if (status.equals(Manager.Status.PAUSED)) {
                detailText.setText(R.string.paused_detail);
            } else if (status.equals(Manager.Status.ERROR)) {
                String reason = m.lastLogLine();
                detailText.setText(reason.isEmpty()
                        ? getString(R.string.error_detail)
                        : getString(R.string.error_detail) + "\n" + reason);
            } else if (status.equals(Manager.Status.STOPPED)) {
                detailText.setText(R.string.press_to_start);
            } else {
                detailText.setText("");
            }
        });
    }

    private void updateSharedPreferences(Manager m, View view) {
        SharedPreferences sharedPref = requireActivity().getPreferences(Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPref.edit();

        m.getStatus().observe(getViewLifecycleOwner(), status -> {
            editor.putInt("status", status.ordinal());
            editor.apply();
        });
    }

    private void updateSettings(Manager m, View view) {
        Button settings = view.findViewById(R.id.settings_button);
        settings.setOnClickListener(view1 -> {
            startActivity(new Intent(getActivity(), SettingsActivity.class));
        });
    }

    private void updateAbout(Manager m, View view) {
        Button settings = view.findViewById(R.id.about_button);
        settings.setOnClickListener(view1 -> {
            startActivity(new Intent(getActivity(), AboutActivity.class));
        });
    }
}