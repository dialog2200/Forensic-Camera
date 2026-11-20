// 段写盘器 + 密文核验 + 明文还原。
//
// 与 v3.3.x Kotlin 版本保持逐字节一致的磁盘格式：
//   <dir>/init.mp4        ftyp+moov（明文，作为 seg 0 纳入段链）
//   <dir>/seg_00001.enc   FSG1(4)|seq(4 BE)|nonce(12)|tag(16)|ciphertext
//   <dir>/manifest.jsonl  逐行追加并 fsync
//   <dir>/summary.json    收尾摘要
//   <dir>/open.lock       录制未正常收尾的标记（存在即视为中断）
//
// 段链：link0 = SHA-256("E1" || initSha)；linkN = SHA-256(link(N-1) || ctSha || seq || nonce)
// 其中 "||" 表示按顺序拼接（link/ctSha/seq 取 ASCII 字节，nonce 取 12 字节原始值）。
#include "writer.h"

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <random>
#include <string>
#include <vector>

#if defined(_WIN32)
#include <direct.h>
#include <io.h>
#include <sys/stat.h>
#include <windows.h>
#else
#include <sys/stat.h>
#include <unistd.h>
#endif

#include "crypto.h"

namespace fcc {
namespace {

constexpr int kHeaderLen = 20;  // FSG1(4) + seq(4) + nonce(12)
constexpr int kTagLen = 16;
const char kSegMagic[4] = {'F', 'S', 'G', '1'};
const char kOpenLock[] = "open.lock";

/** 大小写无关比较（不依赖 strcasecmp，Windows 宿主同样可用）。 */
bool ieq(const std::string& a, const std::string& b) {
    if (a.size() != b.size()) return false;
    for (size_t i = 0; i < a.size(); ++i) {
        char x = a[i], y = b[i];
        if (x >= 'A' && x <= 'Z') x = static_cast<char>(x - 'A' + 'a');
        if (y >= 'A' && y <= 'Z') y = static_cast<char>(y - 'A' + 'a');
        if (x != y) return false;
    }
    return true;
}

std::string joinPath(const std::string& dir, const std::string& name) {
    if (dir.empty()) return name;
    const char c = dir.back();
    if (c == '/' || c == '\\') return dir + name;
    return dir + "/" + name;
}

bool fileExists(const std::string& path) {
    // 只做存在性判断，绝不把文件读进内存：核验 open.lock 不应触发一次读盘。
#if defined(_WIN32)
    struct _stat st;
    return _stat(path.c_str(), &st) == 0;
#else
    struct stat st;
    return stat(path.c_str(), &st) == 0;
#endif
}

bool ensureDir(const std::string& dir) {
    if (dir.empty() || fileExists(dir)) return true;
#if defined(_WIN32)
    _mkdir(dir.c_str());
#else
    mkdir(dir.c_str(), 0770);
#endif
    return fileExists(dir);
}

bool readAll(const std::string& path, std::vector<uint8_t>& out) {
    FILE* f = fopen(path.c_str(), "rb");
    if (f == nullptr) return false;
    out.clear();
    uint8_t buf[64 * 1024];
    size_t n;
    while ((n = fread(buf, 1, sizeof(buf), f)) > 0) out.insert(out.end(), buf, buf + n);
    bool ok = ferror(f) == 0;
    fclose(f);
    return ok;
}

void syncFile(FILE* f) {
    if (f == nullptr) return;
    fflush(f);
#if defined(_WIN32)
    _commit(_fileno(f));
#else
    fsync(fileno(f));
#endif
}

bool writeAllSynced(const std::string& path, const std::vector<uint8_t>& bytes) {
    FILE* f = fopen(path.c_str(), "wb");
    if (f == nullptr) return false;
    size_t n = bytes.empty() ? 0 : fwrite(bytes.data(), 1, bytes.size(), f);
    syncFile(f);
    bool ok = (n == bytes.size()) && (ferror(f) == 0);
    fclose(f);
    return ok;
}

bool appendLineSynced(FILE* f, const std::string& line) {
    if (f == nullptr) return false;
    if (fwrite(line.data(), 1, line.size(), f) != line.size()) return false;
    if (fwrite("\n", 1, 1, f) != 1) return false;
    syncFile(f);
    return true;
}

std::string sha256HexFile(const std::string& path) {
    FILE* f = fopen(path.c_str(), "rb");
    if (f == nullptr) return std::string();
    Sha256 h;
    uint8_t buf[64 * 1024];
    size_t n;
    while ((n = fread(buf, 1, sizeof(buf), f)) > 0) h.update(buf, n);
    bool ok = ferror(f) == 0;
    fclose(f);
    if (!ok) return std::string();
    uint8_t out[32];
    h.finish(out);
    return toHex(out, sizeof(out));
}

std::string sha256HexStr(const std::string& s) { return sha256Hex(s.data(), s.size()); }

/** 填充随机数：Android/Linux 走 /dev/urandom；Windows 宿主用 MSVC 的 random_device(RtlGenRandom)。 */
bool fillRandom(uint8_t* out, size_t n) {
#if defined(_WIN32)
    std::random_device rd;
    for (size_t i = 0; i < n; ++i) out[i] = static_cast<uint8_t>(rd() & 0xFF);
    return true;
#else
    if (n == 0) return true;
    FILE* f = fopen("/dev/urandom", "rb");
    if (f == nullptr) return false;
    size_t got = fread(out, 1, n, f);
    fclose(f);
    return got == n;  // 取不到足够熵则失败，绝不退化成可预测的伪随机
#endif
}

void putU32BE(std::vector<uint8_t>& b, uint32_t v) {
    b.push_back(static_cast<uint8_t>((v >> 24) & 0xFF));
    b.push_back(static_cast<uint8_t>((v >> 16) & 0xFF));
    b.push_back(static_cast<uint8_t>((v >> 8) & 0xFF));
    b.push_back(static_cast<uint8_t>(v & 0xFF));
}

uint32_t getU32BE(const uint8_t* p) {
    return (static_cast<uint32_t>(p[0]) << 24) | (static_cast<uint32_t>(p[1]) << 16) |
           (static_cast<uint32_t>(p[2]) << 8) | static_cast<uint32_t>(p[3]);
}

/** 段头（20 字节）同时作为 AEAD 的 AAD，改动 seq/nonce 会直接导致验签失败。 */
std::vector<uint8_t> makeHeader(int seq, const uint8_t nonce[kNonceBytes]) {
    std::vector<uint8_t> h;
    h.reserve(kHeaderLen);
    h.insert(h.end(), kSegMagic, kSegMagic + 4);
    putU32BE(h, static_cast<uint32_t>(seq));
    h.insert(h.end(), nonce, nonce + kNonceBytes);
    return h;
}

// ---- 极简 JSON 字段读取（只解析本实现自己写出的单行对象）----
bool jsonString(const std::string& line, const char* key, std::string& out) {
    std::string pat = std::string("\"") + key + "\"";
    size_t p = line.find(pat);
    if (p == std::string::npos) return false;
    p = line.find(':', p + pat.size());
    if (p == std::string::npos) return false;
    ++p;
    while (p < line.size() && (line[p] == ' ' || line[p] == '\t')) ++p;
    if (p >= line.size() || line[p] != '"') return false;
    ++p;
    std::string v;
    while (p < line.size() && line[p] != '"') {
        if (line[p] == '\\' && p + 1 < line.size()) {
            char c = line[p + 1];
            switch (c) {
                case 'n': v.push_back('\n'); break;
                case 't': v.push_back('\t'); break;
                case 'r': v.push_back('\r'); break;
                default: v.push_back(c); break;
            }
            p += 2;
            continue;
        }
        v.push_back(line[p++]);
    }
    out = std::move(v);
    return true;
}

bool jsonInt(const std::string& line, const char* key, int64_t& out) {
    std::string pat = std::string("\"") + key + "\"";
    size_t p = line.find(pat);
    if (p == std::string::npos) return false;
    p = line.find(':', p + pat.size());
    if (p == std::string::npos) return false;
    ++p;
    while (p < line.size() && (line[p] == ' ' || line[p] == '\t')) ++p;
    bool neg = false;
    if (p < line.size() && line[p] == '-') { neg = true; ++p; }
    int64_t v = 0;
    bool any = false;
    while (p < line.size() && line[p] >= '0' && line[p] <= '9') {
        v = v * 10 + (line[p] - '0');
        any = true;
        ++p;
    }
    if (!any) return false;
    out = neg ? -v : v;
    return true;
}

struct SegEntry {
    int seq = 0;
    std::string ctSha;
    std::string link;
};

/** 读取 manifest：忽略解析失败的行（断电时最后一行常不完整）。 */
std::vector<SegEntry> readManifest(const std::string& dir, std::string& initLink) {
    std::vector<SegEntry> out;
    std::vector<uint8_t> raw;
    if (!readAll(joinPath(dir, "manifest.jsonl"), raw)) return out;
    std::string text(raw.begin(), raw.end());
    size_t pos = 0;
    while (pos < text.size()) {
        size_t nl = text.find('\n', pos);
        std::string line = text.substr(pos, nl == std::string::npos ? std::string::npos : nl - pos);
        pos = (nl == std::string::npos) ? text.size() : nl + 1;
        if (line.empty()) continue;
        int64_t seq = -1;
        if (!jsonInt(line, "seq", seq)) continue;
        std::string link;
        jsonString(line, "link", link);
        if (seq == 0) {
            initLink = link;
            continue;
        }
        if (seq <= 0) continue;
        SegEntry e;
        e.seq = static_cast<int>(seq);
        jsonString(line, "ct", e.ctSha);
        e.link = link;
        out.push_back(std::move(e));
    }
    std::sort(out.begin(), out.end(), [](const SegEntry& a, const SegEntry& b) {
        return a.seq < b.seq;
    });
    return out;
}

/** 读取并校验一段：magic/seq/密文摘要/AEAD 标签，全过才算好段。 */
bool verifySegment(const std::vector<uint8_t>& dek, const std::string& path, int expectSeq,
                   const std::string& expectCtSha, std::vector<uint8_t>& plain,
                   std::vector<uint8_t>& nonce, std::string& ctSha) {
    std::vector<uint8_t> raw;
    if (!readAll(path, raw)) return false;
    if (raw.size() < static_cast<size_t>(kHeaderLen + kTagLen)) return false;
    if (memcmp(raw.data(), kSegMagic, 4) != 0) return false;
    if (static_cast<int>(getU32BE(raw.data() + 4)) != expectSeq) return false;
    nonce.assign(raw.begin() + 8, raw.begin() + kHeaderLen);
    const uint8_t* tag = raw.data() + kHeaderLen;
    const uint8_t* ct = raw.data() + kHeaderLen + kTagLen;
    const size_t ctLen = raw.size() - kHeaderLen - kTagLen;
    ctSha = sha256Hex(ct, ctLen);
    if (!expectCtSha.empty() && !ieq(ctSha, expectCtSha)) return false;
    return gcmOpen(dek.data(), nonce.data(), kNonceBytes, raw.data(), kHeaderLen, ct,
                   static_cast<int>(ctLen), tag, plain);
}

std::string chainNext(const std::string& link, const std::string& ctSha, int seq,
                      const std::vector<uint8_t>& nonce) {
    std::vector<uint8_t> in(link.begin(), link.end());
    in.insert(in.end(), ctSha.begin(), ctSha.end());
    const std::string seqStr = std::to_string(seq);
    in.insert(in.end(), seqStr.begin(), seqStr.end());
    in.insert(in.end(), nonce.begin(), nonce.end());
    return sha256Hex(in.data(), in.size());
}

/** 无密钥时的段结构核验：magic/段号/密文摘要。nonce 与 ctSha 都在明文头，链可重算。 */
bool inspectSegmentNoKey(const std::string& path, int expectSeq,
                         const std::string& expectCtSha, std::vector<uint8_t>& nonce,
                         std::string& ctSha) {
    std::vector<uint8_t> raw;
    if (!readAll(path, raw)) return false;
    if (raw.size() < static_cast<size_t>(kHeaderLen + kTagLen)) return false;
    if (memcmp(raw.data(), kSegMagic, 4) != 0) return false;
    if (static_cast<int>(getU32BE(raw.data() + 4)) != expectSeq) return false;
    nonce.assign(raw.begin() + 8, raw.begin() + kHeaderLen);
    const uint8_t* ct = raw.data() + kHeaderLen + kTagLen;
    ctSha = sha256Hex(ct, raw.size() - kHeaderLen - kTagLen);
    return expectCtSha.empty() || ieq(ctSha, expectCtSha);
}

// ---- 收尾时长补写 / manifest 段链重建 ----
// init.mp4 在录制开始时写出，此时总时长未知，mvhd/tkhd/mdhd duration 只能写 0。
// Android 相册/MediaStore 读不出分片 MP4 的时长，会显示 0 分钟。收尾时把总时长
// 写回 init.mp4（仅改 4/8 字节 duration，box 大小不变），并重算 segment chain。

uint64_t getU64BE(const uint8_t* p) {
    uint64_t v = 0;
    for (int i = 0; i < 8; ++i) v = (v << 8) | p[i];
    return v;
}

void setU32BE(uint8_t* p, uint32_t v) {
    p[0] = static_cast<uint8_t>(v >> 24);
    p[1] = static_cast<uint8_t>(v >> 16);
    p[2] = static_cast<uint8_t>(v >> 8);
    p[3] = static_cast<uint8_t>(v);
}

void setU64BE(uint8_t* p, uint64_t v) {
    for (int i = 7; i >= 0; --i) {
        p[i] = static_cast<uint8_t>(v & 0xFF);
        v >>= 8;
    }
}

/** 递归遍历 moov/trak/mdia/mvex，把 mvhd/tkhd/mdhd/mehd 的 duration 改为 dur（1e6 时间基）。 */
bool patchBoxDurations(uint8_t* p, size_t start, size_t end, uint64_t dur, int& patched) {
    size_t pos = start;
    while (pos + 8 <= end) {
        size_t size = getU32BE(p + pos);
        char type[5] = {0, 0, 0, 0, 0};
        memcpy(type, p + pos + 4, 4);
        size_t hdr = 8;
        if (size == 1) {
            if (pos + 16 > end) return false;
            uint64_t large = getU64BE(p + pos + 8);
            if (large < 16 || large > static_cast<uint64_t>(end - pos)) return false;
            size = static_cast<size_t>(large);
            hdr = 16;
        }
        if (size < hdr || pos + size > end) return false;
        const size_t payload = pos + hdr;
        const size_t boxEnd = pos + size;
        if (strcmp(type, "moov") == 0 || strcmp(type, "trak") == 0 ||
            strcmp(type, "mdia") == 0 || strcmp(type, "mvex") == 0) {
            if (!patchBoxDurations(p, payload, boxEnd, dur, patched)) return false;
        } else if (strcmp(type, "mvhd") == 0 || strcmp(type, "tkhd") == 0 ||
                   strcmp(type, "mdhd") == 0) {
            if (payload + 4 > boxEnd) return false;
            const uint8_t version = p[payload];
            size_t off;
            if (strcmp(type, "tkhd") == 0) off = (version == 0) ? 20 : 28;
            else off = (version == 0) ? 16 : 24;
            if (payload + off + (version == 0 ? 4 : 8) > boxEnd) return false;
            if (version == 0) {
                uint32_t v = dur > 0xFFFFFFFFull ? 0xFFFFFFFFu : static_cast<uint32_t>(dur);
                setU32BE(p + payload + off, v);
            } else {
                setU64BE(p + payload + off, dur);
            }
            patched++;
        } else if (strcmp(type, "mehd") == 0) {
            if (payload + 4 > boxEnd) return false;
            const uint8_t version = p[payload];
            if (payload + 4 + (version == 0 ? 4 : 8) > boxEnd) return false;
            if (version == 0) {
                uint32_t v = dur > 0xFFFFFFFFull ? 0xFFFFFFFFu : static_cast<uint32_t>(dur);
                setU32BE(p + payload + 4, v);
            } else {
                setU64BE(p + payload + 4, dur);
            }
            patched++;
        }
        pos += size;
    }
    return true;
}

bool patchInitDurations(std::vector<uint8_t>& data, uint64_t dur) {
    if (data.empty()) return false;
    int patched = 0;
    if (!patchBoxDurations(data.data(), 0, data.size(), dur, patched)) return false;
    return patched > 0;
}

bool replaceFile(const std::string& tmp, const std::string& dst) {
#if defined(_WIN32)
    return MoveFileExA(tmp.c_str(), dst.c_str(), MOVEFILE_REPLACE_EXISTING) != 0;
#else
    return std::rename(tmp.c_str(), dst.c_str()) == 0;
#endif
}

/** 用新 init 哈希重建 manifest 的全部 link（密文分片不动），返回新根与新清单摘要。 */
bool buildNewManifest(const std::string& dir, const std::string& newInitSha,
                      const std::string& newLink0, int expectSegs,
                      std::string& out, std::string& newRoot, std::string& newCipherSha) {
    std::vector<uint8_t> raw;
    if (!readAll(joinPath(dir, "manifest.jsonl"), raw)) return false;
    const std::string text(raw.begin(), raw.end());
    out.clear();
    out.reserve(text.size() + 64);
    std::string link = newLink0;
    int count = 0;
    size_t pos = 0;
    while (pos < text.size()) {
        size_t nl = text.find('\n', pos);
        std::string line = text.substr(pos, nl == std::string::npos ? std::string::npos : nl - pos);
        pos = (nl == std::string::npos) ? text.size() : nl + 1;
        if (line.empty()) continue;
        int64_t seq = -1;
        if (!jsonInt(line, "seq", seq)) return false;
        if (seq == 0) {
            out += "{\"seq\":0,\"init\":\"" + newInitSha + "\",\"link\":\"" + newLink0 + "\"}\n";
            continue;
        }
        if (seq != static_cast<int64_t>(count) + 1) return false;
        std::string nonceB64, tag, ct;
        int64_t size = -1;
        if (!jsonString(line, "nonce", nonceB64) || !jsonString(line, "tag", tag) ||
            !jsonString(line, "ct", ct) || !jsonInt(line, "size", size)) {
            return false;
        }
        std::vector<uint8_t> nonce = base64Decode(nonceB64);
        if (nonce.size() != 12 || size < 0) return false;
        link = chainNext(link, ct, static_cast<int>(seq), nonce);
        out += "{\"seq\":" + std::to_string(seq) + ",\"nonce\":\"" + nonceB64 +
               "\",\"tag\":\"" + tag + "\",\"ct\":\"" + ct +
               "\",\"link\":\"" + link + "\",\"size\":" + std::to_string(size) + "}\n";
        count++;
    }
    if (count != expectSegs) return false;
    newRoot = link;
    newCipherSha = sha256Hex(out.data(), out.size());
    return true;
}

}  // namespace

std::string segPath(const std::string& dir, int seq) {
    char name[32];
    snprintf(name, sizeof(name), "seg_%05d.enc", seq);
    return joinPath(dir, name);
}

// ---------------------------------------------------------------------------
// SegmentWriter
// ---------------------------------------------------------------------------

SegmentWriter::SegmentWriter(std::string dir, std::vector<uint8_t> dek, std::vector<Track> tracks,
                             int64_t segMs)
    : dir_(std::move(dir)), dek_(std::move(dek)), tracks_(std::move(tracks)), writer_(tracks_) {
    if (segMs > 0) segMs_ = segMs;
    for (const auto& t : tracks_) {
        pending_.emplace_back(t.id, std::vector<Sample>());
        if (t.kind == "video") hasVideoTrack_ = true;
    }
}

SegmentWriter::~SegmentWriter() {
    if (manifest_ != nullptr) {
        fclose(manifest_);
        manifest_ = nullptr;
    }
    secureZero(dek_.data(), dek_.size());
}

bool SegmentWriter::begin() {
    if (begun_) return true;
    if (dek_.size() != kDekBytes) {
        error_ = "DEK 长度非法";
        return false;
    }
    if (!ensureDir(dir_)) {
        error_ = "无法创建密文目录";
        return false;
    }

    std::vector<uint8_t> init = writer_.buildInit();
    if (init.empty() || !writeAllSynced(joinPath(dir_, "init.mp4"), init)) {
        error_ = "init.mp4 写入失败";
        return false;
    }
    const std::string initSha = sha256Hex(init.data(), init.size());
    link_ = sha256HexStr(std::string("E1") + initSha);

    manifest_ = fopen(joinPath(dir_, "manifest.jsonl").c_str(), "wb");
    if (manifest_ == nullptr) {
        error_ = "manifest.jsonl 无法创建";
        return false;
    }
    const std::string line = "{\"seq\":0,\"init\":\"" + initSha + "\",\"link\":\"" + link_ + "\"}";
    if (!appendLineSynced(manifest_, line)) {
        error_ = "manifest 首行写入失败";
        return false;
    }
    // 中断标记：正常收尾时删除，仍存在即说明会话未跑完。
    std::vector<uint8_t> mark;
    writeAllSynced(joinPath(dir_, kOpenLock), mark);
    begun_ = true;
    return true;
}

void SegmentWriter::push(int trackId, Sample sample) {
    if (!begun_ || failed_) return;
    const bool isVideo = (trackId == writer_.videoTrackId());
    if (fragStart_ < 0) fragStart_ = sample.pts;
    if (isVideo) {
        // 段边界必须落在关键帧上，否则单段无法独立解码。
        if (sample.key && sample.pts - fragStart_ >= segMs_ * 1000LL) flush();
        lastVideo_ = sample.pts;
    } else if (!hasVideoTrack_) {
        // 纯音频（仅一路音轨）：没有关键帧概念，按时间戳切段。
        if (sample.pts - fragStart_ >= segMs_ * 1000LL) flush();
        lastAudio_ = sample.pts;
    }
    if (failed_) return;
    for (auto& p : pending_) {
        if (p.first == trackId) {
            p.second.push_back(std::move(sample));
            return;
        }
    }
}

void SegmentWriter::flush() {
    if (failed_) return;
    const int vid = writer_.videoTrackId();
    bool any = false;
    int64_t nextStart = fragStart_;
    for (const auto& p : pending_) {
        if (!p.second.empty() && p.first == vid) {
            any = true;
            nextStart = p.second.back().pts;
        }
    }
    if (!any && !hasVideoTrack_) {
        for (const auto& p : pending_) {
            if (!p.second.empty()) {
                any = true;
                nextStart = p.second.back().pts;
            }
        }
    }
    if (!any) return;

    ++segSeq_;
    std::vector<uint8_t> frag = writer_.buildFragment(segSeq_, pending_);
    for (auto& p : pending_) p.second.clear();
    fragStart_ = nextStart;
    if (frag.empty()) return;  // 与历史实现一致：空分片不落盘，但段号已消耗

    uint8_t nonce[kNonceBytes];
    if (!fillRandom(nonce, sizeof(nonce))) {
        failed_ = true;
        error_ = "随机数源不可用";
        return;
    }
    std::vector<uint8_t> header = makeHeader(segSeq_, nonce);
    std::vector<uint8_t> ct;
    uint8_t tag[kTagBytes];
    if (!gcmSeal(dek_.data(), nonce, kNonceBytes, header.data(), static_cast<int>(header.size()),
                 frag.data(), static_cast<int>(frag.size()), ct, tag)) {
        failed_ = true;
        error_ = "第 " + std::to_string(segSeq_) + " 段加密失败";
        return;
    }

    std::vector<uint8_t> out;
    out.reserve(header.size() + kTagLen + ct.size());
    out.insert(out.end(), header.begin(), header.end());
    out.insert(out.end(), tag, tag + kTagLen);
    out.insert(out.end(), ct.begin(), ct.end());
    if (!writeAllSynced(segPath(dir_, segSeq_), out)) {
        failed_ = true;
        error_ = "第 " + std::to_string(segSeq_) + " 段写盘失败";
        return;
    }

    const std::string ctSha = sha256Hex(ct.data(), ct.size());
    const std::vector<uint8_t> nonceVec(nonce, nonce + kNonceBytes);
    link_ = chainNext(link_, ctSha, segSeq_, nonceVec);

    const std::string seqStr = std::to_string(segSeq_);
    const std::string line = "{\"seq\":" + seqStr + ",\"nonce\":\"" + base64Encode(nonce, kNonceBytes) +
                             "\",\"tag\":\"" + base64Encode(tag, kTagLen) + "\",\"ct\":\"" + ctSha +
                             "\",\"link\":\"" + link_ + "\",\"size\":" + std::to_string(ct.size()) +
                             "}";
    if (!appendLineSynced(manifest_, line)) {
        failed_ = true;
        error_ = "manifest 追加失败（磁盘可能已满）";
        return;
    }
    secureZero(ct.data(), ct.size());
}

bool SegmentWriter::finalizeDurations(Summary& s) {
    if (segSeq_ <= 0 || s.durationMs <= 0) return false;
    const std::string initPath = joinPath(dir_, "init.mp4");
    std::vector<uint8_t> init;
    if (!readAll(initPath, init)) return false;
    const uint64_t dur = static_cast<uint64_t>(s.durationMs) * 1000ull;  // 1e6 时间基
    if (!patchInitDurations(init, dur)) return false;
    const std::string newInitSha = sha256Hex(init.data(), init.size());
    const std::string newLink0 = sha256HexStr(std::string("E1") + newInitSha);
    std::string newManifest, newRoot, newCipher;
    if (!buildNewManifest(dir_, newInitSha, newLink0, segSeq_, newManifest, newRoot, newCipher)) {
        return false;
    }
    const std::string initTmp = joinPath(dir_, "init.mp4.tmp");
    const std::string manTmp = joinPath(dir_, "manifest.jsonl.tmp");
    std::vector<uint8_t> manBytes(newManifest.begin(), newManifest.end());
    if (!writeAllSynced(initTmp, init) || !writeAllSynced(manTmp, manBytes)) {
        remove(initTmp.c_str());
        remove(manTmp.c_str());
        return false;
    }
    if (!replaceFile(initTmp, initPath) ||
        !replaceFile(manTmp, joinPath(dir_, "manifest.jsonl"))) {
        remove(initTmp.c_str());
        remove(manTmp.c_str());
        return false;
    }
    s.segRoot = newRoot;
    s.cipherSha = newCipher;
    return true;
}

Summary SegmentWriter::finish() {
    Summary s;
    if (!begun_) {
        s.error = error_.empty() ? "未调用 begin()" : error_;
        return s;
    }
    flush();
    if (manifest_ != nullptr) {
        syncFile(manifest_);
        fclose(manifest_);
        manifest_ = nullptr;
    }
    s.segCount = segSeq_;
    s.segRoot = link_;
    s.cipherSha = sha256HexFile(joinPath(dir_, "manifest.jsonl"));
    s.durationMs = lastVideo_ > 0 ? lastVideo_ / 1000 : lastAudio_ / 1000;
    s.error = error_;

    // 把总时长写回 init.mp4，让相册/MediaStore 能读到时长；仅改元数据并重建段链。
    if (s.error.empty()) {
        Summary patched;
        patched.durationMs = s.durationMs;
        if (finalizeDurations(patched)) {
            s.segRoot = patched.segRoot;
            s.cipherSha = patched.cipherSha;
        }
    }

    const std::string line = "{\"segCount\":" + std::to_string(s.segCount) + ",\"segRoot\":\"" +
                             s.segRoot + "\",\"cipherSha\":\"" + s.cipherSha +
                             "\",\"durationMs\":" + std::to_string(s.durationMs) + "}";
    std::vector<uint8_t> bytes(line.begin(), line.end());
    if (!writeAllSynced(joinPath(dir_, "summary.json"), bytes) && s.error.empty()) {
        s.error = "summary.json 写入失败";
    }
#if defined(_WIN32)
    _unlink(joinPath(dir_, kOpenLock).c_str());
#else
    unlink(joinPath(dir_, kOpenLock).c_str());
#endif
    begun_ = false;
    return s;
}

// ---------------------------------------------------------------------------
// 核验 / 还原
// ---------------------------------------------------------------------------

InspectResult inspectDir(const std::string& dir, const std::vector<uint8_t>& dek, bool withPlainHash,
                         const std::string& expectPlainSha) {
    InspectResult r;
    const bool haveKey = (dek.size() == kDekBytes);
    r.keyAvailable = haveKey;
    if (!fileExists(dir)) {
        r.note = "密文目录缺失";
        return r;
    }

    std::string initLink;
    std::vector<SegEntry> entries = readManifest(dir, initLink);
    if (initLink.empty()) r.note += "init 段未入链；";

    r.segCount = static_cast<int>(entries.size());
    for (size_t i = 0; i < entries.size(); ++i) {
        const int want = static_cast<int>(i) + 1;
        if (entries[i].seq != want) r.missingSeqs.push_back(want);
    }

    // 段链重算（entries 已按段号升序）。无密钥时也能做：链只依赖明文头里的 nonce 与密文摘要。
    std::string link = initLink;
    bool chainOk = !initLink.empty();
    for (const auto& e : entries) {
        std::vector<uint8_t> nonce;
        std::string ctSha;
        bool structOk = false;
        if (haveKey) {
            std::vector<uint8_t> plain;
            structOk = verifySegment(dek, segPath(dir, e.seq), e.seq, e.ctSha, plain, nonce, ctSha);
            if (structOk) secureZero(plain.data(), plain.size());
        } else {
            structOk = inspectSegmentNoKey(segPath(dir, e.seq), e.seq, e.ctSha, nonce, ctSha);
        }
        if (!structOk) {
            r.badSeqs.push_back(e.seq);
            continue;
        }
        const std::string expectLink = chainNext(link, ctSha, e.seq, nonce);
        if (!e.link.empty() && expectLink != e.link) chainOk = false;
        if (!e.link.empty()) link = e.link;
    }
    r.recomputedRoot = link;

    const std::string expectedRoot = readSummaryField(dir, "segRoot");
    if (!expectedRoot.empty() && !ieq(expectedRoot, link)) chainOk = false;
    r.chainOk = chainOk;
    r.interrupted = fileExists(joinPath(dir, kOpenLock));

    if (!haveKey) {
        r.note = "Keystore 密钥不可得：密文结构与段链已核验，无法还原明文（仅限本机、未卸载、未换机）";
        return r;
    }
    if (withPlainHash && r.badSeqs.empty() && r.missingSeqs.empty()) {
        r.plainSha = hashPlaintext(dir, dek);
        if (!expectPlainSha.empty() && !r.plainSha.empty() && !ieq(r.plainSha, expectPlainSha)) {
            r.note += "还原后整文件 FHASH 与台账不一致；";
        }
    }
    return r;
}

bool restoreToFile(const std::string& dir, const std::vector<uint8_t>& dek, const std::string& dest,
                   int64_t* outBytes) {
    if (dek.size() != kDekBytes) return false;
    std::vector<uint8_t> init;
    if (!readAll(joinPath(dir, "init.mp4"), init)) return false;
    FILE* out = fopen(dest.c_str(), "wb");
    if (out == nullptr) return false;

    int64_t total = 0;
    bool ok = fwrite(init.data(), 1, init.size(), out) == init.size();
    total += static_cast<int64_t>(init.size());

    std::string initLink;
    std::vector<SegEntry> entries = readManifest(dir, initLink);
    for (const auto& e : entries) {
        if (!ok) break;
        std::vector<uint8_t> plain, nonce;
        std::string ctSha;
        if (!verifySegment(dek, segPath(dir, e.seq), e.seq, e.ctSha, plain, nonce, ctSha)) {
            ok = false;
            break;
        }
        if (!plain.empty() && fwrite(plain.data(), 1, plain.size(), out) != plain.size()) ok = false;
        total += static_cast<int64_t>(plain.size());
        secureZero(plain.data(), plain.size());
    }
    syncFile(out);
    fclose(out);
    if (!ok) return false;
    if (outBytes != nullptr) *outBytes = total;
    return true;
}

std::string hashPlaintext(const std::string& dir, const std::vector<uint8_t>& dek) {
    if (dek.size() != kDekBytes) return std::string();
    std::vector<uint8_t> init;
    if (!readAll(joinPath(dir, "init.mp4"), init)) return std::string();
    Sha256 h;
    h.update(init.data(), init.size());

    std::string initLink;
    std::vector<SegEntry> entries = readManifest(dir, initLink);
    for (const auto& e : entries) {
        std::vector<uint8_t> plain, nonce;
        std::string ctSha;
        if (!verifySegment(dek, segPath(dir, e.seq), e.seq, e.ctSha, plain, nonce, ctSha)) {
            return std::string();
        }
        h.update(plain.data(), plain.size());
        secureZero(plain.data(), plain.size());
    }
    uint8_t out[32];
    h.finish(out);
    return toHex(out, sizeof(out));
}

std::string readSummaryField(const std::string& dir, const std::string& key) {
    std::vector<uint8_t> raw;
    if (readAll(joinPath(dir, "summary.json"), raw)) {
        std::string text(raw.begin(), raw.end());
        std::string v;
        if (jsonString(text, key.c_str(), v)) return v;
    }
    // 兼容 Kotlin 写入的 meta.json
    if (readAll(joinPath(dir, "meta.json"), raw)) {
        std::string meta(raw.begin(), raw.end());
        std::string v;
        if (jsonString(meta, key.c_str(), v)) return v;
    }
    return std::string();
}

}  // namespace fcc
