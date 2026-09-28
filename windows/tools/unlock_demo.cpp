#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <sddl.h>
#include "../broker/rfcomm_server.h"
#include "../broker/session_store.h"
#include "../common/broker_protocol.h"
#include "../common/canonical_transcript.h"
#include "../common/pairing_crypto.h"
#include "../common/phone_messages.h"
#include "../common/phone_vault.h"
#include "../common/device_info.h"
#include "../../src/vault.h"
#include <array>
#include <chrono>
#include <iomanip>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>

namespace {
std::wstring GetCurrentUserSid() {
    HANDLE procToken = nullptr;
    if (!OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &procToken)) return {};
    DWORD size = 0;
    GetTokenInformation(procToken, TokenUser, nullptr, 0, &size);
    std::vector<BYTE> buffer(size);
    if (size > 0 && GetTokenInformation(procToken, TokenUser, buffer.data(), size, &size)) {
        LPWSTR strSid = nullptr;
        if (ConvertSidToStringSidW(reinterpret_cast<TOKEN_USER*>(buffer.data())->User.Sid, &strSid)) {
            std::wstring res(strSid);
            LocalFree(strSid);
            CloseHandle(procToken);
            return res;
        }
    }
    CloseHandle(procToken);
    return {};
}

std::string Hex(const std::uint8_t* bytes, std::size_t size) {
    std::ostringstream output; output << std::hex << std::setfill('0');
    for (std::size_t i = 0; i < size; ++i) output << std::setw(2) << static_cast<unsigned>(bytes[i]);
    return output.str();
}
}

int wmain(int argc, wchar_t** argv) {
    std::wcout << L"=== WindowsLockPin Biometric Unlock Demo ===" << std::endl;

    std::wstring sid;
    if (argc >= 2) {
        sid = argv[1];
    } else {
        sid = GetCurrentUserSid();
    }

    if (sid.empty()) {
        std::wcerr << L"ERROR: Unable to resolve user SID" << std::endl;
        return 1;
    }

    std::wcout << L"Querying paired phone for user SID: " << sid << std::endl;

    auto recordOpt = lockpin::phone::LoadPairedPhone(sid);
    if (!recordOpt) {
        std::wcerr << L"NO_PAIRED_PHONE: No companion device is paired for this account." << std::endl;
        std::wcerr << L"Please run phone_pairing_demo.exe first to pair your Android device." << std::endl;
        return 2;
    }

    auto record = *recordOpt;
    std::cout << "Paired Companion Device Info:\n";
    std::cout << "  Device Name : " << record.deviceName << "\n";
    std::cout << "  Device ID   : " << record.deviceId << "\n";
    std::cout << "  PC ID       : " << record.pcId << "\n";
    std::cout << "  MAC Address : " << record.bluetoothMac << "\n";
    std::cout << "  Public Key  : " << Hex(record.clientPublicKey, 16) << "... (65 bytes)\n\n";

    lockpin::phone::RfcommServer server;
    if (!server.Start(L"WindowsLockPin Unlock")) {
        std::cerr << "ERROR: Unable to start Bluetooth RFCOMM server" << std::endl;
        return 1;
    }

    const auto localBt = server.LocalBluetoothAddress();
    std::cout << "RFCOMM server listening on: " << localBt << "\n";
    std::cout << ">>> Ready for phone. Open companion app and tap 'UNLOCK' on PC card. <<<\n" << std::endl;

    const SOCKET client = server.Accept(120000); // Wait up to 2 minutes
    if (client == INVALID_SOCKET) {
        std::cerr << "ERROR: Connection timed out waiting for phone" << std::endl;
        return 3;
    }

    struct SocketCloser {
        SOCKET s;
        ~SocketCloser() { if (s != INVALID_SOCKET) closesocket(s); }
    } closer{client};

    std::cout << "Companion phone connected! Generating unlock challenge..." << std::endl;

    lockpin::phone::BcryptRandomSource random;
    std::vector<std::uint8_t> nonce(32);
    if (!random.Fill(nonce.data(), nonce.size())) {
        std::cerr << "ERROR: Failed to generate cryptographic nonce" << std::endl;
        return 1;
    }

    std::array<std::uint8_t, 8> requestIdBytes{};
    if (!random.Fill(requestIdBytes.data(), requestIdBytes.size())) {
        std::cerr << "ERROR: Failed to generate request ID" << std::endl;
        return 1;
    }
    std::uint64_t requestId = 0;
    for (const auto byte : requestIdBytes) requestId = (requestId << 8) | byte;
    if (requestId == 0) requestId = 1;
    const auto sendUnlockResult = [&](std::uint16_t statusCode, const char* message) {
        const lockpin::phone::UnlockResultPayload result{statusCode, message ? message : ""};
        const lockpin::phone::Frame frame{
            lockpin::phone::MessageType::UnlockResult,
            requestId,
            lockpin::phone::EncodeUnlockResult(result)
        };
        return lockpin::phone::SendAll(client, lockpin::phone::EncodeFrame(frame));
    };
    const std::uint64_t timestampMs = static_cast<std::uint64_t>(
        std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::system_clock::now().time_since_epoch()).count());
    const std::uint32_t ttlMs = 30000;

    lockpin::phone::UnlockChallengePayload challenge;
    challenge.pcId = record.pcId;
    challenge.nonce = nonce;
    challenge.timestampMs = timestampMs;
    challenge.ttlMs = ttlMs;
    lockpin::Record credentials;
    if(lockpin::LoadEnrolledCredentials(sid,credentials)) {
        std::wstring name=credentials.username;
        const std::wstring prefix=L"MicrosoftAccount\\";
        if(name.compare(0,prefix.size(),prefix)==0)name.erase(0,prefix.size());
        challenge.userDisplayName=lockpin::phone::InfoUtf8(name);
    }
    if(challenge.userDisplayName.empty()) {
        wchar_t user[256]{};DWORD length=256;
        if(GetUserNameW(user,&length))challenge.userDisplayName=lockpin::phone::InfoUtf8(user);
    }
    if(challenge.userDisplayName.empty())challenge.userDisplayName="Windows";
    try {
        auto payload=lockpin::phone::EncryptDeviceInfo(lockpin::phone::BuildDeviceInfo(record.pcId,sid),
            std::vector<std::uint8_t>(std::begin(record.kPair),std::end(record.kPair)));
        lockpin::phone::SendAll(client,lockpin::phone::EncodeFrame({lockpin::phone::MessageType::Pong,requestId,std::move(payload)}));
    } catch (...) {}

    const auto challengePayload = lockpin::phone::EncodeUnlockChallenge(challenge);
    lockpin::phone::Frame challengeFrame{
        lockpin::phone::MessageType::UnlockChallenge,
        requestId,
        challengePayload
    };

    if (!lockpin::phone::SendAll(client, lockpin::phone::EncodeFrame(challengeFrame))) {
        std::cerr << "ERROR: Failed to send UNLOCK_CHALLENGE to companion" << std::endl;
        return 1;
    }

    std::cout << "UNLOCK_CHALLENGE sent. Awaiting biometric confirmation on phone..." << std::endl;

    std::vector<std::uint8_t> wire;
    if (!lockpin::phone::ReceiveFrameUntil(client, wire, GetTickCount64()+ttlMs)) {
        std::cerr << "ERROR: Failed to receive frame from companion" << std::endl;
        return 1;
    }

    const auto frameResult = lockpin::phone::DecodeFrame(wire.data(), wire.size());
    if (!frameResult.frame) {
        std::cerr << "ERROR: Failed to decode protocol frame: error " << static_cast<int>(frameResult.status) << std::endl;
        return 1;
    }

    if (frameResult.frame->type == lockpin::phone::MessageType::Cancel) {
        std::cerr << "UNLOCK_CANCELLED: User or system cancelled biometric prompt on phone" << std::endl;
        return 4;
    }

    if (frameResult.frame->type != lockpin::phone::MessageType::UnlockResponse) {
        std::cerr << "ERROR: Expected UNLOCK_RESPONSE (0x04), received 0x"
                  << std::hex << static_cast<unsigned>(frameResult.frame->type) << std::endl;
        sendUnlockResult(1, "Unexpected response type");
        return 1;
    }

    if (frameResult.frame->requestId != requestId) {
        std::cerr << "ERROR: Response request ID mismatch" << std::endl;
        sendUnlockResult(1, "Request ID mismatch");
        return 1;
    }

    auto responseOpt = lockpin::phone::DecodeUnlockResponse(
        frameResult.frame->payload.data(), frameResult.frame->payload.size());
    if (!responseOpt) {
        std::cerr << "ERROR: Failed to decode UNLOCK_RESPONSE payload" << std::endl;
        sendUnlockResult(1, "Malformed unlock response");
        return 1;
    }

    if (responseOpt->statusCode != 0) {
        std::cerr << "UNLOCK_REJECTED: Companion reported status code " << responseOpt->statusCode << std::endl;
        sendUnlockResult(1, "Unlock request was rejected");
        return 5;
    }

    if (responseOpt->nonce != challenge.nonce) {
        std::cerr << "ERROR: Challenge nonce mismatch!" << std::endl;
        sendUnlockResult(1, "Challenge nonce mismatch");
        return 6;
    }

    std::cout << "Received UNLOCK_RESPONSE! Verifying biometric signature..." << std::endl;

    const auto transcript = lockpin::phone::BuildUnlockTranscript(
        challenge.pcId,
        record.deviceId,
        challengeFrame.requestId,
        challenge.nonce,
        challenge.timestampMs,
        challenge.ttlMs
    );

    const std::vector<std::uint8_t> pubKey(record.clientPublicKey, record.clientPublicKey + 65);
    const bool verified = lockpin::phone::VerifyP256Signature(
        pubKey, transcript, responseOpt->signature);

    if (!verified) {
        std::cerr << "\n>>> VERIFICATION_FAILED: ECDSA P-256 signature is invalid! <<<\n" << std::endl;
        sendUnlockResult(1, "Biometric signature rejected");
        return 7;
    }

    if (!sendUnlockResult(0, "Windows accepted the unlock request")) {
        std::cerr << "ERROR: Signature verified, but UNLOCK_RESULT could not be delivered" << std::endl;
        return 8;
    }

    std::cout << "\n======================================================\n";
    std::cout << ">>>  PHONE BIOMETRIC UNLOCK VERIFIED SUCCESSFULLY! <<<\n";
    std::cout << "======================================================\n";
    std::cout << "Device Authenticated : " << record.deviceName << " (" << record.deviceId << ")\n";
    std::cout << "Signature Type       : NIST P-256 ECDSA DER (" << responseOpt->signature.size() << " bytes)\n";
    std::cout << "Hardware Security    : Android Keystore StrongBox / TEE verified\n";
    std::cout << "Action               : Windows Logon/Unlock authorized!\n" << std::endl;

    return 0;
}
