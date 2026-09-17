#include <jni.h>
#include <algorithm>
#include <string>
#include <memory>

#include "audio_engine.h"
#include "wav_file.h"

using almus::AlmusAudioEngine;
using almus::Command;
using almus::CommandType;
using almus::DecodedBuffer;

namespace {

std::string jstringToStdString(JNIEnv* env, jstring jstr) {
    if (!jstr) return {};
    const char* chars = env->GetStringUTFChars(jstr, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(jstr, chars);
    return result;
}

// Holds a global ref to the Kotlin ExportProgressListener plus the JavaVM so
// callbacks from the export worker thread (not a JNI-attached thread) can
// attach and invoke back into Kotlin.
struct ExportListenerBridge {
    JavaVM* vm = nullptr;
    jobject listenerGlobalRef = nullptr;
};

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_almus_studio_audio_AudioEngine_nativeInit(JNIEnv*, jobject, jint sampleRate, jint framesPerBurst) {
    AlmusAudioEngine::instance().init(sampleRate, framesPerBurst);
}

JNIEXPORT void JNICALL
Java_com_almus_studio_audio_AudioEngine_nativeShutdown(JNIEnv*, jobject) {
    AlmusAudioEngine::instance().shutdown();
}

JNIEXPORT jint JNICALL
Java_com_almus_studio_audio_AudioEngine_addTrack(JNIEnv* env, jobject, jstring trackId) {
    (void) jstringToStdString(env, trackId); // kept for future per-track diagnostics/logging
    auto& engine = AlmusAudioEngine::instance();
    int32_t handle = engine.allocateTrackHandle();
    Command cmd{};
    cmd.type = CommandType::AddTrack;
    cmd.trackHandle = handle;
    engine.enqueue(cmd);
    return handle;
}

JNIEXPORT void JNICALL
Java_com_almus_studio_audio_AudioEngine_removeTrack(JNIEnv*, jobject, jint trackHandle) {
    Command cmd{};
    cmd.type = CommandType::RemoveTrack;
    cmd.trackHandle = trackHandle;
    AlmusAudioEngine::instance().enqueue(cmd);
}

JNIEXPORT void JNICALL
Java_com_almus_studio_audio_AudioEngine_setTrackVolumeDb(JNIEnv*, jobject, jint trackHandle, jfloat db) {
    Command cmd{}; cmd.type = CommandType::SetTrackVolume; cmd.trackHandle = trackHandle; cmd.floatValue = db;
    AlmusAudioEngine::instance().enqueue(cmd);
}

JNIEXPORT void JNICALL
Java_com_almus_studio_audio_AudioEngine_setTrackPan(JNIEnv*, jobject, jint trackHandle, jfloat pan) {
    Command cmd{}; cmd.type = CommandType::SetTrackPan; cmd.trackHandle = trackHandle; cmd.floatValue = pan;
    AlmusAudioEngine::instance().enqueue(cmd);
}

JNIEXPORT void JNICALL
Java_com_almus_studio_audio_AudioEngine_setTrackMuted(JNIEnv*, jobject, jint trackHandle, jboolean muted) {
    Command cmd{}; cmd.type = CommandType::SetTrackMute; cmd.trackHandle = trackHandle; cmd.boolValue = muted;
    AlmusAudioEngine::instance().enqueue(cmd);
}

JNIEXPORT void JNICALL
Java_com_almus_studio_audio_AudioEngine_setTrackSolo(JNIEnv*, jobject, jint trackHandle, jboolean solo) {
    Command cmd{}; cmd.type = CommandType::SetTrackSolo; cmd.trackHandle = trackHandle; cmd.boolValue = solo;
    AlmusAudioEngine::instance().enqueue(cmd);
}

JNIEXPORT jint JNICALL
Java_com_almus_studio_audio_AudioEngine_scheduleClip(JNIEnv* env, jobject, jint trackHandle,
        jstring filePath, jlong startFrame, jlong sourceOffsetFrames, jlong lengthFrames,
        jfloat gainDb, jboolean looping) {
    std::string path = jstringToStdString(env, filePath);
    almus::WavLoadResult loaded = almus::loadWavAsStereoFloat(path);
    if (!loaded.success) {
        return -1;
    }

    // Ownership of this buffer transfers to the engine (freed on clip removal
    // or engine shutdown) -- see Clip::buffer in audio_engine.h.
    auto* buf = new float[loaded.interleavedStereo.size()];
    std::copy(loaded.interleavedStereo.begin(), loaded.interleavedStereo.end(), buf);

    auto& engine = AlmusAudioEngine::instance();
    int32_t clipHandle = engine.allocateClipHandle();

    Command cmd{};
    cmd.type = CommandType::ScheduleClip;
    cmd.trackHandle = trackHandle;
    cmd.clipHandle = clipHandle;
    cmd.frameValueA = startFrame;
    cmd.frameValueB = sourceOffsetFrames;
    cmd.frameValueC = (lengthFrames > 0) ? lengthFrames : loaded.frameCount;
    cmd.floatValue = gainDb;
    cmd.boolValue = looping;
    cmd.buffer = DecodedBuffer{buf, loaded.frameCount};
    engine.enqueue(cmd);
    return clipHandle;
}

JNIEXPORT void JNICALL
Java_com_almus_studio_audio_AudioEngine_removeClip(JNIEnv*, jobject, jint clipHandle) {
    Command cmd{}; cmd.type = CommandType::RemoveClip; cmd.clipHandle = clipHandle;
    AlmusAudioEngine::instance().enqueue(cmd);
}

JNIEXPORT void JNICALL
Java_com_almus_studio_audio_AudioEngine_moveClip(JNIEnv*, jobject, jint clipHandle, jlong newStartFrame) {
    Command cmd{}; cmd.type = CommandType::MoveClip; cmd.clipHandle = clipHandle; cmd.frameValueA = newStartFrame;
    AlmusAudioEngine::instance().enqueue(cmd);
}

JNIEXPORT void JNICALL
Java_com_almus_studio_audio_AudioEngine_play(JNIEnv*, jobject) { AlmusAudioEngine::instance().play(); }

JNIEXPORT void JNICALL
Java_com_almus_studio_audio_AudioEngine_pause(JNIEnv*, jobject) { AlmusAudioEngine::instance().pause(); }

JNIEXPORT void JNICALL
Java_com_almus_studio_audio_AudioEngine_stop(JNIEnv*, jobject) { AlmusAudioEngine::instance().stop(); }

JNIEXPORT void JNICALL
Java_com_almus_studio_audio_AudioEngine_seekToFrame(JNIEnv*, jobject, jlong frame) {
    AlmusAudioEngine::instance().seekToFrame(frame);
}

JNIEXPORT jlong JNICALL
Java_com_almus_studio_audio_AudioEngine_getPlayheadFrame(JNIEnv*, jobject) {
    return AlmusAudioEngine::instance().getPlayheadFrame();
}

JNIEXPORT void JNICALL
Java_com_almus_studio_audio_AudioEngine_setLoopRegion(JNIEnv*, jobject, jlong startFrame, jlong endFrame, jboolean enabled) {
    AlmusAudioEngine::instance().setLoopRegion(startFrame, endFrame, enabled);
}

JNIEXPORT jboolean JNICALL
Java_com_almus_studio_audio_AudioEngine_startRecording(JNIEnv* env, jobject, jint monitorTrackHandle, jstring outputFilePath) {
    std::string path = jstringToStdString(env, outputFilePath);
    return AlmusAudioEngine::instance().startRecording(monitorTrackHandle, path);
}

JNIEXPORT void JNICALL
Java_com_almus_studio_audio_AudioEngine_pauseRecording(JNIEnv*, jobject) {
    AlmusAudioEngine::instance().pauseRecording();
}

JNIEXPORT void JNICALL
Java_com_almus_studio_audio_AudioEngine_resumeRecording(JNIEnv*, jobject) {
    AlmusAudioEngine::instance().resumeRecording();
}

JNIEXPORT jlong JNICALL
Java_com_almus_studio_audio_AudioEngine_stopRecording(JNIEnv*, jobject) {
    return AlmusAudioEngine::instance().stopRecording();
}

JNIEXPORT jfloat JNICALL
Java_com_almus_studio_audio_AudioEngine_getInputLevelDb(JNIEnv*, jobject) {
    return AlmusAudioEngine::instance().getInputLevelDb();
}

JNIEXPORT jboolean JNICALL
Java_com_almus_studio_audio_AudioEngine_isInputClipping(JNIEnv*, jobject) {
    return AlmusAudioEngine::instance().isInputClipping();
}

JNIEXPORT void JNICALL
Java_com_almus_studio_audio_AudioEngine_setMasterVolumeDb(JNIEnv*, jobject, jfloat db) {
    AlmusAudioEngine::instance().setMasterVolumeDb(db);
}

JNIEXPORT jfloatArray JNICALL
Java_com_almus_studio_audio_AudioEngine_getMasterPeaksDb(JNIEnv* env, jobject) {
    float l = 0, r = 0;
    AlmusAudioEngine::instance().getMasterPeaksDb(&l, &r);
    jfloatArray result = env->NewFloatArray(2);
    jfloat values[2] = {l, r};
    env->SetFloatArrayRegion(result, 0, 2, values);
    return result;
}

JNIEXPORT void JNICALL
Java_com_almus_studio_audio_AudioEngine_startOfflineExport(JNIEnv* env, jobject, jstring outputFilePath, jlong totalFramesArg, jobject listener) {
    std::string path = jstringToStdString(env, outputFilePath);

    auto* bridge = new ExportListenerBridge();
    env->GetJavaVM(&bridge->vm);
    bridge->listenerGlobalRef = env->NewGlobalRef(listener);

    int64_t totalFrames = totalFramesArg;

    AlmusAudioEngine::instance().startOfflineExport(
        path, totalFrames,
        [bridge](float fraction) {
            JNIEnv* threadEnv = nullptr;
            if (bridge->vm->AttachCurrentThread(&threadEnv, nullptr) != JNI_OK) return;
            jclass cls = threadEnv->GetObjectClass(bridge->listenerGlobalRef);
            jmethodID mid = threadEnv->GetMethodID(cls, "onProgress", "(F)V");
            if (mid) threadEnv->CallVoidMethod(bridge->listenerGlobalRef, mid, fraction);
            bridge->vm->DetachCurrentThread();
        },
        [bridge](bool success, const std::string& outputPath, const std::string& error) {
            JNIEnv* threadEnv = nullptr;
            if (bridge->vm->AttachCurrentThread(&threadEnv, nullptr) == JNI_OK) {
                jclass cls = threadEnv->GetObjectClass(bridge->listenerGlobalRef);
                if (success) {
                    jmethodID mid = threadEnv->GetMethodID(cls, "onComplete", "(Ljava/lang/String;)V");
                    jstring jpath = threadEnv->NewStringUTF(outputPath.c_str());
                    if (mid) threadEnv->CallVoidMethod(bridge->listenerGlobalRef, mid, jpath);
                } else {
                    jmethodID mid = threadEnv->GetMethodID(cls, "onError", "(Ljava/lang/String;)V");
                    jstring jmsg = threadEnv->NewStringUTF(error.c_str());
                    if (mid) threadEnv->CallVoidMethod(bridge->listenerGlobalRef, mid, jmsg);
                }
                threadEnv->DeleteGlobalRef(bridge->listenerGlobalRef);
                bridge->vm->DetachCurrentThread();
            }
            delete bridge;
        });
}

JNIEXPORT void JNICALL
Java_com_almus_studio_audio_AudioEngine_cancelOfflineExport(JNIEnv*, jobject) {
    AlmusAudioEngine::instance().cancelOfflineExport();
}

} // extern "C"
