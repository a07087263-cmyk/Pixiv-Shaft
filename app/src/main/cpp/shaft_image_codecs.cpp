#include <android/bitmap.h>
#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <memory>
#include <string>
#include <vector>

#include "avif/avif.h"
#include "jxl/decode.h"
#include "jxl/encode.h"

namespace {

constexpr int FORMAT_AVIF = 1;
constexpr int FORMAT_JXL = 2;

void throwJava(JNIEnv* env, const char* message, const char* className = "java/lang/IllegalStateException") {
    env->ThrowNew(env->FindClass(className), message);
}

std::string jString(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result = chars == nullptr ? std::string() : std::string(chars);
    if (chars != nullptr) env->ReleaseStringUTFChars(value, chars);
    return result;
}

bool readFile(const std::string& path, std::vector<uint8_t>& out) {
    std::ifstream input(path, std::ios::binary | std::ios::ate);
    if (!input) return false;
    const std::streamoff size = input.tellg();
    if (size <= 0) return false;
    input.seekg(0);
    out.resize(static_cast<size_t>(size));
    return static_cast<bool>(input.read(reinterpret_cast<char*>(out.data()), size));
}

bool writeFile(const std::string& path, const uint8_t* data, size_t size) {
    std::ofstream output(path, std::ios::binary | std::ios::trunc);
    return static_cast<bool>(output.write(reinterpret_cast<const char*>(data), static_cast<std::streamsize>(size))) &&
           output.flush() && static_cast<bool>(output);
}

int formatOf(const uint8_t* data, size_t size) {
    if (size >= 2 && data[0] == 0xff && data[1] == 0x0a) return FORMAT_JXL;
    if (size >= 12 && data[0] == 0x00 && data[1] == 0x00 && data[2] == 0x00 && data[3] == 0x0c &&
        std::memcmp(data + 4, "JXL ", 4) == 0) return FORMAT_JXL;
    if (size >= 12 && std::memcmp(data + 4, "ftyp", 4) == 0 &&
        (std::memcmp(data + 8, "avif", 4) == 0 || std::memcmp(data + 8, "avis", 4) == 0)) {
        return FORMAT_AVIF;
    }
    return 0;
}

struct RgbaImage {
    uint32_t width = 0;
    uint32_t height = 0;
    std::vector<uint8_t> pixels;
};

bool decodeJxl(const uint8_t* data, size_t size, RgbaImage& output, bool pixels, std::string& error) {
    JxlDecoder* decoder = JxlDecoderCreate(nullptr);
    if (decoder == nullptr) {
        error = "JxlDecoderCreate failed";
        return false;
    }
    struct Guard {
        JxlDecoder* value;
        ~Guard() { JxlDecoderDestroy(value); }
    } guard{decoder};
    const uint32_t events = JXL_DEC_BASIC_INFO | (pixels ? JXL_DEC_FULL_IMAGE : 0);
    if (JxlDecoderSubscribeEvents(decoder, events) != JXL_DEC_SUCCESS ||
        JxlDecoderSetInput(decoder, data, size) != JXL_DEC_SUCCESS) {
        error = "could not initialize JXL input";
        return false;
    }
    JxlDecoderCloseInput(decoder);
    JxlBasicInfo info{};
    JxlPixelFormat format{4, JXL_TYPE_UINT8, JXL_NATIVE_ENDIAN, 0};
    bool haveInfo = false;
    while (true) {
        const JxlDecoderStatus status = JxlDecoderProcessInput(decoder);
        if (status == JXL_DEC_BASIC_INFO) {
            if (JxlDecoderGetBasicInfo(decoder, &info) != JXL_DEC_SUCCESS || info.xsize == 0 || info.ysize == 0) {
                error = "invalid JXL basic info";
                return false;
            }
            output.width = info.xsize;
            output.height = info.ysize;
            haveInfo = true;
            if (!pixels) return true;
        } else if (status == JXL_DEC_NEED_IMAGE_OUT_BUFFER) {
            if (!haveInfo) {
                error = "JXL output was requested before basic info";
                return false;
            }
            const size_t pixelCount = static_cast<size_t>(output.width) * output.height;
            if (pixelCount > SIZE_MAX / 4) {
                error = "JXL dimensions overflow";
                return false;
            }
            output.pixels.resize(pixelCount * 4);
            if (JxlDecoderSetImageOutBuffer(decoder, &format, output.pixels.data(), output.pixels.size()) != JXL_DEC_SUCCESS) {
                error = "could not set JXL output buffer";
                return false;
            }
        } else if (status == JXL_DEC_FULL_IMAGE) {
            if (!haveInfo || output.pixels.empty()) {
                error = "JXL image arrived before its output buffer";
                return false;
            }
            return true;
        } else if (status == JXL_DEC_SUCCESS) {
            if (!pixels) return haveInfo;
            error = "JXL ended without a full image";
            return false;
        } else if (status == JXL_DEC_ERROR || status == JXL_DEC_NEED_MORE_INPUT) {
            error = "JXL decode failed with status " + std::to_string(status);
            return false;
        }
        // COLOR_ENCODING, FRAME and other informational events need no action here.
    }
}

struct AvifDecoderGuard {
    avifDecoder* value = avifDecoderCreate();
    ~AvifDecoderGuard() { avifDecoderDestroy(value); }
};

bool decodeAvif(const uint8_t* data, size_t size, RgbaImage& output, bool pixels, std::string& error) {
    AvifDecoderGuard decoder;
    if (decoder.value == nullptr) {
        error = "avifDecoderCreate failed";
        return false;
    }
    decoder.value->maxThreads = 2;
    avifResult result = avifDecoderSetIOMemory(decoder.value, data, size);
    if (result != AVIF_RESULT_OK || (result = avifDecoderParse(decoder.value)) != AVIF_RESULT_OK ||
        (result = avifDecoderNextImage(decoder.value)) != AVIF_RESULT_OK) {
        error = std::string("AVIF decode failed: ") + avifResultToString(result);
        return false;
    }
    const avifImage* image = decoder.value->image;
    if (image == nullptr || image->width == 0 || image->height == 0) {
        error = "AVIF has no image";
        return false;
    }
    output.width = image->width;
    output.height = image->height;
    if (!pixels) return true;
    avifRGBImage rgb{};
    avifRGBImageSetDefaults(&rgb, image);
    rgb.format = AVIF_RGB_FORMAT_RGBA;
    rgb.depth = 8;
    const size_t pixelCount = static_cast<size_t>(output.width) * output.height;
    if (pixelCount > SIZE_MAX / 4) {
        error = "AVIF dimensions overflow";
        return false;
    }
    output.pixels.resize(pixelCount * 4);
    rgb.pixels = output.pixels.data();
    rgb.rowBytes = output.width * 4;
    result = avifImageYUVToRGB(image, &rgb);
    if (result != AVIF_RESULT_OK) {
        error = std::string("AVIF RGB conversion failed: ") + avifResultToString(result);
        return false;
    }
    return true;
}

bool decode(const uint8_t* data, size_t size, RgbaImage& output, bool pixels, std::string& error) {
    switch (formatOf(data, size)) {
        case FORMAT_AVIF: return decodeAvif(data, size, output, pixels, error);
        case FORMAT_JXL: return decodeJxl(data, size, output, pixels, error);
        default: error = "not an AVIF or JXL image"; return false;
    }
}

jobject toBitmap(JNIEnv* env, const RgbaImage& source, int sampleSize) {
    const int sample = std::max(1, sampleSize);
    const uint32_t width = std::max(1U, source.width / static_cast<uint32_t>(sample));
    const uint32_t height = std::max(1U, source.height / static_cast<uint32_t>(sample));
    jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
    jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
    jfieldID argbField = env->GetStaticFieldID(configClass, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
    jobject config = env->GetStaticObjectField(configClass, argbField);
    jmethodID create = env->GetStaticMethodID(bitmapClass, "createBitmap",
        "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    jobject bitmap = env->CallStaticObjectMethod(bitmapClass, create, width, height, config);
    if (bitmap == nullptr || env->ExceptionCheck()) return nullptr;
    env->CallVoidMethod(bitmap, env->GetMethodID(bitmapClass, "setPremultiplied", "(Z)V"), JNI_TRUE);
    AndroidBitmapInfo info{};
    void* raw = nullptr;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_lockPixels(env, bitmap, &raw) != ANDROID_BITMAP_RESULT_SUCCESS) {
        throwJava(env, "could not lock output Bitmap");
        return nullptr;
    }
    auto* destination = static_cast<uint8_t*>(raw);
    for (uint32_t y = 0; y < height; ++y) {
        auto* row = destination + static_cast<size_t>(y) * info.stride;
        for (uint32_t x = 0; x < width; ++x) {
            uint32_t sums[4]{};
            uint32_t count = 0;
            for (int sy = 0; sy < sample; ++sy) {
                const uint32_t srcY = y * sample + sy;
                if (srcY >= source.height) continue;
                for (int sx = 0; sx < sample; ++sx) {
                    const uint32_t srcX = x * sample + sx;
                    if (srcX >= source.width) continue;
                    const uint8_t* px = source.pixels.data() + (static_cast<size_t>(srcY) * source.width + srcX) * 4;
                    for (int c = 0; c < 4; ++c) sums[c] += px[c];
                    ++count;
                }
            }
            const uint32_t divisor = std::max(1U, count);
            const uint32_t alpha = sums[3] / divisor;
            for (int c = 0; c < 3; ++c) {
                const uint32_t straight = sums[c] / divisor;
                // Android Canvas requires premultiplied output. JXL/libavif decode to straight alpha.
                row[x * 4 + c] = static_cast<uint8_t>((straight * alpha + 127) / 255);
            }
            row[x * 4 + 3] = static_cast<uint8_t>(alpha);
        }
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return bitmap;
}

struct LockedBitmap {
    JNIEnv* env;
    jobject bitmap;
    AndroidBitmapInfo info{};
    void* pixels = nullptr;
    bool locked = false;

    LockedBitmap(JNIEnv* e, jobject b) : env(e), bitmap(b) {
        jclass bitmapClass = env->GetObjectClass(bitmap);
        env->CallVoidMethod(bitmap, env->GetMethodID(bitmapClass, "setPremultiplied", "(Z)V"), JNI_FALSE);
        if (env->ExceptionCheck()) return;
        if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
            info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
            AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
            throwJava(env, "expected an unlocked-readable ARGB_8888 Bitmap", "java/lang/IllegalArgumentException");
            return;
        }
        locked = true;
    }
    ~LockedBitmap() { if (locked) AndroidBitmap_unlockPixels(env, bitmap); }
};

bool hasTranslucentPixels(const LockedBitmap& bitmap) {
    const auto* base = static_cast<const uint8_t*>(bitmap.pixels);
    for (uint32_t y = 0; y < bitmap.info.height; ++y) {
        const auto* row = base + static_cast<size_t>(y) * bitmap.info.stride;
        for (uint32_t x = 0; x < bitmap.info.width; ++x) {
            if (row[x * 4 + 3] != 255) return true;
        }
    }
    return false;
}

bool encodeAvif(JNIEnv* env, jobject bitmap, const std::string& path, int crf, int preset, std::string& error) {
    LockedBitmap source(env, bitmap);
    if (!source.locked) return false;
    avifImage* image = avifImageCreate(source.info.width, source.info.height, 8, AVIF_PIXEL_FORMAT_YUV420);
    if (image == nullptr) {
        error = "avifImageCreate failed";
        return false;
    }
    struct ImageGuard { avifImage* value; ~ImageGuard() { avifImageDestroy(value); } } imageGuard{image};
    image->colorPrimaries = AVIF_COLOR_PRIMARIES_BT709;
    image->transferCharacteristics = AVIF_TRANSFER_CHARACTERISTICS_BT709;
    image->matrixCoefficients = AVIF_MATRIX_COEFFICIENTS_BT709;
    image->yuvRange = AVIF_RANGE_LIMITED;
    const bool alpha = hasTranslucentPixels(source);
    if (alpha && avifImageAllocatePlanes(image, AVIF_PLANES_A) != AVIF_RESULT_OK) {
        error = "could not allocate AVIF alpha planes";
        return false;
    }
    avifRGBImage rgb{};
    avifRGBImageSetDefaults(&rgb, image);
    rgb.format = AVIF_RGB_FORMAT_RGBA;
    rgb.depth = 8;
    rgb.pixels = static_cast<uint8_t*>(source.pixels);
    rgb.rowBytes = source.info.stride;
    avifResult result = avifImageRGBToYUV(image, &rgb);
    if (result != AVIF_RESULT_OK) {
        error = std::string("AVIF RGB conversion failed: ") + avifResultToString(result);
        return false;
    }
    avifEncoder* encoder = avifEncoderCreate();
    if (encoder == nullptr) {
        error = "avifEncoderCreate failed";
        return false;
    }
    struct EncoderGuard { avifEncoder* value; ~EncoderGuard() { avifEncoderDestroy(value); } } encoderGuard{encoder};
    encoder->codecChoice = AVIF_CODEC_CHOICE_SVT;
    encoder->maxThreads = 2;
    encoder->speed = preset;
    encoder->quality = 50;
    encoder->qualityAlpha = 50;
    const std::string crfText = std::to_string(crf);
    if (avifEncoderSetCodecSpecificOption(encoder, "crf", crfText.c_str()) != AVIF_RESULT_OK ||
        avifEncoderSetCodecSpecificOption(encoder, "fast-decode", "1") != AVIF_RESULT_OK) {
        error = "could not set SVT CRF/fast-decode";
        return false;
    }
    result = avifEncoderAddImage(encoder, image, 1, AVIF_ADD_IMAGE_FLAG_SINGLE);
    avifRWData encoded = AVIF_DATA_EMPTY;
    if (result == AVIF_RESULT_OK) result = avifEncoderFinish(encoder, &encoded);
    struct DataGuard { avifRWData* value; ~DataGuard() { avifRWDataFree(value); } } dataGuard{&encoded};
    if (result != AVIF_RESULT_OK) {
        error = std::string("SVT AVIF encode failed: ") + avifResultToString(result) + " " + encoder->diag.error;
        return false;
    }
    if (!writeFile(path, encoded.data, encoded.size)) {
        error = "could not write AVIF output";
        return false;
    }
    return true;
}

bool processJxl(JxlEncoder* encoder, const std::string& path, std::string& error) {
    JxlEncoderCloseInput(encoder);
    std::ofstream output(path, std::ios::binary | std::ios::trunc);
    if (!output) {
        error = "could not open JXL output";
        return false;
    }
    std::vector<uint8_t> buffer(64 * 1024);
    while (true) {
        uint8_t* next = buffer.data();
        size_t available = buffer.size();
        const JxlEncoderStatus status = JxlEncoderProcessOutput(encoder, &next, &available);
        const size_t produced = buffer.size() - available;
        if (produced > 0) output.write(reinterpret_cast<const char*>(buffer.data()), static_cast<std::streamsize>(produced));
        if (status == JXL_ENC_SUCCESS) break;
        if (status != JXL_ENC_NEED_MORE_OUTPUT) {
            error = "JXL encode failed with status " + std::to_string(status);
            return false;
        }
    }
    output.flush();
    if (!output) {
        error = "could not finish JXL output";
        return false;
    }
    return true;
}

struct JxlEncoderGuard {
    JxlEncoder* value = JxlEncoderCreate(nullptr);
    ~JxlEncoderGuard() { JxlEncoderDestroy(value); }
};

bool encodeJxlBitmap(JNIEnv* env, jobject bitmap, const std::string& path, int effort, std::string& error) {
    LockedBitmap source(env, bitmap);
    if (!source.locked) return false;
    JxlEncoderGuard encoder;
    if (encoder.value == nullptr) {
        error = "JxlEncoderCreate failed";
        return false;
    }
    JxlBasicInfo info;
    JxlEncoderInitBasicInfo(&info);
    info.xsize = source.info.width;
    info.ysize = source.info.height;
    info.bits_per_sample = 8;
    // The input below is always RGBA. Four-channel input requires one declared
    // alpha channel even when every pixel is opaque; zero extra channels is only
    // valid for a packed RGB buffer.
    info.alpha_bits = 8;
    info.num_extra_channels = 1;
    info.uses_original_profile = JXL_TRUE;
    if (JxlEncoderSetBasicInfo(encoder.value, &info) != JXL_ENC_SUCCESS) {
        error = "could not set JXL basic info";
        return false;
    }
    JxlColorEncoding color;
    JxlColorEncodingSetToSRGB(&color, JXL_FALSE);
    if (JxlEncoderSetColorEncoding(encoder.value, &color) != JXL_ENC_SUCCESS) {
        error = "could not set JXL color encoding";
        return false;
    }
    JxlEncoderFrameSettings* frame = JxlEncoderFrameSettingsCreate(encoder.value, nullptr);
    if (frame == nullptr || JxlEncoderSetFrameLossless(frame, JXL_TRUE) != JXL_ENC_SUCCESS ||
        JxlEncoderFrameSettingsSetOption(frame, JXL_ENC_FRAME_SETTING_EFFORT, effort) != JXL_ENC_SUCCESS) {
        error = "could not configure lossless JXL";
        return false;
    }
    std::vector<uint8_t> packed(static_cast<size_t>(info.xsize) * info.ysize * 4);
    const auto* base = static_cast<const uint8_t*>(source.pixels);
    for (uint32_t y = 0; y < info.ysize; ++y) {
        std::memcpy(packed.data() + static_cast<size_t>(y) * info.xsize * 4,
                    base + static_cast<size_t>(y) * source.info.stride,
                    static_cast<size_t>(info.xsize) * 4);
    }
    const JxlPixelFormat format{4, JXL_TYPE_UINT8, JXL_NATIVE_ENDIAN, 0};
    if (JxlEncoderAddImageFrame(frame, &format, packed.data(), packed.size()) != JXL_ENC_SUCCESS) {
        error = "could not add JXL pixels (encoder error " + std::to_string(JxlEncoderGetError(encoder.value)) + ")";
        return false;
    }
    return processJxl(encoder.value, path, error);
}

bool encodeJxlJpeg(const uint8_t* data, size_t size, const std::string& path, int effort, std::string& error) {
    if (size < 2 || data[0] != 0xff || data[1] != 0xd8) {
        error = "not a JPEG stream";
        return false;
    }
    JxlEncoderGuard encoder;
    if (encoder.value == nullptr) {
        error = "JxlEncoderCreate failed";
        return false;
    }
    if (JxlEncoderUseBoxes(encoder.value) != JXL_ENC_SUCCESS ||
        JxlEncoderStoreJPEGMetadata(encoder.value, JXL_TRUE) != JXL_ENC_SUCCESS) {
        error = "could not enable JPEG reconstruction metadata";
        return false;
    }
    JxlEncoderFrameSettings* frame = JxlEncoderFrameSettingsCreate(encoder.value, nullptr);
    if (frame == nullptr ||
        JxlEncoderFrameSettingsSetOption(frame, JXL_ENC_FRAME_SETTING_EFFORT, effort) != JXL_ENC_SUCCESS ||
        JxlEncoderAddJPEGFrame(frame, data, size) != JXL_ENC_SUCCESS) {
        error = "could not transcode JPEG to JXL";
        return false;
    }
    return processJxl(encoder.value, path, error);
}

bool reconstructJpeg(const uint8_t* data, size_t size, std::vector<uint8_t>& output, std::string& error) {
    JxlDecoder* decoder = JxlDecoderCreate(nullptr);
    if (decoder == nullptr) {
        error = "JxlDecoderCreate failed";
        return false;
    }
    struct Guard { JxlDecoder* value; ~Guard() { JxlDecoderDestroy(value); } } guard{decoder};
    if (JxlDecoderSubscribeEvents(decoder, JXL_DEC_JPEG_RECONSTRUCTION | JXL_DEC_FULL_IMAGE) != JXL_DEC_SUCCESS ||
        JxlDecoderSetInput(decoder, data, size) != JXL_DEC_SUCCESS) {
        error = "could not initialize JPEG reconstruction";
        return false;
    }
    JxlDecoderCloseInput(decoder);
    size_t buffered = 0;
    while (true) {
        const JxlDecoderStatus status = JxlDecoderProcessInput(decoder);
        if (status == JXL_DEC_JPEG_RECONSTRUCTION) {
            output.resize(std::max<size_t>(64 * 1024, size));
            buffered = 0;
            if (JxlDecoderSetJPEGBuffer(decoder, output.data(), output.size()) != JXL_DEC_SUCCESS) {
                error = "could not set JPEG reconstruction buffer";
                return false;
            }
        } else if (status == JXL_DEC_JPEG_NEED_MORE_OUTPUT) {
            const size_t unused = JxlDecoderReleaseJPEGBuffer(decoder);
            buffered = output.size() - unused;
            output.resize(output.size() * 2);
            if (JxlDecoderSetJPEGBuffer(decoder, output.data() + buffered, output.size() - buffered) != JXL_DEC_SUCCESS) {
                error = "could not grow JPEG reconstruction buffer";
                return false;
            }
        } else if (status == JXL_DEC_SUCCESS) {
            const size_t unused = JxlDecoderReleaseJPEGBuffer(decoder);
            if (!output.empty()) output.resize(output.size() - unused);
            if (output.empty()) {
                error = "this JXL has no JPEG reconstruction data";
                return false;
            }
            return true;
        } else if (status == JXL_DEC_ERROR || status == JXL_DEC_NEED_MORE_INPUT) {
            error = "JPEG reconstruction failed with status " + std::to_string(status);
            return false;
        }
    }
}

bool validParams(int value, int min, int max) { return value >= min && value <= max; }

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_ceui_pixiv_imagecodec_NativeImageCodec_nativeEncodeAvif(JNIEnv* env, jclass, jobject bitmap,
        jstring output, jint crf, jint preset) {
    if (!validParams(crf, 0, 63) || !validParams(preset, 0, 13)) {
        throwJava(env, "invalid AVIF parameters", "java/lang/IllegalArgumentException");
        return JNI_FALSE;
    }
    std::string error;
    if (!encodeAvif(env, bitmap, jString(env, output), crf, preset, error)) {
        if (!env->ExceptionCheck()) throwJava(env, error.c_str());
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ceui_pixiv_imagecodec_NativeImageCodec_nativeEncodeJxlBitmap(JNIEnv* env, jclass, jobject bitmap,
        jstring output, jint effort) {
    if (!validParams(effort, 1, 9)) {
        throwJava(env, "invalid JXL effort", "java/lang/IllegalArgumentException");
        return JNI_FALSE;
    }
    std::string error;
    if (!encodeJxlBitmap(env, bitmap, jString(env, output), effort, error)) {
        if (!env->ExceptionCheck()) throwJava(env, error.c_str());
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ceui_pixiv_imagecodec_NativeImageCodec_nativeEncodeJxlJpeg(JNIEnv* env, jclass, jbyteArray input,
        jstring output, jint effort) {
    if (!validParams(effort, 1, 9)) {
        throwJava(env, "invalid JXL effort", "java/lang/IllegalArgumentException");
        return JNI_FALSE;
    }
    const jsize size = env->GetArrayLength(input);
    std::vector<uint8_t> bytes(static_cast<size_t>(size));
    env->GetByteArrayRegion(input, 0, size, reinterpret_cast<jbyte*>(bytes.data()));
    std::string error;
    if (!encodeJxlJpeg(bytes.data(), bytes.size(), jString(env, output), effort, error)) {
        throwJava(env, error.c_str());
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_ceui_pixiv_imagecodec_NativeImageCodec_nativeProbe(JNIEnv* env, jclass, jbyteArray input) {
    const jsize size = env->GetArrayLength(input);
    std::vector<uint8_t> bytes(static_cast<size_t>(size));
    env->GetByteArrayRegion(input, 0, size, reinterpret_cast<jbyte*>(bytes.data()));
    RgbaImage image;
    std::string error;
    if (!decode(bytes.data(), bytes.size(), image, false, error)) {
        throwJava(env, error.c_str(), "java/io/IOException");
        return nullptr;
    }
    jintArray result = env->NewIntArray(3);
    const jint values[3]{static_cast<jint>(image.width), static_cast<jint>(image.height), formatOf(bytes.data(), bytes.size())};
    env->SetIntArrayRegion(result, 0, 3, values);
    return result;
}

extern "C" JNIEXPORT jobject JNICALL
Java_ceui_pixiv_imagecodec_NativeImageCodec_nativeDecode(JNIEnv* env, jclass, jbyteArray input, jint sampleSize) {
    if (sampleSize < 1) {
        throwJava(env, "sampleSize must be positive", "java/lang/IllegalArgumentException");
        return nullptr;
    }
    const jsize size = env->GetArrayLength(input);
    std::vector<uint8_t> bytes(static_cast<size_t>(size));
    env->GetByteArrayRegion(input, 0, size, reinterpret_cast<jbyte*>(bytes.data()));
    RgbaImage image;
    std::string error;
    if (!decode(bytes.data(), bytes.size(), image, true, error)) {
        throwJava(env, error.c_str(), "java/io/IOException");
        return nullptr;
    }
    return toBitmap(env, image, sampleSize);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ceui_pixiv_imagecodec_NativeImageCodec_nativeCanDecodeFile(JNIEnv* env, jclass, jstring path) {
    std::vector<uint8_t> bytes;
    if (!readFile(jString(env, path), bytes)) return JNI_FALSE;
    RgbaImage image;
    std::string error;
    return decode(bytes.data(), bytes.size(), image, true, error) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_ceui_pixiv_imagecodec_NativeImageCodec_nativeReconstructJpeg(JNIEnv* env, jclass, jstring path) {
    std::vector<uint8_t> input;
    if (!readFile(jString(env, path), input)) {
        throwJava(env, "could not read JXL input", "java/io/IOException");
        return nullptr;
    }
    std::vector<uint8_t> output;
    std::string error;
    if (!reconstructJpeg(input.data(), input.size(), output, error)) {
        throwJava(env, error.c_str(), "java/io/IOException");
        return nullptr;
    }
    jbyteArray result = env->NewByteArray(static_cast<jsize>(output.size()));
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(output.size()), reinterpret_cast<const jbyte*>(output.data()));
    return result;
}
