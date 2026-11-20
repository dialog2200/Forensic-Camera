// 分片 MP4（fMP4）写出器：init 段（ftyp+moov）+ 分片（moof+mdat）。
// 与 Kotlin 侧历史实现保持同一 box 结构：全轨 timescale=1_000_000（微秒）、
// default-base-is-moof、trun 逐样本给出 duration/size/flags、AVC Baseline（无 B 帧）。
#ifndef FC_MP4_H
#define FC_MP4_H

#include <cstdint>
#include <string>
#include <vector>

namespace fcc {

struct Track {
    int id = 1;
    std::string kind;  // "video" | "audio"
    int width = 0;
    int height = 0;
    int sampleRate = 0;
    int channels = 0;
    std::vector<uint8_t> csd;
};

struct Sample {
    std::vector<uint8_t> data;
    int64_t pts = 0;  // 微秒
    bool key = false;
};

class Fmp4Writer {
public:
    explicit Fmp4Writer(std::vector<Track> tracks) : tracks_(std::move(tracks)) {}

    std::vector<uint8_t> buildInit() const;
    std::vector<uint8_t> buildFragment(int seq,
                                       const std::vector<std::pair<int, std::vector<Sample>>>& perTrack) const;
    int videoTrackId() const;

private:
    std::vector<Track> tracks_;
};

/** Annex-B 起始码转长度前缀（AVCC）；已是长度前缀则原样返回。 */
std::vector<uint8_t> toAvccSample(const uint8_t* data, size_t len);

}  // namespace fcc

#endif
