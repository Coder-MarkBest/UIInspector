#include <jni.h>
#include <android/log.h>

#include <string>
#include <utility>

namespace {

constexpr const char* kTag = "UIInspectorAgent";

bool clear_exception(JNIEnv* env, const char* operation) {
    if (!env->ExceptionCheck()) return false;
    env->ExceptionDescribe();
    env->ExceptionClear();
    __android_log_print(ANDROID_LOG_ERROR, kTag, "%s failed", operation);
    return true;
}

std::pair<std::string, std::string> parse_options(const char* options) {
    std::string value = options == nullptr ? "" : options;
    const auto separator = value.find(',');
    if (separator == std::string::npos) return {value, ""};
    return {value.substr(0, separator), value.substr(separator + 1)};
}

jobject current_application(JNIEnv* env) {
    jclass activity_thread = env->FindClass("android/app/ActivityThread");
    if (activity_thread == nullptr || clear_exception(env, "Find ActivityThread")) return nullptr;
    jmethodID current = env->GetStaticMethodID(
            activity_thread, "currentApplication", "()Landroid/app/Application;");
    if (current == nullptr || clear_exception(env, "Find currentApplication")) return nullptr;
    jobject application = env->CallStaticObjectMethod(activity_thread, current);
    if (clear_exception(env, "Call currentApplication")) return nullptr;
    return application;
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

jclass load_helper(JNIEnv* env, const std::string& helper_path) {
    jobject application = current_application(env);
    if (application == nullptr) return nullptr;
    jclass application_class = env->GetObjectClass(application);
    jmethodID get_loader = env->GetMethodID(
            application_class, "getClassLoader", "()Ljava/lang/ClassLoader;");
    if (get_loader == nullptr || clear_exception(env, "Find Application.getClassLoader")) return nullptr;
    jobject parent = env->CallObjectMethod(application, get_loader);
    if (clear_exception(env, "Call Application.getClassLoader")) return nullptr;

    jclass dex_loader = env->FindClass("dalvik/system/DexClassLoader");
    if (dex_loader == nullptr || clear_exception(env, "Find DexClassLoader")) return nullptr;
    jmethodID constructor = env->GetMethodID(
            dex_loader, "<init>",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/ClassLoader;)V");
    if (constructor == nullptr || clear_exception(env, "Find DexClassLoader constructor")) return nullptr;
    jstring path = env->NewStringUTF(helper_path.c_str());
    jobject loader = env->NewObject(dex_loader, constructor, path, nullptr, nullptr, parent);
    if (loader == nullptr || clear_exception(env, "Create DexClassLoader")) return nullptr;

    jclass loader_class = env->FindClass("java/lang/ClassLoader");
    jmethodID load = env->GetMethodID(
            loader_class, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    jstring helper_name = env->NewStringUTF("com.company.uiinspector.agent.ViewCaptureBridge");
    jobject helper = env->CallObjectMethod(loader, load, helper_name);
    if (helper == nullptr || clear_exception(env, "Load ViewCaptureBridge")) return nullptr;
    return static_cast<jclass>(helper);
}

bool run_capture(JNIEnv* env, const std::string& helper_path, const std::string& output_path) {
    jobject global = window_manager_global(env);
    if (global == nullptr) return false;
    jobject views = field(env, global, "mViews", "Ljava/util/ArrayList;");
    if (views == nullptr) return false;
    jobject roots = field(env, global, "mRoots", "Ljava/util/ArrayList;");
    jobject params = collect_window_params(env, roots, size(env, views));
    if (params == nullptr) return false;

    jclass helper = load_helper(env, helper_path);
    if (helper == nullptr) return false;
    jmethodID capture = env->GetStaticMethodID(
            helper, "capture",
            "(Ljava/util/List;Ljava/util/List;Ljava/lang/String;)V");
    if (capture == nullptr || clear_exception(env, "Find ViewCaptureBridge.capture")) return false;
    jstring output = env->NewStringUTF(output_path.c_str());
    env->CallStaticVoidMethod(helper, capture, views, params, output);
    return !clear_exception(env, "Call ViewCaptureBridge.capture");
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL Agent_OnAttach(JavaVM* vm, char* options, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK || env == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "Unable to obtain JNIEnv");
        return JNI_ERR;
    }
    auto [helper_path, output_path] = parse_options(options);
    if (helper_path.empty() || output_path.empty()) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "Expected helper APK and snapshot output options");
        return JNI_ERR;
    }
    return run_capture(env, helper_path, output_path) ? JNI_OK : JNI_ERR;
}
