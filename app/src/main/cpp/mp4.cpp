#include "mp4.h"

#include <algorithm>
#include <cstring>

namespace fcc {

typedef std::vector<uint8_t> Bytes;

static void putU8(Bytes& b, int v) { b.push_back((uint8_t) v); }

static void putU16(Bytes& b, int v) {
    b.push_back((uint8_t) ((v >> 8) & 0xFF));
    b.push_back((uint8_t) (v & 0xFF));
}

static void putU32(Bytes& b, int64_t v) {
    b.push_back((uint8_t) ((v >> 24) & 0xFF));
    b.push_back((uint8_t) ((v >> 16) & 0xFF));
    b.push_back((uint8_t) ((v >> 8) & 0xFF));
    b.push_back((uint8_t) (v & 0xFF));
}

static void putU64(Bytes& b, int64_t v) {
    putU32(b, (v >> 32) & 0xFFFFFFFFLL);
    putU32(b, v & 0xFFFFFFFFLL);
}

static void putAscii(Bytes& b, const char* s) {
    while (*s) b.push_back((uint8_t) *s++);
}

static void append(Bytes& dst, const Bytes& src) { dst.insert(dst.end(), src.begin(), src.end()); }

static Bytes box(const char* type, const std::vector<Bytes>& parts) {
    size_t body = 0;
    for (const auto& p : parts) body += p.size();
    Bytes out;
    out.reserve(8 + body);
    putU32(out, (int64_t) (8 + body));
    putAscii(out, type);
    for (const auto& p : parts) append(out, p);
    return out;
}

static Bytes fullBox(const char* type, int version, int flags, const std::vector<Bytes>& parts) {
    std::vector<Bytes> all;
    Bytes head;
    putU8(head, version & 0xFF);
    putU8(head, (flags >> 16) & 0xFF);
    putU8(head, (flags >> 8) & 0xFF);
    putU8(head, flags & 0xFF);
    all.push_back(head);
    for (const auto& p : parts) all.push_back(p);
    return box(type, all);
}

static Bytes countBox(const char* type, int64_t count) {
    Bytes b;
    putU32(b, count);
    return b;
}

static const uint8_t MATRIX[36] = {
    0x00,0x01,0x00,0x00, 0x00,0x00,0x00,0x00, 0x00,0x00,0x00,0x00,
    0x00,0x00,0x00,0x00, 0x00,0x01,0x00,0x00, 0x00,0x00,0x00,0x00,
    0x00,0x00,0x00,0x00, 0x00,0x00,0x00,0x00, 0x40,0x00,0x00,0x00
};

static Bytes zeros(size_t n) { return Bytes(n, 0); }

static Bytes matrixBytes() { return Bytes(MATRIX, MATRIX + 36); }

/** csd-0 可能是 avcC，也可能是 Annex-B 的 SPS/PPS；统一转成 avcC 负载。 */
static Bytes avcCFromCsd(const Bytes& csd) {
    if (csd.empty()) return Bytes();
    if (csd[0] == 1) return csd;

    std::vector<size_t> starts;
    size_t i = 0;
    while (i + 3 < csd.size()) {
        if (csd[i] == 0 && csd[i + 1] == 0) {
            if (csd[i + 2] == 1) { starts.push_back(i + 3); i += 3; continue; }
            if (csd[i + 2] == 0 && csd[i + 3] == 1) { starts.push_back(i + 4); i += 4; continue; }
        }
        ++i;
    }
    if (starts.size() < 2) return csd;

    std::vector<Bytes> nals;
    for (size_t k = 0; k < starts.size(); ++k) {
        size_t s = starts[k];
        size_t e = (k + 1 < starts.size()) ? starts[k + 1] : csd.size();
        while (e > s && csd[e - 1] == 0) --e;
        if (e > s) nals.push_back(Bytes(csd.begin() + s, csd.begin() + e));
    }
    if (nals.size() < 2) return csd;

    const Bytes& sps = nals[0];
    const Bytes& pps = nals[1];
    Bytes out;
    putU8(out, 1);
    out.push_back(sps.size() > 1 ? sps[1] : 0x42);
    out.push_back(sps.size() > 2 ? sps[2] : 0x00);
    out.push_back(sps.size() > 3 ? sps[3] : 0x1F);
    putU8(out, 0xFF);              // lengthSizeMinusOne = 3
    putU8(out, 0xE1);              // numOfSPS = 1
    putU16(out, (int) sps.size());
    append(out, sps);
    putU8(out, 1);                 // numOfPPS = 1
    putU16(out, (int) pps.size());
    append(out, pps);
    return out;
}

static Bytes descriptor(int tag, const Bytes& body) {
    Bytes out;
    putU8(out, tag);
    size_t n = body.size();
    putU8(out, 0x80 | ((n >> 21) & 0x7F));
    putU8(out, 0x80 | ((n >> 14) & 0x7F));
    putU8(out, 0x80 | ((n >> 7) & 0x7F));
    putU8(out, n & 0x7F);
    append(out, body);
    return out;
}

static Bytes avc1Entry(const Track& t) {
    Bytes head;
    append(head, zeros(6));
    putU16(head, 1);               // data_reference_index
    append(head, zeros(16));       // pre_defined(2)+reserved(2)+pre_defined(12)
    putU16(head, t.width);
    putU16(head, t.height);
    putU32(head, 0x00480000);      // 72 dpi
    putU32(head, 0x00480000);
    putU32(head, 0);
    putU16(head, 1);               // frame_count
    append(head, zeros(32));       // compressorname
    putU16(head, 0x0018);          // depth
    putU16(head, 0xFFFF);          // pre_defined = -1
    return box("avc1", {head, box("avcC", {avcCFromCsd(t.csd)})});
}

static Bytes mp4aEntry(const Track& t) {
    Bytes head;
    append(head, zeros(6));
    putU16(head, 1);
    append(head, zeros(8));
    putU16(head, t.channels);
    putU16(head, 16);
    putU16(head, 0);
    putU16(head, 0);
    putU32(head, ((int64_t) t.sampleRate) << 16);

    Bytes decOs;
    putU8(decOs, 0x40);            // Audio ISO/IEC 14496-3
    putU8(decOs, 0x15);            // streamType=5<<2 | upStream=0 | reserved=1
    putU8(decOs, 0x00); putU8(decOs, 0x00); putU8(decOs, 0x00);
    putU32(decOs, 128000);
    putU32(decOs, 128000);
    append(decOs, descriptor(0x05, t.csd));   // AudioSpecificConfig

    Bytes esBody;
    putU16(esBody, t.id);
    putU8(esBody, 0x00);
    append(esBody, descriptor(0x04, decOs));
    Bytes sl;
    putU8(sl, 0x02);
    append(esBody, descriptor(0x06, sl));

    return box("mp4a", {head, fullBox("esds", 0, 0, {descriptor(0x03, esBody)})});
}

static Bytes mvhdBox(const std::vector<Track>& tracks) {
    Bytes b;
    putU32(b, 0); putU32(b, 0);
    putU32(b, 1000000);
    putU32(b, 0);
    putU32(b, 0x00010000);
    putU16(b, 0x0100); putU16(b, 0);
    putU64(b, 0);
    append(b, matrixBytes());
    append(b, zeros(24));
    int maxId = 0;
    for (const auto& t : tracks) maxId = std::max(maxId, t.id);
    putU32(b, maxId + 1);
    return fullBox("mvhd", 0, 0, {b});
}

static Bytes tkhdBox(const Track& t) {
    Bytes b;
    putU32(b, 0); putU32(b, 0);
    putU32(b, t.id);
    putU32(b, 0);
    putU32(b, 0);
    putU64(b, 0);
    putU16(b, 0); putU16(b, 0);
    putU16(b, t.kind == "audio" ? 0x0100 : 0);
    putU16(b, 0);
    append(b, matrixBytes());
    putU32(b, ((int64_t) t.width) << 16);
    putU32(b, ((int64_t) t.height) << 16);
    return fullBox("tkhd", 0, 0x000007, {b});
}

static Bytes mdhdBox(const Track& t) {
    Bytes b;
    putU32(b, 0); putU32(b, 0);
    putU32(b, 1000000);
    putU32(b, 0);
    putU16(b, 0x55C4);   // 'und'
    putU16(b, 0);
    return fullBox("mdhd", 0, 0, {b});
}

static Bytes hdlrBox(const Track& t) {
    Bytes b;
    putU32(b, 0);
    putAscii(b, t.kind == "audio" ? "soun" : "vide");
    append(b, zeros(12));
    putAscii(b, t.kind == "audio" ? "SoundHandler" : "VideoHandler");
    putU8(b, 0);
    return fullBox("hdlr", 0, 0, {b});
}

static Bytes mediaHeaderBox(const Track& t) {
    if (t.kind == "audio") {
        Bytes b;
        putU16(b, 0);   // balance
        putU16(b, 0);   // reserved
        return fullBox("smhd", 0, 0, {b});
    }
    Bytes b;
    putU16(b, 0);       // graphicsmode
    putU16(b, 0); putU16(b, 0); putU16(b, 0);   // opcolor
    return fullBox("vmhd", 0, 1, {b});
}

static Bytes dinfBox() {
    Bytes dref = fullBox("dref", 0, 0, {countBox("", 1), fullBox("url ", 0, 1, {})});
    return box("dinf", {dref});
}

/** init 段的样本表必须存在但为空（entry_count=0），缺 count 字段即为非法 box。 */
static Bytes stblBox(const Track& t) {
    return box("stbl", {
        fullBox("stsd", 0, 0, {countBox("", 1), t.kind == "audio" ? mp4aEntry(t) : avc1Entry(t)}),
        fullBox("stts", 0, 0, {countBox("", 0)}),
        fullBox("stsc", 0, 0, {countBox("", 0)}),
        fullBox("stsz", 0, 0, {[]{ Bytes b; putU32(b, 0); putU32(b, 0); return b; }()}),
        fullBox("stco", 0, 0, {countBox("", 0)})
    });
}

static Bytes trakBox(const Track& t) {
    Bytes minf = box("minf", {mediaHeaderBox(t), dinfBox(), stblBox(t)});
    Bytes mdia = box("mdia", {mdhdBox(t), hdlrBox(t), minf});
    return box("trak", {tkhdBox(t), mdia});
}

static Bytes trexBox(const Track& t) {
    Bytes b;
    putU32(b, t.id);
    putU32(b, 1);
    putU32(b, 0);
    putU32(b, 0);
    putU32(b, 0);
    return fullBox("trex", 0, 0, {b});
}

std::vector<uint8_t> Fmp4Writer::buildInit() const {
    Bytes ftypBody;
    putAscii(ftypBody, "iso5");
    putU32(ftypBody, 512);
    putAscii(ftypBody, "iso5");
    putAscii(ftypBody, "iso6");
    putAscii(ftypBody, "mp41");
    putAscii(ftypBody, "dash");

    std::vector<Bytes> moovParts;
    moovParts.push_back(mvhdBox(tracks_));
    for (const auto& t : tracks_) moovParts.push_back(trakBox(t));
    std::vector<Bytes> trexes;
    for (const auto& t : tracks_) trexes.push_back(trexBox(t));
    moovParts.push_back(box("mvex", trexes));

    Bytes out = box("ftyp", {ftypBody});
    append(out, box("moov", moovParts));
    return out;
}

int Fmp4Writer::videoTrackId() const {
    for (const auto& t : tracks_) if (t.kind == "video") return t.id;
    return tracks_.empty() ? 1 : tracks_.front().id;
}

static Bytes trafBox(const Track& t, const std::vector<Sample>& samples, int dataOffset) {
    Bytes tfhdBody;
    putU32(tfhdBody, t.id);
    Bytes tfhd = fullBox("tfhd", 0, 0x020000, {tfhdBody});

    Bytes tfdtBody;
    putU64(tfdtBody, samples.empty() ? 0 : samples.front().pts);
    Bytes tfdt = fullBox("tfdt", 1, 0, {tfdtBody});

    Bytes trun;
    int flags = 0x000001 | 0x000100 | 0x000200 | 0x000400;
    putU8(trun, 0);
    putU8(trun, (flags >> 16) & 0xFF);
    putU8(trun, (flags >> 8) & 0xFF);
    putU8(trun, flags & 0xFF);
    putU32(trun, (int64_t) samples.size());
    putU32(trun, dataOffset);
    int64_t fallback = (t.kind == "audio" && t.sampleRate > 0)
                       ? (1024LL * 1000000LL / t.sampleRate) : 33333LL;
    for (size_t i = 0; i < samples.size(); ++i) {
        int64_t dur = (i + 1 < samples.size()) ? (samples[i + 1].pts - samples[i].pts) : fallback;
        if (dur <= 0) dur = 1;
        putU32(trun, dur);
        putU32(trun, (int64_t) samples[i].data.size());
        putU32(trun, samples[i].key ? 0x02000000LL : 0x01010000LL);
    }
    return box("traf", {tfhd, tfdt, box("trun", {trun})});
}

std::vector<uint8_t> Fmp4Writer::buildFragment(
        int seq, const std::vector<std::pair<int, std::vector<Sample>>>& perTrack) const {
    if (perTrack.empty()) return Bytes();

    auto buildMoof = [&](const std::vector<int>& offsets) {
        Bytes mfhdBody;
        putU32(mfhdBody, seq);
        std::vector<Bytes> parts;
        parts.push_back(fullBox("mfhd", 0, 0, {mfhdBody}));
        for (size_t i = 0; i < perTrack.size(); ++i) {
            const Track* t = nullptr;
            for (const auto& c : tracks_) if (c.id == perTrack[i].first) t = &c;
            if (!t) continue;
            parts.push_back(trafBox(*t, perTrack[i].second, offsets[i]));
        }
        return box("moof", parts);
    };

    std::vector<int> zeroOffsets(perTrack.size(), 0);
    Bytes probe = buildMoof(zeroOffsets);
    std::vector<int> offsets(perTrack.size(), 0);
    int cursor = (int) probe.size() + 8;
    for (size_t i = 0; i < perTrack.size(); ++i) {
        offsets[i] = cursor;
        for (const auto& s : perTrack[i].second) cursor += (int) s.data.size();
    }
    Bytes moof = buildMoof(offsets);

    Bytes payload;
    for (const auto& pt : perTrack)
        for (const auto& s : pt.second) append(payload, s.data);

    Bytes out = moof;
    append(out, box("mdat", {payload}));
    return out;
}

std::vector<uint8_t> toAvccSample(const uint8_t* data, size_t len) {
    bool annexB = len > 4 && data[0] == 0 && data[1] == 0 &&
                  (data[2] == 1 || (data[2] == 0 && data[3] == 1));
    if (!annexB) return Bytes(data, data + len);

    std::vector<size_t> starts;
    size_t i = 0;
    while (i + 3 < len) {
        if (data[i] == 0 && data[i + 1] == 0) {
            if (data[i + 2] == 1) { starts.push_back(i + 3); i += 3; continue; }
            if (data[i + 2] == 0 && data[i + 3] == 1) { starts.push_back(i + 4); i += 4; continue; }
        }
        ++i;
    }
    if (starts.empty()) return Bytes(data, data + len);

    Bytes out;
    out.reserve(len);
    for (size_t k = 0; k < starts.size(); ++k) {
        size_t s = starts[k];
        size_t e = (k + 1 < starts.size()) ? starts[k + 1] : len;
        while (e > s && data[e - 1] == 0) --e;
        if (e <= s) continue;
        putU32(out, (int64_t) (e - s));
        out.insert(out.end(), data + s, data + e);
    }
    return out;
}

}  // namespace fcc
