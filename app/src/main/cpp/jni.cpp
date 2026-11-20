// JNI 桥：把 Kotlin 侧的样本流接到原生封装/加密/核验实现上。
//
// 约定：
//  - 轨参数用紧凑字符串传递，避免在 C++ 里引入 JSON 解析器：
//      "1|video|1280|720|0|0|<csdBase64>;2|audio|0|0|44100|2|<csdBase64>"
//  - 返回值同样用 "key=value;..." 紧凑串，Kotlin 侧 split 即可。
#include <jni.h>

#include <cstdio>
#include <cstdlib>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "crypto.h"
#include "mp4.h"
#include "writer.h"

using fcc::Sample;
using fcc::SegmentWriter;
using fcc::Track;

namespace {

std::mutex gMutex;
std::map<int64_t, std::unique_ptr<SegmentWriter>> gWriters;
int64_t gNextHandle = 1;

std::string jstr(JNIEnv* env, jstring s) {
    if (s == nullptr) return std::string();
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out = (c != nullptr) ? std::string(c) : std::string();
    if (c != nullptr) env->ReleaseStringUTFChars(s, c);
    return out;
}

jstring newJstr(JNIEnv* env, const std::string& s) { return env->NewStringUTF(s.c_str()); }

std::vector<uint8_t> jbytes(JNIEnv* env, jbyteArray arr) {
    std::vector<uint8_t> out;
    if (arr == nullptr) return out;
    const jsize n = env->GetArrayLength(arr);
    out.resize(static_cast<size_t>(n));
    if (n > 0) env->GetByteArrayRegion(arr, 0, n, reinterpret_cast<jbyte*>(out.data()));
    return out;
}

jbyteArray newJbytes(JNIEnv* env, const std::vector<uint8_t>& v) {
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(v.size()));
    if (arr != nullptr && !v.empty()) {
        env->SetByteArrayRegion(arr, 0, static_cast<jsize>(v.size()),
                                reinterpret_cast<const jbyte*>(v.data()));
    }
    return arr;
}

std::vector<std::string> split(const std::string& s, char sep) {
    std::vector<std::string> out;
    size_t pos = 0;
    while (true) {
        size_t p = s.find(sep, pos);
        if (p == std::string::npos) {
            out.push_back(s.substr(pos));
            break;
        }
        out.push_back(s.substr(pos, p - pos));
        pos = p + 1;
    }
    return out;
}

/** 解析轨参数串；返回 false 表示格式非法。 */
bool parseTracks(const std::string& spec, std::vector<Track>& tracks) {
    if (spec.empty()) return false;
    for (const auto& item : split(spec, ';')) {
        if (item.empty()) continue;
        const std::vector<std::string> f = split(item, '|');
        if (f.size() < 6) return false;
        Track t;
        t.id = atoi(f[0].c_str());
        t.kind = f[1];
        t.width = atoi(f[2].c_str());
        t.height = atoi(f[3].c_str());
        t.sampleRate = atoi(f[4].c_str());
        t.channels = atoi(f[5].c_str());
        if (f.size() >= 7 && !f[6].empty()) t.csd = fcc::base64Decode(f[6]);
        if (t.id <= 0 || (t.kind != "video" && t.kind != "audio")) return false;
        tracks.push_back(std::move(t));
    }
    return !tracks.empty();
}

std::string joinInts(const std::vector<int>& v) {
    std::string s;
    for (size_t i = 0; i < v.size(); ++i) {
        if (i > 0) s += ",";
        s += std::to_string(v[i]);
    }
    return s;
}

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_forensic_camera_enc_NativeEnc_nativeSelfTest(JNIEnv* env, jclass) {
    return newJstr(env, fcc::cryptoSelfTest());
}

JNIEXPORT jbyteArray JNICALL
Java_com_forensic_camera_enc_NativeEnc_nativeBuildInit(JNIEnv* env, jclass, jstring tracksSpec) {
    std::vector<Track> tracks;
    if (!parseTracks(jstr(env, tracksSpec), tracks)) return newJbytes(env, {});
    fcc::Fmp4Writer w(tracks);
    return newJbytes(env, w.buildInit());
}

JNIEXPORT jbyteArray JNICALL
Java_com_forensic_camera_enc_NativeEnc_nativeAvcc(JNIEnv* env, jclass, jbyteArray data) {
    std::vector<uint8_t> src = jbytes(env, data);
    return newJbytes(env, fcc::toAvccSample(src.data(), src.size()));
}

JNIEXPORT jlong JNICALL
Java_com_forensic_camera_enc_NativeEnc_nativeCreate(JNIEnv* env, jclass, jstring dir, jbyteArray dek,
                                                    jstring tracksSpec, jlong segMs) {
    std::vector<Track> tracks;
    if (!parseTracks(jstr(env, tracksSpec), tracks)) return 0;
    std::vector<uint8_t> key = jbytes(env, dek);
    if (key.size() != fcc::kDekBytes) return 0;
    auto writer = std::make_unique<SegmentWriter>(jstr(env, dir), std::move(key), std::move(tracks),
                                                  segMs > 0 ? segMs : 2000);
    std::lock_guard<std::mutex> lock(gMutex);
    const int64_t handle = gNextHandle++;
    gWriters[handle] = std::move(writer);
    return handle;
}

JNIEXPORT jboolean JNICALL
Java_com_forensic_camera_enc_NativeEnc_nativeBegin(JNIEnv*, jclass, jlong handle) {
    std::lock_guard<std::mutex> lock(gMutex);
    auto it = gWriters.find(handle);
    if (it == gWriters.end()) return JNI_FALSE;
    return it->second->begin() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_forensic_camera_enc_NativeEnc_nativePush(JNIEnv* env, jclass, jlong handle, jint trackId,
                                                  jbyteArray data, jlong ptsUs, jboolean key) {
    std::lock_guard<std::mutex> lock(gMutex);
    auto it = gWriters.find(handle);
    if (it == gWriters.end()) return JNI_FALSE;
    Sample s;
    s.data = jbytes(env, data);
    s.pts = static_cast<int64_t>(ptsUs);
    s.key = (key == JNI_TRUE);
    if (s.data.empty()) return JNI_TRUE;  // 空样本直接丢弃，不视为失败
    it->second->push(static_cast<int>(trackId), std::move(s));
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_forensic_camera_enc_NativeEnc_nativeFinish(JNIEnv* env, jclass, jlong handle) {
    std::string out;
    {
        std::lock_guard<std::mutex> lock(gMutex);
        auto it = gWriters.find(handle);
        if (it != gWriters.end()) {
            fcc::Summary s = it->second->finish();
            out = "segCount=" + std::to_string(s.segCount) + ";segRoot=" + s.segRoot +
                  ";cipherSha=" + s.cipherSha + ";durationMs=" + std::to_string(s.durationMs) +
                  ";error=" + s.error;
            gWriters.erase(it);
        } else {
            out = "segCount=0;segRoot=;cipherSha=;durationMs=0;error=handle 无效";
        }
    }
    return newJstr(env, out);
}

JNIEXPORT void JNICALL
Java_com_forensic_camera_enc_NativeEnc_nativeRelease(JNIEnv*, jclass, jlong handle) {
    std::lock_guard<std::mutex> lock(gMutex);
    gWriters.erase(handle);
}

JNIEXPORT jstring JNICALL
Java_com_forensic_camera_enc_NativeEnc_nativeInspect(JNIEnv* env, jclass, jstring dir, jbyteArray dek,
                                                     jstring expectPlainSha, jboolean withPlain) {
    fcc::InspectResult r = fcc::inspectDir(jstr(env, dir), jbytes(env, dek),
                                           withPlain == JNI_TRUE, jstr(env, expectPlainSha));
    std::string out = "key=" + std::to_string(r.keyAvailable ? 1 : 0) +
                      ";segs=" + std::to_string(r.segCount) + ";bad=" + joinInts(r.badSeqs) +
                      ";missing=" + joinInts(r.missingSeqs) +
                      ";chain=" + std::to_string(r.chainOk ? 1 : 0) + ";root=" + r.recomputedRoot +
                      ";plain=" + r.plainSha +
                      ";interrupted=" + std::to_string(r.interrupted ? 1 : 0) + ";note=" + r.note;
    return newJstr(env, out);
}

JNIEXPORT jlong JNICALL
Java_com_forensic_camera_enc_NativeEnc_nativeRestore(JNIEnv* env, jclass, jstring dir, jbyteArray dek,
                                                     jstring dest) {
    int64_t total = -1;
    if (!fcc::restoreToFile(jstr(env, dir), jbytes(env, dek), jstr(env, dest), &total)) return -1;
    return static_cast<jlong>(total);
}

JNIEXPORT jstring JNICALL
Java_com_forensic_camera_enc_NativeEnc_nativePlainHash(JNIEnv* env, jclass, jstring dir,
                                                       jbyteArray dek) {
    return newJstr(env, fcc::hashPlaintext(jstr(env, dir), jbytes(env, dek)));
}

JNIEXPORT jstring JNICALL
Java_com_forensic_camera_enc_NativeEnc_nativeSha256File(JNIEnv* env, jclass, jstring path) {
    // 复用 writer 内部的流式摘要能力：这里以只读方式整文件哈希（导出后比对用）
    const std::string p = jstr(env, path);
    FILE* f = fopen(p.c_str(), "rb");
    if (f == nullptr) return newJstr(env, "");
    fcc::Sha256 h;
    uint8_t buf[64 * 1024];
    size_t n;
    while ((n = fread(buf, 1, sizeof(buf), f)) > 0) h.update(buf, n);
    fclose(f);
    uint8_t out[32];
    h.finish(out);
    return newJstr(env, fcc::toHex(out, sizeof(out)));
}

}  // extern "C"
