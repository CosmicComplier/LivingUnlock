#pragma once
#include <windows.h>
#include <bcrypt.h>
#include <string>
#include <vector>
#include <stdexcept>
#include "pairing_crypto.h"
namespace lockpin::phone {
inline std::string InfoUtf8(const std::wstring& v) {
    if(v.empty())return {};
    int n=WideCharToMultiByte(CP_UTF8,WC_ERR_INVALID_CHARS,v.data(),static_cast<int>(v.size()),nullptr,0,nullptr,nullptr);
    if(n<=0)return {};std::string out(static_cast<size_t>(n),'\0');
    WideCharToMultiByte(CP_UTF8,0,v.data(),static_cast<int>(v.size()),out.data(),n,nullptr,nullptr);return out;
}
inline std::string InfoJson(const std::wstring& v) {
    std::string out="\"";for(unsigned char c:InfoUtf8(v)) {
        if(c=='"'||c=='\\'){out+='\\';out+=static_cast<char>(c);}else if(c>=32)out+=static_cast<char>(c);
    }return out+'"';
}
inline std::wstring InfoRegistry(HKEY root,const std::wstring& path,const wchar_t* name) {
    wchar_t text[1024]{};DWORD bytes=sizeof(text);
    if(RegGetValueW(root,path.c_str(),name,RRF_RT_REG_SZ,nullptr,text,&bytes)!=ERROR_SUCCESS)return {};
    text[1023]=0;return text;
}
inline ULONGLONG InfoQword(HKEY root,const std::wstring& path,const wchar_t* name) {
    ULONGLONG n=0;DWORD bytes=sizeof(n);
    return RegGetValueW(root,path.c_str(),name,RRF_RT_REG_QWORD,nullptr,&n,&bytes)==ERROR_SUCCESS?n:0;
}
inline std::uint64_t InfoNowMs() {
    FILETIME ft{};GetSystemTimeAsFileTime(&ft);ULARGE_INTEGER v{};v.LowPart=ft.dwLowDateTime;v.HighPart=ft.dwHighDateTime;
    return (v.QuadPart-116444736000000000ULL)/10000;
}
inline std::string BuildDeviceInfo(const std::string& pcId,const std::wstring& sid) {
    const auto now=InfoNowMs();const auto boot=now-GetTickCount64();
    auto build=InfoRegistry(HKEY_LOCAL_MACHINE,L"SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion",L"CurrentBuildNumber");
    auto edition=InfoRegistry(HKEY_LOCAL_MACHINE,L"SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion",L"EditionID");
    auto os=std::wstring(_wtoi(build.c_str())>=22000?L"Windows 11 ":L"Windows 10 ")+edition+L" ("+build+L")";
    auto cpu=InfoRegistry(HKEY_LOCAL_MACHINE,L"HARDWARE\\DESCRIPTION\\System\\CentralProcessor\\0",L"ProcessorNameString");
    std::wstring gpu;DISPLAY_DEVICEW device{};device.cb=sizeof(device);
    for(DWORD i=0;i<16 && EnumDisplayDevicesW(nullptr,i,&device,0);++i) {
        if(!(device.StateFlags&DISPLAY_DEVICE_MIRRORING_DRIVER) && gpu.find(device.DeviceString)==std::wstring::npos) {
            if(!gpu.empty())gpu+=L" / ";gpu+=device.DeviceString;
        }device={};device.cb=sizeof(device);
    }
    MEMORYSTATUSEX mem{};mem.dwLength=sizeof(mem);GlobalMemoryStatusEx(&mem);
    auto key=sid+L"\\Software\\LivingUnlock";
    auto location=InfoRegistry(HKEY_USERS,key,L"BootLocation");
    auto captured=InfoQword(HKEY_USERS,key,L"LocationCapturedMs"),previousBoot=InfoQword(HKEY_USERS,key,L"LocationBootMs");
    bool current=InfoQword(HKEY_USERS,key,L"RecordBootLocation")==1 && captured>=boot && captured<=now &&
        (previousBoot>boot?previousBoot-boot:boot-previousBoot)<120000;
    if(!current)location.clear();
    return "{\"v\":1,\"pcId\":\""+pcId+"\",\"capturedMs\":"+std::to_string(now)+",\"bootMs\":"+std::to_string(boot)+
        ",\"os\":"+InfoJson(os)+",\"cpu\":"+InfoJson(cpu)+",\"gpu\":"+InfoJson(gpu)+
        ",\"memoryBytes\":"+std::to_string(mem.ullTotalPhys)+",\"bootLocation\":"+InfoJson(location)+
        ",\"locationCapturedMs\":"+std::to_string(current?captured:0)+"}";
}
inline std::vector<std::uint8_t> EncryptDeviceInfo(const std::string& json,const std::vector<std::uint8_t>& pairKey) {
    const std::string domain="LivingUnlock device info v1";
    auto key=HmacSha256(pairKey,std::vector<std::uint8_t>(domain.begin(),domain.end()));
    BCRYPT_ALG_HANDLE alg=nullptr;BCRYPT_KEY_HANDLE handle=nullptr;
    struct Cleanup {BCRYPT_ALG_HANDLE& a;BCRYPT_KEY_HANDLE& k;Sha256Digest& b;
        ~Cleanup(){if(k)BCryptDestroyKey(k);if(a)BCryptCloseAlgorithmProvider(a,0);SecureZeroMemory(b.data(),b.size());}} cleanup{alg,handle,key};
    auto ok=[](NTSTATUS s){if(s<0)throw std::runtime_error("Device metadata encryption failed");};
    ok(BCryptOpenAlgorithmProvider(&alg,BCRYPT_AES_ALGORITHM,nullptr,0));
    ok(BCryptSetProperty(alg,BCRYPT_CHAINING_MODE,reinterpret_cast<PUCHAR>(const_cast<wchar_t*>(BCRYPT_CHAIN_MODE_GCM)),sizeof(BCRYPT_CHAIN_MODE_GCM),0));
    ok(BCryptGenerateSymmetricKey(alg,&handle,nullptr,0,key.data(),static_cast<ULONG>(key.size()),0));
    std::vector<std::uint8_t> out(4+12+json.size()+16);out[0]='L';out[1]='U';out[2]='D';out[3]='I';
    ok(BCryptGenRandom(nullptr,out.data()+4,12,BCRYPT_USE_SYSTEM_PREFERRED_RNG));
    BCRYPT_AUTHENTICATED_CIPHER_MODE_INFO auth;BCRYPT_INIT_AUTH_MODE_INFO(auth);
    auth.pbNonce=out.data()+4;auth.cbNonce=12;auth.pbAuthData=out.data();auth.cbAuthData=4;
    auth.pbTag=out.data()+16+json.size();auth.cbTag=16;ULONG written=0;
    ok(BCryptEncrypt(handle,reinterpret_cast<PUCHAR>(const_cast<char*>(json.data())),static_cast<ULONG>(json.size()),&auth,nullptr,0,out.data()+16,static_cast<ULONG>(json.size()),&written,0));
    return out;
}
}
