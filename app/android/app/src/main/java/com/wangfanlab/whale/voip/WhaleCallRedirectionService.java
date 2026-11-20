package com.wangfanlab.whale.voip;

import android.content.ComponentName;
import android.net.Uri;
import android.telecom.CallRedirectionService;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;

import com.wangfanlab.whale.modules.voip.RedirectCandidate;
import com.wangfanlab.whale.modules.voip.RedirectDecision;
import com.wangfanlab.whale.modules.voip.RedirectionLogic;
import com.wangfanlab.whale.modules.voip.VoipAccounts;

import java.util.ArrayList;
import java.util.List;

/**
 * 拨号→VoIP 重定向（HANDOFF 二节，路A配套）。原厂拨号器发起呼叫时，Telecom 咨询
 * 本服务；这里把呼叫重定向到 whale 自己的 managed 承接账户（whale-in-managed，
 * FakeConnectionService），号码原样携带 → 呼叫由 whale VoIP 承接且走原厂 InCallUI。
 *
 * 与参考件差异（按 android.jar 实际 API 修正）：
 * 参考件伪码 redirectCallTo(whale)/placeCall(handle, account, null) 在 SDK 中不存在；
 * 真实 API 为 redirectCall(Uri, PhoneAccountHandle, boolean) / placeCallUnmodified()。
 *
 * 决策规则（纯逻辑见 RedirectionLogic，可 JVM 单测）：
 * - 仅当 whale-in-managed 出现在 call-capable 列表（=已启用）才接管；
 * - whale 自己发起的呼叫（拨 1 打 AI 等）不接管，避免自我拦截；
 * - 紧急呼叫 Telecom 本就绕过重定向服务（API 约定），永不到达此处；
 * - 拿不准一律 placeCallUnmodified()，绝不让正常拨号落入失败路径。
 *
 * 启用（测试期，披露留痕操作，见验证预案文档）：
 *   adb shell cmd telecom set-test-call-redirection-app com.wangfanlab.whale
 * 正式启用（本阶段不做，待 Tob/用户批）：
 *   RoleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_REDIRECTION) 用户授权。
 */
public class WhaleCallRedirectionService extends CallRedirectionService {
    private static final String TAG = "WhaleVoip";

    @Override
    public void onPlaceCall(Uri handle, PhoneAccountHandle initialPhoneAccount,
                            boolean allowInteractiveResponse) {
        FileLog.init(this);
        TelecomManager tm = getSystemService(TelecomManager.class);

        boolean initiatedByWhale = false;
        if (initialPhoneAccount != null) {
            ComponentName c = initialPhoneAccount.getComponentName();
            initiatedByWhale = c != null
                    && VoipAccounts.WHALE_PACKAGE.equals(c.getPackageName());
        }

        List<RedirectCandidate> candidates = new ArrayList<>();
        PhoneAccountHandle managedTarget = null;
        try {
            List<PhoneAccountHandle> accts = tm.getCallCapablePhoneAccounts();
            if (accts != null) {
                for (PhoneAccountHandle h : accts) {
                    ComponentName c = h.getComponentName();
                    if (c == null) continue;
                    candidates.add(new RedirectCandidate(
                            c.getPackageName(), c.getClassName(), h.getId()));
                    if (VoipAccounts.WHALE_PACKAGE.equals(c.getPackageName())
                            && VoipAccounts.CONNECTION_SERVICE_CLASS.equals(c.getClassName())
                            && VoipAccounts.ID_MANAGED_IN.equals(h.getId())) {
                        managedTarget = h;
                    }
                }
            }
        } catch (Throwable t) {
            FileLog.e("getCallCapablePhoneAccounts failed → pass through", t);
        }

        RedirectDecision decision = RedirectionLogic.decide(candidates, initiatedByWhale);
        if (decision instanceof RedirectDecision.Redirect && managedTarget != null) {
            // confirmFirst=false：测试期免确认直接承接（override 属显式测试配置）。
            FileLog.i("redirectCall → whale-in-managed (stock InCallUI), number masked");
            redirectCall(handle, managedTarget, false);
        } else {
            FileLog.i("placeCallUnmodified (whale managed account not call-capable"
                    + " or call initiated by whale)");
            placeCallUnmodified();
        }
    }
}
