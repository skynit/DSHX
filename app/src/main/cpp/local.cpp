#include <jni.h>
#include <android/log.h>
#include <llama.h>
#include <atomic>
#include <mutex>
#include <string>
#include <vector>

struct Local {
    llama_model * model = nullptr;
    llama_context * context = nullptr;
    std::atomic_bool cancelled{false};
    int input = 0;
    int output = 0;
    ~Local() { if (context) llama_free(context); if (model) llama_model_free(model); }
};

static void fail(JNIEnv * env, const char * message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}

static std::string bytes(JNIEnv * env, jbyteArray value) {
    std::string result(env->GetArrayLength(value), '\0');
    env->GetByteArrayRegion(value, 0, result.size(), reinterpret_cast<jbyte *>(result.data()));
    return result;
}

extern "C" JNIEXPORT jlong JNICALL
Java_local_dshx_app_LocalModel_load(JNIEnv * env, jobject, jbyteArray filename) {
    static std::once_flag initialized;
    std::call_once(initialized, [] {
        llama_backend_init();
        llama_log_set([](ggml_log_level level, const char * text, void *) {
            if (level == GGML_LOG_LEVEL_ERROR) __android_log_write(ANDROID_LOG_ERROR, "DSHXLocal", text);
        }, nullptr);
    });
    auto * local = new Local();
    auto model = llama_model_default_params();
    model.n_gpu_layers = 0;
    local->model = llama_model_load_from_file(bytes(env, filename).c_str(), model);
    if (!local->model) { delete local; fail(env, "无法加载此本地模型，请检查文件完整性、模型架构和设备内存。"); return 0; }
    return reinterpret_cast<jlong>(local);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_local_dshx_app_LocalModel_format(JNIEnv * env, jobject, jlong handle, jobjectArray source) {
    const auto * local = reinterpret_cast<Local *>(handle);
    const char * tmpl = llama_model_chat_template(local->model, nullptr);
    if (!tmpl) { fail(env, "模型缺少对话模板，请选择带聊天模板的 GGUF 模型。"); return nullptr; }
    const int count = env->GetArrayLength(source);
    std::vector<std::string> parts;
    parts.reserve(count);
    for (int index = 0; index < count; ++index) {
        auto value = static_cast<jbyteArray>(env->GetObjectArrayElement(source, index));
        parts.push_back(bytes(env, value));
        env->DeleteLocalRef(value);
    }
    std::vector<llama_chat_message> messages;
    for (int index = 0; index + 1 < count; index += 2) messages.push_back({parts[index].c_str(), parts[index + 1].c_str()});
    std::vector<char> buffer(16384);
    int size = llama_chat_apply_template(tmpl, messages.data(), messages.size(), true, buffer.data(), buffer.size());
    if (size > static_cast<int>(buffer.size()) && size <= 131072) {
        buffer.resize(size);
        size = llama_chat_apply_template(tmpl, messages.data(), messages.size(), true, buffer.data(), buffer.size());
    }
    if (size <= 0 || size > static_cast<int>(buffer.size())) {
        fail(env, "当前推理库不支持此模型的对话模板，请更换模型。"); return nullptr;
    }
    std::string prompt(buffer.data(), size);
    char architecture[64] = {};
    llama_model_meta_val_str(local->model, "general.architecture", architecture, sizeof(architecture));
    if (std::string(architecture) == "qwen3") prompt += "<think>\n\n</think>\n\n";
    auto result = env->NewByteArray(prompt.size());
    env->SetByteArrayRegion(result, 0, prompt.size(), reinterpret_cast<const jbyte *>(prompt.data()));
    return result;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_local_dshx_app_LocalModel_generate(JNIEnv * env, jobject, jlong handle, jbyteArray source, jint maximum) {
    auto * local = reinterpret_cast<Local *>(handle);
    const auto * vocabulary = llama_model_get_vocab(local->model);
    const auto prompt = bytes(env, source);
    std::vector<llama_token> tokens(prompt.size() + 32);
    const int count = llama_tokenize(vocabulary, prompt.data(), prompt.size(), tokens.data(), tokens.size(), true, true);
    if (count <= 0 || count + maximum >= 4096) { fail(env, "Local context is too long"); return nullptr; }
    auto context = llama_context_default_params();
    // This context belongs to one request; reserve only its prompt and output budget.
    context.n_ctx = ((count + maximum + 256) / 256) * 256;
    context.n_batch = 256; context.n_ubatch = 64;
    context.n_threads = 2; context.n_threads_batch = 2;
    context.abort_callback = [](void * data) { return static_cast<Local *>(data)->cancelled.load(); };
    context.abort_callback_data = local;
    local->context = llama_init_from_model(local->model, context);
    if (!local->context) { fail(env, "Cannot create local context"); return nullptr; }
    tokens.resize(count); local->input = count;
    for (int offset = 0; offset < count; offset += 256) {
        const int size = std::min(256, count - offset);
        if (local->cancelled || llama_decode(local->context, llama_batch_get_one(tokens.data() + offset, size)) != 0) {
            fail(env, "Local generation was cancelled or failed"); return nullptr;
        }
    }
    auto * sampler = llama_sampler_init_greedy();
    std::string text;
    for (int index = 0; index < maximum && !local->cancelled; ++index) {
        llama_token token = llama_sampler_sample(sampler, local->context, -1);
        if (llama_vocab_is_eog(vocabulary, token)) break;
        ++local->output;
        std::vector<char> piece(256);
        int size = llama_token_to_piece(vocabulary, token, piece.data(), piece.size(), 0, false);
        if (size < 0) { piece.resize(-size); size = llama_token_to_piece(vocabulary, token, piece.data(), piece.size(), 0, false); }
        if (size > 0) text.append(piece.data(), size);
        if (llama_decode(local->context, llama_batch_get_one(&token, 1)) != 0) { local->cancelled = true; break; }
    }
    llama_sampler_free(sampler);
    if (local->cancelled) { fail(env, "Local generation was cancelled or failed"); return nullptr; }
    const auto result = env->NewByteArray(text.size());
    env->SetByteArrayRegion(result, 0, text.size(), reinterpret_cast<const jbyte *>(text.data()));
    return result;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_local_dshx_app_LocalModel_usage(JNIEnv * env, jobject, jlong handle) {
    const auto * local = reinterpret_cast<Local *>(handle);
    jint values[] = {local->input, local->output};
    auto result = env->NewIntArray(2); env->SetIntArrayRegion(result, 0, 2, values); return result;
}
extern "C" JNIEXPORT void JNICALL
Java_local_dshx_app_LocalModel_cancel(JNIEnv *, jobject, jlong handle) { reinterpret_cast<Local *>(handle)->cancelled = true; }
extern "C" JNIEXPORT void JNICALL
Java_local_dshx_app_LocalModel_release(JNIEnv *, jobject, jlong handle) { delete reinterpret_cast<Local *>(handle); }
