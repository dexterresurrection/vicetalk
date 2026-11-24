/*
 * Cloud Talk / Vice Talk — unofficial Telegram client by CloudVPN.
 * Licensed under GNU GPL v. 2 or later, like the Telegram for Android sources it is based on.
 */
package uk.zjd.tgx;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeBind;
import org.telegram.ui.Components.LayoutHelper;

import java.util.List;

/** Settings → «Обход блокировок»: mode, state and a short log. */
public class BypassActivity extends BaseFragment {
    private TextView status;
    private TextView logView;
    private TextView account;
    private Button accountButton;
    private Button refreshButton;
    private LinearLayout donorBox;
    private LinearLayout codeBox;
    private TextView codeText;
    private View codeButtons;
    private String verificationUrl, telegramUrl;
    private static final int REQUEST_APPROVE = 7301;
    private SharedSignIn.Donor asking;   // the app whose window is open now
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            update();
            AndroidUtilities.runOnUIThread(this, 2000);
        }
    };

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(Brand.t("Обход блокировок", "Bypass"));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });
        final int fg = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        final int gray = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);
        final ScrollView scroll = new ScrollView(context);
        scroll.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        final LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        final int pad = AndroidUtilities.dp(18);
        // edge-to-edge: nothing of ours under the navigation bar or the rounded corners of the screen
        box.setPadding(pad, pad, pad, pad + AndroidUtilities.navigationBarHeight + AndroidUtilities.dp(24));
        scroll.addView(box);

        box.addView(text(context, Brand.t(
                "Если Telegram в вашей сети замедлен или заблокирован, " + Brand.name + " сам пускает сообщения, файлы и звонки через серверы " + Account.title() + ". "
                        + "Через обход идёт только трафик этого приложения — другие приложения и браузер не затрагиваются.",
                "When Telegram is slow or blocked on your network, " + Brand.name + " routes messages, files and calls through " + Account.title() + " servers. "
                        + "Only this app's traffic goes through the bypass."), 15, fg));

        // ---- CloudVPN account: the bypass is part of the subscription
        account = text(context, "", 15, fg);
        account.setPadding(0, AndroidUtilities.dp(14), 0, AndroidUtilities.dp(4));
        box.addView(account);
        donorBox = new LinearLayout(context);
        donorBox.setOrientation(LinearLayout.VERTICAL);
        donorBox.setVisibility(View.GONE);
        box.addView(donorBox, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        accountButton = new Button(context);
        accountButton.setOnClickListener(v -> onAccountButton());
        box.addView(accountButton, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        codeBox = new LinearLayout(context);
        codeBox.setOrientation(LinearLayout.VERTICAL);
        codeBox.setVisibility(View.GONE);
        codeText = text(context, "", 15, fg);
        codeText.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(8));
        codeBox.addView(codeText);
        final LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.VERTICAL);
        codeButtons = buttons;
        codeBox.addView(buttons);
        final Button openCabinet = new Button(context);
        openCabinet.setText(Brand.t("Открыть кабинет и подтвердить", "Open the cabinet and confirm"));
        openCabinet.setOnClickListener(v -> open(verificationUrl));
        buttons.addView(openCabinet, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        final Button viaBot = new Button(context);
        viaBot.setText(Brand.t("Подтвердить через бота", "Confirm in the bot"));
        viaBot.setOnClickListener(v -> open(telegramUrl));
        buttons.addView(viaBot, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        box.addView(codeBox);
        refreshButton = new Button(context);
        refreshButton.setText(Brand.t("Обновить серверы обхода", "Refresh bypass servers"));
        refreshButton.setOnClickListener(v -> {
            refreshButton.setEnabled(false);
            new Thread(() -> {
                final String err = Account.fetchProfile(getContext());
                AndroidUtilities.runOnUIThread(() -> {
                    refreshButton.setEnabled(true);
                    refreshButton.setText(err == null ? Brand.t("Серверы обновлены", "Servers refreshed") : err);
                    update();
                });
            }).start();
        });
        box.addView(refreshButton, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        final RadioGroup group = new RadioGroup(context);
        group.setPadding(0, AndroidUtilities.dp(12), 0, AndroidUtilities.dp(8));
        final String[] titles = {
                Brand.t("Автоматически (рекомендуется)", "Automatic (recommended)"),
                Brand.t("Всегда через обход", "Always via bypass"),
                Brand.t("Выключен — только напрямую", "Off — direct only")};
        for (int i = 0; i < titles.length; i++) {
            final RadioButton rb = new RadioButton(context);
            rb.setId(1000 + i);
            rb.setText(titles[i]);
            rb.setTextColor(fg);
            rb.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            rb.setPadding(AndroidUtilities.dp(6), AndroidUtilities.dp(8), 0, AndroidUtilities.dp(8));
            group.addView(rb);
        }
        group.check(1000 + Bypass.getMode());
        group.setOnCheckedChangeListener((g, id) -> {
            Bypass.setMode(id - 1000);
            update();
        });
        box.addView(group);

        status = text(context, "", 15, fg);
        status.setPadding(0, AndroidUtilities.dp(4), 0, AndroidUtilities.dp(12));
        box.addView(status);

        final Button probe = new Button(context);
        probe.setText(Brand.t("Проверить прямое соединение", "Check direct connection"));
        probe.setOnClickListener(v -> {
            probe.setEnabled(false);
            probe.setText(Brand.t("Проверяю…", "Checking…"));
            new Thread(() -> {
                final boolean ok = Bypass.probeDirect();
                AndroidUtilities.runOnUIThread(() -> {
                    probe.setEnabled(true);
                    probe.setText(ok ? Brand.t("Напрямую Telegram отвечает", "Telegram answers directly")
                            : Brand.t("Напрямую Telegram не отвечает", "No direct answer from Telegram"));
                });
            }).start();
        });
        box.addView(probe, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        final TextView about = text(context, Brand.t(
                Brand.name + " — неофициальный клиент на открытом исходном коде Telegram для Android. Мы не связаны с Telegram. "
                        + "Переписку мы не видим: она зашифрована протоколом Telegram, а наши серверы только передают зашифрованные данные.",
                Brand.name + " is an unofficial client built on the open source code of Telegram for Android. We are not affiliated with Telegram. "
                        + "We cannot read your messages: they are encrypted by Telegram's protocol, our servers only relay encrypted data."), 13, gray);
        about.setPadding(0, AndroidUtilities.dp(16), 0, AndroidUtilities.dp(8));
        box.addView(about);

        logView = text(context, "", 11, gray);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setGravity(Gravity.START);
        box.addView(logView);

        fragmentView = scroll;
        update();
        return fragmentView;
    }

    private void open(String url) {
        if (url == null || url.isEmpty()) {
            return;
        }
        try {
            getParentActivity().startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable ignore) {
        }
    }

    private void onAccountButton() {
        final Context c = getContext();
        if (Account.loggedIn(c)) {
            Account.logout(c);
            update();
            loadDonors();
            return;
        }
        if (accountButton.getTag() != null) {   // a sign-in is in progress: the button cancels it
            stopLogin(null);
            return;
        }
        startLogin(null);
    }

    /** donor == null — the code is confirmed in the cabinet or in the bot; otherwise that app of ours confirms it. */
    private void startLogin(SharedSignIn.Donor donor) {
        asking = donor;
        accountButton.setTag(Boolean.TRUE);
        donorBox.setVisibility(View.GONE);
        codeBox.setVisibility(View.GONE);
        update();
        final Context c = getContext().getApplicationContext();
        Account.startLogin(c, new Account.LoginListener() {
            @Override
            public void onCode(String userCode, String vUrl, String tgUrl) {
                verificationUrl = vUrl;
                telegramUrl = tgUrl;
                if (donor != null) {
                    askDonor(c, donor, userCode);
                    return;
                }
                codeText.setText(Brand.t("Код для входа: ", "Sign-in code: ") + userCode + "\n"
                        + Brand.t("Подтвердите его в кабинете " + Account.title() + " (или в боте, если Telegram уже работает). Жду подтверждения…",
                        "Confirm it in the " + Account.title() + " cabinet (or in the bot if Telegram already works). Waiting…"));
                codeButtons.setVisibility(View.VISIBLE);
                codeBox.setVisibility(View.VISIBLE);
            }

            @Override
            public void onDone(boolean ok, String error) {
                stopLogin(error);
            }
        });
    }

    private void stopLogin(String text) {
        Account.cancelLogin();
        asking = null;
        accountButton.setTag(null);
        codeBox.setVisibility(View.GONE);
        if (text != null) {
            message(text);
        }
        update();
        loadDonors();
    }

    private void askDonor(Context c, SharedSignIn.Donor donor, String userCode) {
        new Thread(() -> {
            final boolean there = SharedSignIn.stillThere(c, donor);
            AndroidUtilities.runOnUIThread(() -> {
                if (asking != donor || getParentActivity() == null) {
                    return;
                }
                try {
                    if (!there) {
                        throw new IllegalStateException();
                    }
                    getParentActivity().startActivityForResult(SharedSignIn.approveIntent(donor, userCode), REQUEST_APPROVE);
                    message(Brand.t("Подтвердите вход в «" + donor.label + "»", "Confirm the sign-in in “" + donor.label + "”"));
                } catch (Throwable e) {
                    stopLogin(Brand.t("«" + donor.label + "» не отвечает — войдите по коду", "“" + donor.label + "” does not answer — sign in with a code"));
                }
            });
        }, "tgx-signin").start();
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_APPROVE || asking == null) {
            return;
        }
        if (resultCode == Activity.RESULT_OK) {
            Bypass.note("sign-in: confirmed by another app");
            message(Brand.t("Подтверждено, вхожу…", "Confirmed, signing in…"));
        } else {
            Bypass.note("sign-in: not confirmed");
            stopLogin(SharedSignIn.error(data));
        }
    }

    /** One line under the account button. */
    private void message(String text) {
        codeText.setText(text);
        codeButtons.setVisibility(View.GONE);
        codeBox.setVisibility(View.VISIBLE);
    }

    private void loadDonors() {
        final Context c = getContext();
        if (c == null || donorBox == null || Account.loggedIn(c)) {
            return;
        }
        new Thread(() -> {
            final List<SharedSignIn.Donor> list = SharedSignIn.donors(c.getApplicationContext());
            AndroidUtilities.runOnUIThread(() -> {
                if (donorBox == null || getContext() == null) {
                    return;
                }
                donorBox.removeAllViews();
                for (SharedSignIn.Donor d : list) {
                    final Button b = new Button(donorBox.getContext());
                    b.setAllCaps(false);
                    b.setText(d.name.isEmpty() ? Brand.t("Войти через «" + d.label + "»", "Sign in through “" + d.label + "”")
                            : Brand.t("Продолжить как " + d.name + " — через «" + d.label + "»", "Continue as " + d.name + " — through “" + d.label + "”"));
                    b.setOnClickListener(v -> startLogin(d));
                    donorBox.addView(b, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
                }
                showDonors();
            });
        }, "tgx-donors").start();
    }

    private void showDonors() {
        donorBox.setVisibility(donorBox.getChildCount() > 0 && accountButton.getTag() == null && !Account.loggedIn(donorBox.getContext())
                ? View.VISIBLE : View.GONE);
    }

    /** First screen: the bypass (and the sign-in to it) is reachable before the sign-in to Telegram. */
    public static void addIntroButton(BaseFragment intro, FrameLayout parent) {
        final TextView t = new TextView(parent.getContext());
        t.setText(Brand.t("Обход блокировок", "Bypass"));
        t.setGravity(Gravity.CENTER);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        t.setPadding(AndroidUtilities.dp(16), 0, AndroidUtilities.dp(16), 0);
        ThemeBind.textColor(t, Theme.key_windowBackgroundWhiteBlueText4);
        t.setOnClickListener(v -> intro.presentFragment(new BypassActivity()));
        parent.addView(t, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 36, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 0, 0, 8));
    }

    private static TextView text(Context c, String s, int sp, int color) {
        final TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sp);
        t.setTextColor(color);
        t.setLineSpacing(AndroidUtilities.dp(2), 1f);
        return t;
    }

    private void update() {
        if (status == null) {
            return;
        }
        final boolean in = Account.loggedIn(getContext());
        final String who = Account.userName(getContext());
        final String service = Account.title();
        account.setText(in ? Brand.t("Аккаунт " + service + ": ", service + " account: ") + (who.isEmpty() ? Brand.t("вход выполнен", "signed in") : who)
                : Brand.t("Обход входит в подписку " + service + ". Войдите в аккаунт — серверы обхода возьмутся из вашей подписки.",
                "The bypass is part of the " + service + " subscription. Sign in — the bypass servers come from your subscription."));
        accountButton.setText(in ? Brand.t("Выйти из аккаунта", "Sign out")
                : accountButton.getTag() != null ? Brand.t("Отмена", "Cancel")
                : Brand.t("Войти в " + service + " по коду", "Sign in to " + service + " with a code"));
        showDonors();
        refreshButton.setVisibility(in ? View.VISIBLE : View.GONE);
        final int st = ConnectionsManager.getInstance(UserConfig.selectedAccount).getConnectionState();
        final String conn;
        switch (st) {
            case ConnectionsManager.ConnectionStateConnected:
                conn = Brand.t("подключено", "connected");
                break;
            case ConnectionsManager.ConnectionStateUpdating:
                conn = Brand.t("обновление", "updating");
                break;
            case ConnectionsManager.ConnectionStateWaitingForNetwork:
                conn = Brand.t("нет сети", "no network");
                break;
            case ConnectionsManager.ConnectionStateConnectingToProxy:
                conn = Brand.t("подключение через прокси…", "connecting to proxy…");
                break;
            default:
                conn = Brand.t("подключение…", "connecting…");
        }
        final StringBuilder sb = new StringBuilder();
        sb.append(Bypass.statusLine()).append('\n');
        sb.append(Brand.t("Telegram: ", "Telegram: ")).append(conn).append('\n');
        sb.append(Brand.t("Серверов обхода: ", "Bypass servers: ")).append(Bypass.profileCount())
                .append(Brand.t(", MTProto-прокси: ", ", MTProto proxies: ")).append(Bypass.mtprotoCount());
        if (!Bypass.lastError().isEmpty()) {
            sb.append('\n').append(Brand.t("Ошибка: ", "Error: ")).append(Bypass.lastError());
        }
        status.setText(sb);
        logView.setText(Bypass.logTail());
    }

    @Override
    public void onResume() {
        super.onResume();
        AndroidUtilities.runOnUIThread(refresh, 2000);
        loadDonors();
    }

    @Override
    public void onPause() {
        super.onPause();
        AndroidUtilities.cancelRunOnUIThread(refresh);
    }

    @Override
    public void onFragmentDestroy() {
        Account.cancelLogin();
        super.onFragmentDestroy();
    }
}
