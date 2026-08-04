#include <jni.h>
#include <android/log.h>

#include <string>
#include <vector>

namespace {

constexpr const char* kTag = "UIInspectorAgent";

bool clear_exception(JNIEnv* env, const char* operation) {
    if (!env->ExceptionCheck()) return false;
    env->ExceptionDescribe();
    env->ExceptionClear();
    __android_log_print(ANDROID_LOG_ERROR, kTag, "%s failed", operation);
    return true;
}

struct AgentOptions {
    std::string helper_path;
    std::string command_path;
    std::string output_path;
};

bool parse_options(const char* options, AgentOptions* result) {
    std::string value = options == nullptr ? "" : options;
    __android_log_print(ANDROID_LOG_INFO, kTag, "Agent options: %s", value.c_str());
    std::vector<std::string> parts;
    std::size_t start = 0;
    while (true) {
        const auto separator = value.find(',', start);
        parts.push_back(value.substr(start, separator - start));
        if (separator == std::string::npos) break;
        start = separator + 1;
    }
    __android_log_print(ANDROID_LOG_INFO, kTag, "Parsed %zu option parts", parts.size());
    if ((parts.size() != 2 && parts.size() != 3) || parts[0].empty()) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "Bad option count or empty helper path");
        return false;
    }
    result->helper_path = parts[0];
    if (parts.size() == 2) {
        result->output_path = parts[1];
    } else {
        result->command_path = parts[1];
        result->output_path = parts[2];
    }
    __android_log_print(ANDROID_LOG_INFO, kTag, "helper=%s output=%s",
            result->helper_path.c_str(), result->output_path.c_str());
    return !result->output_path.empty()
            && (parts.size() == 2 || !result->command_path.empty());
}

jobject window_manager_global(JNIEnv* env) {
    jclass type = env->FindClass("android/view/WindowManagerGlobal");
    if (type == nullptr || clear_exception(env, "Find WindowManagerGlobal")) return nullptr;
    jmethodID instance = env->GetStaticMethodID(
            type, "getInstance", "()Landroid/view/WindowManagerGlobal;");
    if (instance == nullptr || clear_exception(env, "Find WindowManagerGlobal.getInstance")) return nullptr;
    jobject result = env->CallStaticObjectMethod(type, instance);
    if (clear_exception(env, "Call WindowManagerGlobal.getInstance")) return nullptr;
    return result;
}

jobject field(JNIEnv* env, jobject owner, const char* name, const char* signature) {
    jclass type = env->GetObjectClass(owner);
    jfieldID id = env->GetFieldID(type, name, signature);
    if (id == nullptr || clear_exception(env, name)) return nullptr;
    jobject value = env->GetObjectField(owner, id);
    if (clear_exception(env, name)) return nullptr;
    return value;
}

jobject new_array_list(JNIEnv* env) {
    jclass list = env->FindClass("java/util/ArrayList");
    if (list == nullptr || clear_exception(env, "Find ArrayList")) return nullptr;
    jmethodID constructor = env->GetMethodID(list, "<init>", "()V");
    if (constructor == nullptr || clear_exception(env, "Find ArrayList constructor")) return nullptr;
    jobject result = env->NewObject(list, constructor);
    if (clear_exception(env, "Create ArrayList")) return nullptr;
    return result;
}

bool add(JNIEnv* env, jobject list, jobject value) {
    jclass type = env->FindClass("java/util/ArrayList");
    jmethodID method = env->GetMethodID(type, "add", "(Ljava/lang/Object;)Z");
    env->CallBooleanMethod(list, method, value);
    return !clear_exception(env, "ArrayList.add");
}

jobject get(JNIEnv* env, jobject list, jint index) {
    jclass type = env->FindClass("java/util/ArrayList");
    jmethodID method = env->GetMethodID(type, "get", "(I)Ljava/lang/Object;");
    jobject result = env->CallObjectMethod(list, method, index);
    if (clear_exception(env, "ArrayList.get")) return nullptr;
    return result;
}

jint size(JNIEnv* env, jobject list) {
    jclass type = env->FindClass("java/util/ArrayList");
    jmethodID method = env->GetMethodID(type, "size", "()I");
    jint result = env->CallIntMethod(list, method);
    if (clear_exception(env, "ArrayList.size")) return 0;
    return result;
}

jobject collect_window_params(JNIEnv* env, jobject roots, jint view_count) {
    jobject result = new_array_list(env);
    if (result == nullptr) return nullptr;
    const jint root_count = roots == nullptr ? 0 : size(env, roots);
    for (jint index = 0; index < view_count; index++) {
        jobject attributes = nullptr;
        if (index < root_count) {
            jobject view_root = get(env, roots, index);
            if (view_root != nullptr) {
                attributes = field(env, view_root, "mWindowAttributes",
                                   "Landroid/view/WindowManager$LayoutParams;");
            }
        }
        if (!add(env, result, attributes)) return nullptr;
    }
    return result;
}

jclass load_helper(JNIEnv* env, const std::string& helper_path, const char* class_name) {
    __android_log_print(ANDROID_LOG_INFO, kTag, "load_helper: path=%s class=%s",
            helper_path.c_str(), class_name);
    // Use BootClassLoader as parent (null) to prevent protocol classes
    // from being resolved via the app ClassLoader. This avoids version
    // mismatches between the collector APK's and helper APK's D8/R8-generated
    // synthetic classes (e.g. Rect$$ExternalSyntheticRecord0).
    jclass dex_loader = env->FindClass("dalvik/system/DexClassLoader");
    if (dex_loader == nullptr || clear_exception(env, "Find DexClassLoader")) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "DexClassLoader class not found");
        return nullptr;
    }
    jmethodID constructor = env->GetMethodID(
            dex_loader, "<init>",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/ClassLoader;)V");
    if (constructor == nullptr || clear_exception(env, "Find DexClassLoader constructor")) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "DexClassLoader constructor not found");
        return nullptr;
    }
    jstring path = env->NewStringUTF(helper_path.c_str());
    // null parent = BootClassLoader delegation only, so protocol classes
    // from the helper APK won't collide with those already loaded by the app.
    jobject loader = env->NewObject(dex_loader, constructor, path, nullptr, nullptr, nullptr);
    if (loader == nullptr || clear_exception(env, "Create DexClassLoader")) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "DexClassLoader creation failed for %s",
                helper_path.c_str());
        return nullptr;
    }
    __android_log_print(ANDROID_LOG_INFO, kTag, "DexClassLoader created for %s (parent=null)", helper_path.c_str());

    jclass loader_class = env->FindClass("java/lang/ClassLoader");
    jmethodID load = env->GetMethodID(
            loader_class, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    jstring helper_name = env->NewStringUTF(class_name);
    jobject helper = env->CallObjectMethod(loader, load, helper_name);
    if (helper == nullptr || clear_exception(env, "Load ViewCaptureBridge")) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "Failed to load class %s", class_name);
        return nullptr;
    }
    __android_log_print(ANDROID_LOG_INFO, kTag, "Class %s loaded successfully", class_name);
    return static_cast<jclass>(helper);
}

bool run_capture(JNIEnv* env, const std::string& helper_path, const std::string& output_path) {
    __android_log_print(ANDROID_LOG_INFO, kTag, "Starting capture: helper=%s output=%s",
            helper_path.c_str(), output_path.c_str());
    jobject global = window_manager_global(env);
    if (global == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "WindowManagerGlobal unavailable");
        return false;
    }
    __android_log_print(ANDROID_LOG_INFO, kTag, "WindowManagerGlobal obtained");
    jobject views = field(env, global, "mViews", "Ljava/util/ArrayList;");
    if (views == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "mViews field unavailable");
        return false;
    }
    jobject roots = field(env, global, "mRoots", "Ljava/util/ArrayList;");
    __android_log_print(ANDROID_LOG_INFO, kTag, "mViews=%d mRoots=%s",
            size(env, views), roots == nullptr ? "null" : "ok");
    jobject params = collect_window_params(env, roots, size(env, views));
    if (params == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "collect_window_params failed");
        return false;
    }
    __android_log_print(ANDROID_LOG_INFO, kTag, "Window params collected, loading helper");

    jclass helper = load_helper(
            env, helper_path, "com.company.uiinspector.agent.ViewCaptureBridge");
    if (helper == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "Failed to load ViewCaptureBridge from helper APK");
        return false;
    }
    __android_log_print(ANDROID_LOG_INFO, kTag, "ViewCaptureBridge loaded");
    jmethodID capture = env->GetStaticMethodID(
            helper, "capture",
            "(Ljava/util/List;Ljava/util/List;Ljava/lang/String;)V");
    if (capture == nullptr || clear_exception(env, "Find ViewCaptureBridge.capture")) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "ViewCaptureBridge.capture method not found");
        return false;
    }
    jstring output = env->NewStringUTF(output_path.c_str());
    env->CallStaticVoidMethod(helper, capture, views, params, output);
    if (clear_exception(env, "Call ViewCaptureBridge.capture")) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "ViewCaptureBridge.capture threw exception");
        return false;
    }
    __android_log_print(ANDROID_LOG_INFO, kTag, "Capture posted to main thread successfully");
    return true;
}

bool run_mutation(
        JNIEnv* env,
        const std::string& helper_path,
        const std::string& command_path,
        const std::string& output_path) {
    __android_log_print(ANDROID_LOG_INFO, kTag, "Starting mutation: helper=%s command=%s output=%s",
            helper_path.c_str(), command_path.c_str(), output_path.c_str());
    jobject global = window_manager_global(env);
    if (global == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "WindowManagerGlobal unavailable for mutation");
        return false;
    }
    jobject views = field(env, global, "mViews", "Ljava/util/ArrayList;");
    if (views == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "mViews field unavailable for mutation");
        return false;
    }
    jobject roots = field(env, global, "mRoots", "Ljava/util/ArrayList;");
    jobject params = collect_window_params(env, roots, size(env, views));
    if (params == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "collect_window_params failed for mutation");
        return false;
    }

    jclass helper = load_helper(
            env, helper_path, "com.company.uiinspector.agent.ViewMutationBridge");
    if (helper == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "Failed to load ViewMutationBridge from helper APK");
        return false;
    }
    __android_log_print(ANDROID_LOG_INFO, kTag, "ViewMutationBridge loaded");
    jmethodID mutate = env->GetStaticMethodID(
            helper, "mutate",
            "(Ljava/util/List;Ljava/util/List;Ljava/lang/String;Ljava/lang/String;)V");
    if (mutate == nullptr || clear_exception(env, "Find ViewMutationBridge.mutate")) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "ViewMutationBridge.mutate method not found");
        return false;
    }
    jstring command = env->NewStringUTF(command_path.c_str());
    jstring output = env->NewStringUTF(output_path.c_str());
    env->CallStaticVoidMethod(helper, mutate, views, params, command, output);
    if (clear_exception(env, "Call ViewMutationBridge.mutate")) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "ViewMutationBridge.mutate threw exception");
        return false;
    }
    __android_log_print(ANDROID_LOG_INFO, kTag, "Mutation posted to main thread successfully");
    return true;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL Agent_OnAttach(JavaVM* vm, char* options, void*) {
    __android_log_print(ANDROID_LOG_INFO, kTag, "Agent_OnAttach called");
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK || env == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "Unable to obtain JNIEnv");
        return JNI_ERR;
    }
    __android_log_print(ANDROID_LOG_INFO, kTag, "JNIEnv obtained");
    AgentOptions parsed;
    if (!parse_options(options, &parsed)) {
        __android_log_print(
                ANDROID_LOG_ERROR, kTag,
                "Expected helper/output capture options or helper/command/output mutation options");
        return JNI_ERR;
    }
    const bool is_capture = parsed.command_path.empty();
    __android_log_print(ANDROID_LOG_INFO, kTag, "Dispatching %s",
            is_capture ? "capture" : "mutation");
    const bool success = is_capture
            ? run_capture(env, parsed.helper_path, parsed.output_path)
            : run_mutation(env, parsed.helper_path, parsed.command_path, parsed.output_path);
    __android_log_print(ANDROID_LOG_INFO, kTag, "Agent_OnAttach returning %s",
            success ? "JNI_OK" : "JNI_ERR");
    return success ? JNI_OK : JNI_ERR;
}
