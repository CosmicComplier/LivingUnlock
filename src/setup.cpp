#include "vault.h"
#include "totp.h"
#include "base32.h"
#include "provisioning.h"
#include "qr_bitmap.h"
#include <bcrypt.h>
#include <array>
#include <string>

namespace {
struct DialogState {
    lockpin::Record record;
    HBITMAP qrBitmap = nullptr;
};
void ClearQr(HWND dialog, DialogState& state) noexcept {
    SendDlgItemMessageW(dialog, 1006, STM_SETIMAGE, IMAGE_BITMAP, 0);
    if (!state.qrBitmap) return;
    DIBSECTION section{};
    if (GetObjectW(state.qrBitmap, sizeof(section), &section) == sizeof(section) && section.dsBm.bmBits) {
        const auto bytes = static_cast<std::size_t>(section.dsBm.bmWidthBytes) *
            static_cast<std::size_t>(section.dsBm.bmHeight < 0 ? -section.dsBm.bmHeight : section.dsBm.bmHeight);
        SecureZeroMemory(section.dsBm.bmBits, bytes);
    }
    DeleteObject(state.qrBitmap);
    state.qrBitmap = nullptr;
}
void ClearFields(HWND dialog) {
    SetDlgItemTextW(dialog, 1002, L""); SetDlgItemTextW(dialog, 1003, L""); SetDlgItemTextW(dialog, 1004, L"");
}
void Bind(HWND dialog, DialogState& state) {
    auto& record = state.record;
    struct Inputs {
        wchar_t email[480]{};
        wchar_t code[7]{};
        ~Inputs() { lockpin::Wipe(*this); }
    } inputs;
    GetDlgItemTextW(dialog, 1001, inputs.email, static_cast<int>(std::size(inputs.email)));
    GetDlgItemTextW(dialog, 1002, record.password, static_cast<int>(std::size(record.password)));
    GetDlgItemTextW(dialog, 1004, inputs.code, static_cast<int>(std::size(inputs.code)));
    if (!wcschr(inputs.email, L'@') || wcschr(inputs.email, L'\\') || !record.password[0] || wcslen(inputs.code) != 6)
        throw std::runtime_error("Incomplete input");
    lockpin::Secret key(std::begin(record.secret), std::end(record.secret));
    std::array<char, 6> code{};
    for (std::size_t i = 0; i < code.size(); ++i) code[i] = static_cast<char>(inputs.code[i]);
    const auto now = lockpin::UnixNow();
    std::optional<std::uint64_t> matched;
    try { matched = lockpin::MatchTotp(key, std::string_view(code.data(), code.size()), now); }
    catch (...) { SecureZeroMemory(key.data(), key.size()); lockpin::Wipe(code); throw; }
    SecureZeroMemory(key.data(), key.size()); lockpin::Wipe(code);
    if (!matched) {
        SetDlgItemTextW(dialog, 1005, L"验证码不匹配，请检查设置密钥、类型和手机时间。");
        return;
    }
    lockpin::Handle token;
    if (!LogonUserW(inputs.email, L"MicrosoftAccount", record.password,
        LOGON32_LOGON_INTERACTIVE, LOGON32_PROVIDER_DEFAULT, &token.value)) {
        const auto error = GetLastError();
        const auto message = L"Windows 账户验证失败（错误 " + std::to_wstring(error) + L"），尚未保存。";
        SetDlgItemTextW(dialog, 1005, message.c_str());
        return;
    }
    if (lockpin::TokenSid(token.value) != record.sid) {
        SetDlgItemTextW(dialog, 1005, L"该账户不是当前 Windows 用户，尚未保存。");
        return;
    }
    const auto qualified = std::wstring(L"MicrosoftAccount\\") + inputs.email;
    WinCheck(wcscpy_s(record.username, qualified.c_str()) == 0);
    const bool replacing = lockpin::IsEnrolled(record.sid);
    if (replacing && MessageBoxW(dialog,
        L"当前用户已经绑定。替换后旧手机条目将失效，是否保存新绑定？",
        L"替换当前用户的绑定", MB_YESNO | MB_ICONQUESTION | MB_DEFBUTTON2) != IDYES) return;
    record.policy.lastSeen = now;
    lockpin::Accept(record.policy, *matched); // Enrollment code cannot immediately be reused for logon.
    lockpin::Enroll(record);
    ClearFields(dialog);
    ClearQr(dialog, state);
    const wchar_t* success = replacing
        ? L"新绑定已保存。请删除 Google Authenticator 中旧的 WindowsLockPin 条目，以免混淆。\n等待手机生成下一枚验证码后再测试；原生 PIN 保留。"
        : L"绑定已保存。可在“登录选项”中选择手机验证码。\n请等待手机生成下一枚验证码再测试；原生 PIN 保留。";
    MessageBoxW(dialog, success,
        L"WindowsLockPin", MB_OK | MB_ICONINFORMATION);
    EndDialog(dialog, IDOK);
}
INT_PTR CALLBACK Dialog(HWND dialog, UINT message, WPARAM wparam, LPARAM lparam) {
    auto* state = reinterpret_cast<DialogState*>(GetWindowLongPtrW(dialog, GWLP_USERDATA));
    if (message == WM_INITDIALOG) {
        try {
        state = reinterpret_cast<DialogState*>(lparam);
        SetWindowLongPtrW(dialog, GWLP_USERDATA, lparam);
        SendDlgItemMessageW(dialog, 1001, EM_SETLIMITTEXT, 479, 0);
        SendDlgItemMessageW(dialog, 1002, EM_SETLIMITTEXT, 1023, 0);
        SendDlgItemMessageW(dialog, 1004, EM_SETLIMITTEXT, 6, 0);
        auto key = lockpin::Base32(state->record.secret, sizeof(state->record.secret));
        auto provisioning = lockpin::BuildProvisioning(key);
        try {
            SetDlgItemTextW(dialog, 1003, key.c_str());
            const auto caption = L"扫码添加 " + provisioning.accountLabel + L"\n二维码只用于绑定，解锁时输入 6 位码";
            SetDlgItemTextW(dialog, 1007, caption.c_str());
            RECT area{};
            WinCheck(GetClientRect(GetDlgItem(dialog, 1006), &area));
            state->qrBitmap = lockpin::CreateQrBitmap(provisioning.uri, area.right, area.bottom);
            SendDlgItemMessageW(dialog, 1006, STM_SETIMAGE, IMAGE_BITMAP,
                reinterpret_cast<LPARAM>(state->qrBitmap));
        } catch (...) {
            SecureZeroMemory(provisioning.uri.data(), provisioning.uri.size());
            SecureZeroMemory(key.data(), key.size() * sizeof(wchar_t));
            throw;
        }
        SecureZeroMemory(provisioning.uri.data(), provisioning.uri.size());
        SecureZeroMemory(key.data(), key.size() * sizeof(wchar_t));
        return TRUE;
        } catch (...) { EndDialog(dialog, -1); return TRUE; }
    }
    if (message == WM_COMMAND && LOWORD(wparam) == IDOK && state) {
        EnableWindow(GetDlgItem(dialog, IDOK), FALSE);
        SetDlgItemTextW(dialog, 1005, L"正在验证，请稍候……");
        try { Bind(dialog, *state); }
        catch (...) { SetDlgItemTextW(dialog, 1005, L"绑定未完成。请检查输入、权限和本机登录配置。"); }
        lockpin::Wipe(state->record.password);
        if (IsWindow(dialog)) {
            SetDlgItemTextW(dialog, 1002, L"");
            EnableWindow(GetDlgItem(dialog, IDOK), TRUE);
        }
        return TRUE;
    }
    if ((message == WM_COMMAND && LOWORD(wparam) == IDCANCEL) || message == WM_CLOSE) {
        ClearFields(dialog);
        if (state) ClearQr(dialog, *state);
        EndDialog(dialog, IDCANCEL); return TRUE;
    }
    if (message == WM_DESTROY && state) ClearQr(dialog, *state);
    return FALSE;
}
}
int WINAPI wWinMain(HINSTANCE instance, HINSTANCE, PWSTR, int) {
    try {
        DialogState state;
        const auto sid = lockpin::CurrentSid();
        WinCheck(wcscpy_s(state.record.sid, sid.c_str()) == 0);
        WinCheck(BCryptGenRandom(nullptr, state.record.secret, sizeof(state.record.secret), BCRYPT_USE_SYSTEM_PREFERRED_RNG) >= 0);
        const auto result = DialogBoxParamW(instance, MAKEINTRESOURCEW(101), nullptr, Dialog,
            reinterpret_cast<LPARAM>(&state));
        return result == IDOK ? 0 : 1;
    } catch (...) {
        MessageBoxW(nullptr, L"初始化失败。请在目标 Windows 用户下运行绑定工具。", L"WindowsLockPin", MB_OK | MB_ICONERROR);
        return 1;
    }
}
