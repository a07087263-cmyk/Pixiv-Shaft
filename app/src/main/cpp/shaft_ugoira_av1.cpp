#include <android/bitmap.h>
#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <memory>
#include <new>
#include <string>
#include <tuple>
#include <utility>
#include <vector>

#include "EbSvtAv1Enc.h"

namespace {

constexpr char kPacketClass[] = "ceui/pixiv/ui/bulk/Av1Packet";

void throwJava(JNIEnv* env, const char* className, const std::string& message) {
    jclass cls = env->FindClass(className);
    if (cls != nullptr) {
        env->ThrowNew(cls, message.c_str());
    }
}

bool checkSvt(JNIEnv* env, EbErrorType status, const char* operation) {
    if (status == EB_ErrorNone) {
        return true;
    }
    throwJava(env, "java/lang/IllegalStateException",
              std::string(operation) + " failed: SVT-AV1 error " + std::to_string(status));
    return false;
}

struct Encoder {
    EbComponentType* handle = nullptr;
    EbSvtAv1EncConfiguration config{};
    bool initialized = false;
    bool finished = false;
    std::vector<uint8_t> codec_config;
    uint32_t width = 0;
    uint32_t height = 0;

    ~Encoder() {
        if (handle != nullptr) {
            if (initialized) {
                svt_av1_enc_deinit(handle);
            }
            svt_av1_enc_deinit_handle(handle);
        }
    }
};

Encoder* fromHandle(jlong value) {
    return reinterpret_cast<Encoder*>(static_cast<intptr_t>(value));
}

jlong toHandle(Encoder* encoder) {
    return static_cast<jlong>(reinterpret_cast<intptr_t>(encoder));
}

inline uint8_t clampByte(int value) {
    return static_cast<uint8_t>(std::max(0, std::min(255, value)));
}

// BT.709, studio/limited swing, 15-bit fixed point. JPEG frames are sRGB RGB;
// leaving the matrix/range unspecified is what makes many hardware H.264 paths
// produce visibly shifted reds/greens on playback.
inline uint8_t rgbToY(uint32_t r, uint32_t g, uint32_t b) {
    return static_cast<uint8_t>(16 + ((5985 * r + 20128 * g + 2032 * b + 16384) >> 15));
}

inline uint8_t rgbSumToU(uint32_t r, uint32_t g, uint32_t b) {
    const int value = (-3298 * static_cast<int>(r) - 11094 * static_cast<int>(g) +
                       14394 * static_cast<int>(b) + 131072) >> 17;
    return clampByte(128 + value);
}

inline uint8_t rgbSumToV(uint32_t r, uint32_t g, uint32_t b) {
    const int value = (14394 * static_cast<int>(r) - 13073 * static_cast<int>(g) -
                       1320 * static_cast<int>(b) + 131072) >> 17;
    return clampByte(128 + value);
}

bool bitmapToI420(JNIEnv* env, jobject bitmap, Encoder& encoder, std::vector<uint8_t>& y,
                  std::vector<uint8_t>& u, std::vector<uint8_t>& v) {
    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || info.width != encoder.width ||
        info.height != encoder.height) {
        throwJava(env, "java/lang/IllegalArgumentException",
                  "SVT-AV1 expects same-sized ARGB_8888 bitmaps");
        return false;
    }

    void* rawPixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &rawPixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        throwJava(env, "java/lang/IllegalStateException", "AndroidBitmap_lockPixels failed");
        return false;
    }
    struct PixelUnlock {
        JNIEnv* env;
        jobject bitmap;
        ~PixelUnlock() { AndroidBitmap_unlockPixels(env, bitmap); }
    } unlock{env, bitmap};

    const uint32_t width = encoder.width;
    const uint32_t height = encoder.height;
    const uint32_t chromaWidth = (width + 1) / 2;
    const uint32_t chromaHeight = (height + 1) / 2;
    y.resize(static_cast<size_t>(width) * height);
    u.resize(static_cast<size_t>(chromaWidth) * chromaHeight);
    v.resize(u.size());

    const auto* pixels = static_cast<const uint8_t*>(rawPixels);
    auto channel = [&](uint32_t px, uint32_t py, uint32_t shift) -> uint32_t {
        const auto* p = pixels + static_cast<size_t>(py) * info.stride + static_cast<size_t>(px) * 4;
        return shift == 0 ? p[0] : (shift == 1 ? p[1] : p[2]);
    };

    for (uint32_t py = 0; py < height; ++py) {
        uint8_t* yRow = y.data() + static_cast<size_t>(py) * width;
        for (uint32_t px = 0; px < width; ++px) {
            yRow[px] = rgbToY(channel(px, py, 0), channel(px, py, 1), channel(px, py, 2));
        }
    }

    // Box-filter each 2x2 block before calculating chroma. At odd right/bottom
    // edges the final sample is replicated, so no source crop is necessary.
    for (uint32_t cy = 0; cy < chromaHeight; ++cy) {
        for (uint32_t cx = 0; cx < chromaWidth; ++cx) {
            uint32_t sums[3] = {0, 0, 0};
            const uint32_t x0 = cx * 2;
            const uint32_t y0 = cy * 2;
            const uint32_t x1 = std::min(x0 + 1, width - 1);
            const uint32_t y1 = std::min(y0 + 1, height - 1);
            const uint32_t xs[2] = {x0, x1};
            const uint32_t ys[2] = {y0, y1};
            for (uint32_t py : ys) {
                for (uint32_t px : xs) {
                    for (uint32_t c = 0; c < 3; ++c) {
                        sums[c] += channel(px, py, c);
                    }
                }
            }
            const size_t index = static_cast<size_t>(cy) * chromaWidth + cx;
            u[index] = rgbSumToU(sums[0], sums[1], sums[2]);
            v[index] = rgbSumToV(sums[0], sums[1], sums[2]);
        }
    }
    return true;
}

class BitReader {
public:
    BitReader(const uint8_t* data, size_t size) : data_(data), size_(size) {}

    bool read(uint32_t count, uint32_t& value) {
        if (count > 32 || bitPosition_ + count > size_ * 8) {
            return false;
        }
        value = 0;
        for (uint32_t i = 0; i < count; ++i) {
            value = (value << 1) |
                    ((data_[bitPosition_ >> 3] >> (7 - (bitPosition_ & 7))) & 1U);
            ++bitPosition_;
        }
        return true;
    }

    bool skip(uint32_t count) {
        if (bitPosition_ + count > size_ * 8) {
            return false;
        }
        bitPosition_ += count;
        return true;
    }

    bool readUvlc() {
        uint32_t leadingZeros = 0;
        uint32_t bit = 0;
        while (true) {
            if (leadingZeros >= 32 || !read(1, bit)) {
                return false;
            }
            if (bit != 0) {
                break;
            }
            ++leadingZeros;
        }
        return leadingZeros == 0 || skip(leadingZeros);
    }

private:
    const uint8_t* data_;
    size_t size_;
    size_t bitPosition_ = 0;
};

bool readLeb128(const uint8_t* data, size_t size, size_t& offset, uint64_t& value) {
    value = 0;
    for (uint32_t i = 0; i < 8; ++i) {
        if (offset >= size) {
            return false;
        }
        const uint8_t byte = data[offset++];
        value |= static_cast<uint64_t>(byte & 0x7fU) << (i * 7);
        if ((byte & 0x80U) == 0) {
            return true;
        }
    }
    return false;
}

struct ObuView {
    uint8_t type;
    size_t begin;
    size_t end;
    size_t payload;
    size_t payloadEnd;
};

bool nextObu(const uint8_t* data, size_t size, size_t& offset, ObuView& obu) {
    if (offset >= size) {
        return false;
    }
    obu.begin = offset;
    const uint8_t header = data[offset++];
    if ((header & 0x81U) != 0) {
        return false;
    }
    obu.type = static_cast<uint8_t>((header >> 3) & 0x0fU);
    const bool extension = (header & 0x04U) != 0;
    const bool hasSize = (header & 0x02U) != 0;
    if (extension) {
        if (offset >= size) {
            return false;
        }
        ++offset;
    }
    uint64_t payloadSize = size - offset;
    if (hasSize && !readLeb128(data, size, offset, payloadSize)) {
        return false;
    }
    if (payloadSize > size - offset) {
        return false;
    }
    obu.payload = offset;
    obu.payloadEnd = offset + static_cast<size_t>(payloadSize);
    obu.end = obu.payloadEnd;
    offset = obu.end;
    return true;
}

// Extract seq_profile/seq_level_idx/seq_tier from the first operating point.
// These three values are required in av1C; guessing a fixed level can make an
// otherwise valid file unnecessarily rejected by older decoders.
bool parseSequenceProfileAndLevel(const uint8_t* data, size_t size, uint8_t& profile,
                                  uint8_t& level, uint8_t& tier) {
    BitReader bits(data, size);
    uint32_t value = 0;
    uint32_t stillPicture = 0;
    uint32_t reduced = 0;
    if (!bits.read(3, value)) {
        return false;
    }
    profile = static_cast<uint8_t>(value);
    if (!bits.read(1, stillPicture) || !bits.read(1, reduced)) {
        return false;
    }
    tier = 0;
    if (reduced != 0) {
        if (!bits.read(5, value)) {
            return false;
        }
        level = static_cast<uint8_t>(value);
        return true;
    }

    uint32_t timingInfoPresent = 0;
    uint32_t decoderModelPresent = 0;
    uint32_t bufferDelayLengthMinusOne = 0;
    if (!bits.read(1, timingInfoPresent)) {
        return false;
    }
    if (timingInfoPresent != 0) {
        uint32_t equalPictureInterval = 0;
        if (!bits.skip(64) || !bits.read(1, equalPictureInterval)) {
            return false;
        }
        if (equalPictureInterval != 0 && !bits.readUvlc()) {
            return false;
        }
        if (!bits.read(1, decoderModelPresent)) {
            return false;
        }
        if (decoderModelPresent != 0) {
            if (!bits.read(5, bufferDelayLengthMinusOne) || !bits.skip(32 + 5 + 5)) {
                return false;
            }
        }
    }

    uint32_t initialDisplayDelayPresent = 0;
    uint32_t operatingPointsMinusOne = 0;
    if (!bits.read(1, initialDisplayDelayPresent) || !bits.read(5, operatingPointsMinusOne)) {
        return false;
    }
    for (uint32_t i = 0; i <= operatingPointsMinusOne; ++i) {
        if (!bits.skip(12) || !bits.read(5, value)) {
            return false;
        }
        if (i == 0) {
            level = static_cast<uint8_t>(value);
        }
        if (value > 7) {
            uint32_t currentTier = 0;
            if (!bits.read(1, currentTier)) {
                return false;
            }
            if (i == 0) {
                tier = static_cast<uint8_t>(currentTier);
            }
        }
        if (decoderModelPresent != 0) {
            uint32_t modelForOp = 0;
            if (!bits.read(1, modelForOp)) {
                return false;
            }
            if (modelForOp != 0) {
                const uint32_t n = bufferDelayLengthMinusOne + 1;
                if (!bits.skip(n + n + 1)) {
                    return false;
                }
            }
        }
        if (initialDisplayDelayPresent != 0) {
            uint32_t delayForOp = 0;
            if (!bits.read(1, delayForOp)) {
                return false;
            }
            if (delayForOp != 0 && !bits.skip(4)) {
                return false;
            }
        }
    }
    return true;
}

bool makeAv1C(const EbBufferHeaderType* streamHeader, std::vector<uint8_t>& output,
              std::string& error) {
    const uint8_t* data = streamHeader->p_buffer;
    const size_t size = streamHeader->n_filled_len;
    size_t offset = 0;
    ObuView obu{};
    while (nextObu(data, size, offset, obu)) {
        if (obu.type != 1) {  // OBU_SEQUENCE_HEADER
            continue;
        }
        uint8_t profile = 0;
        uint8_t level = 0;
        uint8_t tier = 0;
        if (!parseSequenceProfileAndLevel(data + obu.payload, obu.payloadEnd - obu.payload,
                                          profile, level, tier)) {
            error = "could not parse AV1 sequence header";
            return false;
        }
        output.clear();
        output.reserve(4 + obu.end - obu.begin);
        output.push_back(0x81);  // marker=1, version=1
        output.push_back(static_cast<uint8_t>((profile << 5) | level));
        // Main profile, 8-bit YUV420, unknown chroma sample position.
        output.push_back(static_cast<uint8_t>((tier << 7) | 0x0c));
        output.push_back(0x00);  // no initial presentation delay
        output.insert(output.end(), data + obu.begin, data + obu.end);
        return true;
    }
    error = "SVT-AV1 stream header has no sequence-header OBU";
    return false;
}

// Keep sequence-header OBUs in sync samples as well as av1C/configOBUs. Unlike
// FFmpeg's MP4 muxer, Android MediaMuxer does not insert them into the first sample;
// both dav1d and libaom reject the resulting stream. TD/padding are still container noise.
std::vector<uint8_t> packetForMp4(const EbBufferHeaderType& packet) {
    std::vector<uint8_t> output;
    output.reserve(packet.n_filled_len);
    const uint8_t* data = packet.p_buffer;
    const size_t size = packet.n_filled_len;
    size_t offset = 0;
    ObuView obu{};
    while (offset < size) {
        const size_t previous = offset;
        if (!nextObu(data, size, offset, obu) || offset <= previous) {
            // SVT always emits low-overhead OBUs. Preserve an unknown trailing
            // layout rather than silently truncating a frame.
            output.assign(data, data + size);
            return output;
        }
        if (obu.type != 2 && obu.type != 15) {
            output.insert(output.end(), data + obu.begin, data + obu.end);
        }
    }
    return output;
}

jobjectArray wrapPackets(JNIEnv* env, std::vector<std::tuple<std::vector<uint8_t>, int64_t, bool>>& packets) {
    jclass packetClass = env->FindClass(kPacketClass);
    if (packetClass == nullptr) {
        return nullptr;
    }
    jmethodID constructor = env->GetMethodID(packetClass, "<init>", "([BJZ)V");
    if (constructor == nullptr) {
        return nullptr;
    }
    jobjectArray result = env->NewObjectArray(static_cast<jsize>(packets.size()), packetClass, nullptr);
    if (result == nullptr) {
        return nullptr;
    }
    for (jsize i = 0; i < static_cast<jsize>(packets.size()); ++i) {
        auto& packet = packets[static_cast<size_t>(i)];
        auto& bytes = std::get<0>(packet);
        jbyteArray data = env->NewByteArray(static_cast<jsize>(bytes.size()));
        if (data == nullptr) {
            return nullptr;
        }
        if (!bytes.empty()) {
            env->SetByteArrayRegion(data, 0, static_cast<jsize>(bytes.size()),
                                    reinterpret_cast<const jbyte*>(bytes.data()));
        }
        jobject object = env->NewObject(packetClass, constructor, data,
                                        static_cast<jlong>(std::get<1>(packet)),
                                        static_cast<jboolean>(std::get<2>(packet)));
        env->DeleteLocalRef(data);
        if (object == nullptr) {
            return nullptr;
        }
        env->SetObjectArrayElement(result, i, object);
        env->DeleteLocalRef(object);
        if (env->ExceptionCheck()) {
            return nullptr;
        }
    }
    return result;
}

jobjectArray receiveOne(JNIEnv* env, Encoder& encoder, bool draining) {
    std::vector<std::tuple<std::vector<uint8_t>, int64_t, bool>> packets;
    while (true) {
        EbBufferHeaderType* packet = nullptr;
        const EbErrorType status = svt_av1_enc_get_packet(encoder.handle, &packet, draining ? 1 : 0);
        if (status == EB_NoErrorEmptyQueue) {
            break;
        }
        if (!checkSvt(env, status, "svt_av1_enc_get_packet")) {
            return nullptr;
        }
        if (packet == nullptr) {
            break;
        }
        const bool eos = (packet->flags & EB_BUFFERFLAG_EOS) != 0;
        if (packet->n_filled_len > 0) {
            // The initialization-time stream header is not necessarily identical to the
            // sequence header accompanying the first real frame (SVT finalizes fields such
            // as order-hint/timing after seeing input). av1C must describe the actual frames.
            if (encoder.codec_config.empty()) {
                std::string error;
                if (!makeAv1C(packet, encoder.codec_config, error)) {
                    svt_av1_enc_release_out_buffer(&packet);
                    throwJava(env, "java/lang/IllegalStateException", error);
                    return nullptr;
                }
            }
            std::vector<uint8_t> bytes = packetForMp4(*packet);
            if (!bytes.empty()) {
                const bool key = packet->pic_type == EB_AV1_KEY_PICTURE ||
                                 packet->pic_type == EB_AV1_INTRA_ONLY_PICTURE ||
                                 packet->pic_type == EB_AV1_FW_KEY_PICTURE;
                packets.emplace_back(std::move(bytes), packet->pts, key);
            }
        }
        svt_av1_enc_release_out_buffer(&packet);
        if (!draining || eos) {
            break;
        }
    }
    return wrapPackets(env, packets);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_ceui_pixiv_ui_bulk_SvtAv1Encoder_nativeCreate(JNIEnv* env, jobject, jint width, jint height,
                                                   jint fpsNumerator, jint fpsDenominator,
                                                   jint crf, jint preset) {
    if (width < 64 || height < 64 || fpsNumerator <= 0 || fpsDenominator <= 0 || crf < 0 ||
        crf > 63 || preset < 0 || preset > 13) {
        throwJava(env, "java/lang/IllegalArgumentException", "invalid SVT-AV1 encoder parameters");
        return 0;
    }
    std::unique_ptr<Encoder> encoder(new (std::nothrow) Encoder());
    if (!encoder) {
        throwJava(env, "java/lang/OutOfMemoryError", "could not allocate SVT-AV1 encoder");
        return 0;
    }
    encoder->width = static_cast<uint32_t>(width);
    encoder->height = static_cast<uint32_t>(height);
    if (!checkSvt(env, svt_av1_enc_init_handle(&encoder->handle, &encoder->config),
                  "svt_av1_enc_init_handle")) {
        return 0;
    }

    auto& c = encoder->config;
    c.source_width = encoder->width;
    c.source_height = encoder->height;
    c.frame_rate_numerator = static_cast<uint32_t>(fpsNumerator);
    c.frame_rate_denominator = static_cast<uint32_t>(fpsDenominator);
    c.encoder_bit_depth = 8;
    c.encoder_color_format = EB_YUV420;
    c.profile = MAIN_PROFILE;
    c.enc_mode = static_cast<int8_t>(preset);
    c.pred_structure = LOW_DELAY;
    c.hierarchical_levels = 0;
    c.look_ahead_distance = 0;
    c.intra_refresh_type = SVT_AV1_KF_REFRESH;
    c.intra_period_length = std::max(8, std::min(120, (2 * fpsNumerator) / fpsDenominator));
    c.level_of_parallelism = 2;
    c.fast_decode = 1;
    c.tune = 1;  // LOW_DELAY supports PSNR tuning; VQ is forced back to this by SVT.
    c.recode_loop = 0;
    c.enable_tf = 0;
    c.enable_dg = false;
    c.color_primaries = EB_CICP_CP_BT_709;
    c.transfer_characteristics = EB_CICP_TC_BT_709;
    c.matrix_coefficients = EB_CICP_MC_BT_709;
    c.color_range = EB_CR_STUDIO_RANGE;
    c.chroma_sample_position = EB_CSP_UNKNOWN;
    if (!checkSvt(env, svt_av1_enc_parse_parameter(&c, "crf", std::to_string(crf).c_str()),
                  "set CRF") ||
        !checkSvt(env, svt_av1_enc_set_parameter(encoder->handle, &c), "svt_av1_enc_set_parameter") ||
        !checkSvt(env, svt_av1_enc_init(encoder->handle), "svt_av1_enc_init")) {
        return 0;
    }
    encoder->initialized = true;
    return toHandle(encoder.release());
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_ceui_pixiv_ui_bulk_SvtAv1Encoder_nativeCodecConfig(JNIEnv* env, jobject, jlong handleValue) {
    Encoder* encoder = fromHandle(handleValue);
    if (encoder == nullptr || encoder->codec_config.empty()) {
        throwJava(env, "java/lang/IllegalStateException",
                  "encode one AV1 frame before requesting its actual sequence header");
        return nullptr;
    }
    const auto& av1c = encoder->codec_config;
    jbyteArray result = env->NewByteArray(static_cast<jsize>(av1c.size()));
    if (result != nullptr) {
        env->SetByteArrayRegion(result, 0, static_cast<jsize>(av1c.size()),
                                reinterpret_cast<const jbyte*>(av1c.data()));
    }
    return result;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_ceui_pixiv_ui_bulk_SvtAv1Encoder_nativeEncode(JNIEnv* env, jobject, jlong handleValue,
                                                   jobject bitmap, jlong presentationTimeUs) {
    Encoder* encoder = fromHandle(handleValue);
    if (encoder == nullptr || encoder->finished) {
        throwJava(env, "java/lang/IllegalStateException", "SVT-AV1 encoder is closed/finished");
        return nullptr;
    }
    std::vector<uint8_t> y;
    std::vector<uint8_t> u;
    std::vector<uint8_t> v;
    if (!bitmapToI420(env, bitmap, *encoder, y, u, v)) {
        return nullptr;
    }
    EbSvtIOFormat input{};
    input.luma = y.data();
    input.cb = u.data();
    input.cr = v.data();
    input.y_stride = encoder->width;
    input.cb_stride = (encoder->width + 1) / 2;
    input.cr_stride = input.cb_stride;
    EbBufferHeaderType header{};
    header.size = sizeof(header);
    header.p_buffer = reinterpret_cast<uint8_t*>(&input);
    header.n_alloc_len = static_cast<uint32_t>(y.size() + u.size() + v.size());
    header.n_filled_len = header.n_alloc_len;
    header.pts = presentationTimeUs;
    header.dts = presentationTimeUs;
    header.pic_type = EB_AV1_INVALID_PICTURE;
    if (!checkSvt(env, svt_av1_enc_send_picture(encoder->handle, &header), "svt_av1_enc_send_picture")) {
        return nullptr;
    }
    return receiveOne(env, *encoder, false);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_ceui_pixiv_ui_bulk_SvtAv1Encoder_nativeFinish(JNIEnv* env, jobject, jlong handleValue) {
    Encoder* encoder = fromHandle(handleValue);
    if (encoder == nullptr || encoder->finished) {
        throwJava(env, "java/lang/IllegalStateException", "SVT-AV1 encoder is already finished");
        return nullptr;
    }
    EbBufferHeaderType eos{};
    eos.size = sizeof(eos);
    eos.flags = EB_BUFFERFLAG_EOS;
    eos.pic_type = EB_AV1_INVALID_PICTURE;
    if (!checkSvt(env, svt_av1_enc_send_picture(encoder->handle, &eos), "send AV1 EOS")) {
        return nullptr;
    }
    encoder->finished = true;
    return receiveOne(env, *encoder, true);
}

extern "C" JNIEXPORT void JNICALL
Java_ceui_pixiv_ui_bulk_SvtAv1Encoder_nativeClose(JNIEnv*, jobject, jlong handleValue) {
    delete fromHandle(handleValue);
}
