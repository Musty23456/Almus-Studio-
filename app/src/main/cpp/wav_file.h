#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace almus {

// Minimal, dependency-free WAV (RIFF/PCM) reader and writer.
//
// Honest limitation for Phase 1: this reader does NOT resample. A file whose
// sample rate does not match the engine's sample rate will play back at the
// wrong pitch/speed, same as it would on most simple DAWs without a resampler
// stage. Callers should check WavLoadResult::sourceSampleRate against the
// project sample rate and warn the user rather than silently mis-playing audio.
struct WavLoadResult {
    bool success = false;
    int sourceSampleRate = 0;
    int sourceChannels = 0;
    // Always converted to interleaved stereo float32 in [-1, 1], regardless of
    // the source's bit depth or channel count, so the mixer only ever deals
    // with one internal format.
    std::vector<float> interleavedStereo;
    int64_t frameCount = 0;
};

WavLoadResult loadWavAsStereoFloat(const std::string& path);

// Streaming WAV writer for recording. Always writes 32-bit float PCM so no
// precision is lost while later effects/export process the file.
class WavWriter {
public:
    bool open(const std::string& path, int sampleRate, int channelCount);
    // interleaved samples, channelCount per frame
    void writeFrames(const float* interleaved, int64_t frameCount);
    int64_t framesWritten() const { return framesWritten_; }
    void close();
    ~WavWriter();

private:
    FILE* file_ = nullptr;
    int sampleRate_ = 48000;
    int channelCount_ = 1;
    int64_t framesWritten_ = 0;
    void writeHeaderPlaceholder();
    void patchHeaderWithFinalSizes();
};

// Non-streaming writer for offline export (used once, at the end of a render).
bool writeWavFloat(const std::string& path, const float* interleaved,
                    int64_t frameCount, int sampleRate, int channelCount);

} // namespace almus
