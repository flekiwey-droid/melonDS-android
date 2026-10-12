/*
 * JNI bridge for DS local wireless multiplayer.
 *
 * The melonDS core already contains a complete UDP (ENet) based implementation of the Platform::MP_* layer: the LAN
 * MPInterface used by the desktop frontend (host/client roles, player IDs, CMD/reply/ACK handling, reply aggregation
 * and timeouts). This file exposes it to Kotlin so that the Android frontend can run it over a Wi-Fi Direct group
 * (host = group owner, 192.168.49.1) or over a regular shared Wi-Fi network.
 */
#include <jni.h>
#include <atomic>
#include <chrono>
#include <mutex>
#include <string>
#include <cstring>
#include <arpa/inet.h>
#include "net/MPInterface.h"
#include "net/LAN.h"
#include "LocalMultiplayer.h"

using namespace melonDS;

static std::atomic<int64_t> lastEmulatorFrameMs { 0 };
static std::mutex lobbyMutex;

static int64_t nowMs()
{
    return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
}

static bool isEmulatorPumping()
{
    return nowMs() - lastEmulatorFrameMs.load() < 500;
}

static LAN* currentLan()
{
    if (MPInterface::GetType() != MPInterface_LAN)
        return nullptr;
    return (LAN*) &MPInterface::Get();
}

static LAN* ensureLan()
{
    if (MPInterface::GetType() != MPInterface_LAN)
        MPInterface::Set(MPInterface_LAN);
    return currentLan();
}

static std::string ipToString(u32 address)
{
    // ENet/LAN store addresses in network byte order
    char buffer[INET_ADDRSTRLEN] = {0};
    in_addr addr {};
    addr.s_addr = address;
    inet_ntop(AF_INET, &addr, buffer, sizeof(buffer));
    return buffer;
}

namespace LocalMultiplayer
{
    void notifyEmulatorFrame()
    {
        lastEmulatorFrameMs.store(nowMs());
    }
}

extern "C"
{
JNIEXPORT jboolean JNICALL
Java_me_magnum_melonds_MelonLocalMultiplayer_startHost(JNIEnv* env, jobject thiz, jstring playerName, jint maxPlayers)
{
    std::lock_guard<std::mutex> guard(lobbyMutex);
    if (isEmulatorPumping()) return JNI_FALSE;

    LAN* lan = ensureLan();
    if (!lan) return JNI_FALSE;
    lan->EndSession();

    const char* name = env->GetStringUTFChars(playerName, nullptr);
    bool result = lan->StartHost(name, maxPlayers);
    env->ReleaseStringUTFChars(playerName, name);
    return result ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_me_magnum_melonds_MelonLocalMultiplayer_startClient(JNIEnv* env, jobject thiz, jstring playerName, jstring hostAddress)
{
    std::lock_guard<std::mutex> guard(lobbyMutex);
    if (isEmulatorPumping()) return JNI_FALSE;

    LAN* lan = ensureLan();
    if (!lan) return JNI_FALSE;
    lan->EndSession();

    const char* name = env->GetStringUTFChars(playerName, nullptr);
    const char* host = env->GetStringUTFChars(hostAddress, nullptr);
    bool result = lan->StartClient(name, host);
    env->ReleaseStringUTFChars(playerName, name);
    env->ReleaseStringUTFChars(hostAddress, host);
    return result ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_me_magnum_melonds_MelonLocalMultiplayer_startDiscovery(JNIEnv* env, jobject thiz)
{
    std::lock_guard<std::mutex> guard(lobbyMutex);
    if (isEmulatorPumping()) return JNI_FALSE;
    LAN* lan = ensureLan();
    return (lan && lan->StartDiscovery()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_me_magnum_melonds_MelonLocalMultiplayer_endDiscovery(JNIEnv* env, jobject thiz)
{
    std::lock_guard<std::mutex> guard(lobbyMutex);
    if (LAN* lan = currentLan()) lan->EndDiscovery();
}

JNIEXPORT void JNICALL
Java_me_magnum_melonds_MelonLocalMultiplayer_endSession(JNIEnv* env, jobject thiz)
{
    std::lock_guard<std::mutex> guard(lobbyMutex);
    if (isEmulatorPumping()) return;
    if (MPInterface::GetType() != MPInterface_Dummy)
    {
        // Destroying the LAN interface ends the session and deinitializes ENet
        MPInterface::Set(MPInterface_Dummy);
    }
}

JNIEXPORT void JNICALL
Java_me_magnum_melonds_MelonLocalMultiplayer_process(JNIEnv* env, jobject thiz)
{
    std::lock_guard<std::mutex> guard(lobbyMutex);
    // While a game is running, the emulation thread already calls MPInterface::Process() every frame
    if (isEmulatorPumping()) return;
    if (LAN* lan = currentLan()) lan->Process();
}

/**
 * Returns the player list as an array of strings with the format "id|name|status|address|ping|isLocal".
 */
JNIEXPORT jobjectArray JNICALL
Java_me_magnum_melonds_MelonLocalMultiplayer_getPlayers(JNIEnv* env, jobject thiz)
{
    jclass stringClass = env->FindClass("java/lang/String");
    LAN* lan = currentLan();
    if (!lan) return env->NewObjectArray(0, stringClass, nullptr);

    auto players = lan->GetPlayerList();
    jobjectArray result = env->NewObjectArray((jsize) players.size(), stringClass, nullptr);
    for (size_t i = 0; i < players.size(); i++)
    {
        const auto& p = players[i];
        std::string name(p.Name, strnlen(p.Name, sizeof(p.Name)));
        for (auto& c : name) if (c == '|') c = '_';
        std::string entry = std::to_string(p.ID) + "|" + name + "|" + std::to_string((int) p.Status) + "|" + ipToString(p.Address) + "|" + std::to_string(p.Ping) + "|" + (p.IsLocalPlayer ? "1" : "0");
        jstring s = env->NewStringUTF(entry.c_str());
        env->SetObjectArrayElement(result, (jsize) i, s);
        env->DeleteLocalRef(s);
    }
    return result;
}

/**
 * Returns sessions found via LAN broadcast discovery as "address|sessionName|numPlayers|maxPlayers".
 */
JNIEXPORT jobjectArray JNICALL
Java_me_magnum_melonds_MelonLocalMultiplayer_getDiscoveredSessions(JNIEnv* env, jobject thiz)
{
    jclass stringClass = env->FindClass("java/lang/String");
    LAN* lan = currentLan();
    if (!lan) return env->NewObjectArray(0, stringClass, nullptr);

    auto sessions = lan->GetDiscoveryList();
    jobjectArray result = env->NewObjectArray((jsize) sessions.size(), stringClass, nullptr);
    jsize index = 0;
    for (const auto& [address, data] : sessions)
    {
        std::string name(data.SessionName, strnlen(data.SessionName, sizeof(data.SessionName)));
        for (auto& c : name) if (c == '|') c = '_';
        // discovery keys are stored in host byte order
        std::string entry = ipToString(htonl(address)) + "|" + name + "|" + std::to_string(data.NumPlayers) + "|" + std::to_string(data.MaxPlayers);
        jstring s = env->NewStringUTF(entry.c_str());
        env->SetObjectArrayElement(result, index++, s);
        env->DeleteLocalRef(s);
    }
    return result;
}

JNIEXPORT jint JNICALL
Java_me_magnum_melonds_MelonLocalMultiplayer_getMaxPlayers(JNIEnv* env, jobject thiz)
{
    LAN* lan = currentLan();
    return lan ? lan->GetMaxPlayers() : 0;
}
}
