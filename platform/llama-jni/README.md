# `llama_jni` — the native half of offline AI

PARITY.md row 2.5.

## What is here

| file | what it is |
|---|---|
| `llama_jni.cpp` | the JNI shim. Implements the three `external fun`s declared in `OSHI-Android/.../service/LlamaCpp.kt`, which this project compiles as **shared source** |
| `CMakeLists.txt` | builds it against a llama.cpp checkout, statically, into ONE file |

**Nothing here has ever been compiled.** It is built by the `native-llama` job in
`.github/workflows/desktop-ci.yml`, on `windows-latest` and `ubuntu-latest`, and that job
has never run. The development machine for this project is a Mac and cannot produce a
Windows DLL; a hand-made stand-in would be worse than an absent one.

## Why the Kotlin is not copied

`build.gradle.kts` compiles `service/LlamaCpp.kt` straight out of the Android tree
(`sharedServiceSources`). It qualifies because it has no imports at all — not one — which
is the same property that made the six crypto files shareable.

It matters more here than it does for the crypto. A JNI declaration is **half of an ABI**:
the symbols below are named after the Kotlin package and class.

```
Java_com_oshi_messenger_service_LlamaCpp_loadModel     (Ljava/lang/String;)Z
Java_com_oshi_messenger_service_LlamaCpp_generate      (Ljava/lang/String;I)Ljava/lang/String;
Java_com_oshi_messenger_service_LlamaCpp_unloadModel   ()V
```

A copy in `com.oshi.desktop` would name different symbols, so the phone and the desktop
would need two shims that drift silently — right up to an `UnsatisfiedLinkError` at the
first question a user asks. `SharedLlamaCppTripwireTest` derives those three names from the
Kotlin and greps this directory for them, so a rename is caught by the test suite rather
than by a user.

## Building it by hand

```sh
git clone --depth 1 https://github.com/ggml-org/llama.cpp
cmake -S OSHI-Desktop/platform/llama-jni -B build-native \
      -DLLAMA_CPP_DIR="$PWD/llama.cpp" -DCMAKE_BUILD_TYPE=Release
cmake --build build-native --config Release --parallel
```

Produces exactly one file — `libllama_jni.so`, `llama_jni.dll` or `libllama_jni.dylib` —
with llama.cpp linked in statically. That is deliberate: on Windows the JVM loads the shim
by absolute path and Windows then resolves ITS dependencies against the process directory,
`System32` and `PATH`, *not* against the directory the DLL came from, so a perfectly good
pair of DLLs side by side fails with error 126 while naming the file that is present.

CPU only, and `GGML_NATIVE=OFF`. llama.cpp defaults `-march=native` ON, which produces a
binary that runs on the build machine and dies with SIGILL on an older user CPU — invisible
until it is somebody else's crash.

## Using it

`java.library.path` must contain the directory, and it must be set at JVM start:

```sh
java -Djava.library.path=/path/to/the/lib -jar oshi-desktop.jar --client
```

`System.load()` on an absolute path does **not** work as a substitute. The
`System.loadLibrary("llama_jni")` call is inside the shared file's companion `init`, it runs
before any code here can intervene, and `ClassLoader` only ever walks `java.library.path`.

Set `OSHI_LLAMA_LIB_DIR` as well and `/ai status` can tell "you have not built it" apart
from "you built it and the JVM cannot see it" — two problems with different fixes.

## The model

**No GGUF file is in this repository and nothing downloads one.** They are 0.6–2.5 GB, they
are not ours to redistribute, and a CI job that fetched one would pay for it on every push.

Fetch one by hand and point the client at it with `/ai model <path>`. These are the three
tiers the shipped Android client offers, so a phone and this client can share one file:

| tier | file | size |
|---|---|---|
| OSHI AI Pro | [`Qwen3-4B-Q4_K_M.gguf`](https://huggingface.co/Qwen/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf) | 2.50 GB |
| OSHI AI Standard | [`Qwen3-1.7B-Q4_K_M.gguf`](https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf) | 1.11 GB |
| OSHI AI Lite | [`Qwen3-0.6B-Q8_0.gguf`](https://huggingface.co/Qwen/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q8_0.gguf) | 639 MB |

The prompt template is Qwen3 ChatML with `/no_think`. Another family will load and produce
words; it will not honour that template and the cleanup pipeline will not recognise its
special tokens. Nothing detects that, which is worth knowing before blaming the model.

## What is still unproven

The `native-llama` job, once green, will prove the library compiles, exports the right three
symbols, and loads from a JVM. It deliberately does not download a model, so **it will not
prove that a single token has ever been generated.** That claim needs a real model and a
real question, and nobody has run one yet — on any platform.
