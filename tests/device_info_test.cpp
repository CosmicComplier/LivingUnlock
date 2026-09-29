#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <winsock2.h>
#include <windows.h>
#include "device_info.h"
#include "rfcomm_server.h"
#include "broker_protocol.h"
#include <stdexcept>
#include <iostream>
#include <thread>
#include <chrono>
static void Require(bool ok){if(!ok)throw std::runtime_error("Device info/deadline test failed");}
int main(){
    try {
        std::vector<std::uint8_t> key(32,42);
        const auto first=lockpin::phone::EncryptDeviceInfo("{\"v\":1}",key);
        const auto second=lockpin::phone::EncryptDeviceInfo("{\"v\":1}",key);
        Require(first.size()==39 && first!=second);
        Require(lockpin::phone::InfoUtf8(L"\u6D4B\u8BD5") == "\xE6\xB5\x8B\xE8\xAF\x95");
        WSADATA data{};Require(WSAStartup(MAKEWORD(2,2),&data)==0);
        SOCKET listener=socket(AF_INET,SOCK_STREAM,IPPROTO_TCP);Require(listener!=INVALID_SOCKET);
        sockaddr_in addr{};addr.sin_family=AF_INET;addr.sin_addr.s_addr=htonl(INADDR_LOOPBACK);
        Require(bind(listener,reinterpret_cast<sockaddr*>(&addr),sizeof(addr))==0);Require(listen(listener,1)==0);
        int size=sizeof(addr);Require(getsockname(listener,reinterpret_cast<sockaddr*>(&addr),&size)==0);
        SOCKET sender=socket(AF_INET,SOCK_STREAM,IPPROTO_TCP);
        Require(connect(sender,reinterpret_cast<sockaddr*>(&addr),sizeof(addr))==0);
        SOCKET receiver=accept(listener,nullptr,nullptr);Require(receiver!=INVALID_SOCKET);
        const auto wire=lockpin::phone::EncodeFrame({lockpin::phone::MessageType::Ping,1,{1,2,3}});
        Require(send(sender,reinterpret_cast<const char*>(wire.data()),static_cast<int>(wire.size()),0)==static_cast<int>(wire.size()));
        std::vector<std::uint8_t> received;
        Require(lockpin::phone::ReceiveFrameUntil(receiver,received,GetTickCount64()+500) && received==wire);
        // A partial frame must not reset the total deadline for its payload.
        Require(send(sender,reinterpret_cast<const char*>(wire.data()),16,0)==16);
        auto start=GetTickCount64();
        Require(!lockpin::phone::ReceiveFrameUntil(receiver,received,start+120));
        Require(GetTickCount64()-start<1500);
        closesocket(sender);closesocket(receiver);closesocket(listener);WSACleanup();
        std::cout<<"PASS: metadata encryption nonce uniqueness, UTF-8, complete frame and absolute partial-frame timeout\n";
    }catch(const std::exception& e){std::cerr<<e.what();return 1;}
}
