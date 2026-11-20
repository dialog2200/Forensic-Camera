// 段写盘器 + 密文核验 + 明文还原（与 Kotlin 侧历史格式完全一致，统一由原生实现）。
//
// 磁盘布局：
//   <dir>/init.mp4          ftyp+moov（明文，作为 seg0 纳入段链）
//   <dir>/seg_00001.enc     magic(4)|seq(4)|nonce(12)|tag(16)|ciphertext
//   <dir>/manifest.jsonl    逐行追加并 fsync；格式同历史版本
//   <dir>/summary.json      收尾摘要（段数/段链根/清单摘要/时长）
//   <dir>/open.lock         录制会话未正常收尾的标记
#ifndef FC_WRITER_H
#define FC_WRITER_H

#include <cstdint>
#include <cstdio>
#include <string>
#include <vector>

#include "mp4.h"

namespace fcc {

struct Summary {
    int segCount = 0;
    std::string segRoot;
    std::string cipherSha;
    int64_t durationMs = 0;
    std::string error;
};

class SegmentWriter {
public:
    SegmentWriter(std::string dir, std::vector<uint8_t> dek,
                  std::vector<Track> tracks, int64_t segMs = 2000);
    ~SegmentWriter();

    bool begin();
    void push(int trackId, Sample sample);
    Summary finish();

private:
    void flush();
    /** 收尾时把 init.mp4 的 mvhd/tkhd/mdhd duration 写入总时长，并重建段链/清单摘要。
     *  仅改元数据，不动密文分片；失败则保留原链（不影响证据有效性）。 */
    bool finalizeDurations(Summary& s);
    std::string dir_;
    std::string link_;
    std::string error_;
    std::vector<uint8_t> dek_;
    std::vector<Track> tracks_;
    Fmp4Writer writer_;
    std::vector<std::pair<int, std::vector<Sample>>> pending_;
    int segSeq_ = 0;
    int64_t segMs_ = 2000;
    int64_t fragStart_ = -1;
    int64_t lastVideo_ = 0;
    int64_t lastAudio_ = 0;
    bool hasVideoTrack_ = false;
    bool begun_ = false;
    bool failed_ = false;
    FILE* manifest_ = nullptr;
};

struct InspectResult {
    bool keyAvailable = false;
    int segCount = 0;
    std::vector<int> badSeqs;
    std::vector<int> missingSeqs;
    bool chainOk = false;
    std::string recomputedRoot;
    std::string plainSha;
    std::string note;
    bool interrupted = false;

    bool intact() const {
        return keyAvailable && badSeqs.empty() && missingSeqs.empty() && chainOk;
    }
};

InspectResult inspectDir(const std::string& dir, const std::vector<uint8_t>& dek,
                         bool withPlainHash, const std::string& expectPlainSha);

bool restoreToFile(const std::string& dir, const std::vector<uint8_t>& dek,
                   const std::string& dest, int64_t* outBytes);

std::string hashPlaintext(const std::string& dir, const std::vector<uint8_t>& dek);

std::string readSummaryField(const std::string& dir, const std::string& key);

std::string segPath(const std::string& dir, int seq);

}  // namespace fcc

#endif
