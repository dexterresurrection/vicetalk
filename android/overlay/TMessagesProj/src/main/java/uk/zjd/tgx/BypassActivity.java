/*
 * Cloud Talk / Vice Talk — unofficial Telegram client by CloudVPN.
 * Licensed under GNU GPL v. 2 or later, like the Telegram for Android sources it is based on.
 */
package uk.zjd.tgx;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
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

/** Settings → «Обход блокировок»: mode, state and a short log. */
public class BypassActivity extends BaseFragment {
    private TextView status;
    private TextView logView;
    private TextView account;
    private Button accountButton;
    private Button refreshButton;
    private LinearLayout codeBox;
    private TextView codeText;
    private String verificationUrl, telegramUrl;
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
        box.setPadding(pad, pad, pad, pad);
        scroll.addView(box);

        box.addView(text(context, Brand.t(
                "Если Telegram в вашей сети замедлен или заблокирован, " + Brand.name + " сам пускает сообщения, файлы и звонки через серверы CloudVPN. "
                        + "Через обход идёт только трафик этого приложения — другие приложения и браузер не затрагиваются.",
                "When Telegram is slow or blocked on your network, " + Brand.name + " routes messages, files and calls through CloudVPN servers. "
                        + "Only this app's traffic goes through the bypass."), 15, fg));

        // ---- CloudVPN account: the bypass is part of the subscription
        account = text(context, "", 15, fg);
        account.setPadding(0, AndroidUtilities.dp(14), 0, AndroidUtilities.dp(4));
        box.addView(account);
        accountButton = new Button(context);
        accountButton.setOnClickListener(v -> onAccountButton());
        box.addView(accountButton, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        codeBox = new LinearLayout(context);
        codeBox.setOrientation(LinearLayout.VERTICAL);
        codeBox.setVisibility(View.GONE);
        codeText = text(context, "", 15, fg);
        codeText.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(8));
        codeBox.addView(codeText);
        final Button openCabinet = new Button(context);
        openCabinet.setText(Brand.t("Открыть кабинет и подтвердить", "Open the cabinet and confirm"));
        openCabinet.setOnClickListener(v -> open(verificationUrl));
        codeBox.addView(openCabinet, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        final Button viaBot = new Button(context);
        viaBot.setText(Brand.t("Подтвердить через бота", "Confirm in the bot"));
        viaBot.setOnClickListener(v -> open(telegramUrl));
        codeBox.addView(viaBot, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
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
            return;
        }
        accountButton.setEnabled(false);
        Account.startLogin(c, new Account.LoginListener() {
            @Override
            public void onCode(String userCode, String vUrl, String tgUrl) {
                verificationUrl = vUrl;
                telegramUrl = tgUrl;
                codeText.setText(Brand.t("Код для входа: ", "Sign-in code: ") + userCode + "\n"
                        + Brand.t("Подтвердите его в кабинете CloudVPN (или в боте, если Telegram уже работает). Жду подтверждения…",
                        "Confirm it in the CloudVPN cabinet (or in the bot if Telegram already works). Waiting…"));
                codeBox.setVisibility(View.VISIBLE);
            }

            @Override
            public void onDone(boolean ok, String error) {
                codeBox.setVisibility(View.GONE);
                accountButton.setEnabled(true);
                if (!ok && error != null) {
                    codeText.setText(error);
                    codeBox.setVisibility(View.VISIBLE);
                }
                update();
            }
        });
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
        account.setText(in ? Brand.t("Аккаунт CloudVPN: ", "CloudVPN account: ") + (who.isEmpty() ? Brand.t("вход выполнен", "signed in") : who)
                : Brand.t("Обход входит в подписку CloudVPN. Войдите в аккаунт — серверы обхода возьмутся из вашей подписки.",
                "The bypass is part of the CloudVPN subscription. Sign in — the bypass servers come from your subscription."));
        accountButton.setText(in ? Brand.t("Выйти из аккаунта", "Sign out") : Brand.t("Войти через CloudVPN", "Sign in with CloudVPN"));
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
