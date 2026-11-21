package com.wangfanlab.whale.voip;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.util.Log;

import java.util.Collections;
import java.util.List;

import com.wangfanlab.whale.modules.voip.IncomingRoutePolicy;
import com.wangfanlab.whale.modules.voip.VoipAccounts;

public final class CallAccountHelper {
    private static final String TAG = "WhaleVoip";
    /** Existing self-managed account for fake incoming ring. */
    public static final String ACCOUNT_ID = VoipAccounts.ID_SELF_MANAGED;
    /** Managed CallProvider for dial-1 → system InCall UI. */
    public static final String ACCOUNT_ID_AI_OUT = VoipAccounts.ID_AI_OUT;
    /**
     * Managed(CallProvider) 来电/重定向承接账户（HANDOFF 路A）：来电经它上报 Telecom
     * → 原厂 InCallUI 接管（荣耀动画+系统默认铃声）；拨号重定向也指向它（号码不限）。
     */
    public static final String ACCOUNT_ID_IN_MANAGED = VoipAccounts.ID_MANAGED_IN;

    private CallAccountHelper() {}

    public static PhoneAccountHandle handle(Context ctx) {
        return new PhoneAccountHandle(
                new ComponentName(ctx, FakeConnectionService.class), ACCOUNT_ID);
    }

    public static PhoneAccountHandle handleAiOut(Context ctx) {
        return new PhoneAccountHandle(
                new ComponentName(ctx, FakeConnectionService.class), ACCOUNT_ID_AI_OUT);
    }

    public static PhoneAccountHandle handleInManaged(Context ctx) {
        return new PhoneAccountHandle(
                new ComponentName(ctx, FakeConnectionService.class), ACCOUNT_ID_IN_MANAGED);
    }

    public static void ensureRegistered(Context ctx) {
        TelecomManager tm = (TelecomManager) ctx.getSystemService(Context.TELECOM_SERVICE);

        PhoneAccount self = new PhoneAccount.Builder(handle(ctx), "Nova 测试来电")
                .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                .setSupportedUriSchemes(Collections.singletonList(PhoneAccount.SCHEME_TEL))
                .setHighlightColor(Color.parseColor("#34A853"))
                .setShortDescription("Nova VoIP Call (self-managed)")
                .build();
        tm.registerPhoneAccount(self);
        Log.i(TAG, "PhoneAccount registered (self-managed): " + handle(ctx));

        // CallProvider: starts DISABLED on AOSP until user/root enables it.
        // When enabled + placeCall uses this handle, Dialer InCallServiceImpl binds
        // (true system call UI). Do NOT combine with CAPABILITY_SELF_MANAGED.
        PhoneAccount out = new PhoneAccount.Builder(handleAiOut(ctx), "Nova AI")
                .setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER)
                .setSupportedUriSchemes(Collections.singletonList(PhoneAccount.SCHEME_TEL))
                .setHighlightColor(Color.parseColor("#1A73E8"))
                .setShortDescription("拨 1 打 AI（托管，系统通话界面）")
                .build();
        tm.registerPhoneAccount(out);
        Log.i(TAG, "PhoneAccount registered (CallProvider nova-ai-out): " + handleAiOut(ctx)
                + " — enable in Settings → Calling accounts, or root-enable");

        // Managed(CallProvider) 来电承接账户（HANDOFF 路A）：初始 DISABLED，
        // 启用后来电经它上报 → 系统原厂 InCallUI 渲染（荣耀动画+默认铃声），
        // whale 不再自绘来电 UI。启用：Settings → 通话账户，或
        // adb shell cmd telecom set-phone-account-enabled \
        //   com.wangfanlab.whale/com.wangfanlab.whale.voip.FakeConnectionService whale-in-managed 0
        PhoneAccount inManaged = new PhoneAccount.Builder(handleInManaged(ctx), "Whale 来电")
                .setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER)
                .setSupportedUriSchemes(Collections.singletonList(PhoneAccount.SCHEME_TEL))
                .setHighlightColor(Color.parseColor("#1A73E8"))
                .setShortDescription("Whale VoIP 来电（托管，原厂通话界面+系统铃声）")
                .build();
        tm.registerPhoneAccount(inManaged);
        Log.i(TAG, "PhoneAccount registered (managed incoming whale-in-managed): "
                + handleInManaged(ctx) + " — starts DISABLED until user/adb enables");

        // Best-effort: make Nova AI the user-selected default outgoing account so
        // Dialer skips the multi-account picker. Needs MODIFY_PHONE_STATE (usually
        // only system/root XML patch); failure is non-fatal.
        preferAiOutgoingAccount(ctx);
    }

    /**
     * Prefer Nova AI as default outgoing PhoneAccount so Dialer does not show
     * SIM/account chooser when multiple CallProviders support tel:.
     */
    public static boolean preferAiOutgoingAccount(Context ctx) {
        TelecomManager tm = (TelecomManager) ctx.getSystemService(Context.TELECOM_SERVICE);
        PhoneAccountHandle ai = handleAiOut(ctx);
        try {
            java.lang.reflect.Method get = TelecomManager.class.getMethod(
                    "getUserSelectedOutgoingPhoneAccount");
            PhoneAccountHandle cur = (PhoneAccountHandle) get.invoke(tm);
            if (cur != null && ACCOUNT_ID_AI_OUT.equals(cur.getId())) {
                Log.i(TAG, "defaultOutgoing already Nova AI");
                return true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "getUserSelectedOutgoingPhoneAccount", t);
        }
        try {
            // Hidden / @SystemApi on many SDKs — invoke via reflection.
            java.lang.reflect.Method set = TelecomManager.class.getMethod(
                    "setUserSelectedOutgoingPhoneAccount", PhoneAccountHandle.class);
            set.invoke(tm, ai);
            Log.i(TAG, "setUserSelectedOutgoingPhoneAccount → nova-ai-out OK");
            return true;
        } catch (SecurityException se) {
            Log.w(TAG, "setUserSelectedOutgoingPhoneAccount needs MODIFY_PHONE_STATE"
                    + " (use root scripts/set-default-ai-account.sh)", se);
        } catch (Throwable t) {
            Log.w(TAG, "setUserSelectedOutgoingPhoneAccount failed", t);
        }
        return false;
    }

    public static void ring(Context ctx, String number, String displayName) {
        ring(ctx, number, displayName, BridgePrefs.getHost(ctx), BridgePrefs.getPort(ctx));
    }

    public static void ring(Context ctx, String number, String displayName,
                            String bridgeHost, int bridgePort) {
        ensureRegistered(ctx);
        TelecomManager tm = (TelecomManager) ctx.getSystemService(Context.TELECOM_SERVICE);
        // HANDOFF 路A：managed 承接账户已启用 → 以它上报，Telecom 绑定原厂 InCallUI
        // （荣耀动画+系统默认铃声）；未启用 → 回落 self-managed（whale 自绘+自播铃）。
        boolean managedEnabled = isInManagedEnabled(ctx);
        PhoneAccountHandle h = IncomingRoutePolicy.useStockInCallUi(managedEnabled)
                ? handleInManaged(ctx) : handle(ctx);
        Bundle extras = new Bundle();
        Uri addr = Uri.fromParts("tel", number, null);
        extras.putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, addr);
        extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, h);
        extras.putString("display_name", displayName);
        extras.putString("bridge_host", bridgeHost);
        extras.putInt("bridge_port", bridgePort);
        FileLog.i("addNewIncomingCall " + number + " / " + displayName
                + " handle=" + h.getId() + " managedUi=" + managedEnabled
                + " bridge=" + bridgeHost + ":" + bridgePort);
        tm.addNewIncomingCall(h, extras);
    }

    /**
     * managed 来电承接账户是否已启用（决定路A是否生效）。
     * 2026-11-21 magic8 实证：getPhoneAccount(handle).isEnabled() 在本机读不回注册表
     * 里明明 [[X]] 的 enabled 态（MagicOS 怪癖）→ 改以 getCallCapablePhoneAccounts()
     * 迭代判在列（call-capable = 已启用且可呼叫，与本判定语义一致）。
     */
    public static boolean isInManagedEnabled(Context ctx) {
        return isAccountEnabled(ctx, handleInManaged(ctx));
    }

    /** 账户在 call-capable 列表即在位且已启用（MagicOS getPhoneAccount.isEnabled 读不回的规避）。 */
    private static boolean isAccountEnabled(Context ctx, PhoneAccountHandle handle) {
        try {
            TelecomManager tm = (TelecomManager) ctx.getSystemService(Context.TELECOM_SERVICE);
            List<PhoneAccountHandle> capable = tm.getCallCapablePhoneAccounts();
            if (capable != null) {
                for (PhoneAccountHandle h : capable) {
                    if (h != null && handle.getId().equals(h.getId())) return true;
                }
            }
            return false;
        } catch (Throwable t) {
            Log.w(TAG, "isAccountEnabled check failed, assume disabled", t);
            return false;
        }
    }

    /** True if handle is our managed incoming/redirect 承接账户. */
    public static boolean isInManagedHandle(PhoneAccountHandle h) {
        return h != null && ACCOUNT_ID_IN_MANAGED.equals(h.getId());
    }

    /**
     * Place outgoing AI call via managed CallProvider account so system Dialer
     * InCall UI can bind. Falls back to self-managed placeCall if CallProvider
     * account is disabled (still works; UI becomes our InCallActivity).
     *
     * Always sets EXTRA_PHONE_ACCOUNT_HANDLE so Telecom never asks which account.
     */
    public static void placeOutgoingAi(Context ctx) {
        placeOutgoingAi(ctx, BridgePrefs.getHost(ctx), BridgePrefs.getPort(ctx));
    }

    public static void placeOutgoingAi(Context ctx, String bridgeHost, int bridgePort) {
        ensureRegistered(ctx);
        TelecomManager tm = (TelecomManager) ctx.getSystemService(Context.TELECOM_SERVICE);
        PhoneAccountHandle preferred = handleAiOut(ctx);
        boolean useProvider = isAccountEnabled(ctx, preferred);   /* 同 magic8 读不回怪癖的规避（与 isInManagedEnabled 同径） */
        PhoneAccountHandle h = useProvider ? preferred : handle(ctx);
        Uri addr = Uri.fromParts("tel", "1", null);
        Bundle extras = new Bundle();
        // Critical: pin account so multi-SIM / multi-CallProvider UI never appears.
        extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, h);
        extras.putString("display_name", "AI 助手");
        extras.putString("bridge_host", bridgeHost);
        extras.putInt("bridge_port", bridgePort);
        extras.putBoolean("nova_ai_outgoing", true);
        extras.putBoolean("nova_ai_managed", useProvider);
        FileLog.i("placeCall tel:1 handle=" + h.getId()
                + " managed=" + useProvider
                + " bridge=" + bridgeHost + ":" + bridgePort);
        try {
            FileLog.i("TelecomManager.placeCall invoking…");
            tm.placeCall(addr, extras);
            FileLog.i("TelecomManager.placeCall returned ok");
        } catch (RuntimeException e) {
            FileLog.e("TelecomManager.placeCall failed", e);
            throw e;
        }
    }

    /** True if handle is our managed Nova AI CallProvider. */
    public static boolean isAiOutHandle(PhoneAccountHandle h) {
        return h != null && ACCOUNT_ID_AI_OUT.equals(h.getId());
    }

    /** Debug helper: how many enabled CallProviders support tel. */
    public static int countCallCapableTelAccounts(Context ctx) {
        try {
            TelecomManager tm = (TelecomManager) ctx.getSystemService(Context.TELECOM_SERVICE);
            List<PhoneAccountHandle> list = tm.getCallCapablePhoneAccounts();
            return list == null ? 0 : list.size();
        } catch (Throwable t) {
            return -1;
        }
    }
}
