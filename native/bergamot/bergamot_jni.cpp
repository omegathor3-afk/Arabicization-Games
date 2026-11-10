#include <jni.h>

#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

#include "translator/response.h"
#include "translator/response_options.h"
#include "translator/service.h"
#include "translator/translation_model.h"
#include "translator/parser.h"

namespace {

void throwJava(JNIEnv* env, const char* className, const std::string& message) {
  jclass type = env->FindClass(className);
  if (type != nullptr) env->ThrowNew(type, message.c_str());
}

std::string fromJava(JNIEnv* env, jstring value) {
  if (value == nullptr) throw std::invalid_argument("argument must not be null");
  const char* chars = env->GetStringUTFChars(value, nullptr);
  if (chars == nullptr) throw std::runtime_error("could not read Java string");
  std::string result(chars);
  env->ReleaseStringUTFChars(value, chars);
  return result;
}

jstring toJava(JNIEnv* env, const std::string& value) {
  return env->NewStringUTF(value.c_str());
}

struct Runtime {
  explicit Runtime(const std::string& modelConfigPath)
      : service(marian::bergamot::BlockingService::Config{}) {
    if (modelConfigPath.empty()) throw std::invalid_argument("model config path is empty");
    auto options = marian::bergamot::parseOptionsFromFilePath(modelConfigPath);
    model = std::make_shared<marian::bergamot::TranslationModel>(options);
  }

  std::string translate(const std::string& input) {
    if (input.empty()) return {};
    std::lock_guard<std::mutex> guard(mutex);
    std::vector<std::string> sources;
    sources.emplace_back(input);
    std::vector<marian::bergamot::ResponseOptions> responseOptions(1);
    auto responses = service.translateMultiple(model, std::move(sources), responseOptions);
    if (responses.empty()) throw std::runtime_error("Bergamot returned no translation response");
    return responses.front().getTranslatedText();
  }

  std::mutex mutex;
  marian::bergamot::BlockingService service;
  std::shared_ptr<marian::bergamot::TranslationModel> model;
};

Runtime* runtimeFrom(jlong handle) {
  if (handle == 0) throw std::invalid_argument("Bergamot session is closed");
  return reinterpret_cast<Runtime*>(static_cast<intptr_t>(handle));
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_omega_arabify_BergamotNative_nativeCreate(JNIEnv* env, jobject, jstring configPath) {
  try {
    auto runtime = std::make_unique<Runtime>(fromJava(env, configPath));
    return static_cast<jlong>(reinterpret_cast<intptr_t>(runtime.release()));
  } catch (const std::exception& error) {
    throwJava(env, "java/lang/IllegalStateException", error.what());
    return 0;
  } catch (...) {
    throwJava(env, "java/lang/IllegalStateException", "unknown Bergamot initialization failure");
    return 0;
  }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_omega_arabify_BergamotNative_nativeTranslate(JNIEnv* env, jobject, jlong handle, jstring input) {
  try {
    return toJava(env, runtimeFrom(handle)->translate(fromJava(env, input)));
  } catch (const std::exception& error) {
    throwJava(env, "java/lang/IllegalStateException", error.what());
    return nullptr;
  } catch (...) {
    throwJava(env, "java/lang/IllegalStateException", "unknown Bergamot translation failure");
    return nullptr;
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_omega_arabify_BergamotNative_nativeDestroy(JNIEnv* env, jobject, jlong handle) {
  try {
    delete runtimeFrom(handle);
  } catch (const std::exception& error) {
    throwJava(env, "java/lang/IllegalStateException", error.what());
  } catch (...) {
    throwJava(env, "java/lang/IllegalStateException", "unknown Bergamot shutdown failure");
  }
}
